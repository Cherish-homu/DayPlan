package com.jsy.dayplan.acceptance

import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jsy.dayplan.DayPlanApp
import com.jsy.dayplan.data.CountdownEntity
import com.jsy.dayplan.data.PlanDb
import com.jsy.dayplan.reminder.DayPlanNotifications
import com.jsy.dayplan.reminder.FakeReminderScheduler
import com.jsy.dayplan.reminder.ReminderCoordinator
import com.jsy.dayplan.reminder.ReminderPlans
import com.jsy.dayplan.vm.FocusTimerViewModel
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
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
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * v1.1 独立复验的 3 项提醒边界反例（B01–B03）转正式回归，
 * 并补跨午夜提前量、闰日、年度下一次和通知投递状态口径。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = DayPlanApp::class)
@LooperMode(LooperMode.Mode.PAUSED)
class FollowupRegressionTest {

    private lateinit var app: DayPlanApp
    private lateinit var db: PlanDb

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(app, PlanDb::class.java)
            .allowMainThreadQueries().build()
        listOf("focus_timer", "reminder_status").forEach {
            app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        DayPlanNotifications.ensureReminderChannel(app)
        app.getSystemService(NotificationManager::class.java).cancelAll()
    }

    @After
    fun cleanup() {
        db.close()
        DayPlanNotifications.ensureReminderChannel(app)
        app.getSystemService(NotificationManager::class.java).cancelAll()
    }

    private fun annual(
        id: Long = 101,
        daysBefore: Int = 0,
        time: String = "23:55",
        target: String = "2024-11-01",
    ) = CountdownEntity(
        id = id, title = "synthetic anniversary", targetDate = target,
        yearlyRepeat = true, remindEnabled = true,
        remindDaysBefore = daysBefore, remindTime = time,
    )

    @Test
    fun `B01 - 年度提醒跨午夜后 8 分钟仍在宽限内并成功补发`() = runBlocking {
        val c = annual()
        db.dao().insertCountdown(c)
        val plan = ReminderPlans.countdownPlan(c, LocalDate.of(2099, 11, 1))!!
        val scheduler = FakeReminderScheduler()
        val coordinator = ReminderCoordinator(db.dao(), scheduler)

        val fired = coordinator.onFired(plan.occurrenceKey, plan.triggerAtEpochMs + 8 * 60_000L)
        assertNotNull("23:55 的年度提醒次日 00:03 只迟到 8 分钟，必须投递", fired)
        assertEquals("cd", fired!!.entityType)
        assertEquals(c.id, fired.entityId)
        // 触发后仍要注册下一次年度提醒
        assertTrue(
            "年度触发后必须注册下一次有效提醒",
            scheduler.scheduled.any { it.triggerAtEpochMs > plan.triggerAtEpochMs },
        )
    }

    @Test
    fun `B01b - 跨午夜只通知一次`() = runBlocking {
        val c = annual(id = 102)
        db.dao().insertCountdown(c)
        val plan = ReminderPlans.countdownPlan(c, LocalDate.of(2099, 11, 1))!!
        val coordinator = ReminderCoordinator(db.dao(), FakeReminderScheduler())
        val late = plan.triggerAtEpochMs + 8 * 60_000L
        assertNotNull(coordinator.onFired(plan.occurrenceKey, late))
        assertNull("同一发生重复回调不得再次通知", coordinator.onFired(plan.occurrenceKey, late + 1_000L))
        assertTrue(db.dao().allMissed().isEmpty())
    }

    @Test
    fun `B02 - 年度提醒跨午夜后 11 分钟重建留下错过记录`() = runBlocking {
        val c = annual(id = 103)
        db.dao().insertCountdown(c)
        val coordinator = ReminderCoordinator(db.dao(), FakeReminderScheduler())
        coordinator.rebuildAll(LocalDateTime.of(2099, 11, 2, 0, 6))
        assertEquals(
            "23:55 的年度提醒次日 00:06 迟到 11 分钟，应标记 1 条错过",
            1,
            db.dao().allMissed().size,
        )
        assertTrue(
            "错过记录应指向刚过去的真实触发发生",
            db.dao().allMissed().single().occurrenceKey.endsWith("209911012355"),
        )
    }

    @Test
    fun `跨午夜提前 1 天提醒仍按真实触发时刻判定`() = runBlocking {
        val c = annual(id = 104, daysBefore = 1)
        db.dao().insertCountdown(c)
        // 目标 2099-11-01，提前 1 天 → 2099-10-31 23:55
        val plan = ReminderPlans.countdownPlan(c, LocalDate.of(2099, 10, 31))!!
        val coordinator = ReminderCoordinator(db.dao(), FakeReminderScheduler())
        assertNotNull(
            "提前 1 天提醒在次日 00:03 只迟到 8 分钟，应投递",
            coordinator.onFired(plan.occurrenceKey, plan.triggerAtEpochMs + 8 * 60_000L),
        )
    }

    @Test
    fun `闰日年度提醒跨午夜仍能找到上一发生`() = runBlocking {
        val c = annual(id = 105, target = "2024-02-29")
        db.dao().insertCountdown(c)
        // 2099 非闰年 → 当年发生为 2099-02-28 23:55
        val plan = ReminderPlans.countdownPlan(c, LocalDate.of(2099, 2, 28))!!
        assertTrue(plan.occurrenceKey.endsWith("209902282355"))
        val coordinator = ReminderCoordinator(db.dao(), FakeReminderScheduler())
        assertNotNull(
            "闰日按 2/28 处理，次日 00:03 仍在宽限内",
            coordinator.onFired(plan.occurrenceKey, plan.triggerAtEpochMs + 8 * 60_000L),
        )
    }

    @Test
    fun `跨午夜后未来计划只取下一次而不把过去发生当未来闹钟`() = runBlocking {
        val c = annual(id = 106)
        db.dao().insertCountdown(c)
        val now = LocalDateTime.of(2099, 11, 2, 0, 3)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val next = ReminderPlans.countdownNextPlan(c, now)!!
        assertTrue("下一次必须严格晚于当前时刻", next.triggerAtEpochMs > now)
        assertTrue("跨午夜后不能把刚过去的发生当未来闹钟", next.occurrenceKey.endsWith("210011012355"))
    }

    @Test
    fun `跨午夜窗口之外的多年历史不重复补记`() = runBlocking {
        val c = annual(id = 107)
        db.dao().insertCountdown(c)
        val coordinator = ReminderCoordinator(db.dao(), FakeReminderScheduler())
        // 2099-11-10 已远超 2 天回看窗口，不应把 11-01 的历史当最近错过
        coordinator.rebuildAll(LocalDateTime.of(2099, 11, 10, 12, 0))
        assertTrue(db.dao().allMissed().isEmpty())
    }

    @Test
    fun `B03 - 专注结束但通知通道不存在时不得记录成功投递`() {
        app.getSystemService(NotificationManager::class.java)
            .deleteNotificationChannel(DayPlanApp.FOCUS_CHANNEL_ID)
        val vm = FocusTimerViewModel(app)
        vm.start(1)
        // 只推进 Robolectric 虚拟主线程时钟，不修改电脑系统时间
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(61, TimeUnit.SECONDS)

        assertTrue("计时必须正常结束", app.loadTimerState().endNotified)
        assertFalse(app.loadTimerState().running)
        assertNull(
            "通知通道不存在时不能记录“成功投递”",
            app.reminderStatus.lastDeliveredTitle(),
        )
        assertNotNull("应记录受阻原因", app.reminderStatus.blockedReason())
        assertTrue(
            "受阻原因应说明通知通道不可用",
            app.reminderStatus.blockedReason()!!.contains("通道"),
        )
    }

    @Test
    fun `专注通知正常开启时结束状态与投递状态同时成立且只发一次`() {
        val nm = app.getSystemService(NotificationManager::class.java)
        val shadowNm = Shadows.shadowOf(nm)
        val vm = FocusTimerViewModel(app)
        vm.start(1)
        val token = com.jsy.dayplan.reminder.FocusAlarms.tokenOf(vm.state.value)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(61, TimeUnit.SECONDS)

        assertTrue("计时必须结束", app.loadTimerState().endNotified)
        assertEquals("专注计时结束", app.reminderStatus.lastDeliveredTitle())
        assertNull(app.reminderStatus.blockedReason())
        assertEquals(1, shadowNm.getActiveNotifications().size)
        assertFalse("同一轮再次结束必须被拒绝", app.tryMarkFocusEnded(token))
        assertEquals(1, shadowNm.getActiveNotifications().size)
    }

    @Test
    fun `通知发送失败不会清除已结束状态且不伪造成成功`() {
        // 用一个正常通道开始并结束，确认成功；随后删除通道再开始一轮失败
        val vm1 = FocusTimerViewModel(app)
        vm1.start(1)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(61, TimeUnit.SECONDS)
        assertEquals("专注计时结束", app.reminderStatus.lastDeliveredTitle())

        app.getSystemService(NotificationManager::class.java)
            .deleteNotificationChannel(DayPlanApp.FOCUS_CHANNEL_ID)
        app.getSharedPreferences("reminder_status", Context.MODE_PRIVATE)
            .edit().remove("lastDeliveredTitle").commit()
        val vm2 = FocusTimerViewModel(app)
        vm2.start(1)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(61, TimeUnit.SECONDS)

        assertTrue("通知失败也必须结束计时", app.loadTimerState().endNotified)
        assertNull(app.reminderStatus.lastDeliveredTitle())
        assertNotNull(app.reminderStatus.blockedReason())
    }
}
