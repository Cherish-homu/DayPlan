package com.jsy.dayplan.reminder

import android.app.Notification
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jsy.dayplan.DayPlanApp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 到点全屏提醒设置与通知接线：弹窗开关、闹钟音乐、fullScreenIntent。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = DayPlanApp::class)
class ReminderAlarmTest {
    private lateinit var app: DayPlanApp
    private lateinit var prefs: ReminderAlarmPrefs

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("reminder_alarm", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = ReminderAlarmPrefs(app)
    }

    @After
    fun tearDown() {
        app.getSharedPreferences("reminder_alarm", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun fired() = ReminderCoordinator.FiredInfo(
        key = "sched-1-1-20261026100000-1790000000000",
        title = "读文献",
        body = "09:00–10:00",
        entityType = "sched",
        entityId = 1,
        plannedAtEpochMs = 1_790_000_000_000,
    )

    @Test
    fun `默认开启全屏弹窗并使用系统闹钟声`() {
        assertTrue(prefs.popupEnabled)
        assertNull(prefs.soundUri)
        assertEquals("系统闹钟声", prefs.soundName)
    }

    @Test
    fun `保存与清除自定义闹钟音乐`() {
        prefs.saveSound("content://media/external/audio/1", "我的闹钟.mp3")
        assertEquals("content://media/external/audio/1", prefs.soundUri)
        assertEquals("我的闹钟.mp3", prefs.soundName)
        prefs.clearSound()
        assertNull(prefs.soundUri)
        assertEquals("系统闹钟声", prefs.soundName)
    }

    @Test
    fun `开启弹窗时通知带全屏意图和闹钟类别`() {
        val notification = DayPlanNotifications.buildReminder(app, fired())
        assertNotNull("开启弹窗后必须有 fullScreenIntent", notification.fullScreenIntent)
        assertEquals(Notification.CATEGORY_ALARM, notification.category)
    }

    @Test
    fun `关闭弹窗后只保留普通提醒通知`() {
        prefs.popupEnabled = false
        val notification = DayPlanNotifications.buildReminder(app, fired())
        assertNull(notification.fullScreenIntent)
        assertEquals(Notification.CATEGORY_REMINDER, notification.category)
    }

    @Test
    fun `全屏意图携带实体与通知标识`() {
        val intent = ReminderAlarmActivity.intent(app, fired())
        assertEquals(ReminderAlarmActivity::class.java.name, intent.component?.className)
        assertEquals("读文献", intent.getStringExtra("alarm_title"))
        assertEquals("sched", intent.getStringExtra("alarm_entity_type"))
        assertEquals(1L, intent.getLongExtra("alarm_entity_id", 0))
        assertEquals(
            fired().key.hashCode(),
            intent.getIntExtra("alarm_notification_id", -1),
        )
    }
}
