package com.jsy.dayplan.ledger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 金额解析：上限元/分同口径、三类入口规则、任意输入不抛异常。 */
class MoneyTest {

    @Test
    fun `上限同口径 - 99,999,999_99 元通过、100,000,000_00 元拒绝`() {
        val ok = Money.parseYuanToFen("99999999.99")
        assertTrue(ok is Money.ParseResult.Ok)
        assertEquals(Money.MAX_SINGLE_FEN, (ok as Money.ParseResult.Ok).fen)

        val bad = Money.parseYuanToFen("100000000.00")
        assertTrue(bad is Money.ParseResult.Bad)
        assertTrue((bad as Money.ParseResult.Bad).reason.contains("上限"))
    }

    @Test
    fun `续改 - 40 位数字稳定返回 Bad 而不抛异常`() {
        val r = Money.parseYuanToFen("1".repeat(40))
        assertTrue(r is Money.ParseResult.Bad)
        // 科学计数法 / 全角 / 多点 / 长小数
        assertTrue(Money.parseYuanToFen("1e10") is Money.ParseResult.Bad)
        assertTrue(Money.parseYuanToFen("１２３") is Money.ParseResult.Bad)
        assertTrue(Money.parseYuanToFen("1.2.3") is Money.ParseResult.Bad)
        assertTrue(Money.parseYuanToFen("1.234") is Money.ParseResult.Bad)
    }

    @Test
    fun `普通收支 - 必须 大于 0 且拒绝负数`() {
        assertTrue(Money.parseYuanToFen("0") is Money.ParseResult.Bad)
        assertTrue(Money.parseYuanToFen("-50") is Money.ParseResult.Bad)
        assertEquals(50L, (Money.parseYuanToFen("0.50") as Money.ParseResult.Ok).fen)
    }

    @Test
    fun `期初余额 - 允许负值与显式 0，且上限一致`() {
        assertEquals(-5000L, (Money.parseOpeningYuanToFen("-50.00") as Money.ParseResult.Ok).fen)
        assertEquals(0L, (Money.parseOpeningYuanToFen("0") as Money.ParseResult.Ok).fen)
        assertEquals(0L, (Money.parseOpeningYuanToFen("0.00") as Money.ParseResult.Ok).fen)
        assertTrue(Money.parseOpeningYuanToFen("-99999999999") is Money.ParseResult.Bad)
        // 负上限边界
        assertEquals(-Money.MAX_SINGLE_FEN,
            (Money.parseOpeningYuanToFen("-99999999.99") as Money.ParseResult.Ok).fen)
    }

    @Test
    fun `预算 - 允许 0，拒绝负数`() {
        assertEquals(0L, (Money.parseBudgetYuanToFen("0") as Money.ParseResult.Ok).fen)
        assertTrue(Money.parseBudgetYuanToFen("-1") is Money.ParseResult.Bad)
        assertEquals(100_00L, (Money.parseBudgetYuanToFen("100") as Money.ParseResult.Ok).fen)
    }
}
