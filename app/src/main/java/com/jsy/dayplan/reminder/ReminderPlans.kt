package com.jsy.dayplan.reminder

import com.jsy.dayplan.core.CountdownLogic
import com.jsy.dayplan.core.PlanRules
import com.jsy.dayplan.core.ScheduleLogic
import com.jsy.dayplan.data.CountdownEntity
import com.jsy.dayplan.data.ScheduleEntity
import com.jsy.dayplan.data.TodoEntity
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 提醒计划与统一调度。
 *
 * 数据库是提醒任务的事实来源：保存成功后调度；编辑/删除/完成后按“实际注册身份”
 * 取消旧提醒；触发时核对条目仍存在、未完成、版本与当前计划一致。
 *
 * occurrenceKey 形如：
 * - "sched-<id>-<version>-<yyyyMMddHHmm>"（触发时刻）
 * - "todo-<id>-<version>-<yyyyMMddHHmm>"（触发时刻）
 * - "cd-<id>-<version>-<yyyyMMddHHmm>"（真实触发时刻，不是目标日 09:00）
 * version 每次编辑递增；倒数日年度重复的不同年份也因触发时刻不同而身份唯一。
 */
object ReminderPlans {
    const val MISSED_GRACE_MS: Long =
        PlanRules.MISSED_GRACE_MINUTES * 60_000L
    const val MISSED_GRACE_MINUTES: Long = PlanRules.MISSED_GRACE_MINUTES

    /**
     * 候选起点回看天数：跨过目标日午夜后仍要能找到“刚过去的上一发生”，
     * 但不会把多年前的周年当作待处理历史。2 天覆盖跨午夜与时区/延迟边界。
     */
    const val CANDIDATE_LOOKBACK_DAYS: Long = 2

    data class ReminderPlan(
        val occurrenceKey: String,
        val triggerAtEpochMs: Long,
        val title: String,
        val body: String,
        val entityType: String = "",
        val entityId: Long = 0,
    )

    private val dateFmt = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val dtFmt = DateTimeFormatter.ofPattern("yyyyMMddHHmm")

    private fun zone(): ZoneId = ZoneId.systemDefault()

    fun epochOf(dt: LocalDateTime): Long = dt.atZone(zone()).toInstant().toEpochMilli()

    private fun minutesOf(hhmm: String): Int? =
        PlanRules.normalizeTime(hhmm)?.let { t ->
            val p = t.split(":")
            p[0].toInt() * 60 + p[1].toInt()
        }

    // ---------- 日程 ----------
    fun scheduleStart(s: ScheduleEntity): LocalDateTime? {
        if (s.remindMinutesBefore <= 0) return null
        val st = s.startTime ?: return null
        val mins = ScheduleLogic.toMinutes(st) ?: return null
        val start = try {
            LocalDateTime.of(LocalDate.parse(s.startDate), java.time.LocalTime.of(mins / 60, mins % 60))
        } catch (e: Exception) {
            return null
        }
        return start.minusMinutes(s.remindMinutesBefore.toLong())
    }

    fun schedulePlan(s: ScheduleEntity): ReminderPlan? {
        if (s.remindMinutesBefore == 0) return null
        val at = scheduleStart(s) ?: return null
        return ReminderPlan(
            occurrenceKey = "sched-${s.id}-${s.version}-${at.format(dtFmt)}",
            triggerAtEpochMs = epochOf(at),
            title = "日程提醒：${s.title}",
            body = "${s.startDate} ${s.startTime ?: "全天"}" +
                (if (s.location.isBlank()) "" else " · ${s.location}"),
            entityType = "schedule",
            entityId = s.id,
        )
    }

    // ---------- 待办 ----------
    fun todoDue(t: TodoEntity): LocalDateTime? {
        if (t.done || t.dueDate == null || t.dueTime == null) return null
        val d = try {
            LocalDate.parse(t.dueDate)
        } catch (e: Exception) {
            return null
        }
        val mins = ScheduleLogic.toMinutes(t.dueTime) ?: return null
        return LocalDateTime.of(d, java.time.LocalTime.of(mins / 60, mins % 60))
    }

    fun todoPlan(t: TodoEntity): ReminderPlan? {
        val at = todoDue(t) ?: return null
        return ReminderPlan(
            occurrenceKey = "todo-${t.id}-${t.version}-${at.format(dtFmt)}",
            triggerAtEpochMs = epochOf(at),
            title = "待办提醒：${t.title}",
            body = "截止 ${t.dueDate} ${t.dueTime ?: ""}",
            entityType = "todo",
            entityId = t.id,
        )
    }

    // ---------- 倒数日 ----------
    fun countdownNextOccurrence(c: CountdownEntity, today: LocalDate): LocalDate =
        CountdownLogic.nextOccurrence(LocalDate.parse(c.targetDate), today, c.yearlyRepeat)

    /** 倒数日提醒的真实触发时刻：发生日减去提前天数，在用户选择的提醒时分。 */
    fun countdownTrigger(c: CountdownEntity, occurrence: LocalDate): LocalDateTime? {
        val mins = minutesOf(c.remindTime) ?: return null
        val remindDate = occurrence.minusDays(c.remindDaysBefore.toLong())
        return LocalDateTime.of(remindDate, java.time.LocalTime.of(mins / 60, mins % 60))
    }

    private fun countdownPlanForOccurrence(
        c: CountdownEntity,
        occurrence: LocalDate,
        today: LocalDate,
    ): ReminderPlan? {
        val at = countdownTrigger(c, occurrence) ?: return null
        val days = CountdownLogic.daysBetween(today, occurrence)
        return ReminderPlan(
            occurrenceKey = "cd-${c.id}-${c.version}-${at.format(dtFmt)}",
            triggerAtEpochMs = epochOf(at),
            title = "倒数日提醒：${c.title}",
            body = when {
                days > 0 -> "距 $occurrence 还有 $days 天"
                days == 0L -> "$occurrence 就是今天"
                else -> "$occurrence 已过去 ${-days} 天"
            },
            entityType = "countdown",
            entityId = c.id,
        )
    }

    /**
     * 候选发生（含刚过去的上一次 + 未来若干年）。
     *
     * 过去候选从 `today - CANDIDATE_LOOKBACK_DAYS` 起算：年度事件在目标日 23:55
     * 提醒、次日 00:03 才收到回调时，仍能找到上一发生并正确判定 8 分钟宽限；
     * 重建时也能对 11 分钟的迟到留下错过记录。窗口之外的历史不会反复补记。
     * 未来计划仍由调用方按触发时刻严格过滤，不会把过去发生当作未来闹钟注册。
     */
    fun countdownCandidates(c: CountdownEntity, today: LocalDate): List<ReminderPlan> {
        if (!c.remindEnabled || c.remindDaysBefore !in listOf(0, 1, 7)) return emptyList()
        val original = try {
            LocalDate.parse(c.targetDate)
        } catch (e: Exception) {
            return emptyList()
        }
        val anchor = today.minusDays(CANDIDATE_LOOKBACK_DAYS)
        val first = CountdownLogic.nextOccurrence(original, anchor, c.yearlyRepeat)
        val occurrences = mutableListOf(first)
        if (c.yearlyRepeat) {
            var y = first.year + 1
            repeat(3) {
                occurrences.add(CountdownLogic.resolveYearlyOccurrence(original, y))
                y++
            }
        }
        return occurrences.mapNotNull { countdownPlanForOccurrence(c, it, today) }
            .distinctBy { it.occurrenceKey }
    }

    /** 保存/预览用：当前或之后第一个有效提醒（按真实触发时刻过滤）。 */
    fun countdownPlan(c: CountdownEntity, today: LocalDate): ReminderPlan? {
        val startMs = today.atStartOfDay(zone()).toInstant().toEpochMilli()
        return countdownCandidates(c, today).firstOrNull { it.triggerAtEpochMs >= startMs }
    }

    /** 下一次严格晚于 afterEpochMs 的有效提醒（年度事件触发后重新注册下一年）。 */
    fun countdownNextPlan(
        c: CountdownEntity,
        afterEpochMs: Long,
        today: LocalDate = java.time.Instant.ofEpochMilli(afterEpochMs)
            .atZone(zone()).toLocalDate(),
    ): ReminderPlan? = countdownCandidates(c, today)
        .filter { it.triggerAtEpochMs > afterEpochMs }
        .minByOrNull { it.triggerAtEpochMs }

    /**
     * 旧版倒数日 key（cd-id-ver-yyyyMMdd）不含时刻；触发时按实体当前计划解析，
     * 不把目标日臆测为 09:00。
     */
    fun legacyCountdownOccurrenceDate(key: String): LocalDate? {
        val parts = key.split("-")
        if (parts.size != 4 || parts[0] != "cd") return null
        val stamp = parts[3]
        if (stamp.length != 8) return null
        return try {
            LocalDate.parse(stamp, dateFmt)
        } catch (e: Exception) {
            null
        }
    }

    fun legacyCountdownPlan(c: CountdownEntity, key: String): ReminderPlan? {
        val occ = legacyCountdownOccurrenceDate(key) ?: return null
        if (!c.remindEnabled) return null
        if (c.yearlyRepeat) {
            val original = try { LocalDate.parse(c.targetDate) } catch (e: Exception) { return null }
            if (original.monthValue != occ.monthValue || original.dayOfMonth != occ.dayOfMonth) {
                return null
            }
        } else if (occ.toString() != c.targetDate) {
            return null
        }
        return countdownPlanForOccurrence(c, occ, occ)
    }
}

/**
 * 调度适配器接口。生产实现走 AlarmManager（精确/近似降级）；
 * 测试用 Fake 记录调用，验证新增→修改→取消→重建等行为。
 */
interface ReminderScheduler {
    /** 是否允许精确提醒（SCHEDULE_EXACT_ALARM 可用）。 */
    fun canScheduleExact(): Boolean

    /** 调度一个未来提醒（同一 key 重复调度 = 修改）。 */
    fun schedule(plan: ReminderPlans.ReminderPlan, exactAllowed: Boolean)

    /** 取消某 key 的提醒。 */
    fun cancel(occurrenceKey: String)

    /** 取消全部由本 App 注册的提醒（重建前调用）。 */
    fun cancelAll(keys: List<String>)
}

/** 测试/预览用假调度器：记录调用序列。 */
class FakeReminderScheduler(
    private var exactAllowedFlag: Boolean = true,
) : ReminderScheduler {
    val scheduled = mutableListOf<ReminderPlans.ReminderPlan>()
    val cancelledKeys = mutableListOf<String>()

    override fun canScheduleExact(): Boolean = exactAllowedFlag

    fun setExactAllowed(v: Boolean) { exactAllowedFlag = v }

    override fun schedule(plan: ReminderPlans.ReminderPlan, exactAllowed: Boolean) {
        scheduled.removeAll { it.occurrenceKey == plan.occurrenceKey }
        scheduled.add(plan)
    }

    override fun cancel(occurrenceKey: String) {
        scheduled.removeAll { it.occurrenceKey == occurrenceKey }
        cancelledKeys.add(occurrenceKey)
    }

    override fun cancelAll(keys: List<String>) {
        scheduled.removeAll { s -> keys.any { it == s.occurrenceKey } }
        cancelledKeys.addAll(keys)
    }

    fun containsKey(key: String): Boolean = scheduled.any { it.occurrenceKey == key }
}
