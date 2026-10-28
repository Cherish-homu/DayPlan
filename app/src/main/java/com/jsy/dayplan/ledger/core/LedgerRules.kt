package com.jsy.dayplan.ledger.core

/**
 * 共享业务约束：正常写入入口（Repository.saveTxn / saveTransfer / addAccount /
 * setOpening / setBudget / addCategory / renameCategory）与备份校验器
 * （BackupValidator）必须使用同一套规则。
 *
 * 关键不变量：凡 App 宣布保存成功的记录，其形成的完整账本必须能通过
 * 它自己的备份校验并可恢复。
 */
object LedgerRules {
    /** 单笔/期初/预算金额上限（分）。 */
    const val MAX_AMOUNT_FEN: Long = Money.MAX_SINGLE_FEN

    /** 用户文本（备注、名称）长度上限，字符数。 */
    const val MAX_TEXT_CHARS: Int = 500

    /** 单表条目数上限。 */
    const val MAX_ITEMS: Int = 200_000

    /** 可接受年份区间。 */
    const val MIN_YEAR: Int = 2000
    const val MAX_YEAR: Int = 2100

    /** 备份文件大小上限。 */
    const val MAX_FILE_BYTES: Int = 64 * 1024 * 1024

    fun textOk(s: String): Boolean = s.length <= MAX_TEXT_CHARS

    fun amountOk(fen: Long): Boolean = fen in 1..MAX_AMOUNT_FEN

    /** 期初余额：允许负值与 0，绝对值受上限约束。 */
    fun openingOk(fen: Long): Boolean = kotlin.math.abs(fen) <= MAX_AMOUNT_FEN

    /** 预算：非负，受上限约束（0 = 明确的零预算）。 */
    fun budgetOk(fen: Long): Boolean = fen in 0..MAX_AMOUNT_FEN
}
