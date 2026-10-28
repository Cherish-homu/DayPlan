package com.jsy.dayplan.ledger.core

import com.jsy.dayplan.ledger.data.TransactionEntity
import com.jsy.dayplan.ledger.data.TransferEntity

/**
 * 明细过滤（账目 + 转账）的纯函数实现，VM 与测试共用同一口径。
 *
 * 转账过滤修正了小账本 v1.3 的旧问题：转账必须按业务日期归入当前月，
 * 不能把全部月份的转账混进当月明细；账户筛选对转账按转出/转入账户双向匹配。
 */
object LedgerFilter {

    /** 当月账目 + 分类/账户/关键词筛选，口径与统计一致。 */
    fun filterTxns(
        txns: List<TransactionEntity>,
        month: String,
        categoryId: Long? = null,
        accountId: Long? = null,
        keyword: String = "",
    ): List<TransactionEntity> {
        val start = "$month-01"
        val end = Calculator.lastDayOfMonth(month)
        return txns
            .filter { it.bizDate in start..end }
            .filter { categoryId == null || it.categoryId == categoryId }
            .filter { accountId == null || it.accountId == accountId }
            .filter { keyword.isBlank() || it.note.contains(keyword, ignoreCase = true) }
    }

    /** 当月转账；账户筛选命中转出或转入任一侧即保留。 */
    fun filterTransfers(
        transfers: List<TransferEntity>,
        month: String,
        accountId: Long? = null,
    ): List<TransferEntity> {
        val start = "$month-01"
        val end = Calculator.lastDayOfMonth(month)
        return transfers
            .filter { it.bizDate in start..end }
            .filter { accountId == null || it.fromAccountId == accountId || it.toAccountId == accountId }
    }
}
