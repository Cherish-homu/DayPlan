package com.jsy.dayplan.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.jsy.dayplan.core.FocusTimerLogic

/**
 * 专注计时后台结束闹钟。
 *
 * 使用 ELAPSED_REALTIME_WAKEUP（自开机起的单调时钟）而不是 RTC：
 * 用户修改系统时间不会改变计时长度，设备重启后系统自动清空旧闹钟。
 * 每轮 token 由 bootId + 截止单调时刻 + 时长组成，旧一轮回调无法结束新一轮。
 */
object FocusAlarms {

    fun tokenOf(state: FocusTimerLogic.State): String =
        if (state.roundId.isNotBlank()) state.roundId
        else "${state.bootId}:${state.deadlineElapsedMs}:${state.durationMinutes}"

    private fun pendingIntent(context: Context, token: String, create: Boolean): PendingIntent? {
        val intent = Intent(context, FocusTimerReceiver::class.java).apply {
            action = FocusTimerReceiver.ACTION_FOCUS_END
            putExtra(FocusTimerReceiver.EXTRA_TOKEN, token)
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or
            if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE
        return PendingIntent.getBroadcast(context, token.hashCode(), intent, flags)
    }

    /** 注册结束闹钟；返回 false 表示系统拒绝（界面需如实提示）。 */
    fun schedule(context: Context, state: FocusTimerLogic.State): Boolean {
        if (!state.running || state.paused) return false
        val trigger = state.deadlineElapsedMs
        if (trigger <= SystemClock.elapsedRealtime()) return false
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context, tokenOf(state), create = true) ?: return false
        val exactAllowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.canScheduleExactAlarms()
        } else true
        return try {
            if (exactAllowed) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
            }
            true
        } catch (e: SecurityException) {
            try {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
                true
            } catch (e2: Exception) {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    fun cancel(context: Context, token: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        pendingIntent(context, token, create = false)?.let {
            am.cancel(it)
            it.cancel()
        }
    }
}
