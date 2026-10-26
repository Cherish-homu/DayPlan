package com.jsy.dayplan.ai

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jsy.dayplan.ai.attach.AttachKind
import com.jsy.dayplan.ai.attach.AttachmentContextStore
import com.jsy.dayplan.ai.attach.AttachmentPlanner
import com.jsy.dayplan.ai.attach.PendingAttachment
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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** v1.7.3 普通聊天：流式、无 JSON、无附件、不写库；日程助手默认行为不变。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = com.jsy.dayplan.DayPlanApp::class)
class AiPlainChatTest {

    private lateinit var db: PlanDb
    private lateinit var repo: PlanRepository
    private lateinit var settings: AiSettingsStore
    private lateinit var keys: SecureKeyStore
    private lateinit var store: BatchStore
    private lateinit var history: ChatStore
    private lateinit var executor: PlanDraftExecutor
    private lateinit var dir: File
    private lateinit var transport: StreamScripted
    private lateinit var controller: ChatController

    private inner class StreamScripted : StreamingChatTransport {
        val requests = mutableListOf<TransportRequest>()
        val replies = ArrayDeque<String>()
        val deltas = mutableListOf<String>()
        var finishReason = "stop"
        var streamEmpty = false
        var nonStreamingReply = ""
        var nonStreamingCalls = 0

        override suspend fun call(request: TransportRequest): TransportResponse {
            requests.add(request)
            nonStreamingCalls++
            val text = if (request.body?.contains("\"response_format\"") == true) {
                """{"schemaVersion":1,"kind":"chat","reply":"日程助手回复","questions":[],"actions":[]}"""
            } else {
                nonStreamingReply
            }
            val content = kotlinx.serialization.json.JsonPrimitive(text)
            return TransportResponse(
                200,
                """{"choices":[{"message":{"content":$content},"finish_reason":"stop"}],
                   "usage":{"prompt_tokens":4,"completion_tokens":5}}""",
            )
        }

        override suspend fun stream(
            request: TransportRequest,
            onDelta: (String) -> Unit,
        ): ChatResult {
            requests.add(request)
            if (streamEmpty) return ChatResult("", finishReason, ChatUsage(2, 3))
            val reply = if (replies.isEmpty()) "你好呀" else replies.removeFirst()
            reply.chunked(2).forEach {
                onDelta(it)
                deltas.add(it)
            }
            return ChatResult(reply, finishReason, ChatUsage(2, 3))
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
        settings.config = AiConfig(
            enabled = true,
            autoSave = false,
            chatMode = AiConfig.MODE_PLAIN,
        )
        val session = settings.newChatSession()
        settings.markBoundaryPersisted(settings.dataGeneration, session)
        AiExecutionGate.set(settings.dataGeneration, session, bumpToken = true)
        dir = Files.createTempDirectory("v173-plain").toFile()
        store = BatchStore(File(dir, "batches"))
        history = ChatStore(File(dir, "chat.json"))
        keys = SecureKeyStore(File(dir, "key"), InMemoryKeyCipher())
        keys.saveApiKey("sk-v173-synthetic-key")
        executor = PlanDraftExecutor(
            repo,
            ReminderCoordinator(db.dao(), FakeReminderScheduler()),
            settings,
            store,
        )
        transport = StreamScripted()
        controller = ChatController(
            DeepSeekClient(apiKeyProvider = { keys.loadApiKey() }, transport = transport),
            settings, keys, FixedClock(LocalDateTime.of(2026, 10, 25, 9, 0)),
            repo, executor, store, history,
            AttachmentPlanner(noopRenderer(), null),
            contextStore = AttachmentContextStore(File(dir, "context")),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `普通聊天走流式 无 JSON 且不写库`() {
        val received = mutableListOf<String>()
        val turn = runBlocking {
            controller.send("你好", onDelta = { received.add(it) })
        }

        assertEquals(1, transport.requests.size)
        val body = AiProtocol.json.parseToJsonElement(transport.requests[0].body!!).jsonObject
        assertEquals("true", body["stream"]!!.jsonPrimitive.content)
        assertNull("普通聊天不能带 JSON Output", body["response_format"])
        assertNull("纯聊天不强制关闭思考，避免推理模型返回空正文", body["thinking"])
        assertNull(body["reasoning_effort"])
        val system = body["messages"]!!.jsonArray.first()
            .jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(system.contains("文字聊天"))
        assertFalse(system.contains("schemaVersion"))

        assertEquals("你好呀", turn.assistantEntry.text)
        assertEquals(AiConfig.MODE_PLAIN, turn.assistantEntry.mode)
        assertNull(turn.batchId)
        assertEquals("你好呀", received.joinToString(""))
        assertEquals(2, history.load().size)
        assertTrue(history.load().all { it.mode == AiConfig.MODE_PLAIN })
        assertTrue(runBlocking { repo.loadAll().schedules.isEmpty() })
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `空回复用普通纠正提示重试一次`() {
        transport.replies.addAll(listOf("", "第二次回答"))
        val turn = runBlocking { controller.send("你好") }

        assertEquals(2, transport.requests.size)
        assertEquals("第二次回答", turn.assistantEntry.text)
        val secondSystem = AiProtocol.json
            .parseToJsonElement(transport.requests[1].body!!)
            .jsonObject["messages"]!!.jsonArray.first()
            .jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(secondSystem.contains("上一次回复为空"))
        assertFalse(secondSystem.contains("schemaVersion"))
        assertEquals(0, runBlocking { repo.loadAll().schedules.size })
    }

    @Test
    fun `普通聊天拒绝附件`() {
        val file = Files.createTempFile("plain", ".txt").toFile().apply { writeText("x") }
        val att = PendingAttachment(
            "a1", "a.txt", "text/plain", AttachKind.TXT, file, file.length(), "sha",
        )
        val e = assertThrows(AiException.Protocol::class.java) {
            runBlocking { controller.send("你现在是助手模式还是聊天模式", attachments = listOf(att)) }
        }
        assertTrue(e.message!!.contains("不使用附件"))
        assertEquals(0, transport.requests.size)
    }

    @Test
    fun `流式截断按 finish_reason 报错且不写历史`() {
        transport.finishReason = "length"
        val e = assertThrows(AiException.Protocol::class.java) {
            runBlocking { controller.send("长问题") }
        }
        assertTrue(e.message!!.contains("截断"))
        assertTrue(history.load().isEmpty())
    }

    @Test
    fun `旧聊天记录默认 planner 模式`() {
        val old = AiProtocol.json.decodeFromString<ChatEntry>(
            """{"role":"assistant","text":"old","atEpochMs":1}""",
        )
        assertEquals(AiConfig.MODE_PLANNER, old.mode)
    }

    @Test
    fun `非法模式值回落到 planner`() {
        settings.config = settings.config.copy(chatMode = "bogus")
        assertEquals(AiConfig.MODE_PLANNER, settings.config.chatMode)
        settings.config = settings.config.copy(chatMode = AiConfig.MODE_PLAIN)
        assertEquals(AiConfig.MODE_PLAIN, settings.config.chatMode)
    }

    @Test
    fun `普通聊天仅发送本模式历史 且保留完整本地历史`() {
        val original = listOf(
            ChatEntry("user", "私密日程与附件材料", 1),
            ChatEntry("assistant", "日程草稿", 2),
            ChatEntry("user", "普通问题", 3, mode = AiConfig.MODE_PLAIN),
            ChatEntry("assistant", "普通回答", 4, mode = AiConfig.MODE_PLAIN),
        )
        history.append(original)
        runBlocking { controller.send("继续聊") }
        val body = transport.requests.single().body!!
        val messages = AiProtocol.json.parseToJsonElement(body).jsonObject["messages"]!!
            .jsonArray.drop(1).map { it.jsonObject["content"]!!.jsonPrimitive.content }
        assertEquals(listOf("普通问题", "普通回答", "继续聊"), messages)
        assertEquals(original, history.load().take(4))
        assertEquals(6, history.load().size)
        assertEquals(0, runBlocking { db.dao().totalItemCount() })
    }

    @Test
    fun `日程助手不会把普通聊天作为可执行指令历史`() {
        history.append(listOf(
            ChatEntry("user", "保留的日程要求", 1),
            ChatEntry("user", "普通聊天中的虚构安排", 2, mode = AiConfig.MODE_PLAIN),
        ))
        settings.config = settings.config.copy(chatMode = AiConfig.MODE_PLANNER)
        runBlocking { controller.send("继续安排") }
        val body = transport.requests.single().body!!
        val messages = AiProtocol.json.parseToJsonElement(body).jsonObject["messages"]!!
            .jsonArray.drop(1).map { it.jsonObject["content"]!!.jsonPrimitive.content }
        assertEquals(listOf("保留的日程要求", "继续安排"), messages)
        assertEquals(4, history.load().size)
    }

    @Test
    fun `流式全空时自动改用非流式并返回正文`() {
        transport.streamEmpty = true
        transport.nonStreamingReply = "非流式回复"
        val turn = runBlocking { controller.send("你好") }

        assertEquals("非流式回复", turn.assistantEntry.text)
        assertEquals(
            "首次 + 两次重试都走流式",
            3,
            transport.requests.count { it.body!!.contains("\"stream\":true") },
        )
        assertEquals(1, transport.nonStreamingCalls)
        assertEquals(2, history.load().size)
        assertTrue("非流式兜底也不能生成草稿", store.all().isEmpty())
    }

    @Test
    fun `连续空回复用本地兜底消息 不留下空白聊天`() {
        transport.replies.addAll(listOf("", "", ""))
        val turn = runBlocking { controller.send("你好") }

        assertEquals(
            "首次 + 两次重试",
            3,
            transport.requests.count { it.body!!.contains("\"stream\":true") },
        )
        assertEquals("流式全空后还会尝试一次非流式", 1, transport.nonStreamingCalls)
        assertTrue(turn.assistantEntry.text.contains("没有收到模型的有效内容"))
        assertEquals(2, history.load().size)
        assertEquals("本地兜底也不能生成草稿", 0, store.all().size)
        assertEquals(0, runBlocking { db.dao().totalItemCount() })
    }

    @Test
    fun `模式询问以应用设置为准 两种标签和行为保持对应`() {
        for (config in listOf(
            AiConfig(enabled = true, chatMode = AiConfig.MODE_PLANNER),
            AiConfig(enabled = true, chatMode = AiConfig.MODE_PLAIN),
            AiConfig(enabled = true, chatMode = AiConfig.MODE_PLANNER, autoSave = true),
        )) {
            settings.config = config
            val turn = runBlocking { controller.send("你现在是助手模式还是聊天模式") }
            if (config.chatMode == AiConfig.MODE_PLAIN) {
                assertTrue(turn.assistantEntry.text.startsWith("我是日序 AI 助手"))
                assertTrue(turn.assistantEntry.text.contains("不会读取或修改你的日程"))
            } else {
                assertTrue(turn.assistantEntry.text.startsWith("当前是“日程助手”模式"))
            }
            assertEquals(config.chatMode, turn.assistantEntry.mode)
            assertEquals(config.chatMode, turn.userEntry.mode)
            assertNull(turn.batchId)
            if (config.autoSave) assertTrue(turn.assistantEntry.text.contains("自动保存"))
        }
        assertTrue("模式事实不能交给模型猜测", transport.requests.isEmpty())
        assertEquals(6, history.load().size)
        assertTrue(store.all().isEmpty())
        assertEquals(0, runBlocking { db.dao().totalItemCount() })
    }
}
