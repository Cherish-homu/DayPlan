package com.jsy.dayplan.core

import com.jsy.dayplan.data.CountdownEntity
import com.jsy.dayplan.data.ScheduleEntity
import com.jsy.dayplan.data.TodoEntity
import java.time.LocalDate
import java.time.LocalTime

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

    /** “接下来”区域：优先正在进行的定时日程，其次下一段；全天日程仅作兜底。 */
    data class FocusSchedule(
        val schedule: ScheduleEntity,
        val ongoing: Boolean,
        val startMinute: Int,
        val endMinute: Int,
    )

    fun focusSchedule(
        schedules: List<ScheduleEntity>,
        today: LocalDate,
        now: LocalTime,
    ): FocusSchedule? {
        val nowMin = now.hour * 60 + now.minute
        val todaySchedules = schedulesForToday(schedules, today)
        val timed = todaySchedules.filter { !it.allDay }
            .mapNotNull { s ->
                val start = if (LocalDate.parse(s.startDate).isBefore(today)) 0
                else ScheduleLogic.toMinutes(s.startTime ?: "00:00") ?: 0
                val end = if (LocalDate.parse(s.endDate).isAfter(today)) 24 * 60
                else ScheduleLogic.toMinutes(s.endTime ?: "24:00") ?: 24 * 60
                if (end <= start) null else FocusSchedule(s, nowMin in start until end, start, end)
            }
        val ongoing = timed.filter { it.ongoing }.minByOrNull { it.endMinute }
        if (ongoing != null) return ongoing
        val next = timed.filter { it.startMinute > nowMin }.minByOrNull { it.startMinute }
        if (next != null) return next
        val allDay = todaySchedules.firstOrNull { it.allDay } ?: return null
        return FocusSchedule(allDay, true, 0, 24 * 60)
    }

    /** 日程行时间说明：跨天时仍保留今天可见的时刻；只有恰好次日才写“明天”。 */
    fun scheduleTimeText(s: ScheduleEntity, today: LocalDate): String {
        if (s.allDay) {
            return if (s.startDate == s.endDate) "全天" else "全天 · 多日"
        }
        val startDate = LocalDate.parse(s.startDate)
        val endDate = LocalDate.parse(s.endDate)
        val start = s.startTime ?: ""
        val end = s.endTime ?: ""
        val startsBefore = startDate.isBefore(today)
        val endsAfter = endDate.isAfter(today)
        val endDay = if (endDate == today.plusDays(1)) "明天"
        else "${endDate.monthValue}月${endDate.dayOfMonth}日"
        val startDay = "${startDate.monthValue}月${startDate.dayOfMonth}日"
        return when {
            startsBefore && endsAfter -> "今天全天 · 至 $endDay"
            startsBefore -> "至 $end · ${startDay}起"
            endsAfter -> "$start 起 · ${endDay}结束"
            else -> "$start–$end"
        }
    }

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
