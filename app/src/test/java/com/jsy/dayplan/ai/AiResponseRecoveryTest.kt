package com.jsy.dayplan.ai

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jsy.dayplan.ai.attach.AttachmentContextStore
import com.jsy.dayplan.ai.attach.AttachmentPlanner
import com.jsy.dayplan.ai.attach.PdfPageRenderer
import com.jsy.dayplan.core.FixedClock
import com.jsy.dayplan.data.AiExecutionGate
import com.jsy.dayplan.data.PlanDb
import com.jsy.dayplan.data.PlanRepository
import com.jsy.dayplan.reminder.FakeReminderScheduler
import com.jsy.dayplan.reminder.ReminderCoordinator
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.7.2 定向回归：DeepSeek JSON Output 空内容/非法 JSON 的一次重试、
 * 纯文字降级为聊天、finish_reason 分类，以及重试期间的会话/数据边界。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = com.jsy.dayplan.DayPlanApp::class)
class AiResponseRecoveryTest {

    private lateinit var db: PlanDb
    private lateinit var repo: PlanRepository
    private lateinit var settings: AiSettingsStore
    private lateinit var keys: SecureKeyStore
    private lateinit var store: BatchStore
    private lateinit var history: ChatStore
    private lateinit var contextStore: AttachmentContextStore
    private lateinit var executor: PlanDraftExecutor
    private lateinit var dir: File
    private lateinit var transport: Scripted
    private lateinit var controller: ChatController

    private data class ScriptedResponse(
        val content: String,
        val finishReason: String = "stop",
    )

    private inner class Scripted : ChatTransport {
        val requests = mutableListOf<TransportRequest>()
        val responses = ArrayDeque<ScriptedResponse>()
        var beforeReturn: (() -> Unit)? = null

        override suspend fun call(request: TransportRequest): TransportResponse {
            requests.add(request)
            beforeReturn?.invoke()
            val r = if (responses.isEmpty()) {
                ScriptedResponse("""{"schemaVersion":1,"kind":"chat","reply":"ok"}""")
            } else {
                responses.removeFirst()
            }
            val quoted = kotlinx.serialization.json.JsonPrimitive(r.content).toString()
            return TransportResponse(
                200,
                """{"choices":[{"message":{"content":$quoted},"finish_reason":"${r.finishReason}"}],
                   "usage":{"prompt_tokens":1,"completion_tokens":1}}""",
            )
        }
    }

    private fun noopRenderer() = object : PdfPageRenderer {
        override fun renderPage(file: File, pageIndex: Int, maxDimension: Int) = null
    }

    @Before
    fun setUp() {
        val app: Application = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(app, PlanDb::class.java)
            .allowMainThreadQueries().build()
        repo = PlanRepository(db)
        settings = AiSettingsStore(app)
        settings.config = AiConfig(enabled = true, autoSave = false)
        val session = settings.newChatSession()
        settings.markBoundaryPersisted(settings.dataGeneration, session)
        AiExecutionGate.set(settings.dataGeneration, session, bumpToken = true)
        dir = Files.createTempDirectory("v172-recovery").toFile()
        store = BatchStore(File(dir, "batches"))
        history = ChatStore(File(dir, "chat.json"))
        contextStore = AttachmentContextStore(File(dir, "context"))
        keys = SecureKeyStore(File(dir, "key"), InMemoryKeyCipher())
        keys.saveApiKey("sk-v172-synthetic-key")
        executor = PlanDraftExecutor(
            repo,
            ReminderCoordinator(db.dao(), FakeReminderScheduler()),
            settings,
            store,
        )
        transport = Scripted()
        controller = ChatController(
            DeepSeekClient(apiKeyProvider = { keys.loadApiKey() }, transport = transport),
            settings, keys, FixedClock(LocalDateTime.of(2026, 10, 25, 9, 0)),
            repo, executor, store, history,
            AttachmentPlanner(noopRenderer(), null),
            contextStore = contextStore,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun send(text: String = "你好"): ChatTurn = runBlocking { controller.send(text) }

    private fun body(index: Int = 0) =
        AiProtocol.json.parseToJsonElement(transport.requests[index].body!!).jsonObject

    @Test
    fun `空内容去掉 JSON 模式重试一次后成功`() {
        transport.responses.addAll(
            listOf(
                ScriptedResponse(""),
                ScriptedResponse("""{"schemaVersion":1,"kind":"chat","reply":"你好，我在"}"""),
            ),
        )
        val turn = send("你好")

        assertEquals(2, transport.requests.size)
        assertEquals("你好，我在", turn.assistantEntry.text)
        assertNull("纯聊天不应生成批次", turn.batchId)

        assertNotNull("首次应使用 JSON Output", body(0)["response_format"])
        assertNull("重试应去掉 response_format", body(1)["response_format"])
        assertEquals("none", body(1)["reasoning_effort"]!!.jsonPrimitive.content)
        val retrySystem = body(1)["messages"]!!.jsonArray.first()
            .jsonObject["content"]!!.jsonPrimitive.content
        assertTrue("重试提示必须要求非空 JSON", retrySystem.contains("上一次响应为空"))
        assertEquals(2, history.load().size)
    }

    @Test
    fun `markdown 代码块响应无需重试直接解析`() {
        transport.responses.add(
            ScriptedResponse(
                "```json\n{\"schemaVersion\":1,\"kind\":\"chat\",\"reply\":\"代码块\"}\n```",
            ),
        )
        val turn = send()
        assertEquals(1, transport.requests.size)
        assertEquals("代码块", turn.assistantEntry.text)
    }

    @Test
    fun `两次纯文字降级为聊天且不写库`() {
        transport.responses.addAll(
            listOf(ScriptedResponse("你好呀"), ScriptedResponse("我是日序助手")),
        )
        val turn = send("你好呀")

        assertEquals(2, transport.requests.size)
        assertTrue(turn.assistantEntry.text.contains("我是日序助手"))
        assertTrue(turn.assistantEntry.text.contains("未生成任何草稿"))
        assertNull(turn.batchId)
        assertTrue(runBlocking { repo.loadAll().schedules.isEmpty() })
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `连续空内容给出明确错误且不写库`() {
        transport.responses.addAll(listOf(ScriptedResponse(""), ScriptedResponse("")))
        val e = assertThrows(AiException.Protocol::class.java) {
            runBlocking { controller.send("你好") }
        }
        assertTrue(e.message!!.contains("连续返回空内容"))
        assertEquals(2, transport.requests.size)
        assertTrue(history.load().isEmpty())
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `content_filter 不重试并给出过滤说明`() {
        transport.responses.add(ScriptedResponse("", "content_filter"))
        val e = assertThrows(AiException.Protocol::class.java) {
            runBlocking { controller.send("你好") }
        }
        assertTrue(e.message!!.contains("过滤"))
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `资源不足会安全重试一次`() {
        transport.responses.addAll(
            listOf(
                ScriptedResponse("", "insufficient_system_resource"),
                ScriptedResponse("""{"schemaVersion":1,"kind":"chat","reply":"恢复了"}"""),
            ),
        )
        val turn = send()
        assertEquals(2, transport.requests.size)
        assertEquals("恢复了", turn.assistantEntry.text)
    }

    @Test
    fun `空 JSON 对象触发重试`() {
        transport.responses.addAll(
            listOf(
                ScriptedResponse("{}"),
                ScriptedResponse("""{"schemaVersion":1,"kind":"chat","reply":"非空"}"""),
            ),
        )
        val turn = send()
        assertEquals(2, transport.requests.size)
        assertEquals("非空", turn.assistantEntry.text)
    }

    @Test
    fun `未知 kind 有文字时按聊天显示并提示`() {
        transport.responses.add(
            ScriptedResponse(
                """{"schemaVersion":1,"kind":"chat|clarify|plan","reply":"占位符"}""",
            ),
        )
        val turn = send()
        assertEquals(1, transport.requests.size)
        assertTrue(turn.assistantEntry.text.contains("占位符"))
        assertTrue(turn.assistantEntry.text.contains("未知的 kind"))
        assertNull(turn.batchId)
    }

    @Test
    fun `空 reply 两次给出明确错误`() {
        transport.responses.addAll(
            listOf(
                ScriptedResponse("""{"schemaVersion":1,"kind":"chat","reply":""}"""),
                ScriptedResponse("""{"schemaVersion":1,"kind":"chat","reply":""}"""),
            ),
        )
        val e = assertThrows(AiException.Protocol::class.java) {
            runBlocking { controller.send("你好") }
        }
        assertTrue(e.message!!.contains("空回复"))
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun `首轮请求期间清空对话不会发第二次请求`() {
        transport.responses.add(ScriptedResponse(""))
        transport.beforeReturn = { settings.newChatSession() }
        assertThrows(AiException.Cancelled::class.java) {
            runBlocking { controller.send("你好") }
        }
        assertEquals(1, transport.requests.size)
    }
}
