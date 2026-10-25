package com.jsy.dayplan.reminder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提醒调度适配器（Fake）与计划生成验证：
 * 新增→修改→取消→完成→撤销完成、身份不冲突、重建、10 分钟补发边界、年度下次调度。
 * 适配器单测通过不代表系统通知已在设备上实测（见验证报告）。
 */
class ReminderSchedulingTest {

    private fun scheduleEntity(
        id: Long = 1,
        version: Int = 1,
        date: String = "2026-10-25",
        time: String = "09:00",
        remind: Int = 30,
    ) = com.jsy.dayplan.data.ScheduleEntity(
        id = id, title = "日程$id", startDate = date, endDate = date,
        allDay = false, startTime = time, endTime = com.jsy.dayplan.core.ScheduleLogic.toMinutes(time)?.let { m -> "%02d:%02d".format((m + 60) / 60, (m + 60) % 60) },
        remindMinutesBefore = remind, version = version,
    )

    private fun todoEntity(
        id: Long = 10, version: Int = 1, done: Boolean = false,
        date: String? = "2026-10-25", time: String? = "08:00",
    ) = com.jsy.dayplan.data.TodoEntity(
        id = id, title = "待办$id", dueDate = date, dueTime = time,
        priority = 1, done = done, version = version,
    )

    private fun countdownEntity(
        id: Long = 20, version: Int = 1, date: String = "2026-11-01",
        yearly: Boolean = false, remindDays: Int = 1,
    ) = com.jsy.dayplan.data.CountdownEntity(
        id = id, title = "倒数$id", targetDate = date, category = 0,
        yearlyRepeat = yearly, remindEnabled = remindDays > 0,
        remindDaysBefore = remindDays, remindTime = "09:00",
        version = version,
    )

    @Test
    fun `日程提醒计划 - 提前 30 分钟、occurrenceKey 含版本与时间`() {
        val plan = ReminderPlans.schedulePlan(scheduleEntity())!!
        assertTrue(plan.occurrenceKey.startsWith("sched-1-1-"))
        assertTrue(plan.triggerAtEpochMs > 0)
        assertTrue(plan.title.contains("日程1"))
    }

    @Test
    fun `日程 - 无提醒或全天或过去时不生成计划`() {
        assertTrue(ReminderPlans.schedulePlan(scheduleEntity(remind = 0)) == null)
        val allDay = scheduleEntity().copy(allDay = true, startTime = null, endTime = null)
        assertTrue(ReminderPlans.schedulePlan(allDay) == null)
        val past = scheduleEntity(date = "2020-01-01")
        val plan = ReminderPlans.schedulePlan(past)!!
        assertTrue(plan.triggerAtEpochMs < System.currentTimeMillis())   // 过去条目不调度（由协调器过滤）
    }

    @Test
    fun `待办提醒 - 有截止时间才有计划；完成后取消`() {
        assertTrue(ReminderPlans.todoPlan(todoEntity()) != null)
        // 无日期
        assertTrue(ReminderPlans.todoPlan(todoEntity(date = null, time = null)) == null)
        // 有日期无时间
        assertTrue(ReminderPlans.todoPlan(todoEntity(time = null)) == null)
        // 完成：不生成
        assertTrue(ReminderPlans.todoPlan(todoEntity(done = true)) == null)
    }

    @Test
    fun `倒数日提醒 - 提前 1 天与 7 天、年度按下一次发生`() {
        val today = java.time.LocalDate.of(2026, 10, 24)
        val p1 = ReminderPlans.countdownPlan(countdownEntity(remindDays = 1), today)!!
        // 目标 11-01，提前 1 天 → 10-31 09:00
        assertEquals("cd-20-1-202610310900", p1.occurrenceKey)
        val expected = java.time.LocalDateTime.of(2026, 10, 31, 9, 0)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expected, p1.triggerAtEpochMs)

        val p7 = ReminderPlans.countdownPlan(countdownEntity(remindDays = 7), today)!!
        val expected7 = java.time.LocalDateTime.of(2026, 10, 25, 9, 0)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expected7, p7.triggerAtEpochMs)

        // 年度 + 2/29：2024-02-29 → 下一次 2028-02-29，提前 1 天 = 2028-02-28
        val leap = ReminderPlans.countdownPlan(
            countdownEntity(date = "2024-02-29", yearly = true, remindDays = 1),
            java.time.LocalDate.of(2026, 10, 24),
        )!!
        assertEquals("cd-20-1-202702270900", leap.occurrenceKey)
    }

    @Test
    fun `调度适配器 - 新增修改取消完成撤销完成的调用序列`() {
        val fake = FakeReminderScheduler()
        val s1 = scheduleEntity(id = 1, version = 1)
        val p1 = ReminderPlans.schedulePlan(s1)!!

        // 新增
        fake.schedule(p1, fake.canScheduleExact())
        assertTrue(fake.containsKey(p1.occurrenceKey))

        // 修改（版本+1 → 新 key，旧 key 被取消）
        val s2 = s1.copy(version = 2)
        val p2 = ReminderPlans.schedulePlan(s2)!!
        assertTrue(p2.occurrenceKey != p1.occurrenceKey)
        fake.schedule(p2, fake.canScheduleExact())
        fake.cancel(p1.occurrenceKey)
        assertFalse(fake.containsKey(p1.occurrenceKey))
        assertTrue(fake.containsKey(p2.occurrenceKey))

        // 完成：取消
        fake.cancel(p2.occurrenceKey)
        assertFalse(fake.containsKey(p2.occurrenceKey))

        // 撤销完成：重新调度
        fake.schedule(p2, fake.canScheduleExact())
        assertTrue(fake.containsKey(p2.occurrenceKey))

        // 删除：取消
        fake.cancel(p2.occurrenceKey)
        assertEquals(listOf(p1.occurrenceKey, p2.occurrenceKey, p2.occurrenceKey),
            fake.cancelledKeys)
    }

    @Test
    fun `调度身份 - 多条目 occurrenceKey 互不冲突`() {
        val fake = FakeReminderScheduler()
        val plans = (1L..5L).map { ReminderPlans.schedulePlan(scheduleEntity(id = it))!! }
        plans.forEach { fake.schedule(it, true) }
        assertEquals(5, fake.scheduled.size)
        assertEquals(5, fake.scheduled.map { it.occurrenceKey }.toSet().size)
        // 同一 id 不同时间（跨日）key 不同
        val a = ReminderPlans.schedulePlan(scheduleEntity(id = 9, date = "2026-10-25"))!!
        val b = ReminderPlans.schedulePlan(scheduleEntity(id = 9, date = "2026-10-26"))!!
        assertTrue(a.occurrenceKey != b.occurrenceKey)
    }

    @Test
    fun `权限降级 - 精确提醒被拒时仍可调度近似提醒`() {
        val fake = FakeReminderScheduler(exactAllowedFlag = false)
        assertFalse(fake.canScheduleExact())
        val p = ReminderPlans.schedulePlan(scheduleEntity())!!
        fake.schedule(p, fake.canScheduleExact())   // 降级调度
        assertTrue(fake.containsKey(p.occurrenceKey))
    }

    @Test
    fun `重建 - 保留未来提醒、过滤过去提醒`() {
        val fake = FakeReminderScheduler()
        val coordinator = ReminderCoordinator(
            FakeDaoHolder.create(
                schedules = listOf(
                    scheduleEntity(id = 1, date = "2026-10-25"),   // 未来
                    scheduleEntity(id = 2, date = "2020-01-01"),   // 过去
                ),
                todos = listOf(todoEntity(id = 10, date = "2026-10-25")),
                countdowns = listOf(countdownEntity(id = 20, date = "2026-11-01")),
            ),
            fake,
        )
        val now = java.time.LocalDateTime.of(2026, 10, 24, 12, 0)
        kotlinx.coroutines.runBlocking {
            coordinator.rebuildAll(now)
        }
        // 未来 3 条被调度；过去的日程 2 不调度
        assertTrue(fake.containsKey("sched-1-1-202610250830"))  // 09:00 提前 30 分 = 08:30
        assertTrue(fake.containsKey("todo-10-1-202610250800"))
        assertTrue(fake.containsKey("cd-20-1-202610310900"))
        assertTrue(fake.scheduled.none { it.occurrenceKey.startsWith("sched-2-") })
    }

    @Test
    fun `补发宽限 - 恰好 10 分钟内通知、超过 10 分钟标记错过`() {
        val key = "todo-10-1-202610250800"
        val plannedAt = java.time.LocalDateTime.of(2026, 10, 25, 8, 0)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val parsed = ReminderCoordinator.parseKey(key)!!
        val dao = FakeDaoHolder.create(
            todos = listOf(todoEntity(id = 10)),
        )
        val coordinator = ReminderCoordinator(dao, FakeReminderScheduler())

        val plannedAtEpoch = java.time.LocalDateTime.of(2026, 10, 25, 8, 0)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        // 恰好 10 分钟（<= 10 分钟宽限）：通知
        val nowInGrace = plannedAt + 10 * 60_000L
        val fired = kotlinx.coroutines.runBlocking { coordinator.onFired(key, nowInGrace) }
        assertTrue(fired != null)

        // 超过 10 分钟：标记错过，不通知
        val dao2 = FakeDaoHolder.create(todos = listOf(todoEntity(id = 10)))
        val coordinator2 = ReminderCoordinator(dao2, FakeReminderScheduler())
        val nowLate = plannedAt + 11 * 60_000L
        val fired2 = kotlinx.coroutines.runBlocking { coordinator2.onFired(key, nowLate) }
        assertTrue(fired2 == null)
        assertEquals(1, kotlinx.coroutines.runBlocking { dao2.allMissed().size })
    }

    @Test
    fun `去重 - 同一发生只通知一次`() {
        val key = "todo-10-1-202610250800"
        val dao = FakeDaoHolder.create(todos = listOf(todoEntity(id = 10)))
        val coordinator = ReminderCoordinator(dao, FakeReminderScheduler())
        val plannedAt = java.time.LocalDateTime.of(2026, 10, 25, 8, 0)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        kotlinx.coroutines.runBlocking {
            assertTrue(coordinator.onFired(key, plannedAt + 60_000) != null)
            // 重复回调：不再通知
            assertTrue(coordinator.onFired(key, plannedAt + 60_000) == null)
            assertTrue(dao.occurrence(key) != null)
        }
    }

    @Test
    fun `旧回调无效 - 条目删除或版本变化后触发被忽略`() {
        val key = "sched-1-2-202610250830"
        // 条目已删除
        val daoDeleted = FakeDaoHolder.create()
        val coordinatorDeleted = ReminderCoordinator(daoDeleted, FakeReminderScheduler())
        kotlinx.coroutines.runBlocking {
            assertTrue(coordinatorDeleted.onFired(key, java.time.LocalDateTime.now()) == null)
        }
        // 版本不一致
        val daoOld = FakeDaoHolder.create(schedules = listOf(scheduleEntity(id = 1, version = 1)))
        val coordinatorOld = ReminderCoordinator(daoOld, FakeReminderScheduler())
        kotlinx.coroutines.runBlocking {
            assertTrue(coordinatorOld.onFired(key, java.time.LocalDateTime.now()) == null)
        }
    }
}
