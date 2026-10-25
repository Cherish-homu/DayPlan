package com.jsy.dayplan.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** 日程核心：跨日覆盖、左闭右开 00:00 边界、起止校验、冲突、排序。 */
class ScheduleLogicTest {
    private val today: LocalDate = LocalDate.of(2026, 10, 24)

    private fun span(
        id: Long = 1,
        start: LocalDate = today,
        end: LocalDate = today,
        allDay: Boolean = false,
        st: String? = "09:00",
        et: String? = "10:00",
    ) = ScheduleLogic.ScheduleSpan(id, "s", start, end, allDay, st, et)

    @Test
    fun `单日日程覆盖一天`() {
        assertEquals(listOf(today), ScheduleLogic.coveredDates(span()))
    }

    @Test
    fun `跨日活动覆盖每一天`() {
        val s = span(start = today, end = today.plusDays(2), allDay = true)
        assertEquals(listOf(today, today.plusDays(1), today.plusDays(2)),
            ScheduleLogic.coveredDates(s))
    }

    @Test
    fun `验收 - 结束时间恰为次日 00 点整按左闭右开不多占一天`() {
        // 10/24 22:00 → 10/25 00:00：只覆盖 10/24
        val s = span(start = today, end = today.plusDays(1), st = "22:00", et = "00:00")
        assertEquals(listOf(today), ScheduleLogic.coveredDates(s))
        // 但 10/24 22:00 → 10/25 01:00 覆盖两天
        val s2 = span(start = today, end = today.plusDays(1), st = "22:00", et = "01:00")
        assertEquals(listOf(today, today.plusDays(1)), ScheduleLogic.coveredDates(s2))
    }

    @Test
    fun `起止校验 - 同日结束不晚于开始、跨日合法`() {
        assertTrue(ScheduleLogic.validateSpan(span()) == null)
        // 同日 end == start 拒绝
        val bad = span(st = "09:00", et = "09:00")
        assertTrue(ScheduleLogic.validateSpan(bad)?.contains("早于结束") == true)
        // 跨日 23:00 → 次日 01:00 合法
        val cross = span(start = today, end = today.plusDays(1), st = "23:00", et = "01:00")
        assertEquals(null, ScheduleLogic.validateSpan(cross))
        // 结束日期早于开始拒绝
        val reversed = span(start = today, end = today.minusDays(1))
        assertTrue(ScheduleLogic.validateSpan(reversed)?.contains("早于开始") == true)
        // 全天跨日合法
        assertEquals(null, ScheduleLogic.validateSpan(
            span(start = today, end = today.plusDays(1), allDay = true, st = null, et = null),
        ))
    }

    @Test
    fun `冲突检测 - 同日区间重叠提示且不阻止保存语义`() {
        val a = span(id = 1, st = "09:00", et = "10:00")
        val b = span(id = 2, st = "09:30", et = "10:30")
        val c = span(id = 3, st = "10:00", et = "11:00")  // 端点相接不算重叠
        val conflicts = ScheduleLogic.conflictingWith(a, listOf(b, c))
        assertEquals(listOf(2L), conflicts)
    }

    @Test
    fun `冲突检测 - 全天与任何同日日程冲突`() {
        val allDay = span(id = 1, allDay = true, st = null, et = null)
        val half = span(id = 2, st = "23:00", et = "23:59")
        assertEquals(listOf(2L), ScheduleLogic.conflictingWith(allDay, listOf(half)))
    }

    @Test
    fun `冲突检测 - 跨日日程与覆盖日上的日程冲突`() {
        val multi = span(id = 1, start = today, end = today.plusDays(2), st = "08:00", et = "09:00")
        val single = span(id = 2, start = today.plusDays(1), end = today.plusDays(1),
            st = "08:30", et = "09:30")
        assertEquals(listOf(1L), ScheduleLogic.conflictingWith(single, listOf(multi)))
    }

    @Test
    fun `排序 - 全天在前、按开始时间、跨日按起始日`() {
        data class E(val id: Long, val s: ScheduleLogic.ScheduleSpan)
        val items = listOf(
            E(3, span(id = 3, start = today, st = "13:00", et = "14:00")),
            E(1, span(id = 1, start = today, allDay = true, st = null, et = null)),
            E(2, span(id = 2, start = today, st = "08:00", et = "09:00")),
            E(4, span(id = 4, start = today.plusDays(1), st = "07:00", et = "08:00")),
        )
        val sorted = ScheduleLogic.sortChronologically(items) { it.s }
        assertEquals(listOf(1L, 2L, 3L, 4L), sorted.map { it.id })
    }
}
