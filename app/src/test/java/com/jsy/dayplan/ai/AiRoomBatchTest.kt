package com.jsy.dayplan.ai

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jsy.dayplan.ai.attach.AttachKind
import com.jsy.dayplan.ai.attach.AttachmentPlanner
import com.jsy.dayplan.ai.attach.PendingAttachment
import com.jsy.dayplan.ai.attach.PdfPageRenderer
import com.jsy.dayplan.core.FixedClock
import com.jsy.dayplan.data.PlanDb
import com.jsy.dayplan.data.PlanRepository
import com.jsy.dayplan.data.TodoEntity
import com.jsy.dayplan.reminder.FakeReminderScheduler
import com.jsy.dayplan.reminder.ReminderCoordinator
import com.jsy.dayplan.reminder.ReminderPlans
import com.jsy.dayplan.reminder.ReminderScheduler
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Room 真库端到端：发送→预览零写入→勾选导入→真实 ID/提醒/组件回调→幂等→撤销，
 * 以及整批回滚、版本冲突、数据世代失效、附件批次与晚到响应保护。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = com.jsy.dayplan.DayPlanApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AiRoomBatchTest {

    private lateinit var db: PlanDb
    private lateinit var repo: PlanRepository
    private lateinit var coordinator: ReminderCoordinator
    private lateinit var settings: AiSettingsStore
    private lateinit var keyStore: SecureKeyStore
    private lateinit var store: BatchStore
    private lateinit var history: ChatStore
    private lateinit var executor: PlanDraftExecutor
    private lateinit var controller: ChatController
    private lateinit var transport: ScriptedTransport
    private lateinit var scheduler: FakeReminderScheduler
    private lateinit var tmp: File
    private var widgetRefreshes = 0

    private class ScriptedTransport(private var bodies: List<String>) : ChatTransport {
        val requests = mutableListOf<TransportRequest>()
        var beforeReturn: (() -> Unit)? = null
        private var index = 0

        override suspend fun call(request: TransportRequest): TransportResponse {
            requests.add(request)
            beforeReturn?.invoke()
            val content = bodies[minOf(index, bodies.lastIndex)]
            index++
            val quoted = kotlinx.serialization.json.JsonPrimitive(content).toString()
            return TransportResponse(
                200,
                """{"choices":[{"message":{"content":$quoted},"finish_reason":"stop"}],
                   "usage":{"prompt_tokens":10,"completion_tokens":5}}""",
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
        repo.onDataChanged = { widgetRefreshes++ }
        scheduler = FakeReminderScheduler()
        coordinator = ReminderCoordinator(db.dao(), scheduler)
        settings = AiSettingsStore(app)
        settings.config = AiConfig(enabled = true, autoSave = false)
        tmp = Files.createTempDirectory("dayplan-room-ai").toFile()
        keyStore = SecureKeyStore(File(tmp, "key"), InMemoryKeyCipher())
        keyStore.saveApiKey("sk-test-1234567890abcdef")
        store = BatchStore(File(tmp, "batches"))
        history = ChatStore(File(tmp, "chat.json"))
        executor = PlanDraftExecutor(repo, coordinator, settings, store)
        transport = ScriptedTransport(listOf("""{"kind":"chat","reply":"ok"}"""))
        val client = DeepSeekClient(apiKeyProvider = { keyStore.loadApiKey() }, transport = transport)
        val planner = AttachmentPlanner(noopRenderer(), previewDir = null)
        controller = ChatController(
            client, settings, keyStore, FixedClock(LocalDateTime.of(2026, 10, 25, 9, 0)),
            repo, executor, store, history, planner,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun count() = runBlocking { db.dao().totalItemCount() }

    private fun scheduleAction(
        id: String,
        title: String,
        date: String = "2099-12-31",
        start: String = "09:00",
        end: String = "10:00",
        remind: Int? = 15,
    ): String {
        val rem = if (remind != null) ""","remindMinutesBefore":$remind""" else ""
        return """{"clientActionId":"$id","op":"create_schedule","title":"$title",
             "startDate":"$date","endDate":"$date","allDay":false,
             "startTime":"$start","endTime":"$end","category":0$rem}"""
    }

    private fun todoAction(id: String, title: String, date: String? = "2099-12-30"): String {
        val due = if (date != null) ""","dueDate":"$date"""" else ""
        return """{"clientActionId":"$id","op":"create_todo","title":"$title"$due}"""
    }

    private fun planBody(vararg actions: String, reply: String = "已整理，请确认。") =
        """{"schemaVersion":1,"kind":"plan","reply":"$reply","questions":[],"actions":[${actions.joinToString(",")}]}"""

    private fun chatBody(reply: String = "好的，不安排。") =
        """{"schemaVersion":1,"kind":"chat","reply":"$reply","questions":[],"actions":[]}"""

    private fun useResponses(vararg bodies: String) {
        transport = ScriptedTransport(bodies.toList())
        val client = DeepSeekClient(apiKeyProvider = { keyStore.loadApiKey() }, transport = transport)
        controller = ChatController(
            client, settings, keyStore, FixedClock(LocalDateTime.of(2026, 10, 25, 9, 0)),
            repo, executor, store, history, AttachmentPlanner(noopRenderer(), previewDir = null),
        )
    }

    // ---------- 纯聊天 / 预览 / 保存 ----------

    @Test
    fun `否定与普通聊天不写库`() {
        useResponses(chatBody())
        val turn = runBlocking { controller.send("先别安排明天的事") }
        assertNull(turn.batchId)
        assertEquals(0, count())
        assertEquals(2, history.load().size)
    }

    @Test
    fun `计划先预览零写入 确认后真实落库并注册提醒`() {
        useResponses(planBody(scheduleAction("a1", "读文献"), todoAction("a2", "交报告")))
        val turn = runBlocking { controller.send("明天上午九点到十点读文献，提前十五分钟提醒；下周五交报告") }
        assertNotNull(turn.batchId)
        assertEquals("待确认前不得写库", 0, count())
        val batch = store.load(turn.batchId!!)!!
        assertEquals("PENDING", batch.state)
        assertEquals(2, turn.reviews.size)
        assertTrue(turn.reviews.all { it.status == "ok" })

        val outcome = runBlocking {
            executor.applySelected(batch.batchId, batch.actions.map { it.clientActionId })
        }
        assertTrue(outcome is ApplyOutcome.Success)
        assertEquals(2, count())
        val schedule = runBlocking { db.dao().allSchedules().single() }
        val todo = runBlocking { db.dao().allTodos().single() }
        assertTrue("必须返回真实 ID", schedule.id > 0 && todo.id > 0)
        val applied = (outcome as ApplyOutcome.Success).batch.applied!!
        assertEquals(setOf(schedule.id, todo.id), applied.items.map { it.id }.toSet())
        assertFalse("提醒调度正常时不标记待重建", applied.remindersPending)
        assertTrue("组件刷新回调必须触发", widgetRefreshes >= 1)
        assertTrue(scheduler.scheduled.isNotEmpty())
        assertEquals("0=关闭语义：只有一条带提醒的日程", 1, scheduler.scheduled.size)

        controller.markBatchApplied(batch.batchId)
        assertEquals(
            "手动确认后历史条目应变为可撤销的结果卡片",
            "result",
            history.load().first { it.batchId == batch.batchId }.kind,
        )
    }

    @Test
    fun `重复保存同一批次幂等`() {
        useResponses(planBody(todoAction("a1", "交报告")))
        val turn = runBlocking { controller.send("交报告") }
        val batch = store.load(turn.batchId!!)!!
        val first = runBlocking { executor.applySelected(batch.batchId, listOf("a1")) }
        assertTrue(first is ApplyOutcome.Success)
        val again = runBlocking { executor.applySelected(batch.batchId, listOf("a1")) }
        assertTrue(again is ApplyOutcome.AlreadyApplied)
        assertEquals(1, count())
    }

    @Test
    fun `撤销本批删除新增条目`() {
        useResponses(planBody(todoAction("a1", "交报告"), scheduleAction("a2", "读文献")))
        val turn = runBlocking { controller.send("安排") }
        val batch = store.load(turn.batchId!!)!!
        runBlocking { executor.applySelected(batch.batchId, listOf("a1", "a2")) }
        assertEquals(2, count())
        val undo = runBlocking { executor.undo(batch.batchId) }
        assertTrue(undo is UndoOutcome.Success)
        assertEquals(0, count())
        assertEquals("UNDONE", store.load(batch.batchId)!!.state)
    }

    @Test
    fun `撤销遇用户后续编辑保留当前内容`() {
        useResponses(planBody(todoAction("a1", "交报告")))
        val turn = runBlocking { controller.send("交报告") }
        val batch = store.load(turn.batchId!!)!!
        runBlocking { executor.applySelected(batch.batchId, listOf("a1")) }
        val row = runBlocking { db.dao().allTodos().single() }
        runBlocking { repo.saveTodo(row.copy(title = "用户改过的标题")) }

        val undo = runBlocking { executor.undo(batch.batchId) }
        assertTrue(undo is UndoOutcome.Partial)
        assertTrue((undo as UndoOutcome.Partial).keptTitles.any { it.contains("交报告") })
        assertEquals("用户改过的标题", runBlocking { db.dao().allTodos().single().title })
    }

    @Test
    fun `只导入勾选的条目`() {
        useResponses(planBody(todoAction("a1", "A"), todoAction("a2", "B"), todoAction("a3", "C")))
        val turn = runBlocking { controller.send("三条") }
        val batch = store.load(turn.batchId!!)!!
        val outcome = runBlocking { executor.applySelected(batch.batchId, listOf("a2")) }
        assertTrue(outcome is ApplyOutcome.Success)
        assertEquals(1, count())
        assertEquals("B", runBlocking { db.dao().allTodos().single().title })
    }

    @Test
    fun `任一条目无效整批不写`() {
        useResponses(
            planBody(
                todoAction("a1", "好条目"),
                """{"clientActionId":"a2","op":"create_schedule","title":"缺时间","startDate":"2099-12-31","endDate":"2099-12-31","allDay":false}""",
            ),
        )
        val turn = runBlocking { controller.send("安排") }
        val batch = store.load(turn.batchId!!)!!
        assertTrue(turn.reviews.any { it.status == "invalid" })
        val outcome = runBlocking {
            executor.applySelected(batch.batchId, batch.actions.map { it.clientActionId })
        }
        assertTrue(outcome is ApplyOutcome.Failed)
        assertTrue((outcome as ApplyOutcome.Failed).message.contains("未通过本地校验"))
        assertEquals(0, count())
    }

    @Test
    fun `超过20条拆成多批 不伪报全部完成`() {
        settings.config = settings.config.copy(autoSave = true)
        val actions = (1..25).map { todoAction("a$it", "任务$it") }
        useResponses(planBody(*actions.toTypedArray()))
        val turn = runBlocking { controller.send("25 个任务") }
        assertTrue(turn.warnings.any { it.contains("已分为 2 批") })
        val pending = store.all().filter { it.isPending }.sortedBy { it.createdAtEpochMs }
        assertEquals(2, pending.size)
        assertEquals(20, pending[0].actions.size)
        assertEquals(5, pending[1].actions.size)
        assertEquals("超过 20 条不得自动保存", 0, count())

        val first = runBlocking {
            executor.applySelected(pending[0].batchId, pending[0].actions.map { it.clientActionId })
        }
        assertTrue(first is ApplyOutcome.Success)
        assertEquals(20, count())
        assertEquals("第二批次仍待确认", 1, store.all().count { it.isPending })
        val second = runBlocking {
            executor.applySelected(pending[1].batchId, pending[1].actions.map { it.clientActionId })
        }
        assertTrue(second is ApplyOutcome.Success)
        assertEquals(25, count())
    }

    // ---------- 自动保存 ----------

    @Test
    fun `自动保存仅在整批明确新增且合规时生效`() {
        settings.config = settings.config.copy(autoSave = true)
        useResponses(planBody(scheduleAction("a1", "读文献")))
        val turn = runBlocking { controller.send("明天九点读文献一小时") }
        assertEquals("result", turn.assistantEntry.kind)
        assertEquals(1, count())
        val batch = store.load(turn.batchId!!)!!
        assertEquals("APPLIED", batch.state)
        assertNotNull(batch.applied)
    }

    @Test
    fun `含假设的动作不自动保存`() {
        settings.config = settings.config.copy(autoSave = true)
        val action = """
            {"clientActionId":"a1","op":"create_schedule","title":"复习",
             "startDate":"2099-12-31","endDate":"2099-12-31","allDay":false,
             "startTime":"20:00","endTime":"20:30","assumptions":["自动选择 20:00–20:30"]}
        """.trimIndent()
        useResponses(planBody(action))
        val turn = runBlocking { controller.send("帮我找个空闲的时间复习") }
        assertEquals("plan", turn.assistantEntry.kind)
        assertEquals(0, count())
        assertEquals("PENDING", store.load(turn.batchId!!)!!.state)
    }

    // ---------- 附件批次 / 来源 ----------

    private fun txtAttachment(content: String = "周一 09:00 高数\n周二 14:00 英语", sha: String = "sha-1"): PendingAttachment {
        val f = File(tmp, "课表.txt").apply { writeText(content, Charsets.UTF_8) }
        return PendingAttachment("att1", "课表.txt", "text/plain", AttachKind.TXT, f, f.length(), sha)
    }

    @Test
    fun `附件批次确认前零写入 来源有效才可保存`() {
        settings.config = settings.config.copy(autoSave = true)
        val att = txtAttachment()
        val action = """
            {"clientActionId":"a1","op":"create_schedule","title":"高数",
             "startDate":"2099-12-31","endDate":"2099-12-31","allDay":false,
             "startTime":"09:00","endTime":"10:00","sourceIds":["txt1-L1"]}
        """.trimIndent()
        useResponses(planBody(action))
        val turn = runBlocking { controller.send("把附件里的课加入日程", listOf(att)) }
        val batch = store.load(turn.batchId!!)!!
        assertEquals("attachment", batch.origin)
        assertEquals("附件批次即使开启自动保存也必须先预览", "PENDING", batch.state)
        assertEquals(0, count())
        assertTrue(turn.reviews.single().sourceLabel.contains("txt1-L1"))

        val outcome = runBlocking { executor.applySelected(batch.batchId, listOf("a1")) }
        assertTrue(outcome is ApplyOutcome.Success)
        assertEquals(1, count())

        // 重发同一文件：提示已导入但允许再次导入
        useResponses(planBody(action))
        val again = runBlocking { controller.send("再导入一次", listOf(att)) }
        assertTrue(again.warnings.any { it.contains("此前已导入过") })
    }

    @Test
    fun `未知 sourceId 必须拒绝而不是编造页码`() {
        val att = txtAttachment()
        val action = """
            {"clientActionId":"a1","op":"create_todo","title":"交报告","dueDate":"2099-12-30",
             "sourceIds":["pdf99-p7"]}
        """.trimIndent()
        useResponses(planBody(action))
        val turn = runBlocking { controller.send("只导入这一条", listOf(att)) }
        val review = turn.reviews.single()
        assertEquals("invalid", review.status)
        assertTrue(review.problems.any { it.contains("不存在的来源") })
        val outcome = runBlocking { executor.applySelected(turn.batchId!!, listOf("a1")) }
        assertTrue(outcome is ApplyOutcome.Failed)
        assertEquals(0, count())
    }

    @Test
    fun `图片附件经本地编码进入真实请求体`() {
        val png = File(tmp, "timetable.png")
        val bmp = Bitmap.createBitmap(80, 50, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        png.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        val att = PendingAttachment(
            "imgatt1", "timetable.png", "image/png", AttachKind.IMAGE_PNG,
            png, png.length(), "sha-img-1",
        )
        val action = """
            {"clientActionId":"a1","op":"create_schedule","title":"高数",
             "startDate":"2099-12-31","endDate":"2099-12-31","allDay":false,
             "startTime":"09:00","endTime":"10:00","sourceIds":["img1"]}
        """.trimIndent()
        useResponses(planBody(action))
        val turn = runBlocking { controller.send("识别这张课表", listOf(att)) }
        val requestBody = transport.requests.single().body!!
        assertTrue("图片必须编码成 data URL 放进请求体", requestBody.contains("data:image/jpeg;base64,"))
        assertTrue("请求体应包含图片清单", requestBody.contains("【本次同步发送的图片】"))
        assertTrue("图片清单应带 img1 来源", requestBody.contains("img1"))
        assertEquals("attachment", store.load(turn.batchId!!)!!.origin)
        assertEquals("ok", turn.reviews.single().status)
        assertEquals(0, count())
    }

    // ---------- 版本 / 世代 / 晚到响应 / 提醒失败 ----------

    @Test
    fun `AI 请求期间用户改动导致版本冲突时不覆盖`() {
        val existing = runBlocking {
            repo.saveSchedule(
                com.jsy.dayplan.data.ScheduleEntity(
                    title = "原日程", startDate = "2099-12-31", endDate = "2099-12-31",
                    allDay = false, startTime = "09:00", endTime = "10:00",
                ),
            )
        }
        val update = """
            {"clientActionId":"a1","op":"update_schedule","targetId":${existing.id},"expectedVersion":${existing.version},
             "title":"新标题","startDate":"2099-12-31","endDate":"2099-12-31","allDay":false,
             "startTime":"11:00","endTime":"12:00"}
        """.trimIndent()
        useResponses(planBody(update))
        val turn = runBlocking { controller.send("把原日程改到十一点") }
        assertTrue(turn.reviews.single().status == "ok")
        // 请求返回后、保存前，用户自己编辑
        runBlocking { repo.saveSchedule(existing.copy(title = "用户刚改的")) }
        val outcome = runBlocking { executor.applySelected(turn.batchId!!, listOf("a1")) }
        assertTrue(outcome is ApplyOutcome.Failed)
        assertEquals("用户刚改的", runBlocking { db.dao().allSchedules().single().title })
    }

    @Test
    fun `备份恢复递增数据世代后旧草稿失效`() {
        useResponses(planBody(todoAction("a1", "交报告")))
        val turn = runBlocking { controller.send("交报告") }
        settings.bumpDataGeneration()
        val outcome = runBlocking { executor.applySelected(turn.batchId!!, listOf("a1")) }
        assertTrue(outcome is ApplyOutcome.Stale)
        assertEquals(0, count())
    }

    @Test
    fun `清空对话后晚到响应不写库`() {
        useResponses(planBody(todoAction("a1", "交报告")))
        transport.beforeReturn = { settings.newChatSession() }
        val error = runCatching { runBlocking { controller.send("交报告") } }.exceptionOrNull()
        assertTrue(error is AiException.Cancelled)
        assertEquals(0, count())
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `提醒调度失败仍保存 且不因重试重复写库`() {
        val throwing = object : ReminderScheduler {
            override fun canScheduleExact() = true
            override fun schedule(plan: ReminderPlans.ReminderPlan, exactAllowed: Boolean) {
                throw IllegalStateException("alarm 不可用")
            }

            override fun cancel(occurrenceKey: String) = Unit
            override fun cancelAll(keys: List<String>) = Unit
        }
        val coord = ReminderCoordinator(db.dao(), throwing)
        val exec = PlanDraftExecutor(repo, coord, settings, store)
        useResponses(planBody(scheduleAction("a1", "读文献")))
        val client = DeepSeekClient(apiKeyProvider = { keyStore.loadApiKey() }, transport = transport)
        val ctrl = ChatController(
            client, settings, keyStore, FixedClock(LocalDateTime.of(2026, 10, 25, 9, 0)),
            repo, exec, store, history, AttachmentPlanner(noopRenderer(), previewDir = null),
        )
        val turn = runBlocking { ctrl.send("明天九点读文献") }
        val outcome = runBlocking { exec.applySelected(turn.batchId!!, listOf("a1")) }
        assertTrue(outcome is ApplyOutcome.Success)
        val success = outcome as ApplyOutcome.Success
        assertTrue("数据已保存但提醒失败必须分开报告", success.remindersPending)
        assertEquals(1, count())
        val again = runBlocking { exec.applySelected(turn.batchId!!, listOf("a1")) }
        assertTrue(again is ApplyOutcome.AlreadyApplied)
        assertEquals(1, count())
    }
}
