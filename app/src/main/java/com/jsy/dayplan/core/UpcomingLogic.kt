package com.jsy.dayplan.core

import com.jsy.dayplan.data.ScheduleEntity
import com.jsy.dayplan.data.TodoEntity
import java.time.LocalDate

enum class UpcomingKind { SCHEDULE, TODO }

data class UpcomingEntry(
    val kind: UpcomingKind,
    val id: Long,
    val title: String,
    val timeLabel: String,
    val priority: Int = 0,
)

data class UpcomingDay(
    val date: LocalDate,
    val entries: List<UpcomingEntry>,
    /** 超过每日展示上限、未放进 entries 的条数。 */
    val hiddenCount: Int = 0,
) {
    val totalCount: Int get() = entries.size + hiddenCount
}

/**
 * 首页“未来几天”概览：只统计明天起 [days] 天内的新开始日程与未完成待办。
 * 多日日程按开始日出现一次，避免每天重复占位；已逾期/今天的内容留给首页原有分区。
 */
object UpcomingLogic {
    const val DEFAULT_DAYS = 7
    const val PER_DAY_LIMIT = 4

    fun build(
        today: LocalDate,
        schedules: List<ScheduleEntity>,
        todos: List<TodoEntity>,
        days: Int = DEFAULT_DAYS,
        perDayLimit: Int = PER_DAY_LIMIT,
    ): List<UpcomingDay> {
        require(days in 1..30) { "days 须在 1–30" }
        require(perDayLimit >= 1) { "每天至少展示 1 项" }
        val result = mutableListOf<UpcomingDay>()
        for (offset in 1..days) {
            val date = today.plusDays(offset.toLong())
            val dateText = date.toString()
            val entries = mutableListOf<UpcomingEntry>()

            schedules.forEach { s ->
                if (s.startDate == dateText) {
                    entries += UpcomingEntry(
                        kind = UpcomingKind.SCHEDULE,
                        id = s.id,
                        title = s.title,
                        timeLabel = if (s.allDay) "全天" else "${s.startTime ?: ""}–${s.endTime ?: ""}",
                    )
                }
            }
            todos.forEach { t ->
                if (!t.done && t.dueDate == dateText) {
                    entries += UpcomingEntry(
                        kind = UpcomingKind.TODO,
                        id = t.id,
                        title = t.title,
                        timeLabel = if (t.dueTime.isNullOrBlank()) "截止" else "截止 ${t.dueTime}",
                        priority = t.priority,
                    )
                }
            }
            if (entries.isEmpty()) continue

            val sorted = entries.sortedWith(
                compareBy<UpcomingEntry> { if (it.kind == UpcomingKind.SCHEDULE) 0 else 1 }
                    .thenBy { it.timeLabel }
                    .thenByDescending { it.priority }
                    .thenBy { it.id },
            )
            result += UpcomingDay(
                date = date,
                entries = sorted.take(perDayLimit),
                hiddenCount = (sorted.size - perDayLimit).coerceAtLeast(0),
            )
        }
        return result
    }
}
