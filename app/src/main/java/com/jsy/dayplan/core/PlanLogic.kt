package com.jsy.dayplan.core

import java.time.LocalDate

/** 日程类别。 */
object ScheduleCategory {
    val NAMES = listOf("学习", "工作", "生活", "其他")
}

/**
 * 日程核心：跨日覆盖、左闭右开边界、冲突检测、时间流排序。
 *
 * 时间模型：日程按“设备当前时区下的本地日期和时间”表达——
 * 保存的是本地日期/时间字段；时区变化后仍是当地同一时刻（重算提醒）。
 * 全天事件只保存日期。
 */
object ScheduleLogic {

    data class ScheduleSpan(
        val id: Long,
        val title: String,
        val startDate: LocalDate,
        val endDate: LocalDate,
        val allDay: Boolean,
        val startTime: String?,   // "HH:mm"，全天为 null
        val endTime: String?,
    )

    /**
     * 日程覆盖的日期集合（含首尾）。
     * 左闭右开：结束时间恰好是次日 00:00 的活动不多占一天——
     * 即当 endTime == "00:00" 且 endDate > startDate 时，endDate 那天不视为覆盖。
     * 全天跨日活动在覆盖的每一天都出现。
     */
    fun coveredDates(s: ScheduleSpan): List<LocalDate> {
        val dates = mutableListOf<LocalDate>()
        var d = s.startDate
        var last = s.endDate
        // 非全天且结束时间为次日 00:00：该结束日不占
        if (!s.allDay && s.endTime == "00:00" && s.endDate.isAfter(s.startDate)) {
            last = s.endDate.minusDays(1)
        }
        while (!d.isAfter(last)) {
            dates.add(d)
            d = d.plusDays(1)
        }
        return dates
    }

    /** 起止校验：开始必须早于结束（跨日按日期+时间拼接比较）。 */
    fun validateSpan(s: ScheduleSpan): String? {
        if (s.title.isBlank()) return "标题不能为空"
        if (s.endDate.isBefore(s.startDate)) return "结束日期不能早于开始日期"
        if (!s.allDay) {
            val st = s.startTime ?: return "请填写开始时间"
            val et = s.endTime ?: return "请填写结束时间"
            val startMin = toMinutes(st)
            val endMin = toMinutes(et)
            if (startMin == null || endMin == null) return "时间格式非法"
            if (s.endDate == s.startDate && endMin <= startMin) {
                return "开始必须早于结束"
            }
        }
        return null
    }

    fun toMinutes(hhmm: String): Int? {
        val m = Regex("""^(\d{1,2}):(\d{2})$""").find(hhmm) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        if (h !in 0..23 || min !in 0..59) return null
        return h * 60 + min
    }

    /**
     * 时间冲突：同一日期上两个日程的 [start,end) 区间重叠（全天视为整天）。
     * 返回与目标日程冲突的其他日程 ID 列表（用于轻提示，不阻止保存）。
     */
    fun conflictingWith(
        target: ScheduleSpan,
        others: List<ScheduleSpan>,
    ): List<Long> {
        val targetDays = coveredDates(target).toSet()
        val out = mutableListOf<Long>()
        for (o in others) {
            if (o.id == target.id) continue
            val days = coveredDates(o)
            if (days.none { it in targetDays }) continue
            if (target.allDay || o.allDay) { out.add(o.id); continue }
            // 同一日的时间区间重叠检查（跨日日程按首末日处理，简化为全天级别重叠）
            val overlapOnSameDay = coveredDates(o).intersect(targetDays).isNotEmpty() &&
                (spansMultiDay(o) || spansMultiDay(target) ||
                    overlapsSameDay(target, o))
            if (overlapOnSameDay) out.add(o.id)
        }
        return out
    }

    private fun spansMultiDay(s: ScheduleSpan): Boolean = s.startDate != s.endDate

    private fun overlapsSameDay(a: ScheduleSpan, b: ScheduleSpan): Boolean {
        val as_ = toMinutes(a.startTime ?: return true) ?: return true
        val ae = toMinutes(a.endTime ?: return true) ?: return true
        val bs = toMinutes(b.startTime ?: return true) ?: return true
        val be = toMinutes(b.endTime ?: return true) ?: return true
        return as_ < be && bs < ae
    }

    /** 时间流排序：按开始日期与开始时间（全天排最前），跨日按起始。 */
    fun <T> sortChronologically(
        items: List<T>,
        span: (T) -> ScheduleSpan,
    ): List<T> = items.sortedWith(
        compareBy(
            { t: T -> span(t).startDate.toString() },
            { t: T -> if (span(t).allDay) 0 else (toMinutes(span(t).startTime ?: "00:00") ?: 0) },
        ),
    )
}

/**
 * 专注倒计时状态机：同一次开机期间使用单调时钟（elapsedRealtime）计算剩余时长。
 * 状态持久化（running、deadlineElapsed、pausedRemainingMs、bootId）；
 * 重启后 running 计时器标记暂停并提示“设备已重启，请确认后继续”。
 */
object FocusTimerLogic {

    val QUICK_CHOICES = listOf(5, 15, 25, 45)
    const val MIN_MINUTES = 1
    const val MAX_MINUTES = 180

    data class State(
        val running: Boolean,
        val durationMinutes: Int,
        /** running=true 时：以单调时钟毫秒表示的结束点。 */
        val deadlineElapsedMs: Long,
        /** paused=true 时：剩余毫秒。 */
        val pausedRemainingMs: Long,
        val paused: Boolean,
        val bootId: String,
        /** 计时器自然结束时记录（用于去重，避免过期回调影响新一轮）。 */
        val endNotified: Boolean,
        /** 每秒刷新的最后已知剩余（设备重启后作为暂停剩余，不做时间推算）。 */
        val lastKnownRemainingMs: Long = 0,
        /** 每轮唯一身份；同一单调时刻开始两轮也不会共用回调。 */
        val roundId: String = "",
    ) {
        companion object {
            val IDLE = State(false, 25, 0, 0, true, "none", false, 0)
        }
    }

    fun remainingMs(state: State, nowElapsedMs: Long): Long = when {
        state.running && !state.paused -> (state.deadlineElapsedMs - nowElapsedMs).coerceAtLeast(0)
        else -> state.pausedRemainingMs
    }

    fun start(durationMinutes: Int, nowElapsedMs: Long, bootId: String): State {
        require(durationMinutes in MIN_MINUTES..MAX_MINUTES) { "时长须在 1–180 分钟" }
        val durMs = durationMinutes * 60_000L
        return State(
            running = true,
            durationMinutes = durationMinutes,
            deadlineElapsedMs = nowElapsedMs + durMs,
            pausedRemainingMs = 0,
            paused = false,
            bootId = bootId,
            endNotified = false,
            lastKnownRemainingMs = durMs,
        )
    }

    fun pause(state: State, nowElapsedMs: Long): State {
        if (!state.running || state.paused) return state
        val rem = remainingMs(state, nowElapsedMs)
        return state.copy(paused = true, pausedRemainingMs = rem, lastKnownRemainingMs = rem)
    }

    fun resume(state: State, nowElapsedMs: Long): State {
        if (!state.running || !state.paused) return state
        val remain = state.pausedRemainingMs.coerceAtLeast(0)
        return state.copy(
            paused = false, deadlineElapsedMs = nowElapsedMs + remain,
            lastKnownRemainingMs = remain,
        )
    }

    fun reset(state: State): State = State.IDLE

    fun formatRemaining(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        return "%02d:%02d".format(totalSec / 60, totalSec % 60)
    }

    fun naturalEnd(state: State, nowElapsedMs: Long): Boolean =
        state.running && !state.paused && remainingMs(state, nowElapsedMs) <= 0

    /** 设备重启后：运行中的计时器标记暂停，剩余用重启前最后已知值（不做时间推算）。 */
    fun onReboot(state: State, lastKnownRemainingMs: Long): State {
        if (!state.running) return state
        return state.copy(
            paused = true,
            pausedRemainingMs = lastKnownRemainingMs.coerceAtLeast(0),
            lastKnownRemainingMs = lastKnownRemainingMs.coerceAtLeast(0),
        )
    }

}
