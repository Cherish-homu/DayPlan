package com.jsy.dayplan.reminder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jsy.dayplan.DayPlanApp
import com.jsy.dayplan.ui.MainActivity

/** 通知投递：权限/通道检查、ContentIntent、失败原因可见。 */
object DayPlanNotifications {

    /** null 表示可投递；否则返回人类可读原因。 */
    fun deliveryProblem(context: Context, channelId: String): String? {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return "系统通知未开启"
        }
        val ch = nm.getNotificationChannel(channelId)
            ?: return "通知通道不存在"
        if (ch.importance == NotificationManager.IMPORTANCE_NONE) {
            return "通知通道已关闭"
        }
        return null
    }

    fun ensureReminderChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(DayPlanApp.REMINDER_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    DayPlanApp.REMINDER_CHANNEL_ID,
                    "日程与倒数日提醒",
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }
        if (nm.getNotificationChannel(DayPlanApp.FOCUS_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    DayPlanApp.FOCUS_CHANNEL_ID,
                    "专注计时结束",
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }
    }

    private fun contentIntent(context: Context, fired: ReminderCoordinator.FiredInfo): PendingIntent {
        val intent = MainActivity.openEntityIntent(
            context,
            type = when (fired.entityType) {
                "sched" -> "schedule"
                "todo" -> "todo"
                "cd" -> "countdown"
                else -> ""
            },
            id = fired.entityId,
        )
        return PendingIntent.getActivity(
            context,
            fired.key.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun buildReminder(context: Context, fired: ReminderCoordinator.FiredInfo): Notification =
        NotificationCompat.Builder(context, DayPlanApp.REMINDER_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(fired.title)
            .setContentText(fired.body.ifBlank { "点按查看详情" })
            .setStyle(NotificationCompat.BigTextStyle().bigText(fired.body.ifBlank { "点按查看详情" }))
            .setContentIntent(contentIntent(context, fired))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()

    /** 可能抛 SecurityException（权限在检查后被撤销）；调用方负责释放去重标记。 */
    fun postReminder(context: Context, fired: ReminderCoordinator.FiredInfo) {
        NotificationManagerCompat.from(context).notify(fired.key.hashCode(), buildReminder(context, fired))
    }

    fun buildFocusEnd(context: Context, roundToken: String): Notification {
        val intent = MainActivity.openFocusIntent(context, roundToken)
        val pi = PendingIntent.getActivity(
            context,
            roundToken.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, DayPlanApp.FOCUS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("专注计时结束")
            .setContentText("本轮专注已完成，点按查看")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .build()
    }

    /** 返回 null=已实际发出；否则返回可读的未投递原因。 */
    fun postFocusEnd(context: Context, token: String): String? {
        val problem = deliveryProblem(context, DayPlanApp.FOCUS_CHANNEL_ID)
        if (problem != null) return problem
        return try {
            NotificationManagerCompat.from(context)
                .notify(FOCUS_NOTIFICATION_ID, buildFocusEnd(context, token))
            null
        } catch (e: SecurityException) {
            "通知权限被撤销"
        } catch (e: Exception) {
            "通知发送失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    const val FOCUS_NOTIFICATION_ID = 990_002
}
