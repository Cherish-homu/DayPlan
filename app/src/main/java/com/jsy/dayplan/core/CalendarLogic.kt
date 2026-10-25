package com.jsy.dayplan.core

import com.jsy.dayplan.data.CountdownEntity
import com.jsy.dayplan.data.ScheduleEntity
import com.jsy.dayplan.data.TodoEntity
import java.time.LocalDate

/** 月历类型筛选：单选，真正参与网格标记、当日列表和计数。 */
enum class CalendarTypeFilter(val label: String) {
    ALL("全部"),
    SCHEDULE("日程"),
    TODO("待办"),
    COUNTDOWN("倒数日"),
}

data class CalendarDayContent(
    val schedules: List<ScheduleEntity>,
    val todos: List<TodoEntity>,
    val countdowns: List<CountdownEntity>,
) {
    val total: Int get() = schedules.size + todos.size + countdowns.size
    val isEmpty: Boolean get() = total == 0
}

/**
 * 月历当日的取数口径。筛选、搜索与“这一天到底有没有任何数据”分开判断，
 * 以便界面区分“筛选无结果”和“当天没有安排”。
 */
object CalendarLogic {

    fun schedulesOn(
        schedules: List<ScheduleEntity>,
        day: LocalDate,
        query: String = "",
    ): List<ScheduleEntity> = schedules
        .filter { s ->
            val span = TodayLogic.spanOf(s)
            ScheduleLogic.coveredDates(span).any { it == day } &&
                (query.isBlank() || s.title.contains(query, ignoreCase = true))
        }
        .sortedWith(
            compareBy(
                { if (it.allDay) 0 else 1 },
                { ScheduleLogic.toMinutes(it.startTime ?: "00:00") ?: 0 },
                { it.id },
            ),
        )

    fun todosOn(
        todos: List<TodoEntity>,
        day: LocalDate,
        query: String = "",
    ): List<TodoEntity> = todos
        .filter {
            it.dueDate == day.toString() &&
                (query.isBlank() || it.title.contains(query, ignoreCase = true))
        }
        .sortedWith(
            compareBy({ it.done }, { it.priority * -1 }, { it.dueTime ?: "\uFFFF" }, { it.id }),
        )

    fun countdownsOn(
        countdowns: List<CountdownEntity>,
        day: LocalDate,
        query: String = "",
    ): List<CountdownEntity> = countdowns
        .filter {
            (it.targetDate == day.toString() ||
                (it.yearlyRepeat && it.targetDate.takeLast(5) == day.toString().takeLast(5))) &&
                (query.isBlank() || it.title.contains(query, ignoreCase = true))
        }
        .sortedWith(compareByDescending<CountdownEntity> { it.pinned }.thenBy { it.id })

    fun contentOn(
        schedules: List<ScheduleEntity>,
        todos: List<TodoEntity>,
        countdowns: List<CountdownEntity>,
        day: LocalDate,
        filter: CalendarTypeFilter,
        query: String = "",
    ): CalendarDayContent {
        val s = if (filter == CalendarTypeFilter.ALL || filter == CalendarTypeFilter.SCHEDULE)
            schedulesOn(schedules, day, query) else emptyList()
        val t = if (filter == CalendarTypeFilter.ALL || filter == CalendarTypeFilter.TODO)
            todosOn(todos, day, query) else emptyList()
        val c = if (filter == CalendarTypeFilter.ALL || filter == CalendarTypeFilter.COUNTDOWN)
            countdownsOn(countdowns, day, query) else emptyList()
        return CalendarDayContent(s, t, c)
    }

    /** 未应用类型筛选、但应用搜索时的当日内容，用于区分“没有匹配”与“筛选无结果”。 */
    fun unfilteredContentOn(
        schedules: List<ScheduleEntity>,
        todos: List<TodoEntity>,
        countdowns: List<CountdownEntity>,
        day: LocalDate,
        query: String = "",
    ): CalendarDayContent = contentOn(
        schedules, todos, countdowns, day, CalendarTypeFilter.ALL, query,
    )

    /** 月历格子的三个标记是否出现；筛选时只保留对应类型。 */
    fun markers(
        schedules: List<ScheduleEntity>,
        todos: List<TodoEntity>,
        countdowns: List<CountdownEntity>,
        day: LocalDate,
        filter: CalendarTypeFilter,
        query: String = "",
    ): Set<CalendarTypeFilter> {
        val content = contentOn(schedules, todos, countdowns, day, filter, query)
        return buildSet {
            if (content.schedules.isNotEmpty()) add(CalendarTypeFilter.SCHEDULE)
            if (content.todos.isNotEmpty()) add(CalendarTypeFilter.TODO)
            if (content.countdowns.isNotEmpty()) add(CalendarTypeFilter.COUNTDOWN)
        }
    }

    /** 右上角计数：只数当前筛选后的条目。 */
    fun countFor(content: CalendarDayContent, filter: CalendarTypeFilter): Int = when (filter) {
        CalendarTypeFilter.ALL -> content.total
        CalendarTypeFilter.SCHEDULE -> content.schedules.size
        CalendarTypeFilter.TODO -> content.todos.size
        CalendarTypeFilter.COUNTDOWN -> content.countdowns.size
    }
}
