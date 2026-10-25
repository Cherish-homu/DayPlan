package com.jsy.dayplan.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jsy.dayplan.DayPlanApp
import kotlinx.coroutines.runBlocking

/** 闹钟触发：核对新鲜度、去重、错过判定后发通知（含打开对应条目的 ContentIntent）。 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        val pending = goAsync()
        val app = context.applicationContext as DayPlanApp
        Thread {
            try {
                val fired = runBlocking { app.coordinator.onFired(key, System.currentTimeMillis()) }
                if (fired != null) {
                    try {
                        DayPlanNotifications.postReminder(context, fired)
                        app.reminderStatus.recordDelivered(fired.title, System.currentTimeMillis())
                    } catch (e: SecurityException) {
                        // 权限在检查后被撤销：释放去重标记，重建时可重试
                        runBlocking { app.coordinator.releaseOccurrence(key) }
                        app.reminderStatus.recordBlocked(
                            "通知权限被撤销，提醒未送达", System.currentTimeMillis(),
                        )
                    }
                }
            } catch (e: Exception) {
                // 接收器线程不能有未捕获异常
                app.reminderStatus.recordBlocked(
                    "提醒处理失败：${e.message ?: e.javaClass.simpleName}", System.currentTimeMillis(),
                )
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        const val ACTION_FIRE = "com.jsy.dayplan.ACTION_REMINDER_FIRE"
        const val EXTRA_KEY = "occurrence_key"
    }
}

/**
 * 启动完成 / 日期或时区变化 / 应用升级 / 精确提醒重新授权：
 * 从数据库重新协调提醒、补发宽限内通知、刷新桌面组件。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val interesting = action in setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            ACTION_EXACT_ALARM_PERMISSION_CHANGED,
        )
        if (!interesting) return
        val pending = goAsync()
        val app = context.applicationContext as DayPlanApp
        Thread {
            try {
                runBlocking { app.reconcileAndNotify() }
                try {
                    app.refreshWidgets()
                } catch (e: Exception) { /* 组件刷新失败不影响数据 */ }
                // 设备重启后：运行中的计时器标记暂停（DayPlanApp 读取持久化状态）
                app.onDeviceRebootedIfNeeded()
            } catch (e: Exception) {
                app.reminderStatus.recordBlocked(
                    "提醒重建失败：${e.message ?: e.javaClass.simpleName}", System.currentTimeMillis(),
                )
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        const val ACTION_EXACT_ALARM_PERMISSION_CHANGED =
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
    }
}

/** 专注计时结束闹钟：以单调时钟为基准，核对轮次身份后通知。 */
class FocusTimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FOCUS_END) return
        val token = intent.getStringExtra(EXTRA_TOKEN) ?: return
        val pending = goAsync()
        val app = context.applicationContext as DayPlanApp
        Thread {
            try {
                // 旧一轮/暂停/重置后的回调不能结束当前轮次；原子去重防止前台 ticker 重复通知
                if (!app.tryMarkFocusEnded(token)) return@Thread
                val failure = DayPlanNotifications.postFocusEnd(context, token)
                if (failure == null) {
                    app.reminderStatus.recordDelivered("专注计时结束", System.currentTimeMillis())
                } else {
                    app.reminderStatus.recordBlocked(
                        "专注结束通知未送达：$failure", System.currentTimeMillis(),
                    )
                }
            } catch (e: Exception) {
                app.reminderStatus.recordBlocked(
                    "专注结束提醒失败：${e.message ?: e.javaClass.simpleName}",
                    System.currentTimeMillis(),
                )
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        const val ACTION_FOCUS_END = "com.jsy.dayplan.ACTION_FOCUS_END"
        const val EXTRA_TOKEN = "focus_round_token"
    }
}
