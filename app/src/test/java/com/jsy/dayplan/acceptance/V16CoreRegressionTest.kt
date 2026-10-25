package com.jsy.dayplan.acceptance

import com.jsy.dayplan.ai.AiDraftEditRules
import com.jsy.dayplan.core.CalendarLogic
import com.jsy.dayplan.core.CalendarTypeFilter
import com.jsy.dayplan.core.EntryDefaults
import com.jsy.dayplan.core.TodayLogic
import com.jsy.dayplan.core.TodoDisplay
import com.jsy.dayplan.data.CountdownEntity
import com.jsy.dayplan.data.ScheduleEntity
import com.jsy.dayplan.data.TodoEntity
import com.jsy.dayplan.ui.ChanAnimationMode
import com.jsy.dayplan.ui.ChanMood
import com.jsy.dayplan.ui.ChanMotion
import com.jsy.dayplan.ui.ChanStatusLogic
import com.jsy.dayplan.ui.statusText
import com.jsy.dayplan.vm.SendState
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.6 核心口径回归：类型筛选、日期继承、接下来、待办文案与草稿校验。 */
class V16CoreRegressionTest {

    private val today = LocalDate.of(2026, 10, 25)

    private fun schedule(
        id: Long = 1,
        title: String = "schedule",
        startDate: LocalDate = today,
        endDate: LocalDate = today,
        allDay: Boolean = false,
        start: String? = "09:00",
        end: String? = "10:00",
    ) = ScheduleEntity(
        id = id, title = title,
        startDate = startDate.toString(), endDate = endDate.toString(),
        allDay = allDay,
        startTime = if (allDay) null else start,
        endTime = if (allDay) null else end,
    )

    // ---------- “接下来” ----------

    @Test
    fun `接下来 - 优先正在进行且选择最早结束的一段`() {
        val long = schedule(1, "long", start = "09:00", end = "17:00")
        val soon = schedule(2, "soon", start = "10:00", end = "11:00")
        val next = schedule(3, "next", start = "15:00", end = "16:00")
        val focus = TodayLogic.focusSchedule(
            listOf(long, next, soon), today, LocalTime.of(10, 30),
        )
        assertNotNull(focus)
        assertEquals(2L, focus!!.schedule.id)
        assertTrue(focus.ongoing)
    }

    @Test
    fun `接下来 - 没有进行中时选择最早开始的下一段`() {
        val early = schedule(1, "early", start = "15:00", end = "16:00")
        val late = schedule(2, "late", start = "20:00", end = "21:00")
        val focus = TodayLogic.focusSchedule(
            listOf(late, early), today, LocalTime.of(12, 0),
        )
        assertEquals(1L, focus!!.schedule.id)
        assertFalse(focus.ongoing)
    }

    @Test
    fun `接下来 - 全天日程只作兜底`() {
        val allDay = schedule(1, "birthday", allDay = true)
        val timed = schedule(2, "timed", start = "15:00", end = "16:00")
        assertEquals(
            2L,
            TodayLogic.focusSchedule(listOf(allDay, timed), today, LocalTime.of(12, 0))!!.schedule.id,
        )
        val onlyAllDay = TodayLogic.focusSchedule(listOf(allDay), today, LocalTime.of(12, 0))
        assertEquals(1L, onlyAllDay!!.schedule.id)
        assertTrue(onlyAllDay.ongoing)
    }

    @Test
    fun `接下来 - 跨天日程显示今天凌晨到结束时刻`() {
        val overnight = schedule(
            1, "overnight",
            startDate = today.minusDays(1),
            endDate = today,
            start = "22:00",
            end = "09:00",
        )
        val focus = TodayLogic.focusSchedule(listOf(overnight), today, LocalTime.of(8, 0))
        assertNotNull(focus)
        assertEquals(0, focus!!.startMinute)
        assertEquals(9 * 60, focus.endMinute)
        assertTrue(focus.ongoing)
    }

    @Test
    fun `接下来 - 没有日程返回空态`() {
        assertNull(TodayLogic.focusSchedule(emptyList(), today, LocalTime.NOON))
    }

    @Test
    fun `日程跨天文案保留今天可见时刻`() {
        val overnight = schedule(
            1, "overnight",
            startDate = today.minusDays(1),
            endDate = today,
            start = "22:00",
            end = "09:00",
        )
        assertEquals("至 09:00 · 10月24日起", TodayLogic.scheduleTimeText(overnight, today))
    }

    @Test
    fun `日程跨多天文案 - 只有次日才写明天，否则显示实际结束日期`() {
        val tomorrow = schedule(
            1, "to tomorrow",
            startDate = today,
            endDate = today.plusDays(1),
            start = "20:00",
            end = "09:00",
        )
        assertEquals("20:00 起 · 明天结束", TodayLogic.scheduleTimeText(tomorrow, today))

        val threeDays = schedule(
            2, "three days",
            startDate = today,
            endDate = today.plusDays(3),
            start = "20:00",
            end = "09:00",
        )
        assertEquals("20:00 起 · 10月28日结束", TodayLogic.scheduleTimeText(threeDays, today))
        assertFalse(TodayLogic.scheduleTimeText(threeDays, today).contains("明天"))
    }

    @Test
    fun `日程跨多天文案 - 覆盖今天全天时显示实际结束日期`() {
        val spanning = schedule(
            1, "spanning",
            startDate = today.minusDays(2),
            endDate = today.plusDays(3),
            start = "20:00",
            end = "09:00",
        )
        assertEquals("今天全天 · 至 10月28日", TodayLogic.scheduleTimeText(spanning, today))
    }

    // ---------- 月历筛选 ----------

    private val todos = listOf(
        TodoEntity(1, "todo today", today.toString(), "18:00"),
        TodoEntity(2, "todo done", today.toString(), done = true),
    )
    private val countdowns = listOf(
        CountdownEntity(1, "birthday", "2026-10-25", yearlyRepeat = true),
        CountdownEntity(2, "other day", "2026-11-01"),
    )
    private val schedules = listOf(schedule(1, "meeting", start = "15:00", end = "16:00"))

    @Test
    fun `月历筛选 - 只返回所选类型并同步标记`() {
        val content = CalendarLogic.contentOn(
            schedules, todos, countdowns, today, CalendarTypeFilter.TODO,
        )
        assertTrue(content.schedules.isEmpty())
        assertEquals(2, content.todos.size)
        assertTrue(content.countdowns.isEmpty())
        assertEquals(
            setOf(CalendarTypeFilter.TODO),
            CalendarLogic.markers(
                schedules, todos, countdowns, today, CalendarTypeFilter.TODO,
            ),
        )
    }

    @Test
    fun `月历筛选 - 计数只算当前类型`() {
        val all = CalendarLogic.contentOn(
            schedules, todos, countdowns, today, CalendarTypeFilter.ALL,
        )
        assertEquals(4, CalendarLogic.countFor(all, CalendarTypeFilter.ALL))
        assertEquals(1, CalendarLogic.countFor(all, CalendarTypeFilter.SCHEDULE))
        assertEquals(2, CalendarLogic.countFor(all, CalendarTypeFilter.TODO))
        assertEquals(1, CalendarLogic.countFor(all, CalendarTypeFilter.COUNTDOWN))
    }

    @Test
    fun `月历筛选 - 搜索无结果与当天无数据可区分`() {
        val filterMiss = CalendarLogic.contentOn(
            schedules, todos, countdowns, today, CalendarTypeFilter.COUNTDOWN, "meeting",
        )
        assertTrue(filterMiss.isEmpty)
        val unfilteredMiss = CalendarLogic.unfilteredContentOn(
            schedules, todos, countdowns, today, "no-such-title",
        )
        assertTrue(unfilteredMiss.isEmpty)
        val noFilterMiss = CalendarLogic.unfilteredContentOn(
            schedules, todos, countdowns, today.minusDays(2), "",
        )
        assertTrue(noFilterMiss.isEmpty)
        val allToday = CalendarLogic.unfilteredContentOn(
            schedules, todos, countdowns, today, "",
        )
        assertFalse(allToday.isEmpty)
    }

    @Test
    fun `月历 - 年度倒数日在周年日出现`() {
        val nextYear = LocalDate.of(2027, 10, 25)
        val content = CalendarLogic.contentOn(
            schedules, todos, countdowns, nextYear, CalendarTypeFilter.COUNTDOWN,
        )
        assertEquals(1, content.countdowns.size)
        assertEquals("birthday", content.countdowns.first().title)
    }

    // ---------- 新建默认与日期继承 ----------

    @Test
    fun `月历新建 - 三种类型继承所选日期`() {
        val selected = LocalDate.of(2026, 10, 26)
        val schedule = EntryDefaults.scheduleSuggestion(selected, today, LocalTime.of(14, 20))
        assertEquals(selected, schedule.startDate)
        assertEquals(selected, schedule.endDate)
        assertEquals("09:00", schedule.startTime)
        assertEquals(selected, EntryDefaults.todoPresetDueDate(selected))
        assertEquals(selected, EntryDefaults.countdownTarget(selected, today))
    }

    @Test
    fun `今天新建 - 待办默认无截止，日程建议下一个整点`() {
        assertNull(EntryDefaults.todoPresetDueDate(null))
        val s = EntryDefaults.scheduleSuggestion(null, today, LocalTime.of(14, 20))
        assertEquals("15:00", s.startTime)
        assertEquals("16:00", s.endTime)
        assertEquals(today, s.startDate)
        assertTrue(s.label.contains("默认建议"))
    }

    @Test
    fun `今天 23 点新建 - 建议跨到次日并显式日期`() {
        val s = EntryDefaults.scheduleSuggestion(null, today, LocalTime.of(23, 10))
        assertEquals(today.plusDays(1), s.startDate)
        assertEquals(today.plusDays(1), s.endDate)
        assertEquals("00:00", s.startTime)
        assertEquals("01:00", s.endTime)
        assertTrue(s.label.contains("明天"))
    }

    @Test
    fun `今天新建 - 倒数日维持一个月后默认`() {
        assertEquals(today.plusMonths(1), EntryDefaults.countdownTarget(null, today))
    }

    // ---------- 待办日期文案 ----------

    @Test
    fun `待办文案 - 逾期按天且完成条目不冒充逾期`() {
        val overdue = TodoEntity(title = "report", dueDate = "2026-10-23", dueTime = "18:00")
        assertEquals(2L, TodoDisplay.overdueDays(overdue, today))
        assertEquals("逾期 2 天 · 10月23日 18:00", TodoDisplay.dueLine(overdue, today))
        assertEquals("今天 18:00", TodoDisplay.dueLine(
            TodoEntity(title = "today", dueDate = today.toString(), dueTime = "18:00"), today,
        ))
        assertEquals("明天 09:00", TodoDisplay.dueLine(
            TodoEntity(title = "tomorrow", dueDate = today.plusDays(1).toString(), dueTime = "09:00"),
            today,
        ))
        val done = overdue.copy(done = true)
        assertNull(TodoDisplay.overdueDays(done, today))
        assertEquals("10月23日 18:00", TodoDisplay.dueLine(done, today))
    }

    // ---------- AI 草稿编辑 ----------

    @Test
    fun `AI 草稿 - 待办截止日期可以清空`() {
        assertNull(
            AiDraftEditRules.validate("create_todo", "交报告", "", "", ""),
        )
        assertNull(
            AiDraftEditRules.validate("update_todo", "交报告", "", "18:00", ""),
        )
    }

    @Test
    fun `AI 草稿 - 日程和倒数日仍要求合法日期`() {
        assertEquals(
            "日期需为 yyyy-MM-dd",
            AiDraftEditRules.validate("create_schedule", "开会", "", "15:00", "16:00"),
        )
        assertEquals(
            "日期需为 yyyy-MM-dd",
            AiDraftEditRules.validate("create_countdown", "生日", "", "", ""),
        )
        assertEquals(
            "日期需为 yyyy-MM-dd",
            AiDraftEditRules.validate("create_todo", "交报告", "10/26", "", ""),
        )
        assertEquals(
            "时间需为 HH:mm",
            AiDraftEditRules.validate("create_todo", "交报告", "2026-10-26", "25:00", ""),
        )
    }

    // ---------- 动画偏好 ----------

    // ---------- 角色任务状态 ----------

    @Test
    fun `角色状态 - 草稿不冒充保存，真正成功才显示已保存`() {
        val draft = ChanStatusLogic.from(SendState.Idle, pendingCount = 3, lastSaved = null)
        assertEquals(ChanMood.DRAFT_READY, draft.mood)
        assertEquals("整理出 3 项，待你确认", statusText(draft))

        val saved = ChanStatusLogic.from(SendState.Idle, pendingCount = 0, lastSaved = 3)
        assertEquals(ChanMood.SAVED, saved.mood)
        assertEquals("已保存 3 项", statusText(saved))
    }

    @Test
    fun `角色状态 - 思考优先于草稿，失败提供原因与重试`() {
        val thinking = ChanStatusLogic.from(
            SendState.Sending(0, 0, "发送中…"), pendingCount = 2, lastSaved = null,
        )
        assertEquals(ChanMood.THINKING, thinking.mood)
        assertEquals("正在整理安排…", statusText(thinking))

        val failed = ChanStatusLogic.from(
            SendState.Failed("网络不可用，内容已保留", retryable = true),
            pendingCount = 2,
            lastSaved = null,
        )
        assertEquals(ChanMood.ERROR, failed.mood)
        assertTrue(failed.retryable)
        assertEquals("网络不可用，内容已保留", statusText(failed))
    }

    @Test
    fun `角色动画 - 跟随系统时系统关闭动画即减少`() {
        assertTrue(ChanMotion.shouldReduce(ChanAnimationMode.SYSTEM, 0f))
        assertFalse(ChanMotion.shouldReduce(ChanAnimationMode.SYSTEM, 1f))
        assertTrue(ChanMotion.shouldReduce(ChanAnimationMode.REDUCED, 1f))
        assertFalse(ChanMotion.shouldReduce(ChanAnimationMode.FULL, 0f))
    }
}
