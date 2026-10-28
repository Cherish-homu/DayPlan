package com.jsy.dayplan

import android.app.Application
import android.content.Context
import com.jsy.dayplan.ai.AiContainer
import com.jsy.dayplan.core.FocusTimerLogic
import com.jsy.dayplan.core.SystemAppClock
import com.jsy.dayplan.data.PlanDb
import com.jsy.dayplan.data.PlanRepository
import com.jsy.dayplan.ledger.data.LedgerDb
import com.jsy.dayplan.ledger.data.LedgerRepository
import com.jsy.dayplan.reminder.AndroidReminderScheduler
import com.jsy.dayplan.reminder.DayPlanNotifications
import com.jsy.dayplan.reminder.FocusAlarms
import com.jsy.dayplan.reminder.PrefsReminderKeyStore
import com.jsy.dayplan.reminder.ReminderCoordinator
import com.jsy.dayplan.reminder.ReminderStatusStore
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

class DayPlanApp : Application() {

    lateinit var db: PlanDb
    lateinit var repo: PlanRepository
    lateinit var coordinator: ReminderCoordinator
    lateinit var clock: SystemAppClock
    lateinit var reminderStatus: ReminderStatusStore
    lateinit var ai: AiContainer

    /** 账本独立库：与 PlanDb 完全分离，账本数据/备份/恢复自成体系。 */
    val ledgerRepo: LedgerRepository by lazy {
        LedgerRepository(LedgerDb.get(this)).also {
            it.snapshotDir = File(filesDir, "ledger_restore_snapshots")
        }
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        clock = SystemAppClock(contentResolver)
        db = PlanDb.get(this)
        repo = PlanRepository(db)
        repo.snapshotDir = File(filesDir, "restore_snapshots")
        reminderStatus = ReminderStatusStore(this)
        DayPlanNotifications.ensureReminderChannel(this)

        coordinator = ReminderCoordinator(
            db.dao(),
            AndroidReminderScheduler(this),
            PrefsReminderKeyStore(this),
        )
        coordinator.deliveryAvailable = {
            DayPlanNotifications.deliveryProblem(this, REMINDER_CHANNEL_ID) == null
        }
        coordinator.onDeliveryBlocked = { reason ->
            reminderStatus.recordBlocked(reason, System.currentTimeMillis())
        }

        // 数据变更后刷新桌面小组件（不在 Room 事务内执行，也不阻塞主线程）
        repo.onDataChanged = { refreshWidgets() }

        // v1.4 AI 助手装配 + PDF 文本提取字体资源初始化
        ai = AiContainer(this, repo, coordinator, clock)
        try {
            PDFBoxResourceLoader.init(applicationContext)
        } catch (e: Exception) {
            // 字体资源初始化失败不阻塞文本提取主流程
        }
        appScope.launch { ai.pruneOnStart() }
        detectRebootForTimer()
    }

    fun refreshWidgets() {
        appScope.launch {
            try {
                com.jsy.dayplan.widget.PlanWidgetUpdater.updateAll(this@DayPlanApp, db, LocalDate.now())
            } catch (e: Exception) { /* 组件刷新失败不影响数据 */ }
        }
    }

    /**
     * 重新协调提醒并投递宽限内到期的通知；返回实际投递条目。
     * 调度/数据库失败会抛出，调用方据此区分“数据已恢复但提醒待重建”等状态。
     */
    suspend fun reconcileAndNotify(): List<ReminderCoordinator.FiredInfo> {
        val fired = coordinator.rebuildAll(LocalDateTime.now())
        val posted = mutableListOf<ReminderCoordinator.FiredInfo>()
        fired.forEach { f ->
            try {
                DayPlanNotifications.postReminder(this, f)
                reminderStatus.recordDelivered(f.title, System.currentTimeMillis())
                posted.add(f)
            } catch (e: SecurityException) {
                coordinator.releaseOccurrence(f.key)
                reminderStatus.recordBlocked(
                    "通知权限被撤销，提醒未送达", System.currentTimeMillis(),
                )
            }
        }
        return posted
    }

    // ---------- 专注倒计时持久化（单调时钟 + bootId） ----------
    private val timerPrefs by lazy { getSharedPreferences("focus_timer", MODE_PRIVATE) }
    val focusSessions by lazy { com.jsy.dayplan.data.FocusSessionStore(this) }
    val dayNotes by lazy { com.jsy.dayplan.data.DayNoteStore(this) }

    fun loadTimerState(): FocusTimerLogic.State {
        val j = timerPrefs.getString("state", null) ?: return FocusTimerLogic.State.IDLE
        return try {
            val o = JSONObject(j)
            FocusTimerLogic.State(
                running = o.getBoolean("running"),
                durationMinutes = o.getInt("duration"),
                deadlineElapsedMs = o.getLong("deadline"),
                pausedRemainingMs = o.getLong("remaining"),
                paused = o.getBoolean("paused"),
                bootId = o.getString("bootId"),
                endNotified = o.getBoolean("endNotified"),
                lastKnownRemainingMs = o.optLong("lastKnownRemaining", 0),
                roundId = o.optString("roundId", ""),
            )
        } catch (e: Exception) {
            FocusTimerLogic.State.IDLE
        }
    }

    fun saveTimerState(s: FocusTimerLogic.State) {
        timerPrefs.edit().putString(
            "state",
            JSONObject()
                .put("running", s.running)
                .put("duration", s.durationMinutes)
                .put("deadline", s.deadlineElapsedMs)
                .put("remaining", s.pausedRemainingMs)
                .put("paused", s.paused)
                .put("bootId", s.bootId)
                .put("endNotified", s.endNotified)
                .put("lastKnownRemaining", s.lastKnownRemainingMs)
                .put("roundId", s.roundId)
                .toString(),
        ).apply()
    }

    var rebootNoticePending: Boolean
        get() = timerPrefs.getBoolean("rebootNotice", false)
        set(v) = timerPrefs.edit().putBoolean("rebootNotice", v).apply()

    /**
     * 原子结束一轮专注：接收器与前台 ticker 可能同时到达，
     * 只有先写入 endNotified 的一方返回 true，避免重复通知。
     */
    @Synchronized
    fun tryMarkFocusEnded(token: String): Boolean {
        val s = loadTimerState()
        if (!s.running || s.paused || s.endNotified) return false
        if (FocusAlarms.tokenOf(s) != token) return false
        // 先写唯一轮次的完成记录，再标记状态；中途进程退出后重试也不会重复计数。
        // 账本写入失败不能卡住计时器：仍结束本轮，只是这一次不计入统计。
        val lateMs = (clock.elapsedRealtimeMs() - s.deadlineElapsedMs).coerceAtLeast(0)
        try {
            focusSessions.record(com.jsy.dayplan.data.FocusSession(
                token, s.durationMinutes, (System.currentTimeMillis() - lateMs).coerceAtLeast(0),
            ))
        } catch (e: Exception) {
            android.util.Log.w("DayPlanApp", "focus session record failed: ${e.javaClass.simpleName}")
        }
        saveTimerState(
            s.copy(
                running = false,
                paused = true,
                pausedRemainingMs = 0,
                endNotified = true,
                lastKnownRemainingMs = 0,
            ),
        )
        com.jsy.dayplan.reminder.FocusSoundService.stop(this)
        return true
    }

    /** 供 BootReceiver 调用：检测重启并刷新计时器状态。 */
    fun onDeviceRebootedIfNeeded() = detectRebootForTimer()

    /**
     * 设备重启后：运行中的计时器暂停并提示，不做时间推算。
     * 处理完立即把 bootId 更新为本次开机，避免每次打开都重复提示“同一次重启”。
     */
    fun detectRebootForTimer() {
        val s = loadTimerState()
        val currentBoot = clock.bootId()
        if (s.bootId == currentBoot) return
        if (s.running) {
            val paused = FocusTimerLogic.onReboot(s, s.lastKnownRemainingMs)
                .copy(bootId = currentBoot, endNotified = false)
            saveTimerState(paused)
            rebootNoticePending = true
        } else if (s.bootId != "none") {
            saveTimerState(s.copy(bootId = currentBoot))
        }
    }

    override fun onTerminate() {
        super.onTerminate()
    }

    companion object {
        const val REMINDER_CHANNEL_ID = "dayplan_reminders"
        const val FOCUS_CHANNEL_ID = "dayplan_focus"
    }
}
