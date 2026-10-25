package com.jsy.dayplan.core

import com.jsy.dayplan.data.TodoEntity
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** 待办日期文案：今天/未来/逾期用不同短句，逾期不整行铺红。 */
object TodoDisplay {

    fun overdueDays(t: TodoEntity, today: LocalDate): Long? {
        val due = t.dueDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        if (t.done || !due.isBefore(today)) return null
        return ChronoUnit.DAYS.between(due, today)
    }

    fun dueLine(t: TodoEntity, today: LocalDate): String? {
        val due = t.dueDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        val time = t.dueTime?.takeIf { it.isNotBlank() }
        if (t.done) {
            return "${due.monthValue}月${due.dayOfMonth}日" + (time?.let { " $it" } ?: "")
        }
        val overdue = overdueDays(t, today)
        return when {
            overdue != null -> "逾期 ${overdue} 天 · ${due.monthValue}月${due.dayOfMonth}日" +
                (time?.let { " $it" } ?: "")

            due == today -> "今天" + (time?.let { " $it" } ?: "")
            due == today.plusDays(1) -> "明天" + (time?.let { " $it" } ?: "")
            else -> "${due.monthValue}月${due.dayOfMonth}日" + (time?.let { " $it" } ?: "")
        }
    }
}
