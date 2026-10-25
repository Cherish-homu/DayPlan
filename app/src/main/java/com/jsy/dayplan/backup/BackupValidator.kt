package com.jsy.dayplan.backup

import com.jsy.dayplan.core.PlanRules
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * 备份校验：schema、结构、真实日历日期、起止关系、唯一 ID、枚举、数量自洽、
 * 文本长度与业务约束（与正常写入共用 PlanRules）。
 *
 * 规模上限为三类业务记录合计 10000（与 Repository 写入、导出同一规则）。
 * 时间字段接受一位小时（如 "9:00"）并规范化为 "HH:mm"；返回的 Ok.backup 已规范化，
 * 导入后再次导出的数据必然能通过自身校验。
 */
sealed class ValidateResult {
    data class Ok(val backup: BackupV1, val preview: RestorePreview) : ValidateResult()
    data class Bad(val errors: List<String>) : ValidateResult()
}

data class RestorePreview(
    val schedules: Int,
    val todos: Int,
    val countdowns: Int,
)

object BackupValidator {

    fun isValidFileSize(bytes: Int): Boolean = bytes in 1..PlanRules.MAX_JSON_BYTES

    private fun validDate(s: String, what: String, errors: MutableList<String>) {
        try {
            val d = LocalDate.parse(s)
            if (d.year !in PlanRules.MIN_YEAR..PlanRules.MAX_YEAR) {
                errors.add("$what 日期年份须在 ${PlanRules.MIN_YEAR}–${PlanRules.MAX_YEAR}：$s")
            }
        } catch (e: DateTimeParseException) {
            errors.add("$what 不是真实日历日期：$s")
        }
    }

    private fun normalizeTimeOrError(
        s: String?,
        what: String,
        nullable: Boolean,
        errors: MutableList<String>,
    ): String? {
        if (s == null) {
            if (!nullable) errors.add("$what 缺少时间")
            return null
        }
        val n = PlanRules.normalizeTime(s)
        if (n == null) errors.add("$what 不是合法时间：$s")
        return n ?: s
    }

    private fun validText(
        s: String,
        what: String,
        max: Int = PlanRules.NOTE_MAX,
        errors: MutableList<String>,
    ) {
        if (s.length > max) errors.add("$what 文本超长（>$max 字符）")
    }

    fun validate(input: BackupV1): ValidateResult {
        val errors = mutableListOf<String>()
        if (input.schemaVersion !in BACKUP_SCHEMA_VERSION_MIN..BACKUP_SCHEMA_VERSION) {
            errors.add(
                "不支持 schemaVersion=${input.schemaVersion}" +
                    "（当前支持 $BACKUP_SCHEMA_VERSION_MIN–$BACKUP_SCHEMA_VERSION）",
            )
        }

        // 规模限制：三表合计（只限每表会让 10001 条合计通过校验却无法导出）
        if (input.schedules.size + input.todos.size + input.countdowns.size > PlanRules.MAX_ITEMS) {
            errors.add("三类记录合计超过上限（${PlanRules.MAX_ITEMS}）")
        }
        if (errors.isNotEmpty()) return ValidateResult.Bad(errors)

        val oldSchema = input.schemaVersion < 2

        // 先规范化（时间格式、v1 倒数日提醒开关），再按统一规则校验。
        val schedules = input.schedules.map { s ->
            s.copy(
                startTime = normalizeTimeOrError(
                    s.startTime, "日程 ${s.id} 开始时间", nullable = s.allDay, errors,
                ),
                endTime = normalizeTimeOrError(
                    s.endTime, "日程 ${s.id} 结束时间", nullable = s.allDay, errors,
                ),
            )
        }
        val todos = input.todos.map { t ->
            t.copy(
                dueTime = normalizeTimeOrError(
                    t.dueTime, "待办 ${t.id} 截止时间", nullable = true, errors,
                ),
            )
        }
        val countdowns = input.countdowns.map { c ->
            val enabled = if (oldSchema) c.remindDaysBefore != 0 else c.remindEnabled
            c.copy(
                remindEnabled = enabled,
                remindTime = normalizeTimeOrError(
                    c.remindTime, "倒数日 ${c.id} 提醒时间", nullable = false, errors,
                ) ?: c.remindTime,
            )
        }

        // ID 唯一性
        val schedIds = schedules.map { it.id }
        val todoIds = todos.map { it.id }
        val cdIds = countdowns.map { it.id }
        if (schedIds.size != schedIds.toSet().size) errors.add("日程存在重复 ID")
        if (todoIds.size != todoIds.toSet().size) errors.add("待办存在重复 ID")
        if (cdIds.size != cdIds.toSet().size) errors.add("倒数日存在重复 ID")
        if (schedIds.any { it <= 0 }) errors.add("日程存在非法 ID")
        if (todoIds.any { it <= 0 }) errors.add("待办存在非法 ID")
        if (cdIds.any { it <= 0 }) errors.add("倒数日存在非法 ID")

        schedules.forEach { s ->
            if (!PlanRules.titleOk(s.title)) errors.add("日程 ${s.id} 标题非法")
            validText(s.note, "日程 ${s.id} 备注", errors = errors)
            validText(s.location, "日程 ${s.id} 地点", max = PlanRules.LOCATION_MAX, errors = errors)
            validDate(s.startDate, "日程 ${s.id} 开始", errors)
            validDate(s.endDate, "日程 ${s.id} 结束", errors)
            if (!s.allDay && (s.startTime == null || s.endTime == null)) {
                errors.add("日程 ${s.id} 缺少时间")
            }
            if (s.category !in 0..3) errors.add("日程 ${s.id} 分类非法")
            if (s.remindMinutesBefore !in listOf(0, 15, 30, 60, 120)) {
                errors.add("日程 ${s.id} 提前提醒非法")
            }
            runCatching {
                val sd = LocalDate.parse(s.startDate)
                val ed = LocalDate.parse(s.endDate)
                if (ed.isBefore(sd)) errors.add("日程 ${s.id} 结束日期早于开始")
                if (!s.allDay) {
                    val st = s.startTime ?: throw IllegalStateException("日程 ${s.id} 缺少开始时间")
                    val et = s.endTime ?: throw IllegalStateException("日程 ${s.id} 缺少结束时间")
                    if (sd == ed && et <= st) errors.add("日程 ${s.id} 开始必须早于结束")
                }
            }.onFailure { e ->
                if (e is IllegalStateException) errors.add(e.message!!)
            }
        }
        todos.forEach { t ->
            if (!PlanRules.titleOk(t.title)) errors.add("待办 ${t.id} 标题非法")
            validText(t.note, "待办 ${t.id} 备注", errors = errors)
            if (t.priority !in 0..1) errors.add("待办 ${t.id} 优先级非法")
            if (t.dueDate != null) validDate(t.dueDate, "待办 ${t.id} 截止", errors)
            if (t.dueDate == null && t.dueTime != null) errors.add("待办 ${t.id} 无日期却有截止时间")
        }
        countdowns.forEach { c ->
            if (!PlanRules.titleOk(c.title)) errors.add("倒数日 ${c.id} 标题非法")
            validText(c.note, "倒数日 ${c.id} 备注", errors = errors)
            validDate(c.targetDate, "倒数日 ${c.id} 目标", errors)
            if (c.category !in 0..4) errors.add("倒数日 ${c.id} 类别非法")
            if (c.remindDaysBefore !in listOf(0, 1, 7)) {
                errors.add("倒数日 ${c.id} 提醒天数非法（仅支持当天/1/7 天）")
            }
            if (c.originalDate.isNotEmpty()) validDate(c.originalDate, "倒数日 ${c.id} 原始日期", errors)
        }

        // 数量自洽
        if (input.counts.schedules != input.schedules.size) errors.add("日程数量与清单不符")
        if (input.counts.todos != input.todos.size) errors.add("待办数量与清单不符")
        if (input.counts.countdowns != input.countdowns.size) errors.add("倒数日数量与清单不符")

        if (errors.isNotEmpty()) return ValidateResult.Bad(errors)
        val normalized = input.copy(
            schemaVersion = BACKUP_SCHEMA_VERSION,
            schedules = schedules,
            todos = todos,
            countdowns = countdowns,
        )
        return ValidateResult.Ok(
            normalized,
            RestorePreview(normalized.schedules.size, normalized.todos.size, normalized.countdowns.size),
        )
    }
}
