package com.jsy.dayplan.ai

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jsy.dayplan.ai.attach.AttachKind
import com.jsy.dayplan.ai.attach.AttachmentContextStore
import com.jsy.dayplan.ai.attach.AttachmentPlanner
import com.jsy.dayplan.ai.attach.AttachmentStore
import com.jsy.dayplan.ai.attach.PendingAttachment
import com.jsy.dayplan.ai.attach.PdfPageRenderer
import com.jsy.dayplan.backup.BackupCodec
import com.jsy.dayplan.core.FixedClock
import com.jsy.dayplan.data.AiExecutionGate
import com.jsy.dayplan.data.PlanDb
import com.jsy.dayplan.data.PlanException
import com.jsy.dayplan.data.PlanRepository
import com.jsy.dayplan.data.ScheduleEntity
import com.jsy.dayplan.data.TodoEntity
import com.jsy.dayplan.reminder.FakeReminderScheduler
import com.jsy.dayplan.reminder.ReminderCoordinator
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
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
 * v1.5 R1/R2 正式回归：恢复业务与 AI 边界同事务、启动以 DB 为权威、
 * 自动提交与手动确认分离并在最终事务重核验。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = com.jsy.dayplan.DayPlanApp::class)
class AiV15RegressionTest {

    private lateinit var app: Application
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
    private var response = """{"schemaVersion":1,"kind":"chat","reply":"ok"}"""
    private var beforeReturn: (suspend () -> Unit)? = null

    private inner class Scripted : ChatTransport {
        val requests = mutableListOf<TransportRequest>()
        override suspend fun call(request: TransportRequest): TransportResponse {
            requests.add(request)
            beforeReturn?.invoke()
            return TransportResponse(
                200,
                """{"choices":[{"message":{"content":${JsonPrimitive(response)}},"finish_reason":"stop"}]}""",
            )
        }
    }

    private fun noopRenderer() = object : PdfPageRenderer {
        override fun renderPage(file: File, pageIndex: Int, maxDimension: Int) = null
    }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(app, PlanDb::class.java)
            .allowMainThreadQueries().build()
        repo = PlanRepository(db)
        settings = AiSettingsStore(app)
        settings.config = AiConfig(enabled = true, autoSave = false)
        val session = settings.newChatSession()
        settings.markBoundaryPersisted(settings.dataGeneration, session)
        AiExecutionGate.set(settings.dataGeneration, session, bumpToken = true)
        dir = Files.createTempDirectory("v15-regression").toFile()
        store = BatchStore(File(dir, "batches"))
        history = ChatStore(File(dir, "chat.json"))
        contextStore = AttachmentContextStore(File(dir, "context"))
        keys = SecureKeyStore(File(dir, "key"), InMemoryKeyCipher())
        keys.saveApiKey("sk-v15-synthetic-key")
        executor = PlanDraftExecutor(
            repo, ReminderCoordinator(db.dao(), FakeReminderScheduler()), settings, store,
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
    fun tearDown() { db.close() }

    private fun count() = runBlocking { db.dao().totalItemCount() }

    private suspend fun nextBoundary(): Pair<Int, String> {
        val gen = settings.dataGeneration + 1
        val session = settings.chatSessionId
        repo.setAiEpoch(gen, session)
        settings.markBoundaryPersisted(gen, session)
        AiExecutionGate.set(gen, session, bumpToken = true)
        return gen to session
    }

    private fun todo(id: String = "a1", title: String = "AI row") =
        AiAction(clientActionId = id, op = "create_todo", title = title, dueDate = "2099-12-30")

    private fun schedule(id: String, title: String, start: String = "09:00", end: String = "10:00") =
        AiAction(
            clientActionId = id, op = "create_schedule", title = title,
            startDate = "2099-12-30", endDate = "2099-12-30", allDay = false,
            startTime = start, endTime = end,
        )

    private fun planBody(vararg actions: AiAction) = AiProtocol.json.encodeToString(
        AiPlanResponse.serializer(),
        AiPlanResponse(1, "plan", "review", actions = actions.toList()),
    )

    private fun pending(vararg actions: AiAction): BatchRecord {
        val b = BatchRecord(
            "b_v15", "r_v15", settings.chatSessionId, System.currentTimeMillis(),
            settings.dataGeneration, "text", "review",
            actions = actions.toList(), state = "PENDING",
        )
        store.save(b)
        return b
    }

    // ---------- R1 ----------

    @Test
    fun `恢复业务与AI边界同事务 旧撤销不能删除恢复后的同ID行`() = runBlocking {
        val b = pending(todo())
        assertTrue(executor.applySelected(b.batchId, listOf("a1")) is ApplyOutcome.Success)
        val original = db.dao().allTodos().single()
        val backup = repo.backupSnapshot("2026-10-25T09:00:00", "1.5")
        val restorePlan = backup.copy(
            todos = backup.todos.map { it.copy(title = "RESTORED_UNRELATED_ROW") },
        )
        val beforeGen = repo.currentAiEpoch()!!.dataGeneration
        repo.applyRestoreWithBoundary(
            BackupCodec.toEntities(restorePlan), File(dir, "snap"),
            aiSessionId = settings.chatSessionId,
        )
        // 模拟调用方后处理中断：不更新偏好/内存 gate，直接重建执行器
        val restartedSettings = AiSettingsStore(app)
        val restarted = PlanDraftExecutor(
            repo, ReminderCoordinator(db.dao(), FakeReminderScheduler()), restartedSettings, store,
        )
        assertTrue(repo.currentAiEpoch()!!.dataGeneration > beforeGen)
        assertTrue(restarted.undo(b.batchId) is UndoOutcome.Stale)
        assertEquals(
            "RESTORED_UNRELATED_ROW",
            db.dao().todoById(original.id)?.title,
        )
    }

    @Test
    fun `恢复事务失败时业务与AI边界都不变`() = runBlocking {
        val b = pending(todo())
        executor.applySelected(b.batchId, listOf("a1"))
        val epoch = repo.currentAiEpoch()!!
        val timeBefore = db.dao().allTodos().single()
        val backup = repo.backupSnapshot("2026-10-25T09:00:00", "1.5")
        val bad = backup.copy(todos = backup.todos.map { it.copy(title = "SHOULD_NOT_COMMIT") })
        var failed = false
        try {
            repo.applyRestoreWithBoundary(
                BackupCodec.toEntities(bad), File(dir, "snap_fail"), failAfter = 2,
            )
        } catch (e: PlanException) {
            failed = true
        }
        assertTrue("恢复事务应失败", failed)
        assertEquals(epoch, repo.currentAiEpoch())
        assertEquals(timeBefore, db.dao().todoById(timeBefore.id))
        assertTrue("旧批次仍可撤销", executor.undo(b.batchId) is UndoOutcome.Success)
    }

    @Test
    fun `启动同步采用DB新边界 不用旧偏好覆盖`() = runBlocking {
        val b = pending(todo())
        executor.applySelected(b.batchId, listOf("a1"))
        val backup = repo.backupSnapshot("2026-10-25T09:00:00", "1.5")
        repo.applyRestoreWithBoundary(
            BackupCodec.toEntities(backup), File(dir, "snap_sync"),
            aiSessionId = settings.chatSessionId,
        )
        val dbEpoch = repo.currentAiEpoch()!!
        // 保持偏好为旧世代，模拟恢复提交后进程中断
        assertTrue("恢复后 DB 世代应高于旧偏好", dbEpoch.dataGeneration > settings.dataGeneration)

        val container = AiContainer(
            app, repo, ReminderCoordinator(db.dao(), FakeReminderScheduler()),
            FixedClock(LocalDateTime.of(2026, 10, 25, 9, 0)), InMemoryKeyCipher(),
        )
        runBlocking { container.pruneOnStart() }
        assertEquals(dbEpoch, repo.currentAiEpoch())
        assertEquals(dbEpoch.dataGeneration, settings.dataGeneration)
        assertEquals(dbEpoch.sessionId, settings.chatSessionId)
        assertTrue(container.executor.undo(b.batchId) is UndoOutcome.Stale)
    }

    @Test
    fun `清空对话的本地新会话在启动时持久化 不被旧DB会话复活`() = runBlocking {
        val b = pending(todo())
        executor.applySelected(b.batchId, listOf("a1"))
        controller.clearConversation(AttachmentStore(File(dir, "att")))
        val newSession = settings.chatSessionId
        assertTrue(settings.boundaryDirty)
        assertTrue("旧 DB 边界尚未写入", settings.chatSessionId != repo.currentAiEpoch()!!.sessionId)

        val container = AiContainer(
            app, repo, ReminderCoordinator(db.dao(), FakeReminderScheduler()),
            FixedClock(LocalDateTime.of(2026, 10, 25, 9, 0)), InMemoryKeyCipher(),
        )
        runBlocking { container.pruneOnStart() }
        assertEquals(newSession, repo.currentAiEpoch()!!.sessionId)
        assertEquals(false, settings.boundaryDirty)
        assertTrue("旧批次在清空后仍不能撤销", container.executor.undo(b.batchId) is UndoOutcome.Stale)
    }

    @Test
    fun `legacy 回填只在边界一致时发生`() = runBlocking {
        val b = pending(todo())
        b.applied = AppliedRecord(
            atEpochMs = 1, appliedActionIds = listOf("a1"),
            items = listOf(SavedRef("a1", "todo", 1, 1, "AI row")),
            previous = emptyList(), remindersPending = false,
        )
        b.state = "APPLIED"
        store.save(b)
        nextBoundary()
        assertTrue(executor.applySelected(b.batchId, listOf("a1")) is ApplyOutcome.Stale)
        assertNull("边界不一致时不得补录旧提交", repo.aiCommit(b.batchId))
        assertEquals(0, count())
    }

    // ---------- R2 ----------

    @Test
    fun `自动保存请求期间手动新增冲突 转为待确认且只保留手动条`() = runBlocking {
        settings.config = settings.config.copy(autoSave = true)
        response = planBody(schedule("a1", "AI meeting"))
        beforeReturn = {
            repo.saveSchedule(
                ScheduleEntity(
                    title = "manual meeting", startDate = "2099-12-30", endDate = "2099-12-30",
                    allDay = false, startTime = "09:00", endTime = "10:00",
                ),
            )
        }
        val turn = controller.send("add one meeting")
        assertNull("冲突动作不得自动写入", turn.applied)
        assertEquals(1, count())
        assertTrue(turn.warnings.any { it.contains("待确认") })
        val batch = store.load(turn.batchId!!)!!
        assertEquals("PENDING", batch.state)
        assertNull(batch.applied)
    }

    @Test
    fun `无冲突自动保存仍成功`() = runBlocking {
        settings.config = settings.config.copy(autoSave = true)
        response = planBody(schedule("a1", "AI meeting", "14:00", "15:00"))
        val turn = controller.send("add one meeting")
        assertNotNull(turn.applied)
        assertEquals(1, count())
        assertEquals("APPLIED", store.load(turn.batchId!!)!!.state)
        assertEquals("APPLIED", repo.aiCommit(turn.batchId!!)!!.state)
    }

    @Test
    fun `自动提交与手动确认显式分离 手动仍可确认重叠`() = runBlocking {
        repo.saveSchedule(
            ScheduleEntity(
                title = "manual", startDate = "2099-12-30", endDate = "2099-12-30",
                allDay = false, startTime = "09:00", endTime = "10:00",
            ),
        )
        val b = pending(schedule("a1", "AI overlap"))
        val auto = executor.applySelected(b.batchId, listOf("a1"), automatic = true)
        assertTrue(auto is ApplyOutcome.PreviewRequired)
        assertEquals(1, count())
        assertEquals("PENDING", store.load(b.batchId)!!.state)
        assertNull(store.load(b.batchId)!!.applied)

        val manual = executor.applySelected(b.batchId, listOf("a1"), automatic = false)
        assertTrue(manual is ApplyOutcome.Success)
        assertEquals(2, count())
    }

    @Test
    fun `自动提交只接受整批选中`() = runBlocking {
        settings.config = settings.config.copy(autoSave = true)
        val b = pending(
            schedule("a1", "one", "14:00", "15:00"),
            schedule("a2", "two", "16:00", "17:00"),
        )
        val result = executor.applySelected(b.batchId, listOf("a1"), automatic = true)
        assertTrue(result is ApplyOutcome.PreviewRequired)
        assertEquals(0, count())
        assertEquals("PENDING", store.load(b.batchId)!!.state)
    }

    @Test
    fun `恢复期间自动请求不写库且旧边界撤销失效`() = runBlocking {
        settings.config = settings.config.copy(autoSave = true)
        response = planBody(todo())
        beforeReturn = {
            val snap = repo.backupSnapshot("2026-10-25T09:00:00", "1.5")
            repo.applyRestoreWithBoundary(
                BackupCodec.toEntities(snap), File(dir, "snap_auto"),
                aiGeneration = settings.dataGeneration + 1,
                aiSessionId = settings.chatSessionId,
            )
        }
        val turn = runCatching { controller.send("add auto todo") }.exceptionOrNull()
        assertTrue(turn is AiException.Cancelled)
        assertEquals(0, count())
        assertTrue(store.all().isEmpty())
    }
}
