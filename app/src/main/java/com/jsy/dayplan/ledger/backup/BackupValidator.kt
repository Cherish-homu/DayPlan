package com.jsy.dayplan.ledger.backup

import com.jsy.dayplan.ledger.core.Money
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeParseException

/**
 * 备份文件完整校验：schemaVersion、真实日历日期时间、金额合法性（含单笔上限与
 * 负期初规则）、账目类型与分类类型匹配、ID 唯一性、外键关联、数量与总额一致性、
 * 文件规模与用户文本长度限制。全部通过才允许进入恢复预览。
 *
 * 无效文件在任何清空/写入之前即被拒绝。
 */
data class RestorePreview(
    val accounts: Int,
    val categories: Int,
    val transactions: Int,
    val transfers: Int,
    val budgets: Int,
    val incomeFen: Long,
    val expenseFen: Long,
)

sealed class ValidateResult {
    data class Ok(val backup: BackupV1, val preview: RestorePreview) : ValidateResult()
    data class Bad(val errors: List<String>) : ValidateResult()
}

object BackupValidator {

    /** 可接受年份区间与规模限制：与写入入口共享同一套规则。 */
    val MIN_YEAR: Int = com.jsy.dayplan.ledger.core.LedgerRules.MIN_YEAR
    val MAX_YEAR: Int = com.jsy.dayplan.ledger.core.LedgerRules.MAX_YEAR
    val MAX_ITEMS: Int = com.jsy.dayplan.ledger.core.LedgerRules.MAX_ITEMS
    val MAX_TEXT_CHARS: Int = com.jsy.dayplan.ledger.core.LedgerRules.MAX_TEXT_CHARS
    val MAX_FILE_BYTES: Int = com.jsy.dayplan.ledger.core.LedgerRules.MAX_FILE_BYTES

    private val DATE_RE = Regex("""^\d{4}-\d{2}-\d{2}$""")
    private val TIME_RE = Regex("""^\d{2}:\d{2}$""")
    private val MONTH_RE = Regex("""^\d{4}-\d{2}$""")

    fun isValidFileSize(bytes: Int): Boolean = bytes in 1..MAX_FILE_BYTES

    private fun validDate(s: String, what: String, errors: MutableList<String>) {
        if (!DATE_RE.matches(s)) {
            errors.add("$what 日期格式非法：$s")
            return
        }
        try {
            val d = LocalDate.parse(s)
            if (d.year !in MIN_YEAR..MAX_YEAR) {
                errors.add("$what 日期年份须在 $MIN_YEAR–$MAX_YEAR：$s")
            }
        } catch (e: DateTimeParseException) {
            errors.add("$what 不是真实日历日期：$s")
        }
    }

    private fun validTime(s: String, what: String, errors: MutableList<String>) {
        if (!TIME_RE.matches(s)) {
            errors.add("$what 时间格式非法：$s")
            return
        }
        try {
            LocalTime.parse(s)
        } catch (e: DateTimeParseException) {
            errors.add("$what 不是合法时间：$s（如 99:99）")
        }
    }

    private fun validText(s: String, what: String, errors: MutableList<String>) {
        if (s.length > MAX_TEXT_CHARS) errors.add("$what 文本超长（>${MAX_TEXT_CHARS} 字符）")
    }

    private fun validAmount(v: Long, what: String, errors: MutableList<String>) {
        if (v <= 0) errors.add("$what 金额必须大于 0")
        if (v > Money.MAX_SINGLE_FEN) errors.add("$what 金额超出单笔上限（${Money.MAX_SINGLE_FEN} 分）")
    }

    fun validate(b: BackupV1): ValidateResult {
        val errors = mutableListOf<String>()
        if (b.schemaVersion != BACKUP_SCHEMA_VERSION) {
            errors.add("不支持 schemaVersion=${b.schemaVersion}（当前支持 $BACKUP_SCHEMA_VERSION）")
        }

        // 规模限制（在任何深入校验前快速拒绝）
        if (b.accounts.size > MAX_ITEMS) errors.add("账户条目过多（>${MAX_ITEMS}）")
        if (b.categories.size > MAX_ITEMS) errors.add("分类条目过多（>${MAX_ITEMS}）")
        if (b.transactions.size > MAX_ITEMS) errors.add("账目条目过多（>${MAX_ITEMS}）")
        if (b.transfers.size > MAX_ITEMS) errors.add("转账条目过多（>${MAX_ITEMS}）")
        if (b.budgets.size > MAX_ITEMS) errors.add("预算条目过多（>${MAX_ITEMS}）")
        if (errors.isNotEmpty()) return ValidateResult.Bad(errors)

        // ID 唯一性
        fun <T> dupCheck(name: String, list: List<T>, id: (T) -> Long) {
            val ids = list.map(id)
            if (ids.size != ids.toSet().size) errors.add("$name 存在重复 ID")
            if (ids.any { it <= 0 }) errors.add("$name 存在非法 ID")
        }
        dupCheck("账户", b.accounts) { it.id }
        dupCheck("分类", b.categories) { it.id }
        dupCheck("账目", b.transactions) { it.id }
        dupCheck("转账", b.transfers) { it.id }

        val accountIds = b.accounts.map { it.id }.toSet()
        val categoryTypes = b.categories.associate { it.id to it.type }
        val categoryIds = b.categories.map { it.id }.toSet()

        b.accounts.forEach { a ->
            if (a.name.isBlank()) errors.add("账户 ${a.id} 名称为空")
            validText(a.name, "账户 ${a.id} 名称", errors)
            // 期初余额规则：允许负值，但绝对值不超过单笔上限
            if (a.openingFen < -Money.MAX_SINGLE_FEN || a.openingFen > Money.MAX_SINGLE_FEN) {
                errors.add("账户 ${a.id} 期初余额超出上限（绝对值 ≤ ${Money.MAX_SINGLE_FEN} 分）")
            }
        }
        b.categories.forEach { c ->
            if (c.name.isBlank()) errors.add("分类 ${c.id} 名称为空")
            validText(c.name, "分类 ${c.id} 名称", errors)
            if (c.type !in 0..1) errors.add("分类 ${c.id} 类型非法")
        }
        b.transactions.forEach { t ->
            validAmount(t.amountFen, "账目 ${t.id}", errors)
            if (t.type !in 0..1) {
                errors.add("账目 ${t.id} 类型非法")
            } else if (t.categoryId in categoryTypes && categoryTypes[t.categoryId] != t.type) {
                errors.add("账目 ${t.id}（${if (t.type == 0) "支出" else "收入"}）关联了类型不匹配的分类")
            }
            if (t.accountId !in accountIds) errors.add("账目 ${t.id} 引用了不存在的账户")
            if (t.categoryId !in categoryIds) errors.add("账目 ${t.id} 引用了不存在的分类")
            validDate(t.bizDate, "账目 ${t.id}", errors)
            validTime(t.bizTime, "账目 ${t.id}", errors)
            validText(t.note, "账目 ${t.id} 备注", errors)
        }
        b.transfers.forEach { t ->
            validAmount(t.amountFen, "转账 ${t.id}", errors)
            if (t.fromAccountId !in accountIds) errors.add("转账 ${t.id} 转出账户不存在")
            if (t.toAccountId !in accountIds) errors.add("转账 ${t.id} 转入账户不存在")
            if (t.fromAccountId == t.toAccountId) errors.add("转账 ${t.id} 转出/转入账户相同")
            validDate(t.bizDate, "转账 ${t.id}", errors)
            validTime(t.bizTime, "转账 ${t.id}", errors)
            validText(t.note, "转账 ${t.id} 备注", errors)
        }
        b.budgets.forEach {
            if (!MONTH_RE.matches(it.month)) {
                errors.add("预算月份格式非法：${it.month}")
            } else {
                try {
                    val ym = YearMonth.parse(it.month)
                    if (ym.year !in MIN_YEAR..MAX_YEAR) {
                        errors.add("预算月份年份须在 $MIN_YEAR–$MAX_YEAR：${it.month}")
                    }
                } catch (e: DateTimeParseException) {
                    errors.add("预算月份不是真实月份：${it.month}（如 2026-13）")
                }
            }
            if (it.budgetFen < 0) errors.add("预算金额不能为负：${it.month}")
            if (it.budgetFen > Money.MAX_SINGLE_FEN) errors.add("预算金额超出上限：${it.month}")
        }
        val monthSet = b.budgets.map { it.month }
        if (monthSet.size != monthSet.toSet().size) errors.add("预算月份重复")

        // 数量与总额自洽（供核对，不一致视为备份文件损坏）
        if (b.counts.accounts != b.accounts.size) errors.add("账户数量与清单不符")
        if (b.counts.categories != b.categories.size) errors.add("分类数量与清单不符")
        if (b.counts.transactions != b.transactions.size) errors.add("账目数量与清单不符")
        if (b.counts.transfers != b.transfers.size) errors.add("转账数量与清单不符")
        if (b.counts.budgets != b.budgets.size) errors.add("预算数量与清单不符")
        val income = try {
            b.transactions.filter { it.type == 1 }.map { it.amountFen }
                .fold(0L) { a, v -> Math.addExact(a, v) }
        } catch (e: ArithmeticException) {
            errors.add("收入总额溢出")
            0L
        }
        val expense = try {
            b.transactions.filter { it.type == 0 }.map { it.amountFen }
                .fold(0L) { a, v -> Math.addExact(a, v) }
        } catch (e: ArithmeticException) {
            errors.add("支出总额溢出")
            0L
        }
        if (b.totals.incomeFen != income) errors.add("收入总额与明细不符")
        if (b.totals.expenseFen != expense) errors.add("支出总额与明细不符")

        if (errors.isNotEmpty()) return ValidateResult.Bad(errors)
        val preview = RestorePreview(
            accounts = b.accounts.size,
            categories = b.categories.size,
            transactions = b.transactions.size,
            transfers = b.transfers.size,
            budgets = b.budgets.size,
            incomeFen = income,
            expenseFen = expense,
        )
        return ValidateResult.Ok(b, preview)
    }

    /** 备份内容的业务总额（用于恢复后核对）。 */
    fun businessTotals(b: BackupV1): Pair<Long, Long> {
        val income = b.transactions.filter { it.type == 1 }.map { it.amountFen }
            .fold(0L) { a, v -> Math.addExact(a, v) }
        val expense = b.transactions.filter { it.type == 0 }.map { it.amountFen }
            .fold(0L) { a, v -> Math.addExact(a, v) }
        return income to expense
    }
}
