package com.jsy.dayplan.ledger.core

import java.math.BigInteger

/**
 * 账本核心计算：余额与月度统计。纯函数，输入为不可变快照，便于单元测试。
 *
 * 全部求和/加减使用受检整数运算（CheckedLong），任何溢出立即抛出
 * LedgerOverflowException（携带用户可读信息），绝不产生静默回绕的负数余额。
 *
 * 余额 = 期初余额 + 收入 − 支出 + 转入 − 转出（期初余额不计入收入/支出）。
 * 转账不计入收入、支出或预算消耗。日期口径：按业务日期（用户录入的本地日期）归月。
 */
class LedgerOverflowException(message: String) : Exception(message)

object CheckedLong {
    fun add(a: Long, b: Long, what: String): Long =
        try {
            Math.addExact(a, b)
        } catch (e: ArithmeticException) {
            throw LedgerOverflowException("$what 超出可表示范围，请检查异常大额数据")
        }

    fun sub(a: Long, b: Long, what: String): Long =
        try {
            Math.subtractExact(a, b)
        } catch (e: ArithmeticException) {
            throw LedgerOverflowException("$what 超出可表示范围，请检查异常大额数据")
        }

    /** 对任意集合求和：受检累加。 */
    fun sum(values: Iterable<Long>, what: String): Long {
        var acc = 0L
        for (v in values) acc = add(acc, v, what)
        return acc
    }
}

data class TxnRow(
    val id: Long,
    val type: Int,          // 0=支出 1=收入
    val amountFen: Long,
    val categoryId: Long,
    val accountId: Long,
    val bizDate: String,    // yyyy-MM-dd
    val note: String,
)

data class TransferRow(
    val id: Long,
    val amountFen: Long,
    val fromAccountId: Long,
    val toAccountId: Long,
    val bizDate: String,
    val note: String,
)

data class AccountRow(val id: Long, val name: String, val openingFen: Long, val archived: Boolean)

data class CategoryRow(val id: Long, val name: String, val type: Int, val archived: Boolean)

data class AccountBalance(val accountId: Long, val balanceFen: Long)

data class MonthStats(
    val month: String,          // yyyy-MM
    val incomeFen: Long,
    val expenseFen: Long,
    val balanceFen: Long,
    val expenseByCategory: List<Pair<Long, Long>>, // categoryId -> fen，降序
)

data class BudgetRow(val month: String, val budgetFen: Long)

object Calculator {

    /** 受检的账户合计（总额）：供界面“账户合计”与校验使用。 */
    fun totalOf(balances: List<AccountBalance>): Long =
        CheckedLong.sum(balances.map { it.balanceFen }, "账户合计")

    fun computeBalances(
        accounts: List<AccountRow>,
        txns: List<TxnRow>,
        transfers: List<TransferRow>,
    ): List<AccountBalance> {
        val income = HashMap<Long, Long>()
        val expense = HashMap<Long, Long>()
        val tIn = HashMap<Long, Long>()
        val tOut = HashMap<Long, Long>()
        for (t in txns) {
            if (t.amountFen <= 0) continue
            if (t.type == 1) income.merge(t.accountId, t.amountFen, ::checkedMerge)
            else expense.merge(t.accountId, t.amountFen, ::checkedMerge)
        }
        for (t in transfers) {
            if (t.amountFen <= 0) continue
            tOut.merge(t.fromAccountId, t.amountFen, ::checkedMerge)
            tIn.merge(t.toAccountId, t.amountFen, ::checkedMerge)
        }
        return accounts.map { a ->
            var b = a.openingFen
            b = CheckedLong.add(b, income[a.id] ?: 0L, "账户${a.id} 余额")
            b = CheckedLong.sub(b, expense[a.id] ?: 0L, "账户${a.id} 余额")
            b = CheckedLong.add(b, tIn[a.id] ?: 0L, "账户${a.id} 余额")
            b = CheckedLong.sub(b, tOut[a.id] ?: 0L, "账户${a.id} 余额")
            AccountBalance(a.id, b)
        }
    }

    private fun checkedMerge(a: Long, b: Long): Long =
        try {
            Math.addExact(a, b)
        } catch (e: ArithmeticException) {
            throw LedgerOverflowException("金额累计超出可表示范围")
        }

    fun computeMonthStats(
        month: String,           // yyyy-MM
        txns: List<TxnRow>,
    ): MonthStats {
        val start = "$month-01"
        val end = lastDayOfMonth(month)
        val inMonth = txns.filter { it.bizDate >= start && it.bizDate <= end }
        val income = CheckedLong.sum(
            inMonth.filter { it.type == 1 }.map { it.amountFen }, "$month 收入合计",
        )
        val expense = CheckedLong.sum(
            inMonth.filter { it.type == 0 }.map { it.amountFen }, "$month 支出合计",
        )
        val byCatMap = HashMap<Long, Long>()
        for (t in inMonth) {
            if (t.type == 0) byCatMap.merge(t.categoryId, t.amountFen, ::checkedMerge)
        }
        val byCat = byCatMap.entries
            .sortedByDescending { it.value }
            .map { it.key to it.value }
        val balance = CheckedLong.sub(income, expense, "$month 结余")
        return MonthStats(month, income, expense, balance, byCat)
    }

    fun lastDayOfMonth(month: String): String {
        val (y, m) = month.split("-").map { it.toInt() }
        val leap = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
        val days = when (m) {
            1, 3, 5, 7, 8, 10, 12 -> 31
            4, 6, 9, 11 -> 30
            2 -> if (leap) 29 else 28
            else -> 30
        }
        return "%04d-%02d-%02d".format(y, m, days)
    }

    fun prevMonth(month: String): String {
        val (y, m) = month.split("-").map { it.toInt() }
        return if (m == 1) "${y - 1}-12" else "%04d-%02d".format(y, m - 1)
    }

    fun nextMonth(month: String): String {
        val (y, m) = month.split("-").map { it.toInt() }
        return if (m == 12) "${y + 1}-01" else "%04d-%02d".format(y, m + 1)
    }
}
