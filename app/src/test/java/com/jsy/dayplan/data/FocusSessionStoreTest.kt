package com.jsy.dayplan.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 专注账本：轮次去重、今日/累计聚合、跨天边界、重开持久化。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FocusSessionStoreTest {

    private lateinit var context: Context
    private lateinit var store: FocusSessionStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = FocusSessionStore(context, null)
    }

    @After
    fun tearDown() {
        store.close()
    }

    private fun at(day: LocalDate, hour: Int, minute: Int = 0, second: Int = 0): Long =
        day.atTime(hour, minute, second).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test
    fun `同一轮重复写入只计一次`() {
        assertTrue(store.record(FocusSession("round-1", 25, 1_000L)))
        assertFalse("相同轮次主键必须被忽略", store.record(FocusSession("round-1", 25, 9_999L)))
        val stats = store.snapshot(now = 10_000L, zone = ZoneOffset.UTC)
        assertEquals(1, stats.totalCount)
        assertEquals(25L, stats.totalMinutes)
    }

    @Test
    fun `今日与累计按本地日期聚合`() {
        val today = LocalDate.of(2026, 10, 26)
        store.record(FocusSession("a", 25, at(today, 0, 0, 0)))
        store.record(FocusSession("b", 5, at(today, 23, 59, 59)))
        store.record(FocusSession("c", 15, at(today.minusDays(1), 23, 59, 59)))
        store.record(FocusSession("d", 45, at(today.minusDays(7), 12, 0)))

        val stats = store.snapshot(now = at(today, 12, 0), zone = ZoneOffset.UTC)
        assertEquals(2, stats.todayCount)
        assertEquals(30L, stats.todayMinutes)
        assertEquals(4, stats.totalCount)
        assertEquals(90L, stats.totalMinutes)
        assertEquals(7, stats.week.size)
        assertEquals(today, stats.week.last().date)
        assertEquals(2, stats.week.last().count)
        assertEquals(1, stats.week[5].count)
        assertEquals("七天窗口之外不进入周统计", 0, stats.week.first().count)
    }

    @Test
    fun `跨天边界 - 午夜零点计新一天 前一毫秒仍属旧天`() {
        val today = LocalDate.of(2026, 10, 26)
        val start = at(today, 0, 0, 0)
        store.record(FocusSession("before", 10, start - 1))
        store.record(FocusSession("at", 20, start))

        val stats = store.snapshot(now = at(today, 8, 0), zone = ZoneOffset.UTC)
        assertEquals(1, stats.todayCount)
        assertEquals(20L, stats.todayMinutes)
        assertEquals(2, stats.totalCount)
    }

    @Test
    fun `最近记录按完成时间倒序且最多 50 条`() {
        val base = 1_700_000_000_000L
        repeat(55) { index ->
            store.record(FocusSession("id-$index", 5, base + index * 1_000L))
        }
        val stats = store.snapshot(now = base + 60_000L, zone = ZoneOffset.UTC)
        assertEquals(55, stats.totalCount)
        assertEquals(50, stats.recent.size)
        assertEquals("id-54", stats.recent.first().id)
        assertEquals("id-5", stats.recent.last().id)
    }

    @Test
    fun `关闭后重开数据库仍然保留记录`() {
        val name = "focus_test_${System.nanoTime()}.db"
        try {
            val first = FocusSessionStore(context, name)
            assertTrue(first.record(FocusSession("persisted", 30, 1_234_567L)))
            first.close()

            val second = FocusSessionStore(context, name)
            val stats = second.snapshot(now = 2_000_000L, zone = ZoneOffset.UTC)
            second.close()
            assertEquals(1, stats.totalCount)
            assertEquals(30L, stats.totalMinutes)
            assertEquals("persisted", stats.recent.single().id)
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun `非法轮次或时长被拒绝`() {
        try {
            store.record(FocusSession("", 25, 1L))
            org.junit.Assert.fail("空轮次 ID 应被拒绝")
        } catch (_: IllegalArgumentException) { /* expected */ }
        try {
            store.record(FocusSession("x", 0, 1L))
            org.junit.Assert.fail("0 分钟应被拒绝")
        } catch (_: IllegalArgumentException) { /* expected */ }
        try {
            store.record(FocusSession("y", 181, 1L))
            org.junit.Assert.fail("181 分钟应被拒绝")
        } catch (_: IllegalArgumentException) { /* expected */ }
        assertEquals(0, store.snapshot(now = 2L, zone = ZoneOffset.UTC).totalCount)
    }

    @Test
    fun `统计使用传入时区解释完成时间`() {
        // 完成于上海时间 10-25 23:30；一小时后上海已是 10-26，UTC 仍是 10-25。
        val instant = Instant.parse("2026-10-25T15:30:00Z")
        store.record(FocusSession("utc-15", 25, instant.toEpochMilli()))
        val now = instant.plusSeconds(3600).toEpochMilli()
        assertEquals(
            "上海时区此刻是 10-26 00:30，昨日记录不计入今日",
            0,
            store.snapshot(now = now, zone = ZoneOffset.ofHours(8)).todayCount,
        )
        assertEquals(
            "UTC 此刻仍是 10-25，记录属于今日",
            1,
            store.snapshot(now = now, zone = ZoneOffset.UTC).todayCount,
        )
    }
}
