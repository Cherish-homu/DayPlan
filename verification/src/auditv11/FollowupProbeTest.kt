package com.jsy.dayplan.auditv11

import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jsy.dayplan.DayPlanApp
import com.jsy.dayplan.data.CountdownEntity
import com.jsy.dayplan.data.PlanDb
import com.jsy.dayplan.reminder.FakeReminderScheduler
import com.jsy.dayplan.reminder.ReminderCoordinator
import com.jsy.dayplan.reminder.ReminderPlans
import com.jsy.dayplan.vm.FocusTimerViewModel
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = DayPlanApp::class)
@LooperMode(LooperMode.Mode.PAUSED)
class FollowupProbeTest {
    private lateinit var app: DayPlanApp
    private lateinit var db: PlanDb

    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(app, PlanDb::class.java).allowMainThreadQueries().build()
        listOf("focus_timer", "reminder_status").forEach {
            app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
    @After fun cleanup() { db.close() }

    private fun annual() = CountdownEntity(
        id = 101, title = "synthetic anniversary", targetDate = "2024-11-01",
        yearlyRepeat = true, remindEnabled = true, remindDaysBefore = 0, remindTime = "23:55",
    )

    @Test fun B01_annual_callback_within_grace_after_midnight_must_deliver() = runBlocking {
        val c = annual()
        db.dao().insertCountdown(c)
        val plan = ReminderPlans.countdownPlan(c, LocalDate.of(2099, 11, 1))!!
        val coordinator = ReminderCoordinator(db.dao(), FakeReminderScheduler())
        val fired = coordinator.onFired(plan.occurrenceKey, plan.triggerAtEpochMs + 8 * 60_000L)
        assertNotNull("23:55 annual reminder delayed to 00:03 is only 8 minutes late and must be delivered", fired)
    }

    @Test fun B02_rebuild_after_midnight_must_record_recent_missed_annual_occurrence() = runBlocking {
        val c = annual()
        db.dao().insertCountdown(c)
        val coordinator = ReminderCoordinator(db.dao(), FakeReminderScheduler())
        coordinator.rebuildAll(LocalDateTime.of(2099, 11, 2, 0, 6))
        assertEquals("23:55 annual reminder at 00:06 is 11 minutes late and must be marked missed",
            1, db.dao().allMissed().size)
    }

    @Test fun B03_focus_completion_without_channel_must_not_claim_delivery() {
        app.getSystemService(NotificationManager::class.java)
            .deleteNotificationChannel(DayPlanApp.FOCUS_CHANNEL_ID)
        val vm = FocusTimerViewModel(app)
        vm.start(1)
        // Advance only Robolectric's virtual clock/main looper, never the host system clock.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(61, TimeUnit.SECONDS)
        assertTrue("Timer should have ended in the foreground", app.loadTimerState().endNotified)
        assertNull("No focus notification channel exists: this cannot count as successful delivery",
            app.reminderStatus.lastDeliveredTitle())
    }
}
