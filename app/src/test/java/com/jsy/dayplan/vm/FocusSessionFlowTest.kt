package com.jsy.dayplan.vm

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.jsy.dayplan.DayPlanApp
import com.jsy.dayplan.core.FocusSound
import com.jsy.dayplan.core.FocusTimerLogic
import com.jsy.dayplan.reminder.DayPlanNotifications
import com.jsy.dayplan.reminder.FocusAlarms
import com.jsy.dayplan.reminder.FocusSoundService
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** 专注完成账本与音效联动的真实接线测试（Robolectric 虚拟时钟）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = DayPlanApp::class)
@LooperMode(LooperMode.Mode.PAUSED)
class FocusSessionFlowTest {

    private lateinit var app: DayPlanApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        listOf("focus_timer", "focus_appearance", "reminder_status").forEach {
            app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        DayPlanNotifications.ensureReminderChannel(app)
    }

    private fun idle(seconds: Long) =
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(seconds, TimeUnit.SECONDS)

    @Test
    fun `自然完成一次只记录一次 重复回调不重复计数`() {
        val before = app.focusSessions.snapshot()
        val vm = FocusTimerViewModel(app)
        vm.start(1)
        val token = FocusAlarms.tokenOf(vm.state.value)
        idle(61)

        val after = app.focusSessions.snapshot()
        assertEquals("自然完成应增加一次", before.totalCount + 1, after.totalCount)
        assertEquals("自然完成应累计 1 分钟", before.totalMinutes + 1, after.totalMinutes)
        assertTrue(app.loadTimerState().endNotified)
        assertFalse("同一轮重复回调必须被拒绝", app.tryMarkFocusEnded(token))
        assertEquals("重复回调不得重复计数", after.totalCount, app.focusSessions.snapshot().totalCount)
    }

    @Test
    fun `暂停继续最终只算一次 重置不产生完成记录`() {
        val before = app.focusSessions.snapshot()
        val vm = FocusTimerViewModel(app)
        vm.start(1)
        idle(20)
        vm.pause()
        idle(30)
        assertEquals("暂停期间不结束、不计数", before.totalCount, app.focusSessions.snapshot().totalCount)
        vm.reset()
        assertEquals("重置不冒充完成", before.totalCount, app.focusSessions.snapshot().totalCount)

        vm.start(1)
        vm.pause()
        idle(30)
        vm.resume()
        idle(61)
        assertEquals("暂停继续后最终只记录一次", before.totalCount + 1, app.focusSessions.snapshot().totalCount)
    }

    @Test
    fun `音效关闭时开始计时不启动播放服务`() {
        val shadow = Shadows.shadowOf(app)
        shadow.clearStartedServices()
        val vm = FocusTimerViewModel(app)
        vm.start(1)
        assertNull("默认关闭音效不应启动前台音频服务", shadow.peekNextStartedService())
        vm.reset()
    }

    @Test
    fun `音效随开始暂停继续与自然结束联动`() {
        app.getSharedPreferences("focus_appearance", Context.MODE_PRIVATE)
            .edit().putString("sound", FocusSound.WAVES.name).commit()
        val shadow = Shadows.shadowOf(app)
        shadow.clearStartedServices()
        val vm = FocusTimerViewModel(app)
        vm.start(1)

        assertNotNull("开始专注后应启动音效服务", shadow.peekNextStartedService())
        assertEquals(
            FocusSoundService::class.java.name,
            shadow.nextStartedService.component?.className,
        )

        vm.pause()
        assertEquals(
            "暂停应停止音效服务",
            FocusSoundService::class.java.name,
            shadow.nextStoppedService?.component?.className,
        )

        vm.resume()
        assertEquals(
            "继续应重新启动音效服务",
            FocusSoundService::class.java.name,
            shadow.nextStartedService.component?.className,
        )

        idle(61)
        assertTrue(app.loadTimerState().endNotified)
        assertEquals(
            "自然结束应停止音效服务",
            FocusSoundService::class.java.name,
            shadow.nextStoppedService?.component?.className,
        )
    }

    @Test
    fun `运行中重复开始不会切换轮次`() {
        val vm = FocusTimerViewModel(app)
        vm.start(1)
        val first = FocusTimerLogic.remainingMs(vm.state.value, app.clock.elapsedRealtimeMs())
        vm.start(45)
        assertEquals("运行中重复 start 必须被忽略", 1, vm.state.value.durationMinutes)
        assertTrue(first > 0)
        vm.reset()
    }
}
