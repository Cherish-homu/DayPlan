package com.jsy.dayplan.ledger.backup

import com.jsy.dayplan.ledger.backup.ValidateResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验收 5/6：备份-恢复往返一致性与各类畸形输入。 */
class BackupRoundTripTest {

    private fun sampleBackup(): BackupV1 {
        val accounts = listOf(
            BackupAccount(1, "现金", 100_00, false),
            BackupAccount(2, "银行卡", 20_00, false),
        )
        val categories = listOf(
            BackupCategory(1, "餐饮", 0, false, 1),
            BackupCategory(5, "生活费", 1, false, 1),
        )
        val transactions = listOf(
            BackupTransaction(1, 0, 20_00, 1, 1, "2026-10-01", "12:00", 1000, "午餐"),
            BackupTransaction(2, 1, 50_00, 5, 1, "2026-10-02", "09:30", 2000, "转生活费"),
        )
        val transfers = listOf(
            BackupTransfer(1, 30_00, 1, 2, "2026-10-03", "10:00", 3000, "挪到卡里"),
        )
        val budgets = listOf(BackupBudget("2026-10", 100_00))
        return BackupCodec.snapshot(
            accounts.map { com.jsy.dayplan.ledger.data.AccountEntity(it.id, it.name, it.openingFen, it.archived) },
            categories.map { com.jsy.dayplan.ledger.data.CategoryEntity(it.id, it.name, it.type, it.archived, it.sort) },
            transactions.map {
                com.jsy.dayplan.ledger.data.TransactionEntity(
                    it.id, it.type, it.amountFen, it.categoryId, it.accountId,
                    it.bizDate, it.bizTime, it.createdAtEpochMs, it.note,
                )
            },
            transfers.map {
                com.jsy.dayplan.ledger.data.TransferEntity(
                    it.id, it.amountFen, it.fromAccountId, it.toAccountId,
                    it.bizDate, it.bizTime, it.createdAtEpochMs, it.note,
                )
            },
            budgets.map { com.jsy.dayplan.ledger.data.BudgetEntity(it.month, it.budgetFen) },
            exportedAt = "2026-10-24 12:00:00",
        )
    }

    @Test
    fun `验收5 - 备份往返后业务字段逐条一致`() {
        val original = sampleBackup()
        val text = BackupCodec.encode(original)
        val restored = BackupCodec.decode(text)
        when (val v = BackupValidator.validate(restored)) {
            is ValidateResult.Bad ->
                throw AssertionError(v.errors.joinToString("\n"))
            is ValidateResult.Ok -> {
                val p = v.preview
                assertEquals(2, p.accounts)
                assertEquals(2, p.categories)
                assertEquals(2, p.transactions)
                assertEquals(1, p.transfers)
                assertEquals(1, p.budgets)
                assertEquals(50_00L, p.incomeFen)
                assertEquals(20_00L, p.expenseFen)
            }
        }
        // 逐条对比业务字段
        assertEquals(original.transactions, restored.transactions)
        assertEquals(original.transfers, restored.transfers)
        assertEquals(original.accounts, restored.accounts)
        assertEquals(original.categories, restored.categories)
        assertEquals(original.budgets, restored.budgets)
        // toEntities 保持原 ID
        val plan = BackupCodec.toEntities(restored)
        assertEquals(listOf(1L, 2L), plan.accounts.map { it.id })
        assertEquals(listOf(1L, 2L), plan.txns.map { it.id })
        assertEquals(30_00L, plan.transfers[0].amountFen)
    }

    @Test
    fun `验收6 - 损坏或截断 JSON 解析失败`() {
        val bad = listOf(
            "", "not json", "{\"schemaVersion\":1}", "null", "[]",
            BackupCodec.encode(sampleBackup()).dropLast(30),  // 截断
        )
        for (text in bad) {
            try {
                BackupCodec.decode(text)
                throw AssertionError("应当解析失败：$text")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun `验收6 - 不支持的 schemaVersion 被拒绝`() {
        val b = sampleBackup().copy(schemaVersion = 99)
        val r = BackupValidator.validate(b)
        assertTrue(r is ValidateResult.Bad)
        assertTrue((r as ValidateResult.Bad).errors.any { it.contains("schemaVersion") })
    }

    @Test
    fun `验收6 - 重复 ID 被拒绝`() {
        val b = sampleBackup().copy(
            transactions = sampleBackup().transactions +
                BackupTransaction(1, 0, 100, 1, 1, "2026-10-05", "12:00", 5, "重复ID"),
        )
        val r = BackupValidator.validate(b)
        assertTrue(r is ValidateResult.Bad)
        assertTrue((r as ValidateResult.Bad).errors.any { it.contains("重复 ID") })
    }

    @Test
    fun `验收6 - 引用不存在的账户或分类被拒绝`() {
        val b = sampleBackup().copy(
            transactions = listOf(
                BackupTransaction(1, 0, 100, 999, 1, "2026-10-01", "12:00", 1, ""),
                BackupTransaction(2, 1, 100, 1, 777, "2026-10-01", "12:00", 1, ""),
            ),
        )
        val r = BackupValidator.validate(b)
        assertTrue(r is ValidateResult.Bad)
        val errors = (r as ValidateResult.Bad).errors
        assertTrue(errors.any { it.contains("分类") && it.contains("不存在") })
        assertTrue(errors.any { it.contains("账户") && it.contains("不存在") })
    }

    @Test
    fun `验收6 - 数量与总额不一致视为损坏`() {
        val b = sampleBackup().copy(
            counts = sampleBackup().counts.copy(transactions = 99),
        )
        val r = BackupValidator.validate(b)
        assertTrue(r is ValidateResult.Bad)
        assertTrue(
            (r as ValidateResult.Bad).errors.any { it.contains("数量与清单不符") },
        )
    }

    @Test
    fun `验收6 - 零或负金额与转账同账户被拒绝`() {
        val b = sampleBackup().copy(
            transactions = listOf(
                BackupTransaction(1, 0, 0, 1, 1, "2026-10-01", "12:00", 1, ""),
                BackupTransaction(2, 1, -5, 1, 1, "2026-10-01", "12:00", 1, ""),
            ),
            transfers = listOf(
                BackupTransfer(1, 100, 1, 1, "2026-10-01", "12:00", 1, ""),
            ),
        )
        val r = BackupValidator.validate(b)
        assertTrue(r is ValidateResult.Bad)
        val errors = (r as ValidateResult.Bad).errors
        assertTrue(errors.any { it.contains("大于 0") })
        assertTrue(errors.any { it.contains("相同") })
    }

    @Test
    fun `验收4 - 空账本备份与零预算`() {
        val empty = BackupCodec.snapshot(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), "t")
        val text = BackupCodec.encode(empty)
        val v = BackupValidator.validate(BackupCodec.decode(text))
        assertTrue(v is ValidateResult.Ok)
        val p = (v as ValidateResult.Ok).preview
        assertEquals(0, p.transactions)
        assertEquals(0L, p.incomeFen)
        // 零预算是明确含义：预算 0 = 本月不计划支出（区别于未设置）
        val zb = sampleBackup().copy(budgets = listOf(BackupBudget("2026-11", 0)))
        val vz = BackupValidator.validate(zb)
        assertTrue(vz is ValidateResult.Ok)
    }

    @Test
    fun `验收6 - CSV 转义：逗号引号换行与公式注入`() {
        assertEquals("普通", com.jsy.dayplan.ledger.backup.CsvExporter.escape("普通"))
        assertEquals("\"a,b\"", com.jsy.dayplan.ledger.backup.CsvExporter.escape("a,b"))
        assertEquals("\"he said \"\"hi\"\"\"", com.jsy.dayplan.ledger.backup.CsvExporter.escape("he said \"hi\""))
        assertEquals("\"两\n行\"", com.jsy.dayplan.ledger.backup.CsvExporter.escape("两\n行"))
        // 公式注入防护：= + - @ 开头加单引号
        assertEquals("'=SUM(A1)", com.jsy.dayplan.ledger.backup.CsvExporter.escape("=SUM(A1)"))
        assertEquals("'+1", com.jsy.dayplan.ledger.backup.CsvExporter.escape("+1"))
        // 中文与 UTF-8 BOM
        val csv = com.jsy.dayplan.ledger.backup.CsvExporter.transactionsCsv(
            listOf("业务日期", "备注"), listOf(listOf("2026-10-24", "买菜,含逗号")),
        )
        assertTrue(csv.startsWith("\uFEFF"))
        assertTrue(csv.contains("\"买菜,含逗号\""))
    }

    @Test
    fun `续改 - 单笔金额超上限的备份被拒绝`() {
        val b = sampleBackup().copy(
            transactions = listOf(
                BackupTransaction(1, 0, com.jsy.dayplan.ledger.core.Money.MAX_SINGLE_FEN + 1, 1, 1,
                    "2026-10-01", "12:00", 1, "超单笔上限"),
            ),
        )
        val r = BackupValidator.validate(b)
        assertTrue(r is ValidateResult.Bad)
        assertTrue(
            (r as ValidateResult.Bad).errors.any { it.contains("单笔上限") },
        )
    }

    @Test
    fun `续改 - 不真实日历值被拒绝（2026-02-31 和 99-99 和 2026-13）`() {
        val badDate = sampleBackup().copy(
            transactions = listOf(
                BackupTransaction(1, 0, 100, 1, 1, "2026-02-31", "12:00", 1, ""),
            ),
        )
        assertTrue(BackupValidator.validate(badDate) is ValidateResult.Bad)

        val badTime = sampleBackup().copy(
            transactions = listOf(
                BackupTransaction(1, 0, 100, 1, 1, "2026-10-01", "99:99", 1, ""),
            ),
        )
        assertTrue(BackupValidator.validate(badTime) is ValidateResult.Bad)

        val badMonth = sampleBackup().copy(
            budgets = listOf(BackupBudget("2026-13", 100)),
        )
        assertTrue(BackupValidator.validate(badMonth) is ValidateResult.Bad)
    }

    @Test
    fun `续改 - 支出关联收入分类被拒绝`() {
        val b = sampleBackup().copy(
            transactions = listOf(
                // 分类 5 是收入分类，账目却是支出
                BackupTransaction(1, 0, 100, 5, 1, "2026-10-01", "12:00", 1, ""),
            ),
        )
        val r = BackupValidator.validate(b)
        assertTrue(r is ValidateResult.Bad)
        assertTrue(
            (r as ValidateResult.Bad).errors.any { it.contains("类型不匹配") },
        )
    }

    @Test
    fun `续改 - 期初余额溢出的备份被拒绝`() {
        val b = sampleBackup().copy(
            accounts = listOf(
                BackupAccount(1, "现金", Long.MAX_VALUE, false),
                BackupAccount(2, "银行卡", 20_00, false),
            ),
        )
        val r = BackupValidator.validate(b)
        assertTrue(r is ValidateResult.Bad)
        assertTrue(
            (r as ValidateResult.Bad).errors.any { it.contains("期初余额") },
        )
    }

    @Test
    fun `续改 - 边界合法备份仍被接受`() {
        val txns = listOf(
            BackupTransaction(1, 0, 1, 1, 1, "2024-02-29", "23:59", 1, "闰年合法日"),
        )
        val b = sampleBackup().copy(
            accounts = listOf(
                BackupAccount(1, "现金", -com.jsy.dayplan.ledger.core.Money.MAX_SINGLE_FEN, false),
                BackupAccount(2, "银行卡", com.jsy.dayplan.ledger.core.Money.MAX_SINGLE_FEN, false),
            ),
            transactions = txns,
            counts = sampleBackup().counts.copy(transactions = 1),
            totals = sampleBackup().totals.copy(incomeFen = 0, expenseFen = 1),
        )
        val r = BackupValidator.validate(b)
        assertTrue("应为合法：" + (if (r is ValidateResult.Bad) r.errors.toString() else ""),
            r is ValidateResult.Ok)
    }
}
