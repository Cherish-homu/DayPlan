package com.jsy.dayplan.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** 专注倒计时状态机：固定单调时钟；暂停继续、重启策略、旧回调不影响新计时器。 */
class FocusTimerLogicTest {
    private val clock = FixedClock(elapsedMs = 1_000_000L)

    @Test
    fun `开始 - 剩余时长按单调时钟计算`() {
        val s = FocusTimerLogic.start(25, clock.elapsedRealtimeMs(), clock.bootId())
        assertEquals(25 * 60_000L, FocusTimerLogic.remainingMs(s, clock.elapsedRealtimeMs()))
        clock.advance(5)
        assertEquals(20 * 60_000L, FocusTimerLogic.remainingMs(s, clock.elapsedRealtimeMs()))
        // 修改系统时间不影响单调时钟口径（elapsedRealtime 与 wall clock 无关）
    }

    @Test
    fun `暂停与继续 - 暂停保存剩余、继续以新截止点注册`() {
        var s = FocusTimerLogic.start(25, clock.elapsedRealtimeMs(), clock.bootId())
        clock.advance(10)                       // 剩 15 分钟
        s = FocusTimerLogic.pause(s, clock.elapsedRealtimeMs())
        assertEquals(15 * 60_000L, s.pausedRemainingMs)
        clock.advance(60)                       // 暂停期间流逝（不影响剩余）
        assertEquals(15 * 60_000L, FocusTimerLogic.remainingMs(s, clock.elapsedRealtimeMs()))
        s = FocusTimerLogic.resume(s, clock.elapsedRealtimeMs())   // 新截止点 = now + 15min
        assertEquals(15 * 60_000L, FocusTimerLogic.remainingMs(s, clock.elapsedRealtimeMs()))
        clock.advance(5)
        assertEquals(10 * 60_000L, FocusTimerLogic.remainingMs(s, clock.elapsedRealtimeMs()))
    }

    @Test
    fun `重置 - 回到空闲并清空剩余`() {
        var s = FocusTimerLogic.start(25, clock.elapsedRealtimeMs(), clock.bootId())
        clock.advance(3)
        s = FocusTimerLogic.reset(s)
        assertEquals(FocusTimerLogic.State.IDLE, s)
        assertEquals(0L, FocusTimerLogic.remainingMs(s, clock.elapsedRealtimeMs()))
    }

    @Test
    fun `自然结束 - 剩余为 0 时判定结束`() {
        var s = FocusTimerLogic.start(5, clock.elapsedRealtimeMs(), clock.bootId())
        clock.advance(6)
        assertTrue(FocusTimerLogic.naturalEnd(s, clock.elapsedRealtimeMs()))
        s = FocusTimerLogic.pause(s, clock.elapsedRealtimeMs())   // 暂停后不算自然结束
        assertFalse(FocusTimerLogic.naturalEnd(s, clock.elapsedRealtimeMs()))
    }

    @Test
    fun `设备重启 - 运行中计时器标记暂停用最后已知剩余而非推算`() {
        var s = FocusTimerLogic.start(25, clock.elapsedRealtimeMs(), "boot-1")
        clock.advance(10)
        // ticker 每秒维护 lastKnownRemainingMs = 15 分钟
        s = s.copy(lastKnownRemainingMs = 15 * 60_000L)
        clock.reboot("boot-2")   // elapsed 重置
        s = FocusTimerLogic.onReboot(s, s.lastKnownRemainingMs)
        assertTrue(s.paused)
        // 用重启前最后已知剩余（15 分钟），不用新开机的 elapsed 推算
        assertEquals(15 * 60_000L, s.pausedRemainingMs)
    }

    @Test
    fun `旧回调不能结束新计时器 - endNotified 与新一轮隔离`() {
        var s = FocusTimerLogic.start(5, clock.elapsedRealtimeMs(), clock.bootId())
        clock.advance(6)
        assertTrue(FocusTimerLogic.naturalEnd(s, clock.elapsedRealtimeMs()))
        // 结束后开启新一轮 25 分钟
        s = FocusTimerLogic.start(25, clock.elapsedRealtimeMs(), clock.bootId())
        assertFalse(s.endNotified)
        // 旧一轮的过期回调到达：剩余仍 > 0，naturalEnd 为 false，不影响新计时器
        assertFalse(FocusTimerLogic.naturalEnd(s, clock.elapsedRealtimeMs()))
        assertEquals(25 * 60_000L, FocusTimerLogic.remainingMs(s, clock.elapsedRealtimeMs()))
    }

    @Test
    fun `范围限制 - 1 至 180 分钟`() {
        try {
            FocusTimerLogic.start(0, clock.elapsedRealtimeMs(), clock.bootId())
            org.junit.Assert.fail("0 分钟应被拒绝")
        } catch (e: IllegalArgumentException) { /* expected */ }
        try {
            FocusTimerLogic.start(181, clock.elapsedRealtimeMs(), clock.bootId())
            org.junit.Assert.fail("181 分钟应被拒绝")
        } catch (e: IllegalArgumentException) { /* expected */ }
        assertEquals(1, FocusTimerLogic.MIN_MINUTES)
        assertEquals(180, FocusTimerLogic.MAX_MINUTES)
        // 固定 Clock 本身可推进（模拟测试不依赖真实今天）
        val c = FixedClock()
        val before = c.now()
        c.advance(1440)
        assertEquals(before.plusDays(1), c.now())
        assertEquals(LocalDateTime.of(2026, 10, 25, 9, 0), c.now())
    }

    @Test
    fun `格式化 - mmss 展示`() {
        assertEquals("25:00", FocusTimerLogic.formatRemaining(25 * 60_000L))
        assertEquals("00:59", FocusTimerLogic.formatRemaining(59_999L))
        assertEquals("00:00", FocusTimerLogic.formatRemaining(0))
    }
}
