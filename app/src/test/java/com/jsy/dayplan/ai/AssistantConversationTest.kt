package com.jsy.dayplan.ai

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jsy.dayplan.ai.attach.AttachmentPlanner
import com.jsy.dayplan.ai.attach.PdfPageRenderer
import com.jsy.dayplan.core.FixedClock
import com.jsy.dayplan.data.*
import com.jsy.dayplan.reminder.FakeReminderScheduler
import com.jsy.dayplan.reminder.ReminderCoordinator
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AssistantConversationTest {
    private lateinit var db: PlanDb
    private lateinit var repo: PlanRepository
    private lateinit var settings: AiSettingsStore
    private lateinit var store: BatchStore
    private lateinit var history: ChatStore
    private lateinit var executor: PlanDraftExecutor
    private lateinit var controller: ChatController
    private lateinit var dir: File
    private val wire = Scripted()

    private class Scripted : StreamingChatTransport {
        val requests = mutableListOf<TransportRequest>()
        val responses = ArrayDeque<ChatResult>()
        var fail: AiException? = null
        var beforeReturn: (() -> Unit)? = null
        private fun next(request: TransportRequest): ChatResult {
            requests.add(request)
            fail?.let { throw it }
            beforeReturn?.invoke()
            return responses.removeFirst()
        }
        override suspend fun stream(request: TransportRequest, onDelta: (String) -> Unit): ChatResult {
            val result = next(request)
            if (result.content.isNotEmpty()) onDelta(result.content)
            return result
        }
        override suspend fun call(request: TransportRequest): TransportResponse {
            val result = next(request)
            return TransportResponse(200, buildJsonObject {
                put("choices", buildJsonArray { add(buildJsonObject {
                    put("finish_reason", result.finishReason)
                    put("message", buildJsonObject {
                        put("content", result.content)
                        put("reasoning_content", result.reasoning)
                        put("tool_calls", buildJsonArray { result.toolCalls.forEach { tool -> add(buildJsonObject {
                            put("id", tool.id); put("type", "function")
                            put("function", buildJsonObject { put("name", tool.name); put("arguments", tool.arguments) })
                        }) } })
                    })
                }) })
            }.toString())
        }
    }

    @Before fun setup() {
        val app: Application = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(app, PlanDb::class.java).allowMainThreadQueries().build()
        repo = PlanRepository(db)
        settings = AiSettingsStore(app)
        settings.config = AiConfig(enabled = true, autoSave = true, chatMode = AiConfig.MODE_ASSISTANT)
        val session = settings.newChatSession()
        settings.markBoundaryPersisted(settings.dataGeneration, session)
        AiExecutionGate.set(settings.dataGeneration, session, bumpToken = true)
        dir = Files.createTempDirectory("assistant-v178-").toFile()
        store = BatchStore(File(dir, "batches"))
        history = ChatStore(File(dir, "history.json"))
        val keys = SecureKeyStore(File(dir, "key"), InMemoryKeyCipher()).also { it.saveApiKey("sk-test-assistant-only") }
        executor = PlanDraftExecutor(repo, ReminderCoordinator(db.dao(), FakeReminderScheduler()), settings, store)
        controller = ChatController(DeepSeekClient(apiKeyProvider = { keys.loadApiKey() }, transport = wire),
            settings, keys, FixedClock(LocalDateTime.of(2026, 10, 26, 18, 0)), repo, executor, store, history,
            AttachmentPlanner(object : PdfPageRenderer {
                override fun renderPage(file: File, pageIndex: Int, maxDimension: Int) = null
            }, null))
    }
    @After fun close() { db.close() }

    private fun tool(action: String, operation: String = "create", id: String? = null): ChatResult {
        val args = buildJsonObject {
            put("operation", operation)
            id?.let { put("draftId", it) }
            put("actions", AiProtocol.json.parseToJsonElement("[$action]"))
        }
        return ChatResult("", "tool_calls", null, listOf(ToolCall("call_test", "propose_draft", args.toString())))
    }
    private val schedule = """{"op":"create_schedule","title":"地铁站集合","startDate":"2026-10-27","startTime":"09:00","location":"洋湖新城2号口"}"""
    private fun send(text: String = "明天九点在洋湖新城2号口集合"): ChatTurn = runBlocking { controller.send(text) }
    private fun batch(turn: ChatTurn) = store.load(turn.batchId!!)!!

    @Test fun `只有工具没有正文也能生成草稿 且必须确认才保存`() {
        wire.responses.add(tool(schedule))
        val turn = send()
        val draft = batch(turn)
        assertEquals("09:30", draft.actions.single().endTime)
        assertTrue(draft.actions.single().assumptions.isNotEmpty())
        assertTrue(turn.reviews.all { it.status == "ok" })
        assertEquals(0, runBlocking { repo.loadAll().schedules.size })
        assertFalse(executor.autoSaveEligible(draft, turn.reviews, settings.config))
        val request = AiProtocol.json.parseToJsonElement(wire.requests.single().body!!).jsonObject
        assertNotNull(request["tools"])
        assertNull(request["response_format"])
        assertNull(request["thinking"])
        assertNull(request["reasoning_effort"])
        assertTrue(turn.assistantEntry.text.contains("尚未保存"))
    }

    @Test fun `缺钟点先保留草稿 短回复补同一草稿并保留地点日期`() {
        wire.responses.add(tool("""{"op":"create_schedule","title":"去医院","startDate":"2026-10-27","location":"市医院"}"""))
        val first = send("明早去市医院，帮我记一下")
        val old = batch(first)
        assertEquals(1, old.questions.size)
        assertEquals("invalid", first.reviews.single().status)
        wire.responses.add(tool("""{"startTime":"09:00"}""", "update", old.batchId))
        val next = send("九点吧")
        val current = batch(next)
        assertEquals(old.batchId, next.batchId)
        assertEquals(old.actions.single().clientActionId, current.actions.single().clientActionId)
        assertEquals("市医院", current.actions.single().location)
        assertEquals("2026-10-27", current.actions.single().startDate)
        assertTrue(current.questions.isEmpty())
        assertEquals(1, store.all().size)
        assertEquals(1, history.load().count { it.kind == "plan" })
        assertEquals("ok", next.reviews.single().status)
    }

    @Test fun `改开始时间会移动默认结束时间 跨午夜保留正确日期`() {
        wire.responses.add(tool(schedule))
        val first = send()
        wire.responses.add(tool("""{"startTime":"23:45"}""", "update", first.batchId))
        val draft = batch(send("改到晚上十一点四十五"))
        assertEquals("2026-10-28", draft.actions.single().endDate)
        assertEquals("00:15", draft.actions.single().endTime)
    }

    @Test fun `用户明确时长不用默认假设 修改开始仍保留时长`() {
        wire.responses.add(tool("""{"op":"create_schedule","title":"跑步","startDate":"2026-10-27","startTime":"09:00","durationMinutes":60}"""))
        val first = send("明天九点跑步一小时")
        assertTrue(batch(first).actions.single().assumptions.isEmpty())
        wire.responses.add(tool("""{"startTime":"10:00"}""", "update", first.batchId))
        assertEquals("11:00", batch(send("改十点")).actions.single().endTime)
    }

    @Test fun `无日期任务可以生成待办 正常闲聊不创建任何草稿`() {
        wire.responses.add(ChatResult("不想上班的时候可以先休息一下。", "stop", null))
        assertNull(send("明天真不想上班").batchId)
        assertTrue(store.all().isEmpty())
        wire.responses.add(tool("""{"op":"create_todo","title":"买牙膏"}"""))
        val turn = send("记一下买牙膏")
        assertEquals("ok", turn.reviews.single().status)
        assertNull(batch(turn).actions.single().dueDate)
    }

    @Test fun `流式空回复改用一次非流式工具请求恢复 不写假成功`() {
        wire.responses.add(ChatResult("", "stop", null))
        wire.responses.add(tool(schedule))
        var resets = 0
        val result = runBlocking { controller.send("明天九点集合", onReset = { resets++ }) }
        assertNotNull(result.batchId)
        assertEquals(2, wire.requests.size)
        assertTrue(wire.requests[0].body!!.contains("\"stream\":true"))
        assertTrue(wire.requests[1].body!!.contains("\"stream\":false"))
        assertTrue(resets > 0)
        assertEquals(2, history.load().size)
    }

    @Test fun `连续为空或只有思考必须失败 不写本地成功聊天或草稿`() {
        repeat(2) { wire.responses.add(ChatResult("", "stop", null, reasoning = "不能冒充最终回复")) }
        val error = assertThrows(AiException.Protocol::class.java) { send() }
        assertTrue(error.message!!.contains("尚未保存"))
        assertEquals(2, wire.requests.size)
        assertTrue(history.load().isEmpty())
        assertTrue(store.all().isEmpty())
    }

    @Test fun `坏工具参数自动修复一次且没有半成品落盘`() {
        wire.responses.add(ChatResult("", "tool_calls", null, listOf(ToolCall("bad", "propose_draft", "{"))))
        wire.responses.add(tool(schedule))
        assertNotNull(send().batchId)
        assertEquals(1, store.all().size)
        assertEquals(2, wire.requests.size)
    }

    @Test fun `截断的工具调用不可执行也不重复请求`() {
        wire.responses.add(tool(schedule).copy(finishReason = "length"))
        assertThrows(AiException.Protocol::class.java) { send() }
        assertEquals(1, wire.requests.size)
        assertTrue(store.all().isEmpty())
    }

    @Test fun `鉴权失败直接返回 不进行无意义重试`() {
        wire.fail = AiException.Auth("synthetic unauthorized")
        assertThrows(AiException.Auth::class.java) { send() }
        assertEquals(1, wire.requests.size)
        assertTrue(history.load().isEmpty())
    }

    @Test fun `请求中清空会话必须丢弃旧回复和工具`() {
        wire.responses.add(tool(schedule))
        wire.beforeReturn = { settings.newChatSession() }
        assertThrows(AiException.Cancelled::class.java) { send() }
        assertTrue(store.all().isEmpty())
        assertTrue(history.load().isEmpty())
    }

    @Test fun `确认保存幂等且仍可撤销`() = runBlocking {
        wire.responses.add(tool(schedule))
        val turn = send()
        val ids = batch(turn).actions.map { it.clientActionId }
        assertTrue(executor.applySelected(turn.batchId!!, ids) is ApplyOutcome.Success)
        controller.markBatchApplied(turn.batchId)
        assertTrue(executor.applySelected(turn.batchId, ids) is ApplyOutcome.AlreadyApplied)
        assertEquals(1, repo.loadAll().schedules.size)
        assertEquals("result", history.load().last().kind)
        assertTrue(executor.undo(turn.batchId) is UndoOutcome.Success)
        assertEquals(0, repo.loadAll().schedules.size)
    }

    @Test fun `取消草稿不触碰业务数据且移除确认卡`() {
        wire.responses.add(tool(schedule))
        val first = send()
        wire.responses.add(ChatResult("", "tool_calls", null, listOf(ToolCall("cancel", "cancel_draft", """{"draftId":"${first.batchId}"}"""))))
        val canceled = send("先别记了")
        assertNull(canceled.batchId)
        assertEquals("CANCELLED", store.load(first.batchId!!)!!.state)
        assertFalse(history.load().any { it.kind == "plan" })
        assertEquals(0, runBlocking { repo.loadAll().schedules.size })
    }

    @Test fun `已保存草稿不能被工具更新或再次创建`() = runBlocking {
        wire.responses.add(tool(schedule))
        val first = send()
        executor.applySelected(first.batchId!!, batch(first).actions.map { it.clientActionId })
        repeat(2) { wire.responses.add(tool("""{"startTime":"10:00"}""", "update", first.batchId)) }
        assertThrows(AiException.Protocol::class.java) { send("改十点") }
        assertEquals(1, store.all().size)
        assertEquals("09:00", repo.loadAll().schedules.single().startTime)
    }

    @Test fun `草稿文件重开后仍可补充 且新工具不能注入附件证据或数据库目标`() {
        wire.responses.add(tool(schedule.dropLast(1) + """, "targetId":99,"sourceIds":["fake"]}"""))
        val first = send()
        store = BatchStore(File(dir, "batches"))
        // A fresh store instance reads the persisted draft; the next request includes its authoritative ID.
        assertEquals(first.batchId, store.all().single().batchId)
        assertNull(batch(first).actions.single().targetId)
        assertTrue(batch(first).actions.single().sourceIds.isEmpty())
        wire.responses.add(tool("""{"note":"带上证件"}""", "update", first.batchId))
        val next = send("备注带上证件")
        assertEquals("带上证件", batch(next).actions.single().note)
        assertTrue(wire.requests.last().body!!.contains(first.batchId!!))
    }

    @Test fun `字段类型错误会修复一次而不是抛出泛型崩溃`() {
        wire.responses.add(ChatResult("", "tool_calls", null,
            listOf(ToolCall("bad", "propose_draft", """{"operation":[],"actions":[]}"""))))
        wire.responses.add(tool(schedule))
        assertNotNull(send().batchId)
        assertEquals(2, wire.requests.size)
    }

    @Test fun `多项草稿按条目ID补充 保留未修改条目`() {
        wire.responses.add(tool(schedule + """,{"op":"create_todo","title":"带证件"}"""))
        val first = send()
        val todo = batch(first).actions.last()
        wire.responses.add(tool("""{"clientActionId":"${todo.clientActionId}","note":"身份证"}""", "update", first.batchId))
        val updated = batch(send("带的证件是身份证"))
        assertEquals(2, updated.actions.size)
        assertEquals("09:00", updated.actions.first().startTime)
        assertEquals("身份证", updated.actions.last().note)
    }
}
