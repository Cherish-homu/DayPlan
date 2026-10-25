package com.jsy.dayplan.reminder

import com.jsy.dayplan.core.CountdownLogic
import com.jsy.dayplan.data.CountdownEntity
import com.jsy.dayplan.data.MissedReminderEntity
import com.jsy.dayplan.data.PlanDao
import com.jsy.dayplan.data.ReminderOccurrenceEntity
import com.jsy.dayplan.data.ScheduleEntity
import com.jsy.dayplan.data.TodoEntity
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 提醒协调器：组合数据库（事实来源）、实际注册身份（ReminderKeyStore）与调度器。
 *
 * 职责：
 * - 保存/编辑/删除/完成/撤销完成后取消“实际注册的完整 key”，再调度新计划；
 * - 触发时核对条目存在、版本一致、提醒启用、未完成，按真实触发时刻判定宽限；
 * - 去重使用数据库唯一键原子插入，并发触发只有一方得到通知权；
 * - 年度倒数日触发后注册下一次有效提醒（含当天提醒）；
 * - 重建时处理宽限内补发与已错过记录，并留下可查询的错过状态。
 */
class ReminderCoordinator(
    private val dao: PlanDao,
    private val scheduler: ReminderScheduler,
    private val keyStore: ReminderKeyStore = InMemoryReminderKeyStore(),
) {
    /** 当前是否具备实际投递条件（通知权限 + 通道开启）；由 App 注入。 */
    var deliveryAvailable: () -> Boolean = { true }

    /** 投递受阻时回调（界面展示原因）。 */
    var onDeliveryBlocked: (reason: String) -> Unit = {}

    private val dateFmt = DateTimeFormatter.ofPattern("yyyyMMdd")

    private fun nowMs(): Long = System.currentTimeMillis()

    private fun todayOf(epochMs: Long): LocalDate =
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()

    // ---------- 取消实际注册身份 ----------
    private suspend fun cancelRegistered(type: String, id: Long) {
        val keys = keyStore.keysFor(type, id)
        if (keys.isNotEmpty()) scheduler.cancelAll(keys)
        keyStore.removeAll(keys)
    }

    /** v1 短 key（无时间后缀），升级后也可能残留。 */
    private fun cancelLegacyShort(type: String, id: Long, version: Int) {
        scheduler.cancel("$type-$id-$version")
        if (version > 1) scheduler.cancel("$type-$id-${version - 1}")
    }

    private fun cancelLegacyCountdownDateKeys(c: CountdownEntity, nowYear: Int) {
        val target = runCatching { LocalDate.parse(c.targetDate) }.getOrNull() ?: return
        val years = setOf(target.year, nowYear - 1, nowYear, nowYear + 1)
        years.forEach { y ->
            val d = if (c.yearlyRepeat) {
                CountdownLogic.resolveYearlyOccurrence(target, y)
            } else target
            scheduler.cancel("cd-${c.id}-${c.version}-${d.format(dateFmt)}")
        }
    }

    // ---------- 保存 / 编辑 ----------
    suspend fun onScheduleSaved(s: ScheduleEntity) {
        cancelRegistered("sched", s.id)
        cancelLegacyShort("sched", s.id, s.version)
        dao.clearOccurrencesByPrefix("sched-${s.id}-")
        val plan = ReminderPlans.schedulePlan(s) ?: return
        if (plan.triggerAtEpochMs > nowMs()) scheduleAndRecord(plan)
    }

    suspend fun onScheduleDeleted(id: Long, version: Int) {
        cancelRegistered("sched", id)
        cancelLegacyShort("sched", id, version)
        dao.clearOccurrencesByPrefix("sched-$id-")
    }

    suspend fun onTodoSaved(t: TodoEntity) {
        cancelRegistered("todo", t.id)
        cancelLegacyShort("todo", t.id, t.version)
        dao.clearOccurrencesByPrefix("todo-${t.id}-")
        val plan = ReminderPlans.todoPlan(t) ?: return
        if (plan.triggerAtEpochMs > nowMs()) scheduleAndRecord(plan)
    }

    suspend fun onTodoDeleted(id: Long, version: Int) {
        cancelRegistered("todo", id)
        cancelLegacyShort("todo", id, version)
        dao.clearOccurrencesByPrefix("todo-$id-")
    }

    /** 倒数日：保存时只注册下一个“严格未来”的有效提醒。 */
    suspend fun onCountdownSaved(c: CountdownEntity, today: LocalDateTime) {
        val now = nowMs()
        cancelRegistered("cd", c.id)
        cancelLegacyShort("cd", c.id, c.version)
        cancelLegacyCountdownDateKeys(c, today.year)
        dao.clearOccurrencesByPrefix("cd-${c.id}-")
        val plan = ReminderPlans.countdownNextPlan(c, now) ?: return
        if (plan.triggerAtEpochMs > now) scheduleAndRecord(plan)
    }

    suspend fun onCountdownDeleted(id: Long, version: Int) {
        cancelRegistered("cd", id)
        cancelLegacyShort("cd", id, version)
        dao.clearOccurrencesByPrefix("cd-$id-")
    }

    private fun scheduleAndRecord(plan: ReminderPlans.ReminderPlan) {
        scheduler.schedule(plan, scheduler.canScheduleExact())
        keyStore.add(plan.occurrenceKey)
    }

    // ---------- 触发 ----------
    suspend fun onFired(key: String, now: LocalDateTime): FiredInfo? =
        onFired(key, now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())

    suspend fun onFired(key: String, nowEpochMs: Long): FiredInfo? {
        val parsed = parseKey(key) ?: return null

        var resolvedTitle = ""
        var resolvedBody = ""
        var plannedAt: Long
        var countdown: CountdownEntity? = null

        when (parsed.type) {
            "sched" -> {
                val s = dao.scheduleById(parsed.id) ?: return null
                if (s.version != parsed.version) return null
                val plan = ReminderPlans.schedulePlan(s) ?: return null
                if (plan.occurrenceKey != key) return null
                plannedAt = plan.triggerAtEpochMs
                resolvedTitle = plan.title
                resolvedBody = plan.body
            }
            "todo" -> {
                val t = dao.todoById(parsed.id) ?: return null
                if (t.version != parsed.version || t.done) return null
                val plan = ReminderPlans.todoPlan(t) ?: return null
                if (plan.occurrenceKey != key) return null
                plannedAt = plan.triggerAtEpochMs
                resolvedTitle = plan.title
                resolvedBody = plan.body
            }
            "cd" -> {
                val c = dao.countdownById(parsed.id) ?: return null
                if (c.version != parsed.version || !c.remindEnabled) return null
                countdown = c
                val today = todayOf(nowEpochMs)
                val match = ReminderPlans.countdownCandidates(c, today)
                    .firstOrNull { it.occurrenceKey == key }
                if (match != null) {
                    plannedAt = match.triggerAtEpochMs
                    resolvedTitle = match.title
                    resolvedBody = match.body
                } else {
                    // 升级前注册的旧 key：按实体当前字段重建真实触发时刻
                    val legacy = ReminderPlans.legacyCountdownPlan(c, key) ?: return null
                    plannedAt = legacy.triggerAtEpochMs
                    resolvedTitle = legacy.title
                    resolvedBody = legacy.body
                }
            }
            else -> return null
        }

        // 过早回调（系统近似误差之外）忽略
        if (nowEpochMs + 60_000L < plannedAt) return null

        if (!deliveryAvailable()) {
            onDeliveryBlocked("通知权限或通知通道已关闭，提醒无法投递")
            return null
        }

        // 宽限严格按毫秒：10 分钟整允许，+1 秒即错过。
        val lateMs = nowEpochMs - plannedAt
        val missed = lateMs > ReminderPlans.MISSED_GRACE_MS

        // 原子去重：并发触发时只有一个调用能得到非 -1。
        val inserted = dao.insertOccurrenceIgnore(ReminderOccurrenceEntity(key, nowEpochMs))

        if (countdown != null) {
            // 即使本次重复/错过，也确保年度事件的下一次提醒已注册。
            rearmCountdown(countdown, nowEpochMs)
        }
        if (inserted < 0) return null

        if (missed) {
            dao.insertMissed(
                MissedReminderEntity(
                    occurrenceKey = key,
                    plannedAtEpochMs = plannedAt,
                    detectedAtEpochMs = nowEpochMs,
                    title = resolvedTitle,
                ),
            )
            keyStore.removeAll(listOf(key))
            return null
        }

        return FiredInfo(
            key = key,
            title = resolvedTitle,
            body = resolvedBody,
            entityType = parsed.type,
            entityId = parsed.id,
            plannedAtEpochMs = plannedAt,
        )
    }

    /** 通知投递抛异常时释放去重标记，允许重建时重试。 */
    suspend fun releaseOccurrence(key: String) {
        dao.deleteOccurrence(key)
    }

    private suspend fun rearmCountdown(c: CountdownEntity, nowEpochMs: Long) {
        val next = ReminderPlans.countdownNextPlan(c, nowEpochMs) ?: return
        if (next.triggerAtEpochMs <= nowEpochMs) return
        scheduleAndRecord(next)
    }

    // ---------- 重建 / 协调 ----------
    /**
     * 启动、打开应用、重启、升级、时区变化与重新授权后调用：
     * 1) 取消所有已登记身份（含旧版短 key/旧倒数日日期 key）；
     * 2) 对宽限内到期的发生补发、超时未发的写入错过记录（不静默跳过）；
     * 3) 注册所有严格未来的提醒（年度倒数日至少下一次）。
     *
     * @return 需要在后台线程实际投递的通知。
     */
    suspend fun rebuildAll(now: LocalDateTime): List<FiredInfo> {
        val nowEpoch = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        cancelEverything(todayOf(nowEpoch))
        val deliveries = mutableListOf<FiredInfo>()

        val schedules = dao.allSchedules()
        val todos = dao.allTodos()
        val countdowns = dao.allCountdowns()

        // 处理已经到点（含宽限内/已错过）的发生
        schedules.forEach { s ->
            val plan = ReminderPlans.schedulePlan(s) ?: return@forEach
            if (plan.triggerAtEpochMs <= nowEpoch) {
                if (nowEpoch - plan.triggerAtEpochMs <= ReminderPlans.MISSED_GRACE_MS) {
                    onFired(plan.occurrenceKey, nowEpoch)?.let { deliveries.add(it) }
                } else {
                    markMissed(plan, nowEpoch)
                }
            }
        }
        todos.forEach { t ->
            val plan = ReminderPlans.todoPlan(t) ?: return@forEach
            if (plan.triggerAtEpochMs <= nowEpoch) {
                if (nowEpoch - plan.triggerAtEpochMs <= ReminderPlans.MISSED_GRACE_MS) {
                    onFired(plan.occurrenceKey, nowEpoch)?.let { deliveries.add(it) }
                } else {
                    markMissed(plan, nowEpoch)
                }
            }
        }
        countdowns.forEach { c ->
            val candidates = ReminderPlans.countdownCandidates(c, todayOf(nowEpoch))
            val latestDue = candidates.filter { it.triggerAtEpochMs <= nowEpoch }
                .maxByOrNull { it.triggerAtEpochMs } ?: return@forEach
            if (nowEpoch - latestDue.triggerAtEpochMs <= ReminderPlans.MISSED_GRACE_MS) {
                onFired(latestDue.occurrenceKey, nowEpoch)?.let { deliveries.add(it) }
            } else {
                markMissed(latestDue, nowEpoch)
                rearmCountdown(c, nowEpoch)
            }
        }

        // 注册严格未来的提醒
        schedules.forEach { s ->
            ReminderPlans.schedulePlan(s)?.let { p ->
                if (p.triggerAtEpochMs > nowEpoch) scheduleAndRecord(p)
            }
        }
        todos.forEach { t ->
            ReminderPlans.todoPlan(t)?.let { p ->
                if (p.triggerAtEpochMs > nowEpoch) scheduleAndRecord(p)
            }
        }
        countdowns.forEach { c ->
            ReminderPlans.countdownNextPlan(c, nowEpoch)?.let { p ->
                if (p.triggerAtEpochMs > nowEpoch) scheduleAndRecord(p)
            }
        }
        return deliveries
    }

    private suspend fun markMissed(plan: ReminderPlans.ReminderPlan, nowEpoch: Long) {
        val inserted = dao.insertOccurrenceIgnore(
            ReminderOccurrenceEntity(plan.occurrenceKey, nowEpoch),
        )
        if (inserted < 0) return
        dao.insertMissed(
            MissedReminderEntity(
                occurrenceKey = plan.occurrenceKey,
                plannedAtEpochMs = plan.triggerAtEpochMs,
                detectedAtEpochMs = nowEpoch,
                title = plan.title,
            ),
        )
        keyStore.removeAll(listOf(plan.occurrenceKey))
    }

    private suspend fun cancelEverything(today: LocalDate) {
        val keys = linkedSetOf<String>()
        keys.addAll(keyStore.all())
        val nowYear = today.year

        dao.allSchedules().forEach { s ->
            ReminderPlans.schedulePlan(s)?.let { keys.add(it.occurrenceKey) }
            keys.add("sched-${s.id}-${s.version}")
            if (s.version > 1) keys.add("sched-${s.id}-${s.version - 1}")
        }
        dao.allTodos().forEach { t ->
            ReminderPlans.todoPlan(t)?.let { keys.add(it.occurrenceKey) }
            keys.add("todo-${t.id}-${t.version}")
            if (t.version > 1) keys.add("todo-${t.id}-${t.version - 1}")
        }
        dao.allCountdowns().forEach { c ->
            ReminderPlans.countdownCandidates(c, today).forEach { keys.add(it.occurrenceKey) }
            keys.add("cd-${c.id}-${c.version}")
            if (c.version > 1) keys.add("cd-${c.id}-${c.version - 1}")
            val target = runCatching { LocalDate.parse(c.targetDate) }.getOrNull()
            if (target != null) {
                setOf(target.year, nowYear - 1, nowYear, nowYear + 1).forEach { y ->
                    val d = if (c.yearlyRepeat) {
                        CountdownLogic.resolveYearlyOccurrence(target, y)
                    } else target
                    keys.add("cd-${c.id}-${c.version}-${d.format(dateFmt)}")
                }
            }
        }
        if (keys.isNotEmpty()) scheduler.cancelAll(keys.toList())
        keyStore.clear()
    }

    data class FiredInfo(
        val key: String,
        val title: String,
        val body: String = "",
        val entityType: String = "",
        val entityId: Long = 0,
        val plannedAtEpochMs: Long = 0,
    )

    data class ParsedKey(
        val type: String,
        val id: Long,
        val version: Int,
        val stamp: String,
        val plannedAt: Long?,
    )

    companion object {
        /** 解析 key；12 位时间戳解析为触发时刻，8 位旧倒数日日期不作时刻猜测。 */
        fun parseKey(key: String): ParsedKey? {
            val parts = key.split("-")
            if (parts.size < 3) return null
            val type = parts[0]
            val id = parts[1].toLongOrNull() ?: return null
            val version = parts[2].toIntOrNull() ?: return null
            val stamp = if (parts.size >= 4) {
                parts[3] + (parts.getOrNull(4) ?: "")
            } else ""
            val plannedAt = if (stamp.length == 12) {
                try {
                    LocalDateTime.parse(
                        stamp, DateTimeFormatter.ofPattern("yyyyMMddHHmm"),
                    ).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                } catch (e: Exception) {
                    null
                }
            } else null
            return ParsedKey(type, id, version, stamp, plannedAt)
        }
    }
}
