package com.jsy.dayplan.core

import com.jsy.dayplan.data.CountdownEntity
import com.jsy.dayplan.data.ScheduleEntity
import com.jsy.dayplan.data.TodoEntity
import java.time.LocalDate

/**
 * 首页与「今日安排」小组件共用的口径：今日日程、待办分组与排序。
 * 任何一处改动都会同时影响两处，避免各算一套。
 */
object TodayLogic {

    data class TodoGroups(
        val overdue: List<TodoEntity>,
        val dueToday: List<TodoEntity>,
        val noDate: List<TodoEntity>,
        val future: List<TodoEntity>,
        val completed: List<TodoEntity>,
    )

    fun spanOf(s: ScheduleEntity): ScheduleLogic.ScheduleSpan = ScheduleLogic.ScheduleSpan(
        s.id, s.title, LocalDate.parse(s.startDate), LocalDate.parse(s.endDate),
        s.allDay, s.startTime, s.endTime,
    )

    /** 今天覆盖的全部日程，按时间流排序（全天在前）。 */
    fun schedulesForToday(schedules: List<ScheduleEntity>, today: LocalDate): List<ScheduleEntity> {
        val spans = schedules.associateBy { it.id }
        return ScheduleLogic.sortChronologically(
            schedules.filter { s -> ScheduleLogic.coveredDates(spanOf(s)).any { it == today } },
        ) { spanOf(it) }.also { spans.size }
    }

    /** 待办统一排序：重要优先，再按截止日期/时间，无日期最后。 */
    fun sortTodos(items: List<TodoEntity>): List<TodoEntity> = items.sortedWith(
        compareByDescending<TodoEntity> { it.priority }
            .thenBy { it.dueDate ?: "\uFFFF" }
            .thenBy { it.dueTime ?: "\uFFFF" }
            .thenBy { it.id },
    )

    fun todoGroups(todos: List<TodoEntity>, today: LocalDate): TodoGroups {
        val todayStr = today.toString()
        val active = todos.filter { !it.done }
        return TodoGroups(
            overdue = sortTodos(active.filter { it.dueDate != null && it.dueDate < todayStr }),
            dueToday = sortTodos(active.filter { it.dueDate == todayStr }),
            noDate = sortTodos(active.filter { it.dueDate == null }),
            future = sortTodos(active.filter { it.dueDate != null && it.dueDate > todayStr }),
            completed = sortTodos(todos.filter { it.done })
                .sortedByDescending { it.doneAtEpochMs },
        )
    }

    /** 首页/组件“重要日子”最多 3 张：置顶优先、临近优先（复用已测试排序）。 */
    fun topCountdowns(
        countdowns: List<CountdownEntity>,
        today: LocalDate,
        limit: Int = 3,
    ): List<CountdownEntity> {
        fun occ(c: CountdownEntity) = CountdownLogic.nextOccurrence(
            LocalDate.parse(c.targetDate), today, c.yearlyRepeat,
        )
        return CountdownLogic.sortWith(
            countdowns,
            { it.pinned },
            { occ(it) },
            today,
            { it.yearlyRepeat },
        ).take(limit)
    }
}
