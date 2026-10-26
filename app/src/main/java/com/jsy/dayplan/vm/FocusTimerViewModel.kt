package com.jsy.dayplan.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jsy.dayplan.DayPlanApp
import com.jsy.dayplan.core.FocusAppearance
import com.jsy.dayplan.core.FocusPreferences
import com.jsy.dayplan.core.FocusSound
import com.jsy.dayplan.core.FocusTimerLogic
import com.jsy.dayplan.data.FocusStats
import com.jsy.dayplan.reminder.DayPlanNotifications
import com.jsy.dayplan.reminder.FocusAlarms
import com.jsy.dayplan.reminder.FocusSoundService
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
 * - 设备重启后暂停并提示，剩余取重启前最后已知值；
 * - 自然结束先写入本地专注账本（轮次 ID 去重），再发结束通知。
 */
class FocusTimerViewModel(app: Application) : AndroidViewModel(app) {
    private val app0 = app as DayPlanApp
    private val preferences = FocusPreferences(app)
    val appearance = MutableStateFlow(preferences.load())
    val stats = MutableStateFlow(FocusStats())
    val playingSound: StateFlow<FocusSound> = FocusSoundService.playing
    val soundError: StateFlow<String?> = FocusSoundService.error

    private val _state: MutableStateFlow<FocusTimerLogic.State> = run {
        app0.detectRebootForTimer()
        MutableStateFlow(app0.loadTimerState())
    }

    val state: StateFlow<FocusTimerLogic.State> = _state

    val rebootNotice = MutableStateFlow(app0.rebootNoticePending)

    /** 后台结束闹钟是否成功注册；false 时界面需如实提示。 */
    val alarmScheduled = MutableStateFlow(false)

    private var ticker: Job? = null
    private var statsDay: LocalDate = LocalDate.now()

    val remainingMs = MutableStateFlow(
        FocusTimerLogic.remainingMs(_state.value, app0.clock.elapsedRealtimeMs()),
    )

    private var lastPersistAt = 0L

    init {
        viewModelScope.launch(Dispatchers.IO) {
            app0.focusSessions.revision.collect { stats.value = app0.focusSessions.snapshot() }
        }
        val s = _state.value
        if (s.running && !s.paused && s.bootId == app0.clock.bootId()) {
            // 进程被回收后重新进入：重新注册同一轮次的结束闹钟（幂等）。
            alarmScheduled.value = FocusAlarms.schedule(app0, s)
            refreshSound()
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
                    } else if (nowElapsed - lastPersistAt >= 5_000L) {
                        // 每 5 秒持久化一次最后已知剩余；状态变化时立即持久化。
                        lastPersistAt = nowElapsed
                        val updated = s.copy(lastKnownRemainingMs = rem)
                        _state.value = updated
                        app0.saveTimerState(updated)
                    }
                }
                // 跨过午夜后刷新“今日”统计，避免页面一直显示昨天次数。
                val today = LocalDate.now()
                if (today != statsDay) {
                    statsDay = today
                    refreshStats()
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
        if (_state.value.running) return
        if (minutes !in FocusTimerLogic.MIN_MINUTES..FocusTimerLogic.MAX_MINUTES) return
        updateAppearance(appearance.value.copy(minutes = minutes))
        FocusSoundService.clearError()
        val old = _state.value
        if (old.running) FocusAlarms.cancel(app0, FocusAlarms.tokenOf(old))
        val s = FocusTimerLogic.start(minutes, app0.clock.elapsedRealtimeMs(), app0.clock.bootId())
            .copy(roundId = java.util.UUID.randomUUID().toString())
        _state.value = s
        app0.saveTimerState(s)
        remainingMs.value = FocusTimerLogic.remainingMs(s, app0.clock.elapsedRealtimeMs())
        lastPersistAt = app0.clock.elapsedRealtimeMs()
        alarmScheduled.value = FocusAlarms.schedule(app0, s)
        refreshSound()
    }

    fun pause() {
        val s0 = _state.value
        if (!s0.running || s0.paused) return
        if (FocusTimerLogic.naturalEnd(s0, app0.clock.elapsedRealtimeMs())) {
            finishNaturally(s0)
            return
        }
        val s = FocusTimerLogic.pause(s0, app0.clock.elapsedRealtimeMs())
        _state.value = s
        app0.saveTimerState(s)
        remainingMs.value = s.pausedRemainingMs
        FocusAlarms.cancel(app0, FocusAlarms.tokenOf(s0))
        alarmScheduled.value = false
        FocusSoundService.stop(app0)
    }

    fun resume() {
        val s0 = _state.value
        if (!s0.running || !s0.paused) return
        val s = FocusTimerLogic.resume(s0, app0.clock.elapsedRealtimeMs())
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
        refreshSound()
    }

    fun reset() {
        val old = _state.value
        if (FocusTimerLogic.naturalEnd(old, app0.clock.elapsedRealtimeMs())) {
            finishNaturally(old)
            return
        }
        if (old.running) FocusAlarms.cancel(app0, FocusAlarms.tokenOf(old))
        val s = FocusTimerLogic.reset(old).copy(durationMinutes = appearance.value.minutes)
        _state.value = s
        app0.saveTimerState(s)
        remainingMs.value = 0
        alarmScheduled.value = false
        FocusSoundService.stop(app0)
    }

    private fun updateAppearance(value: FocusAppearance) {
        appearance.value = value
        preferences.save(value)
    }
    fun chooseDuration(minutes: Int) {
        if (!_state.value.running && minutes in FocusTimerLogic.MIN_MINUTES..FocusTimerLogic.MAX_MINUTES) {
            updateAppearance(appearance.value.copy(minutes = minutes))
        }
    }
    fun setScene(scene: Int) { updateAppearance(appearance.value.copy(scene = scene.coerceIn(0, 4))) }
    fun setBlur(blur: Float) { updateAppearance(appearance.value.copy(blur = blur.coerceIn(0f, 12f))) }
    fun setScrim(scrim: Float) { updateAppearance(appearance.value.copy(scrim = scrim.coerceIn(0.15f, 0.85f))) }
    fun setSound(sound: FocusSound) {
        FocusSoundService.clearError()
        updateAppearance(appearance.value.copy(sound = sound))
        refreshSound()
    }
    /** 拖动时只更新预览与偏好，松手后再重启音频，避免反复重建播放器。 */
    fun setVolume(volume: Float) { updateAppearance(appearance.value.copy(volume = volume.coerceIn(0f, 1f))) }

    fun refreshSound() {
        // 通知栏“关闭声音”会直接改写偏好，这里以磁盘值为准，避免继续用内存里的旧选择。
        val saved = preferences.load()
        if (saved.sound != appearance.value.sound) appearance.value = saved
        val s = _state.value
        if (s.running && !s.paused && saved.sound != FocusSound.OFF) {
            FocusSoundService.start(app0)
        } else {
            FocusSoundService.stop(app0)
        }
    }
    fun refreshStats() {
        viewModelScope.launch(Dispatchers.IO) { stats.value = app0.focusSessions.snapshot() }
    }

    fun clearRebootNotice() {
        app0.rebootNoticePending = false
        rebootNotice.value = false
    }

    fun notificationsEnabled(): Boolean =
        DayPlanNotifications.deliveryProblem(app0, DayPlanApp.FOCUS_CHANNEL_ID) == null
}
