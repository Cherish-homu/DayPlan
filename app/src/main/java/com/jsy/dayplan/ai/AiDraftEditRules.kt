package com.jsy.dayplan.ai

import com.jsy.dayplan.core.PlanRules
import java.time.LocalDate

/**
 * AI 草稿编辑弹窗的本地校验。与主编辑器保持一致：
 * 待办的截止日期可以清空；日程/倒数日仍要求日期。
 */
object AiDraftEditRules {

    fun isSchedule(op: String): Boolean = op.endsWith("schedule")
    fun isTodo(op: String): Boolean = op.endsWith("todo")
    fun isCountdown(op: String): Boolean = op.endsWith("countdown")

    fun validate(
        op: String,
        title: String,
        date: String,
        time1: String,
        time2: String,
    ): String? {
        if (title.isBlank()) return "标题不能为空"
        val dateRequired = isSchedule(op) || isCountdown(op)
        if (date.isBlank()) {
            if (dateRequired) return "日期需为 yyyy-MM-dd"
        } else if (runCatching { LocalDate.parse(date.trim()) }.isFailure) {
            return "日期需为 yyyy-MM-dd"
        }
        if (time1.isNotBlank() && PlanRules.normalizeTime(time1) == null) {
            return "时间需为 HH:mm"
        }
        if (time2.isNotBlank() && PlanRules.normalizeTime(time2) == null) {
            return "时间需为 HH:mm"
        }
        return null
    }
}
