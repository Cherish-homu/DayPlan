package com.jsy.dayplan.ai

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jsy.dayplan.ai.attach.AttachKind
import com.jsy.dayplan.ai.attach.AttachmentContextStore
import com.jsy.dayplan.ai.attach.AttachmentException
import com.jsy.dayplan.ai.attach.AttachmentPlanner
import com.jsy.dayplan.ai.attach.PendingAttachment
import com.jsy.dayplan.ai.attach.PdfPageRenderer
import com.jsy.dayplan.core.FixedClock
import com.jsy.dayplan.data.AiCommitEntity
import com.jsy.dayplan.data.AiExecutionGate
import com.jsy.dayplan.data.PlanDao
import com.jsy.dayplan.data.PlanDb
import com.jsy.dayplan.data.PlanRepository
import com.jsy.dayplan.data.ScheduleEntity
import com.jsy.dayplan.reminder.FakeReminderScheduler
import com.jsy.dayplan.reminder.ReminderCoordinator
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * v1.4 定向回归：并发/失败重试幂等、恢复与清空边界、附件多轮材料、
 * 批内冲突与动作身份、附件范围完整性。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = com.jsy.dayplan.DayPlanApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AiV14RegressionTest {

    private lateinit var db: PlanDb
    private lateinit var repo: PlanRepository
    private lateinit var settings: AiSettingsStore
    private lateinit var keys: SecureKeyStore
    private lateinit var store: BatchStore
    private lateinit var history: ChatStore
    private lateinit var contextStore: AttachmentContextStore
    private lateinit var executor: PlanDraftExecutor
    private lateinit var scheduler: FakeReminderScheduler
    private lateinit var dir: File
    private lateinit var transport: Scripted
    private lateinit var controller: ChatController

    private val responses = ArrayDeque<String>()
    private var beforeReturn: (() -> Unit)? = null

    private inner class Scripted : ChatTransport {
        val requests = mutableListOf<TransportRequest>()
        override suspend fun call(request: TransportRequest): TransportResponse {
            requests.add(request)
            beforeReturn?.invoke()
            val content = if (responses.isEmpty()) chatBody() else responses.removeFirst()
            return TransportResponse(
                200,
                """{"choices":[{"message":{"content":${JsonPrimitive(content)}},"finish_reason":"stop"}]}""",
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
        settings.newChatSession()
        dir = Files.createTempDirectory("v14-regression").toFile()
        store = BatchStore(File(dir, "batches"))
        history = ChatStore(File(dir, "chat.json"))
        contextStore = AttachmentContextStore(File(dir, "context"))
        keys = SecureKeyStore(File(dir, "key"), InMemoryKeyCipher())
        keys.saveApiKey("sk-v14-synthetic-key")
        scheduler = FakeReminderScheduler()
        executor = PlanDraftExecutor(repo, ReminderCoordinator(db.dao(), scheduler), settings, store)
        transport = Scripted()
        controller = newController(transport)
    }

    private fun newController(t: ChatTransport): ChatController = ChatController(
        DeepSeekClient(apiKeyProvider = { keys.loadApiKey() }, transport = t),
        settings, keys, FixedClock(LocalDateTime.of(2026, 10, 25, 9, 0)),
        repo, executor, store, history,
        AttachmentPlanner(noopRenderer(), null),
        contextStore = contextStore,
    )

    @After
    fun tearDown() { db.close() }

    private fun count() = runBlocking { db.dao().totalItemCount() }

    private fun schedule(id: String, title: String, date: String = "2099-12-30", start: String = "09:00", end: String = "10:00") =
        AiAction(
            clientActionId = id, op = "create_schedule", title = title,
            startDate = date, endDate = date, allDay = false,
            startTime = start, endTime = end,
        )

    private fun todo(id: String = "a1", title: String = "synthetic") =
        AiAction(clientActionId = id, op = "create_todo", title = title, dueDate = "2099-12-30")

    private fun plan(vararg a: AiAction) = AiProtocol.json.encodeToString(
        AiPlanResponse.serializer(),
        AiPlanResponse(1, "plan", "review", actions = a.toList()),
    )

    private fun chatBody() = """{"schemaVersion":1,"kind":"chat","reply":"ok"}"""

    private fun clarifyBody() =
        """{"schemaVersion":1,"kind":"clarify","reply":"Which year?","questions":["Which year?"]}"""

    private fun pending(vararg actions: AiAction): BatchRecord {
        val b = BatchRecord(
            "b_v14", "r_v14", settings.chatSessionId, System.currentTimeMillis(),
            settings.dataGeneration, "text", "review", actions = actions.toList(), state = "PENDING",
        )
        store.save(b)
        return b
    }

    private fun txtAttachment(marker: String, sha: String = "sha-txt"): PendingAttachment {
        val f = File(dir, "schedule.txt").apply { writeText("$marker meeting on 12-30 at 09:00") }
        return PendingAttachment("txt_att", "schedule.txt", "text/plain", AttachKind.TXT, f, f.length(), sha)
    }

    // ---------- F1 ----------

    @Test
    fun `同批次并发保存只写入一次`() = runBlocking {
        val b = pending(todo())
        val firstCommitted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val callbacks = AtomicInteger(0)
        repo.onDataChanged = {
            if (callbacks.incrementAndGet() == 1) {
                firstCommitted.countDown()
                check(releaseFirst.await(20, TimeUnit.SECONDS))
            }
        }
        val first = async(Dispatchers.IO) { executor.applySelected(b.batchId, listOf("a1")) }
        assertTrue(firstCommitted.await(20, TimeUnit.SECONDS))
        val second = try {
            executor.applySelected(b.batchId, listOf("a1"))
        } finally {
            releaseFirst.countDown()
        }
        val firstOutcome = first.await()
        assertTrue(firstOutcome is ApplyOutcome.Success)
        assertTrue(second is ApplyOutcome.AlreadyApplied)
        assertEquals(1, count())
        val commit = repo.aiCommit(b.batchId)!!
        assertTrue(commit.appliedJson.contains("\"id\""))
        repo.onDataChanged = null
    }

    @Test
    fun `提交后批次文件写失败 重试与丢失文件都不重复写入且可撤销`() = runBlocking {
        val b = pending(todo())
        val blocker = File(dir, "batches/${b.batchId}.json.tmp")
        assertTrue(blocker.mkdir())
        val first = executor.applySelected(b.batchId, listOf("a1"))
        assertTrue(first is ApplyOutcome.Success)
        assertEquals("业务已提交，文件失败不能谎报整批未写", 1, count())
        assertNotNull(repo.aiCommit(b.batchId))
        assertTrue(blocker.delete())
        val retry = executor.applySelected(b.batchId, listOf("a1"))
        assertTrue(retry is ApplyOutcome.AlreadyApplied)
        assertEquals(1, count())

        // 再模拟文件彻底丢失：提交事实仍可识别并撤销
        File(dir, "batches/${b.batchId}.json").delete()
        File(dir, "batches/${b.batchId}.json.bak").delete()
        // 新执行器实例模拟进程重启后仅凭 Room 提交事实识别
        val restarted = PlanDraftExecutor(
            repo, ReminderCoordinator(db.dao(), FakeReminderScheduler()), settings, store,
        )
        val retry2 = restarted.applySelected(b.batchId, listOf("a1"))
        assertTrue(retry2 is ApplyOutcome.AlreadyApplied)
        assertEquals(1, count())
        assertTrue(restarted.undo(b.batchId) is UndoOutcome.Success)
        assertEquals(0, count())
    }

    // ---------- F2 ----------

    @Test
    fun `恢复期间返回的旧响应零写入`() = runBlocking {
        responses.addLast(plan(todo()))
        val generationAtSend = settings.dataGeneration
        beforeReturn = {
            runBlocking {
                repo.applyRestore(
                    com.jsy.dayplan.backup.BackupCodec.toEntities(
                        repo.backupSnapshot("2026-10-25T09:00:00", "1.4"),
                    ),
                    File(dir, "restore"),
                )
            }
            settings.bumpDataGeneration()
            AiExecutionGate.invalidate()
        }
        val error = runCatching { controller.send("add a dated todo") }.exceptionOrNull()
        assertTrue(error is AiException.Cancelled)
        assertTrue(settings.dataGeneration > generationAtSend)
        assertEquals(0, count())
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `清空对话时已加载的旧批次不能继续保存`() = runBlocking {
        val b = pending(todo())
        val reading = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val realDao = db.dao()
        val gatedDao = object : PlanDao by realDao {
            override suspend fun allSchedules(): List<ScheduleEntity> {
                reading.countDown()
                check(proceed.await(20, TimeUnit.SECONDS))
                return realDao.allSchedules()
            }
        }
        val gatedExecutor = PlanDraftExecutor(
            PlanRepository(db, gatedDao),
            ReminderCoordinator(realDao, FakeReminderScheduler()),
            settings, store,
        )
        val save = async(Dispatchers.IO) { gatedExecutor.applySelected(b.batchId, listOf("a1")) }
        assertTrue(reading.await(20, TimeUnit.SECONDS))
        try {
            controller.clearConversation(com.jsy.dayplan.ai.attach.AttachmentStore(File(dir, "att")))
            assertEquals("CANCELLED", store.load(b.batchId)!!.state)
        } finally {
            proceed.countDown()
        }
        val outcome = save.await()
        assertTrue(outcome is ApplyOutcome.Stale)
        assertEquals(0, count())
    }

    @Test
    fun `恢复后旧提交不可撤销且旧草稿不可保存`() = runBlocking {
        val b = pending(todo())
        executor.applySelected(b.batchId, listOf("a1"))
        assertEquals(1, count())
        val newGen = settings.dataGeneration + 1
        repo.setAiEpoch(newGen, settings.chatSessionId)
        settings.markBoundaryPersisted(newGen, settings.chatSessionId)
        AiExecutionGate.set(newGen, settings.chatSessionId, bumpToken = true)
        assertTrue(executor.applySelected(b.batchId, listOf("a1")) is ApplyOutcome.Stale)
        assertTrue(executor.undo(b.batchId) is UndoOutcome.Stale)
        assertEquals(1, count())
        // 旧数据仍在，只是旧 AI 批次不能再操作
    }

    // ---------- F3 ----------

    @Test
    fun `附件澄清后纯文字追问仍携带原材料并保持附件批次`() = runBlocking {
        val att = txtAttachment("UNIQUE_V14_MARKER_7788")
        responses.addLast(clarifyBody())
        controller.send("import this file", listOf(att))
        assertTrue(transport.requests.last().body!!.contains("UNIQUE_V14_MARKER_7788"))
        assertEquals(1, contextStore.currentAttachments(settings.chatSessionId, settings.dataGeneration).size)

        responses.addLast(plan(todo()))
        controller.send("2099")
        val body = transport.requests.last().body!!
        assertTrue("追问必须仍含原文材料", body.contains("UNIQUE_V14_MARKER_7788"))
        val batch = store.all().single()
        assertEquals("attachment", batch.origin)
        assertTrue(batch.validSourceIds.contains("txt1-L1"))
        assertEquals(0, count())
    }

    @Test
    fun `图片材料跨控制器重启仍可续聊`() {
        val png = File(dir, "timetable.png")
        val bmp = Bitmap.createBitmap(60, 40, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        png.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        val att = PendingAttachment(
            "img_att", "timetable.png", "image/png", AttachKind.IMAGE_PNG,
            png, png.length(), "sha-img",
        )
        runBlocking {
            responses.addLast(clarifyBody())
            controller.send("read this timetable", listOf(att))
            // 新 controller 共享私有上下文目录，模拟进程重启
            val t2 = Scripted()
            val c2 = newController(t2)
            responses.addLast(plan(todo()))
            c2.send("year is 2099")
            val body = t2.requests.last().body!!
            assertTrue("图片必须重新编码进后续请求", body.contains("data:image/jpeg;base64,"))
            assertTrue(body.contains("【本次同步发送的图片】"))
            val batch = store.all().single()
            assertEquals("attachment", batch.origin)
            assertTrue(batch.validSourceIds.any { it.startsWith("img") })
        }
    }

    @Test
    fun `多附件续聊保留选页范围且只发送所选页`() {
        val txt = txtAttachment("MULTI_TXT_MARKER")
        val pdf = File(dir, "two-pages.pdf")
        com.tom_roush.pdfbox.pdmodel.PDDocument().use { doc ->
            fun page(text: String) {
                val p = com.tom_roush.pdfbox.pdmodel.PDPage(com.tom_roush.pdfbox.pdmodel.common.PDRectangle.A4)
                doc.addPage(p)
                com.tom_roush.pdfbox.pdmodel.PDPageContentStream(doc, p).use { cs ->
                    cs.beginText()
                    cs.setFont(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA, 12f)
                    cs.newLineAtOffset(40f, 700f)
                    cs.showText(text)
                    cs.endText()
                }
            }
            page("PAGE ONE SHOULD NOT BE SENT")
            page("PAGE TWO RANGE MARKER 2099")
            doc.save(pdf)
        }
        val pdfAtt = PendingAttachment(
            "pdf_att", "two-pages.pdf", "application/pdf", AttachKind.PDF,
            pdf, pdf.length(), "sha-pdf",
        )
        runBlocking {
            responses.addLast(clarifyBody())
            controller.send(
                "import both",
                listOf(txt, pdfAtt),
                com.jsy.dayplan.ai.attach.AttachmentOptions(pdfPages = mapOf("pdf_att" to setOf(2))),
            )
            responses.addLast(chatBody())
            controller.send("which year? 2099")
            val body = transport.requests.last().body!!
            assertTrue(body.contains("MULTI_TXT_MARKER"))
            assertTrue("第 2 页材料必须保留", body.contains("PAGE TWO RANGE MARKER 2099"))
            assertFalse("未选第 1 页不得发送", body.contains("PAGE ONE SHOULD NOT BE SENT"))
            assertTrue(body.contains("pdf2-p2") || body.contains("pdf2"))
            val ctx = contextStore.currentAttachments(settings.chatSessionId, settings.dataGeneration)
            assertEquals(2, ctx.size)
            assertTrue(ctx.first { it.id == "pdf_att" }.pdfPages == setOf(2))
        }
    }

    @Test
    fun `清空后材料不再续用 缓存缺失要求重选`() = runBlocking {
        val att = txtAttachment("UNIQUE_CLEAR_MARKER")
        responses.addLast(clarifyBody())
        controller.send("import", listOf(att))
        controller.clearConversation(com.jsy.dayplan.ai.attach.AttachmentStore(File(dir, "att")))
        assertTrue(contextStore.currentAttachments(settings.chatSessionId, settings.dataGeneration).isEmpty())

        responses.addLast(plan(todo()))
        controller.send("继续")
        assertFalse(transport.requests.last().body!!.contains("UNIQUE_CLEAR_MARKER"))

        // 文件缺失时必须要求重选，不能只拿文件名继续
        val att2 = txtAttachment("UNIQUE_MISSING_MARKER", sha = "sha2")
        responses.addLast(clarifyBody())
        controller.send("import again", listOf(att2))
        assertTrue(att2.file.delete())
        val e = assertThrows(AttachmentException::class.java) {
            runBlocking { controller.send("2099") }
        }
        assertTrue(e.message!!.contains("重新选择"))
    }

    // ---------- F4 ----------

    @Test
    fun `批内同时段不同标题阻止自动保存 但预览后可手动确认`() = runBlocking {
        settings.config = settings.config.copy(autoSave = true)
        responses.addLast(plan(schedule("a1", "first meeting"), schedule("a2", "second meeting")))
        val turn = controller.send("arrange two meetings")
        assertEquals(0, count())
        val batch = store.load(turn.batchId!!)!!
        assertTrue(turn.reviews.all { it.conflicts.isNotEmpty() })
        assertTrue(executor.autoSaveEligible(batch, turn.reviews, settings.config).not())
        val manual = executor.applySelected(batch.batchId, listOf("a1", "a2"))
        assertTrue("预览后允许用户明确确认重叠", manual is ApplyOutcome.Success)
        assertEquals(2, count())
    }

    @Test
    fun `重复与空动作身份不自动保存`() = runBlocking {
        settings.config = settings.config.copy(autoSave = true)
        val same = schedule("same-id", "same meeting")
        responses.addLast(plan(same, same))
        val turn = controller.send("add one meeting")
        assertEquals(0, count())
        assertTrue(turn.reviews.all { it.status == "invalid" })
        assertTrue(executor.applySelected(turn.batchId!!, listOf("same-id")) is ApplyOutcome.Failed)

        val blank = schedule("", "blank id")
        responses.addLast(plan(blank))
        val t2 = controller.send("add blank")
        assertEquals(0, count())
        assertTrue(t2.reviews.single().status == "invalid")
    }

    @Test
    fun `相邻时段不误报 同名不同日期不误去重`() = runBlocking {
        settings.config = settings.config.copy(autoSave = true)
        responses.addLast(
            plan(
                schedule("a1", "first", start = "09:00", end = "10:00"),
                schedule("a2", "second", start = "10:00", end = "11:00"),
            ),
        )
        val turn = controller.send("adjacent")
        assertTrue("左闭右开，10:00 相邻不算冲突", turn.reviews.all { it.conflicts.isEmpty() })
        assertEquals(2, count())

        responses.addLast(
            plan(
                schedule("b1", "same title", date = "2100-01-01"),
                schedule("b2", "same title", date = "2100-01-02"),
            ),
        )
        val t2 = controller.send("same title different dates")
        assertTrue(t2.reviews.all { it.duplicateInBatch.isEmpty() })
        assertEquals(4, count())
    }

    @Test
    fun `批内修改与新增组合也检查冲突`() = runBlocking {
        val existing = repo.saveSchedule(
            ScheduleEntity(
                title = "existing", startDate = "2099-12-30", endDate = "2099-12-30",
                allDay = false, startTime = "09:00", endTime = "10:00",
            ),
        )
        settings.config = settings.config.copy(autoSave = true)
        responses.addLast(
            plan(
                AiAction(
                    clientActionId = "u1", op = "update_schedule", targetId = existing.id,
                    expectedVersion = existing.version, title = "moved",
                    startDate = "2099-12-30", endDate = "2099-12-30", allDay = false,
                    startTime = "11:00", endTime = "12:00",
                ),
                schedule("n1", "new meeting", start = "11:30", end = "12:30"),
            ),
        )
        val turn = controller.send("move and add")
        assertEquals("只剩原有 1 条，未自动保存", 1, count())
        assertTrue(turn.reviews.all { it.conflicts.isNotEmpty() })
    }

    // ---------- 提交事实兼容 ----------

    @Test
    fun `v13 旧批次有 applied 无提交记录时补录且不重复写`() = runBlocking {
        val b = pending(todo())
        val applied = AppliedRecord(
            atEpochMs = 1,
            appliedActionIds = listOf("a1"),
            items = listOf(SavedRef("a1", "todo", 1, 1, "synthetic")),
            previous = emptyList(),
            remindersPending = false,
        )
        b.applied = applied
        b.state = "APPLIED"
        store.save(b)
        val outcome = executor.applySelected(b.batchId, listOf("a1"))
        assertTrue(outcome is ApplyOutcome.AlreadyApplied)
        assertEquals(0, count())
        assertEquals("APPLIED", repo.aiCommit(b.batchId)!!.state)
    }
}
