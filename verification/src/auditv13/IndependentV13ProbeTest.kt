package com.jsy.dayplan.auditv13

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jsy.dayplan.ai.*
import com.jsy.dayplan.ai.attach.*
import com.jsy.dayplan.core.FixedClock
import com.jsy.dayplan.data.*
import com.jsy.dayplan.backup.BackupCodec
import com.jsy.dayplan.reminder.*
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonPrimitive
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Independent expected-correct assertions; failures are counterexamples, not fixes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = com.jsy.dayplan.DayPlanApp::class)
class IndependentV13ProbeTest {
    private lateinit var db: PlanDb
    private lateinit var repo: PlanRepository
    private lateinit var settings: AiSettingsStore
    private lateinit var store: BatchStore
    private lateinit var keys: SecureKeyStore
    private lateinit var executor: PlanDraftExecutor
    private lateinit var history: ChatStore
    private lateinit var dir: File
    private val requests = mutableListOf<TransportRequest>()
    private var response = """{"schemaVersion":1,"kind":"chat","reply":"ok"}"""
    private var beforeReturn: (() -> Unit)? = null
    private lateinit var controller: ChatController

    @Before fun setup() {
        val app: Application = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(app, PlanDb::class.java).allowMainThreadQueries().build()
        repo = PlanRepository(db)
        settings = AiSettingsStore(app)
        settings.config = AiConfig(enabled = true, autoSave = true)
        settings.newChatSession()
        dir = Files.createTempDirectory("v13-independent-").toFile()
        store = BatchStore(File(dir, "batches"))
        keys = SecureKeyStore(File(dir, "key"), InMemoryKeyCipher())
        keys.saveApiKey("sk-independent-synthetic-key")
        history = ChatStore(File(dir, "chat.json"))
        executor = PlanDraftExecutor(repo, ReminderCoordinator(db.dao(), FakeReminderScheduler()), settings, store)
        val transport = object : ChatTransport {
            override suspend fun call(request: TransportRequest): TransportResponse {
                requests.add(request)
                beforeReturn?.invoke()
                return TransportResponse(200, """{"choices":[{"message":{"content":${JsonPrimitive(response)}},"finish_reason":"stop"}]}""")
            }
        }
        val renderer = object : PdfPageRenderer {
            override fun renderPage(file: File, pageIndex: Int, maxDimension: Int) = null
        }
        controller = ChatController(DeepSeekClient(apiKeyProvider = { keys.loadApiKey() }, transport = transport),
            settings, keys, FixedClock(LocalDateTime.of(2026, 10, 25, 9, 0)), repo, executor,
            store, history, AttachmentPlanner(renderer, null))
    }

    @After fun teardown() { db.close() }
    private fun plan(vararg a: AiAction): String =
        AiProtocol.json.encodeToString(AiPlanResponse.serializer(), AiPlanResponse(1, "plan", "review", actions = a.toList()))
    private fun todo(id: String = "a1", title: String = "synthetic") =
        AiAction(clientActionId = id, op = "create_todo", title = title, dueDate = "2099-12-30")
    private fun schedule(id: String, title: String) = AiAction(clientActionId = id, op = "create_schedule",
        title = title, startDate = "2099-12-30", endDate = "2099-12-30", startTime = "09:00", endTime = "10:00")
    private fun pending(vararg actions: AiAction): BatchRecord {
        val b = BatchRecord("b_probe", "r_probe", settings.chatSessionId, System.currentTimeMillis(),
            settings.dataGeneration, "text", "review", actions = actions.toList(), state = "PENDING")
        store.save(b)
        return b
    }

    @Test fun A01_restoreDuringRequestMustInvalidateOldResponse() = runBlocking {
        response = plan(todo())
        val generationAtSend = settings.dataGeneration
        val restorePlan = BackupCodec.toEntities(repo.backupSnapshot("2026-10-25T09:00:00", "1.3"))
        beforeReturn = {
            runBlocking { repo.applyRestore(restorePlan, File(dir, "restore_snapshots")) }
            settings.bumpDataGeneration()
        }
        runCatching { controller.send("add a synthetic dated todo") }
        assertTrue(settings.dataGeneration > generationAtSend)
        assertEquals("A01 old in-flight request must not write after generation changes", 0, db.dao().totalItemCount())
    }

    @Test fun A02_intraBatchConflictMustBlockAutomaticSave() = runBlocking {
        response = plan(schedule("a1", "first meeting"), schedule("a2", "second meeting"))
        controller.send("arrange these two synthetic meetings")
        assertEquals("A02 overlapping new schedules require preview instead of auto-save", 0, db.dao().totalItemCount())
    }

    @Test fun A03_diskFailureAfterCommitMustNotAllowDuplicateRetry() = runBlocking {
        val b = pending(todo())
        val blocker = File(dir, "batches/${b.batchId}.json.tmp")
        assertTrue(blocker.mkdir()) // force deterministic write failure; original pending JSON survives
        val first = runCatching { executor.applySelected(b.batchId, listOf("a1")) }
        val countAfterFirst = db.dao().totalItemCount()
        val stateAfterFirst = store.load(b.batchId)?.state
        assertTrue(blocker.delete())
        val retry = runCatching { executor.applySelected(b.batchId, listOf("a1")) }
        assertEquals("A03 firstError=${first.exceptionOrNull()?.javaClass?.simpleName}, firstCount=$countAfterFirst, state=$stateAfterFirst, retry=${retry.getOrNull()}",
            1, db.dao().totalItemCount())
    }

    @Test fun A04_concurrentSaveSameBatchMustBeIdempotent() = runBlocking {
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
        val second = try { executor.applySelected(b.batchId, listOf("a1")) } finally { releaseFirst.countDown() }
        first.await()
        assertEquals("A04 same batch saved concurrently; second=$second", 1, db.dao().totalItemCount())
    }

    @Test fun A05_duplicateActionIdentityMustBeRejected() = runBlocking {
        val a = schedule("same-id", "same meeting")
        response = plan(a, a)
        controller.send("add one meeting")
        assertEquals("A05 duplicate clientActionId must not bypass batch duplicate check", 0, db.dao().totalItemCount())
    }

    @Test fun A06_attachmentMustRemainAvailableForClarificationFollowup() = runBlocking {
        settings.config = settings.config.copy(autoSave = false)
        val marker = "UNIQUE_ATTACHMENT_PLAN_X719"
        val f = File(dir, "schedule.txt").apply { writeText("$marker meeting on 12-30 at 09:00; year unclear") }
        val attachment = PendingAttachment("att_probe", "schedule.txt", "text/plain", AttachKind.TXT, f, f.length(), "synthetic-sha")
        response = """{"schemaVersion":1,"kind":"clarify","reply":"Which year?","questions":["Which year?"]}"""
        controller.send("import this file", listOf(attachment))
        assertTrue(requests.last().body!!.contains(marker))
        response = plan(todo())
        controller.send("2099")
        assertTrue("A06 follow-up request lost all attachment content after clarification", requests.last().body!!.contains(marker))
    }

    @Test fun A07_clearedSessionMustRejectPreviouslyLoadedPendingBatch() = runBlocking {
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
        val gatedExecutor = PlanDraftExecutor(PlanRepository(db, gatedDao),
            ReminderCoordinator(realDao, FakeReminderScheduler()), settings, store)
        val save = async(Dispatchers.IO) { gatedExecutor.applySelected(b.batchId, listOf("a1")) }
        assertTrue(reading.await(20, TimeUnit.SECONDS))
        try {
            controller.clearConversation(AttachmentStore(File(dir, "attachments")))
            assertEquals("CANCELLED", store.load(b.batchId)!!.state)
        } finally { proceed.countDown() }
        save.await()
        assertEquals("A07 executor must reject a batch from a previous chat session", 0, db.dao().totalItemCount())
    }

    @Test fun A08_pdfPageTailMustNotBeSilentlyDropped() {
        val marker = "FINAL_MEETING_2099_12_30_0900"
        val pdf = File(dir, "dense-page.pdf")
        com.tom_roush.pdfbox.pdmodel.PDDocument().use { doc ->
            val page = com.tom_roush.pdfbox.pdmodel.PDPage(com.tom_roush.pdfbox.pdmodel.common.PDRectangle.A4)
            doc.addPage(page)
            com.tom_roush.pdfbox.pdmodel.PDPageContentStream(doc, page).use { cs ->
                cs.beginText()
                cs.setFont(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA, 5f)
                cs.newLineAtOffset(30f, 780f)
                repeat(100) { cs.showText("Agenda detail filler line with scheduled information number $it. "); cs.newLineAtOffset(0f, -6f) }
                cs.showText(marker)
                cs.endText()
            }
            doc.save(pdf)
        }
        val full = PdfTextExtractor.extract(pdf)
        assertTrue("fixture must contain marker", full.pages.single().text.contains(marker))
        val renderer = object : PdfPageRenderer {
            override fun renderPage(file: File, pageIndex: Int, maxDimension: Int) = null
        }
        val result = AttachmentPlanner(renderer, null).compose(listOf(
            PendingAttachment("pdf_probe", "dense-page.pdf", "application/pdf", AttachKind.PDF, pdf, pdf.length(), "synthetic-sha")))
        val preserved = result.segments.any { it.text.contains(marker) }
        val warned = result.warnings.any { it.contains("截断") || it.contains("超长") }
        assertTrue("A08 PDF page tail dropped without warning: extracted=${full.pages.single().text.length}, sent=${result.totalDocChars}, warnings=${result.warnings}", preserved || warned)
    }
}
