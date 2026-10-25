package com.jsy.dayplan.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jsy.dayplan.DayPlanApp
import com.jsy.dayplan.core.FocusTimerLogic
import com.jsy.dayplan.reminder.DayPlanNotifications
import com.jsy.dayplan.reminder.FocusAlarms
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 专注倒计时：
 * - 状态持久化到 App（含 lastKnownRemainingMs），每秒 ticker 只负责前台展示；
 * - 开始/继续时用 ELAPSED_REALTIME_WAKEUP 注册真实的后台结束闹钟，暂停/重置取消；
 * - 进程普通回收后，闹钟仍能唤醒 FocusTimerReceiver 发结束通知；
 * - 设备重启后暂停并提示，剩余取重启前最后已知值。
 */
class FocusTimerViewModel(app: Application) : AndroidViewModel(app) {
    private val app0 = app as DayPlanApp

    private val _state: MutableStateFlow<FocusTimerLogic.State> = run {
        app0.detectRebootForTimer()
        MutableStateFlow(app0.loadTimerState())
    }

    val state: StateFlow<FocusTimerLogic.State> = _state

    val rebootNotice = MutableStateFlow(app0.rebootNoticePending)

    /** 后台结束闹钟是否成功注册；false 时界面需如实提示。 */
    val alarmScheduled = MutableStateFlow(
        _state.value.running && !_state.value.paused && _state.value.bootId == app0.clock.bootId(),
    )

    private var ticker: kotlinx.coroutines.Job? = null

    val remainingMs = MutableStateFlow(
        FocusTimerLogic.remainingMs(_state.value, app0.clock.elapsedRealtimeMs()),
    )

    private var lastPersistAt = 0L

    init {
        val s = _state.value
        if (s.running && !s.paused && s.bootId == app0.clock.bootId()) {
            // 进程被回收后重新进入：重新注册同一轮次的结束闹钟（幂等）。
            FocusAlarms.schedule(app0, s)
        }
        startTicker()
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (isActive) {
                val s = _state.value
                val nowElapsed = app0.clock.elapsedRealtimeMs()
                val rem = FocusTimerLogic.remainingMs(s, nowElapsed)
                remainingMs.value = rem

                if (s.running && !s.paused) {
                    if (rem <= 0) {
                        finishNaturally(s)
                    } else {
                        // 每 5 秒持久化一次最后已知剩余；状态变化时立即持久化。
                        if (nowElapsed - lastPersistAt >= 5_000L) {
                            lastPersistAt = nowElapsed
                            val updated = s.copy(lastKnownRemainingMs = rem)
                            _state.value = updated
                            app0.saveTimerState(updated)
                        }
                    }
                }
                delay(1000)
            }
        }
    }

    private fun finishNaturally(s: FocusTimerLogic.State) {
        val token = FocusAlarms.tokenOf(s)
        // 后台闹钟可能已先结束本轮：同步持久化状态，不再重复通知
        if (!app0.tryMarkFocusEnded(token)) {
            _state.value = app0.loadTimerState()
            alarmScheduled.value = false
            return
        }
        _state.value = app0.loadTimerState()
        alarmScheduled.value = false
        FocusAlarms.cancel(app0, token)
        val failure = DayPlanNotifications.postFocusEnd(app0, token)
        if (failure == null) {
            app0.reminderStatus.recordDelivered("专注计时结束", System.currentTimeMillis())
        } else {
            app0.reminderStatus.recordBlocked(
                "专注结束通知未送达：$failure", System.currentTimeMillis(),
            )
        }
    }

    fun start(minutes: Int) {
        val old = _state.value
        if (old.running) FocusAlarms.cancel(app0, FocusAlarms.tokenOf(old))
        val s = FocusTimerLogic.start(minutes, app0.clock.elapsedRealtimeMs(), app0.clock.bootId())
            .copy(roundId = java.util.UUID.randomUUID().toString())
        _state.value = s
        app0.saveTimerState(s)
        remainingMs.value = FocusTimerLogic.remainingMs(s, app0.clock.elapsedRealtimeMs())
        lastPersistAt = app0.clock.elapsedRealtimeMs()
        alarmScheduled.value = FocusAlarms.schedule(app0, s)
    }

    fun pause() {
        val s0 = _state.value
        val s = FocusTimerLogic.pause(s0, app0.clock.elapsedRealtimeMs())
        _state.value = s
        app0.saveTimerState(s)
        remainingMs.value = s.pausedRemainingMs
        FocusAlarms.cancel(app0, FocusAlarms.tokenOf(s0))
        alarmScheduled.value = false
    }

    fun resume() {
        val s = FocusTimerLogic.resume(_state.value, app0.clock.elapsedRealtimeMs())
            .copy(
                bootId = app0.clock.bootId(),
                endNotified = false,
                roundId = java.util.UUID.randomUUID().toString(),
            )
        _state.value = s
        app0.saveTimerState(s)
        remainingMs.value = FocusTimerLogic.remainingMs(s, app0.clock.elapsedRealtimeMs())
        lastPersistAt = app0.clock.elapsedRealtimeMs()
        alarmScheduled.value = FocusAlarms.schedule(app0, s)
    }

    fun reset() {
        val old = _state.value
        if (old.running) FocusAlarms.cancel(app0, FocusAlarms.tokenOf(old))
        val s = FocusTimerLogic.reset(old)
        _state.value = s
        app0.saveTimerState(s)
        remainingMs.value = 0
        alarmScheduled.value = false
    }

    fun clearRebootNotice() {
        app0.rebootNoticePending = false
        rebootNotice.value = false
    }

    fun notificationsEnabled(): Boolean =
        DayPlanNotifications.deliveryProblem(app0, DayPlanApp.FOCUS_CHANNEL_ID) == null
}
