package com.jsy.dayplan.core

import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * 新建表单的日期/时间默认值。集中在这里，便于回归测试，
 * 也避免“今天新建待办默认无截止”和“月历新建继承日期”两套口径分叉。
 */
object EntryDefaults {

    data class ScheduleSuggestion(
        val startDate: LocalDate,
        val endDate: LocalDate,
        val startTime: String,
        val endTime: String,
        /** 显示给用户的中性说明，明确这是建议而不是已填数据。 */
        val label: String,
    )

    /** 今天新建：下一个整点、1 小时；跨到次日时日期也显式前进。 */
    fun scheduleSuggestion(
        presetDate: LocalDate?,
        today: LocalDate,
        now: LocalTime,
    ): ScheduleSuggestion {
        val target = presetDate ?: today
        if (target != today) {
            return ScheduleSuggestion(
                target, target, "09:00", "10:00",
                "默认建议：${target.monthValue}月${target.dayOfMonth}日 09:00–10:00",
            )
        }
        val next = now.truncatedTo(ChronoUnit.HOURS).plusHours(1)
        val startDate = if (next.hour == 0 && now.hour == 23) today.plusDays(1) else today
        val end = next.plusHours(1)
        val endDate = if (end.hour == 0 && next.hour == 23) startDate.plusDays(1) else startDate
        val dayLabel = when (startDate) {
            today -> "今天"
            today.plusDays(1) -> "明天"
            else -> "${startDate.monthValue}月${startDate.dayOfMonth}日"
        }
        return ScheduleSuggestion(
            startDate,
            endDate,
            "%02d:%02d".format(next.hour, next.minute),
            "%02d:%02d".format(end.hour, end.minute),
            "默认建议：$dayLabel ${"%02d:%02d".format(next.hour, next.minute)}–${"%02d:%02d".format(end.hour, end.minute)}",
        )
    }

    /**
     * 待办截止：今天页新建默认不设截止；月历指定日期时预填该日。
     * 返回 null 表示“未定日期”。
     */
    fun todoPresetDueDate(presetDate: LocalDate?): LocalDate? = presetDate

    /** 倒数日目标：月历指定日期时继承；否则维持“一个月后”的旧默认。 */
    fun countdownTarget(presetDate: LocalDate?, today: LocalDate): LocalDate =
        presetDate ?: today.plusMonths(1)
}
