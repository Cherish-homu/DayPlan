package com.jsy.dayplan.vm

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jsy.dayplan.DayPlanApp
import com.jsy.dayplan.ai.AiContainer
import com.jsy.dayplan.ai.AiSettingsStore
import com.jsy.dayplan.ai.ChatTransport
import com.jsy.dayplan.ai.TransportRequest
import com.jsy.dayplan.ai.TransportResponse
import com.jsy.dayplan.core.FixedClock
import com.jsy.dayplan.data.PlanDb
import com.jsy.dayplan.data.PlanRepository
import com.jsy.dayplan.reminder.FakeReminderScheduler
import com.jsy.dayplan.reminder.ReminderCoordinator
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * ViewModel 级接线：选择附件不联网；点发送才请求；预览零写库；导入后真实落库。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = DayPlanApp::class)
@LooperMode(LooperMode.Mode.PAUSED)
class AiViewModelTest {

    private class Capture : ChatTransport {
        val requests = mutableListOf<TransportRequest>()
        override suspend fun call(request: TransportRequest): TransportResponse {
            requests.add(request)
            val plan = """
                {"schemaVersion":1,"kind":"plan","reply":"【测试】已整理","questions":[],
                 "actions":[{"clientActionId":"a1","op":"create_todo","title":"交报告","dueDate":"2099-12-30",
                             "sourceIds":["txt1-L1"]}]}
            """.trimIndent()
            val quoted = kotlinx.serialization.json.JsonPrimitive(plan).toString()
            return TransportResponse(
                200,
                """{"choices":[{"message":{"content":$quoted},"finish_reason":"stop"}]}""",
            )
        }
    }

    private lateinit var app: DayPlanApp
    private lateinit var db: PlanDb
    private lateinit var repo: PlanRepository
    private lateinit var container: AiContainer
    private lateinit var vm: AiViewModel
    private lateinit var capture: Capture

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(app, PlanDb::class.java)
            .allowMainThreadQueries().build()
        repo = PlanRepository(db)
        val coordinator = ReminderCoordinator(db.dao(), FakeReminderScheduler())
        container = AiContainer(
            app, repo, coordinator, FixedClock(LocalDateTime.of(2026, 10, 25, 9, 0)),
            com.jsy.dayplan.ai.InMemoryKeyCipher(),
        )
        container.settings.config = container.settings.config.copy(enabled = true, autoSave = false)
        container.keyStore.saveApiKey("sk-test-1234567890abcdef")
        capture = Capture()
        container.setTransportForTest(capture)
        vm = AiViewModel(app, container)
    }

    @After
    fun tearDown() {
        // 先取消 ViewModel 的后台 reload，再关闭数据库，避免事务与关闭竞争
        vm.viewModelScope.cancel()
        val main = Shadows.shadowOf(Looper.getMainLooper())
        main.idle()
        db.close()
    }

    private fun waitFor(timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val main = Shadows.shadowOf(Looper.getMainLooper())
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < deadline) {
            main.idle()
            Thread.sleep(20)
        }
        main.idle()
    }

    @Test
    fun `选择附件不联网 点发送后预览零写入 导入才落库`() {
        val appCtx: Application = app
        val txt = File(Files.createTempDirectory("vm-attach").toFile(), "课表.txt").apply {
            writeText("周一 09:00 高数", Charsets.UTF_8)
        }
        vm.addAttachment(Uri.fromFile(txt), "text/plain")
        waitFor { vm.attachments.value.isNotEmpty() }

        assertEquals(1, vm.attachments.value.size)
        assertTrue("选择附件完成前不应发出任何网络请求", capture.requests.isEmpty())
        assertEquals(0, runBlocking { db.dao().totalItemCount() })

        vm.input.value = "把附件里的课加入日程"
        vm.send()
        waitFor { vm.batches.value.isNotEmpty() && vm.sendState.value is SendState.Idle }

        assertEquals("点发送才应发出一次请求", 1, capture.requests.size)
        assertEquals("预览阶段不得写库", 0, runBlocking { db.dao().totalItemCount() })
        val ui = vm.batches.value.single()
        assertEquals(1, ui.reviews.size)
        assertEquals("ok", ui.reviews[0].status)
        assertTrue("默认勾选有效条目", ui.selection.values.single())

        var applied = false
        vm.applyBatch(ui.record.batchId) { applied = true }
        waitFor { applied && vm.batches.value.isEmpty() }

        assertTrue(applied)
        assertEquals(1, runBlocking { db.dao().totalItemCount() })
        assertTrue(
            "保存后历史应显示结果卡片（可撤销）",
            vm.entries.value.any { it.kind == "result" && it.batchId == ui.record.batchId },
        )
        assertTrue("附件内容应随请求发送", capture.requests[0].body!!.contains("附件提取内容"))
        assertTrue(capture.requests[0].body!!.contains("周一 09:00 高数"))
    }

    @Test
    fun `Key 掩码与入口开关持久化`() {
        assertNotNullMasked()
        assertTrue(vm.maskedKey()!!.startsWith("sk-"))
        assertTrue(vm.maskedKey()!!.contains("••••"))
        val before = vm.config().showWhale
        vm.saveConfig(vm.config().copy(showWhale = !before))
        assertEquals(!before, AiSettingsStore(app).config.showWhale)
        assertTrue(vm.readiness() == com.jsy.dayplan.ai.ChatController.Readiness.READY)
    }

    private fun assertNotNullMasked() {
        assertTrue("已配置 Key 应有掩码", vm.maskedKey() != null)
    }
}
