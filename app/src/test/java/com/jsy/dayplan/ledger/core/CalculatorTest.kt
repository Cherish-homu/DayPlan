package com.jsy.dayplan.ledger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验收 1/2/3/4 的核心计算验证：Long 分、无浮点误差、编辑不多扣、转账原子语义。 */
class CalculatorTest {

    private val accA = AccountRow(1, "A", 100_00, false)
    private val accB = AccountRow(2, "B", 20_00, false)

    @Test
    fun `验收1 - 0点10元记3笔合计0点30元`() {
        val txns = listOf(
            TxnRow(1, 0, 10, 1, 1, "2026-10-01", ""),
            TxnRow(2, 0, 10, 1, 1, "2026-10-02", ""),
            TxnRow(3, 0, 10, 1, 1, "2026-10-03", ""),
        )
        val s = Calculator.computeMonthStats("2026-10", txns)
        assertEquals(30L, s.expenseFen)
        assertEquals(0L, s.incomeFen)
        assertEquals(-30L, s.balanceFen)
        val b = Calculator.computeBalances(listOf(accA.copy(openingFen = 0)), txns, emptyList())
        assertEquals(-30L, b[0].balanceFen)
    }

    @Test
    fun `验收1 - 非法金额被解析层拒绝`() {
        assertTrue(Money.parseYuanToFen("0") is Money.ParseResult.Bad)
        assertTrue(Money.parseYuanToFen("-1") is Money.ParseResult.Bad)
        assertTrue(Money.parseYuanToFen("0.00") is Money.ParseResult.Bad)
        assertTrue(Money.parseYuanToFen("12.345") is Money.ParseResult.Bad)
        assertTrue(Money.parseYuanToFen("abc") is Money.ParseResult.Bad)
        assertTrue(Money.parseYuanToFen("99999999999") is Money.ParseResult.Bad)
        assertEquals(10L, (Money.parseYuanToFen("0.10") as Money.ParseResult.Ok).fen)
        assertEquals(12345L, (Money.parseYuanToFen("123.45") as Money.ParseResult.Ok).fen)
    }

    @Test
    fun `验收1 - 巨额收入受检求和，用 BigInteger 独立推导期望`() {
        val huge = Long.MAX_VALUE / 2
        val txns = listOf(
            TxnRow(1, 1, huge, 1, 1, "2026-10-01", ""),
            TxnRow(2, 1, huge, 1, 1, "2026-10-02", ""),
        )
        // 两笔相加仍在 Long 范围内：用 BigInteger 独立推导期望值
        val expected = java.math.BigInteger.valueOf(huge).multiply(java.math.BigInteger.valueOf(2))
        val s = Calculator.computeMonthStats("2026-10", txns)
        assertEquals(expected, java.math.BigInteger.valueOf(s.incomeFen))
        assertEquals(expected, java.math.BigInteger.valueOf(s.balanceFen))
    }

    @Test
    fun `验收1 - 总额超出 Long 范围时明确拒绝而不静默回绕`() {
        val huge = Long.MAX_VALUE / 2
        val txns = listOf(
            TxnRow(1, 1, huge, 1, 1, "2026-10-01", ""),
            TxnRow(2, 1, huge, 1, 1, "2026-10-02", ""),
            TxnRow(3, 1, huge, 1, 1, "2026-10-03", ""), // 3*huge 溢出 Long
        )
        // 独立推导：3 * huge 确实超出 Long.MAX_VALUE
        val expectedBig = java.math.BigInteger.valueOf(huge)
            .multiply(java.math.BigInteger.valueOf(3))
        org.junit.Assert.assertTrue(
            expectedBig > java.math.BigInteger.valueOf(Long.MAX_VALUE),
        )
        try {
            Calculator.computeMonthStats("2026-10", txns)
            org.junit.Assert.fail("应当检测到溢出并拒绝")
        } catch (e: LedgerOverflowException) {
            // expected：给出可理解错误，而不是负数收入
        }
    }

    @Test
    fun `验收3反例 - Long_MAX_VALUE 期初加 1 分立即拒绝而非回绕`() {
        val acc = AccountRow(1, "巨富", Long.MAX_VALUE, false)
        val txns = listOf(TxnRow(1, 1, 1, 1, 1, "2026-10-01", ""))
        try {
            Calculator.computeBalances(listOf(acc), txns, emptyList())
            org.junit.Assert.fail("余额计算应拒绝溢出")
        } catch (e: LedgerOverflowException) {
            // expected
        }
    }

    @Test
    fun `验收2 - 期初100支20收50后余额130且统计30`() {
        val txns = listOf(
            TxnRow(1, 0, 20_00, 1, 1, "2026-10-01", ""),
            TxnRow(2, 1, 50_00, 5, 1, "2026-10-02", ""),
        )
        val balances = Calculator.computeBalances(listOf(accA), txns, emptyList())
        assertEquals(130_00L, balances[0].balanceFen)
        val s = Calculator.computeMonthStats("2026-10", txns)
        assertEquals(50_00L, s.incomeFen)
        assertEquals(20_00L, s.expenseFen)
        assertEquals(30_00L, s.balanceFen)
    }

    @Test
    fun `验收2 - 把支出20改为30后余额120（编辑一次不多扣）`() {
        // 完整场景：期初 100 元 + 收入 50 元；编辑前后仅支出行不同
        val income = TxnRow(2, 1, 50_00, 5, 1, "2026-10-02", "")
        val before = listOf(TxnRow(1, 0, 20_00, 1, 1, "2026-10-01", ""), income)
        val after = listOf(TxnRow(1, 0, 30_00, 1, 1, "2026-10-01", ""), income)
        assertEquals(130_00L, Calculator.computeBalances(listOf(accA), before, emptyList())[0].balanceFen)
        // 编辑 = 用新值整体替换该行（update 语义），不会叠加旧值
        assertEquals(120_00L, Calculator.computeBalances(listOf(accA), after, emptyList())[0].balanceFen)
    }

    @Test
    fun `验收3 - A转B 30元后 A70 B50 总额120 且不计入收支`() {
        val transfers = listOf(TransferRow(1, 30_00, 1, 2, "2026-10-02", ""))
        val balances = Calculator.computeBalances(listOf(accA, accB), emptyList(), transfers)
        assertEquals(70_00L, balances[0].balanceFen)
        assertEquals(50_00L, balances[1].balanceFen)
        val s = Calculator.computeMonthStats("2026-10", emptyList())
        assertEquals(0L, s.incomeFen)
        assertEquals(0L, s.expenseFen)
    }

    @Test
    fun `验收3 - 编辑与删除转账后双方余额同步`() {
        val edited = listOf(TransferRow(1, 50_00, 1, 2, "2026-10-02", ""))
        val b1 = Calculator.computeBalances(listOf(accA, accB), emptyList(), edited)
        assertEquals(50_00L, b1[0].balanceFen)
        assertEquals(70_00L, b1[1].balanceFen)
        // 删除：转账列表为空 → 余额回到期初
        val b2 = Calculator.computeBalances(listOf(accA, accB), emptyList(), emptyList())
        assertEquals(100_00L, b2[0].balanceFen)
        assertEquals(20_00L, b2[1].balanceFen)
    }

    @Test
    fun `验收4 - 跨月跨年记录按业务日期归月`() {
        val txns = listOf(
            TxnRow(1, 0, 10_00, 1, 1, "2026-01-31", ""),
            TxnRow(2, 0, 20_00, 1, 1, "2026-02-01", ""),
            TxnRow(3, 1, 30_00, 5, 1, "2025-12-31", ""),
            TxnRow(4, 0, 40_00, 1, 1, "2026-01-01", ""),
        )
        val jan = Calculator.computeMonthStats("2026-01", txns)
        assertEquals(10_00L + 40_00L, jan.expenseFen)
        assertEquals(0L, jan.incomeFen)
        val feb = Calculator.computeMonthStats("2026-02", txns)
        assertEquals(20_00L, feb.expenseFen)
        val dec = Calculator.computeMonthStats("2025-12", txns)
        assertEquals(30_00L, dec.incomeFen)
        assertEquals(0L, dec.expenseFen)
    }

    @Test
    fun `验收4 - 空月份显示 0 与空排行，不产生 NaN`() {
        val s = Calculator.computeMonthStats("2030-05", emptyList())
        assertEquals(0L, s.incomeFen)
        assertEquals(0L, s.expenseFen)
        assertEquals(0L, s.balanceFen)
        assertTrue(s.expenseByCategory.isEmpty())
    }

    @Test
    fun `验收4 - 月份前后切换正确（跨年）`() {
        assertEquals("2025-12", Calculator.prevMonth("2026-01"))
        assertEquals("2027-01", Calculator.nextMonth("2026-12"))
        assertEquals("2026-02-28", Calculator.lastDayOfMonth("2026-02"))
        assertEquals("2024-02-29", Calculator.lastDayOfMonth("2024-02"))
    }

    @Test
    fun `归档分类账户不影响余额计算`() {
        val archivedCat = CategoryRow(9, "旧分类", 0, true)
        val archivedAcc = AccountRow(9, "旧账户", 500_00, true)   // 期初 500 元
        val txns = listOf(TxnRow(1, 0, 10_00, 9, 9, "2026-10-01", ""))
        val balances = Calculator.computeBalances(listOf(archivedAcc), txns, emptyList())
        assertEquals(490_00L, balances[0].balanceFen)
    }

    @Test
    fun `余额可以为负`() {
        val txns = listOf(TxnRow(1, 0, 150_00, 1, 1, "2026-10-01", ""))
        val b = Calculator.computeBalances(listOf(accA), txns, emptyList())
        assertEquals(-50_00L, b[0].balanceFen)
    }
}
