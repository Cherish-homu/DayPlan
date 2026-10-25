package com.jsy.dayplan.auditv14

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jsy.dayplan.ai.*
import com.jsy.dayplan.ai.attach.*
import com.jsy.dayplan.backup.BackupCodec
import com.jsy.dayplan.core.FixedClock
import com.jsy.dayplan.data.*
import com.jsy.dayplan.reminder.*
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Correct-behaviour assertions, isolated Room + synthetic data. No production changes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = com.jsy.dayplan.DayPlanApp::class)
class IndependentV14ProbeTest {
    private lateinit var db: PlanDb
    private lateinit var repo: PlanRepository
    private lateinit var settings: AiSettingsStore
    private lateinit var store: BatchStore
    private lateinit var keys: SecureKeyStore
    private lateinit var executor: PlanDraftExecutor
    private lateinit var dir: File
    private lateinit var controller: ChatController
    private var beforeReturn: (() -> Unit)? = null
    private var response = ""

    @Before fun setup() {
        val app: Application = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(app, PlanDb::class.java).allowMainThreadQueries().build()
        repo = PlanRepository(db)
        settings = AiSettingsStore(app)
        settings.config = AiConfig(enabled = true, autoSave = true)
        settings.newChatSession()
        dir = Files.createTempDirectory("v14-independent-").toFile()
        store = BatchStore(File(dir, "batches"))
        keys = SecureKeyStore(File(dir, "key"), InMemoryKeyCipher())
        keys.saveApiKey("sk-independent-synthetic-key")
        executor = PlanDraftExecutor(repo, ReminderCoordinator(db.dao(), FakeReminderScheduler()), settings, store)
        val transport = object : ChatTransport {
            override suspend fun call(request: TransportRequest): TransportResponse {
                beforeReturn?.invoke()
                return TransportResponse(200, """{"choices":[{"message":{"content":${JsonPrimitive(response)}},"finish_reason":"stop"}]}""")
            }
        }
        val renderer = object : PdfPageRenderer {
            override fun renderPage(file: File, pageIndex: Int, maxDimension: Int) = null
        }
        controller = ChatController(DeepSeekClient(apiKeyProvider = { keys.loadApiKey() }, transport = transport),
            settings, keys, FixedClock(LocalDateTime.of(2026, 10, 25, 9, 0)), repo, executor,
            store, ChatStore(File(dir, "chat.json")), AttachmentPlanner(renderer, null))
    }

    @After fun teardown() { db.close() }

    @Test fun B01_restoreCommitCutMustInvalidateOldUndoDurably() = runBlocking {
        val action = AiAction("a1", "create_todo", "original AI row", dueDate = "2099-12-30")
        val batch = BatchRecord("b_cut", "r_cut", settings.chatSessionId, System.currentTimeMillis(),
            settings.dataGeneration, "text", "review", actions = listOf(action), state = "PENDING")
        store.save(batch)
        val first = executor.applySelected(batch.batchId, listOf("a1"))
        assertTrue(first is ApplyOutcome.Success)
        val backup = repo.backupSnapshot("2026-10-25T09:00:00", "1.4")
        val originalId = backup.todos.single().id
        val restore = backup.copy(todos = backup.todos.map { it.copy(title = "RESTORED_UNRELATED_ROW") })

        // Actual restore transaction commits. Simulate a cut BEFORE the caller updates prefs/epoch.
        // No physical kill is claimed; this is deterministic failure-boundary injection.
        repo.applyRestore(BackupCodec.toEntities(restore), File(dir, "restore_snapshots"))
        assertEquals("RESTORED_UNRELATED_ROW", db.dao().todoById(originalId)!!.title)

        // Recreate settings/executor from the persisted stores, like restart at that cut.
        val app: Application = ApplicationProvider.getApplicationContext()
        val restartedSettings = AiSettingsStore(app)
        val restarted = PlanDraftExecutor(repo, ReminderCoordinator(db.dao(), FakeReminderScheduler()),
            restartedSettings, BatchStore(File(dir, "batches")))
        val undo = restarted.undo(batch.batchId)
        assertEquals("B01 old undo must preserve a restored row with reused ID/version; outcome=$undo",
            "RESTORED_UNRELATED_ROW", db.dao().todoById(originalId)?.title)
    }

    @Test fun B02_autoSaveMustRevalidateBusyCalendarAfterResponse() = runBlocking {
        val action = AiAction("a1", "create_schedule", "AI meeting", startDate = "2099-12-30",
            endDate = "2099-12-30", startTime = "09:00", endTime = "10:00")
        response = AiProtocol.json.encodeToString(AiPlanResponse.serializer(),
            AiPlanResponse(1, "plan", "review", actions = listOf(action)))
        beforeReturn = {
            // An ordinary save while the model request is in flight.
            runBlocking {
                repo.saveSchedule(ScheduleEntity(title = "new manual meeting", startDate = "2099-12-30",
                    endDate = "2099-12-30", allDay = false, startTime = "09:00", endTime = "10:00"))
            }
        }
        val turn = controller.send("add one meeting")
        assertEquals("B02 calendar changed during request; conflicting AI action must remain a draft, applied=${turn.applied}",
            1, db.dao().totalItemCount())
        assertNull("conflicting action must not auto-save", turn.applied)
    }
}
