package com.jsy.dayplan.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * AlarmManager 实现：
 * - canScheduleExactAlarms() == true 时用 setExactAndAllowWhileIdle（适合用户提醒）；
 * - 未获精确授权时降级 setAndAllowWhileIdle（系统允许的近似提醒，界面标注“可能延迟”）；
 * - 不用 WorkManager 承担分钟级准点闹钟。
 */
class AndroidReminderScheduler(private val context: Context) : ReminderScheduler {

    private val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun pendingIntent(plan: ReminderPlans.ReminderPlan): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_FIRE
            putExtra(ReminderReceiver.EXTRA_KEY, plan.occurrenceKey)
        }
        return PendingIntent.getBroadcast(
            context,
            plan.occurrenceKey.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    override fun canScheduleExact(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.canScheduleExactAlarms()
        } else true
    }

    override fun schedule(plan: ReminderPlans.ReminderPlan, exactAllowed: Boolean) {
        val pi = pendingIntent(plan)
        if (exactAllowed && canScheduleExact()) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, plan.triggerAtEpochMs, pi)
                return
            } catch (e: SecurityException) {
                // 权限在运行中被撤销：降级为近似提醒
            }
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, plan.triggerAtEpochMs, pi)
    }

    override fun cancel(occurrenceKey: String) {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_FIRE
        }
        val pi = PendingIntent.getBroadcast(
            context,
            occurrenceKey.hashCode(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        if (pi != null) {
            am.cancel(pi)
            pi.cancel()
        }
    }

    override fun cancelAll(keys: List<String>) {
        keys.forEach { cancel(it) }
    }
}
