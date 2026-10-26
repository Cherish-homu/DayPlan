package com.jsy.dayplan.core

import com.jsy.dayplan.data.ScheduleEntity
import com.jsy.dayplan.data.TodoEntity
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 首页未来几天概览：只取明天起 7 天、多日日程不重复、已完/无日期待办不计。 */
class UpcomingLogicTest {
    private val today = LocalDate.of(2026, 10, 26)

    private fun schedule(
        id: Long,
        title: String,
        start: LocalDate,
        end: LocalDate = start,
        allDay: Boolean = false,
        startTime: String? = "09:00",
        endTime: String? = "10:00",
    ) = ScheduleEntity(
        id = id,
        title = title,
        startDate = start.toString(),
        endDate = end.toString(),
        allDay = allDay,
        startTime = if (allDay) null else startTime,
        endTime = if (allDay) null else endTime,
    )

    private fun todo(id: Long, title: String, due: LocalDate?, done: Boolean = false, time: String? = null) =
        TodoEntity(id = id, title = title, dueDate = due?.toString(), dueTime = time, done = done)

    @Test
    fun `只统计明天起七天内的新开始日程与未完成待办`() {
        val schedules = listOf(
            schedule(1, "今天", today),
            schedule(2, "明天", today.plusDays(1)),
            schedule(3, "第八天", today.plusDays(8)),
            schedule(4, "多日", today.plusDays(2), today.plusDays(4)),
        )
        val todos = listOf(
            todo(10, "今天截止", today),
            todo(11, "明天截止", today.plusDays(1), time = "18:00"),
            todo(12, "已完成", today.plusDays(2), done = true),
            todo(13, "无日期", null),
            todo(14, "第八天", today.plusDays(8)),
        )
        val days = UpcomingLogic.build(today, schedules, todos)
        assertEquals(listOf(today.plusDays(1), today.plusDays(2)), days.map { it.date })
        assertEquals(listOf("明天", "明天截止"), days[0].entries.map { it.title })
        assertEquals(listOf("多日"), days[1].entries.map { it.title })
        assertTrue(days.none { it.date == today })
        assertTrue(days.none { it.date == today.plusDays(8) })
    }

    @Test
    fun `同日日程排在待办前并按时间排序`() {
        val date = today.plusDays(1)
        val days = UpcomingLogic.build(
            today,
            listOf(
                schedule(2, "下午会", date, startTime = "15:00", endTime = "16:00"),
                schedule(1, "上午会", date, startTime = "09:00", endTime = "10:00"),
            ),
            listOf(
                todo(4, "普通截止", date, time = "18:00"),
                todo(3, "重要截止", date, time = "12:00").copy(priority = 1),
            ),
        )
        val entries = days.single().entries
        assertEquals(listOf("上午会", "下午会", "重要截止", "普通截止"), entries.map { it.title })
        assertEquals(UpcomingKind.SCHEDULE, entries.first().kind)
    }

    @Test
    fun `每日上限与隐藏数量`() {
        val date = today.plusDays(1)
        val schedules = (1..6).map { schedule(it.toLong(), "日程$it", date, startTime = "0$it:00", endTime = "0$it:30") }
        val days = UpcomingLogic.build(today, schedules, emptyList(), perDayLimit = 3)
        assertEquals(3, days.single().entries.size)
        assertEquals(3, days.single().hiddenCount)
        assertEquals(6, days.single().totalCount)
    }

    @Test
    fun `没有未来条目时返回空列表`() {
        assertTrue(
            UpcomingLogic.build(today, listOf(schedule(1, "今天", today)), listOf(todo(2, "今天", today))).isEmpty(),
        )
    }

    @Test
    fun `多日日程只在开始日出现一次`() {
        val start = today.plusDays(2)
        val days = UpcomingLogic.build(
            today,
            listOf(schedule(1, "三天项目", start, start.plusDays(2))),
            emptyList(),
        )
        assertEquals(1, days.size)
        assertEquals(start, days.single().date)
        assertEquals(1, days.sumOf { it.entries.size })
    }
}
