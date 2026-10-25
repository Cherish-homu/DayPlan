package com.jsy.dayplan.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** 倒数日计算：固定 Clock，覆盖今天/明天/昨天、跨月年闰日、年度 2/29 规则、排序。 */
class CountdownLogicTest {
    private val today: LocalDate = LocalDate.of(2026, 10, 24)

    @Test
    fun `今天显示就是今天`() {
        assertEquals(0L, CountdownLogic.daysBetween(today, today))
        assertEquals("就是今天", CountdownLogic.displayText(0))
    }

    @Test
    fun `明天与昨天`() {
        assertEquals(1L, CountdownLogic.daysBetween(today, today.plusDays(1)))
        assertEquals("还有 1 天", CountdownLogic.displayText(1))
        assertEquals(-3L, CountdownLogic.daysBetween(today, today.minusDays(3)))
        assertEquals("已过去 3 天", CountdownLogic.displayText(-3))
    }

    @Test
    fun `跨月与跨年`() {
        val d1 = CountdownLogic.daysBetween(today, LocalDate.of(2026, 11, 1))
        assertEquals(8L, d1)   // 10/24 → 11/1
        val d2 = CountdownLogic.daysBetween(today, LocalDate.of(2027, 1, 1))
        assertEquals(69L, d2)  // 10/24 → 12/31 是 68 天，+1 = 69
    }

    @Test
    fun `闰日普通目标 2028-02-29 存在`() {
        val target = LocalDate.of(2028, 2, 29)
        val d = CountdownLogic.daysBetween(today, target)
        assertTrue(d > 0)
    }

    @Test
    fun `年度事件 - 2月29日非闰年按2月28日`() {
        val original = LocalDate.of(2024, 2, 29)
        // 2026 非闰年 → 2026-02-28
        assertEquals(LocalDate.of(2026, 2, 28),
            CountdownLogic.resolveYearlyOccurrence(original, 2026))
        // 2028 闰年 → 2028-02-29
        assertEquals(LocalDate.of(2028, 2, 29),
            CountdownLogic.resolveYearlyOccurrence(original, 2028))
    }

    @Test
    fun `年度事件 - 今天就是周年则返回今天不提前跳下一年`() {
        val original = LocalDate.of(2020, 10, 24)
        assertEquals(today, CountdownLogic.nextOccurrence(original, today, true))
    }

    @Test
    fun `年度事件 - 已过当年周年则定位下一年`() {
        val original = LocalDate.of(2020, 10, 20)
        // today = 2026-10-24，10-20 已过 → 下一次周年 2027-10-20
        assertEquals(LocalDate.of(2027, 10, 20),
            CountdownLogic.nextOccurrence(original, today, true))
        // 当天恰好是周年 → 返回今天
        assertEquals(LocalDate.of(2026, 10, 24),
            CountdownLogic.nextOccurrence(LocalDate.of(2020, 10, 24), today, true))
    }

    @Test
    fun `非年度事件 - 过期判定与显示`() {
        val past = LocalDate.of(2026, 9, 1)
        assertTrue(CountdownLogic.isPast(past, today, false))
        assertEquals("已过去 53 天",
            CountdownLogic.displayText(CountdownLogic.daysBetween(today, past)))
        // 年度事件永不过期
        assertTrue(!CountdownLogic.isPast(past, today, true))
    }

    @Test
    fun `排序 - 置顶优先、临近优先、已过去区按最近过去`() {
        data class Item(val id: Long, val pin: Boolean, val occ: LocalDate, val yearly: Boolean)

        val items = listOf(
            Item(1, false, today.plusDays(10), false),  // 远
            Item(2, false, today, false),               // 今天
            Item(3, true, today.plusDays(30), false),   // 置顶（最远）
            Item(4, false, today.minusDays(20), false), // 已过去（更早）
            Item(5, false, today.minusDays(2), false),  // 已过去（最近）
        )
        val sorted = CountdownLogic.sortWith(
            items, { it.pin }, { it.occ }, today, { it.yearly },
        )
        assertEquals(3L, sorted[0].id)   // 置顶最前
        assertEquals(2L, sorted[1].id)   // 今天
        assertEquals(1L, sorted[2].id)   // 还有 10 天
        assertEquals(5L, sorted[3].id)   // 已过去：最近过去在前
        assertEquals(4L, sorted[4].id)
    }
}
