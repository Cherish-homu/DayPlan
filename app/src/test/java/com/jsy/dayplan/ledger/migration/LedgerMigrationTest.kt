package com.jsy.dayplan.ledger.migration

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jsy.dayplan.ledger.backup.BackupCodec
import com.jsy.dayplan.ledger.backup.BackupValidator
import com.jsy.dayplan.ledger.backup.ValidateResult
import com.jsy.dayplan.ledger.core.Calculator
import com.jsy.dayplan.ledger.core.LedgerFilter
import com.jsy.dayplan.ledger.core.Money
import com.jsy.dayplan.ledger.data.LedgerDb
import com.jsy.dayplan.ledger.data.LedgerException
import com.jsy.dayplan.ledger.data.LedgerRepository
import com.jsy.dayplan.ledger.data.TransactionEntity
import com.jsy.dayplan.ledger.data.TransferEntity
import com.jsy.dayplan.ledger.vm.pickDefaultAccountId
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 小账本旧 JSON → 新日序账本 的迁移回归：
 * 用 E:\PersonalLedger\tools 两份真实导出的样例（已复制到 src/test/resources/ledger），
 * 断言数量、总额、余额、统计、预算与交接文档附录 B 完全一致。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LedgerMigrationTest {

    private lateinit var db: LedgerDb
    private lateinit var repo: LedgerRepository
    private lateinit var snapDir: File

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, LedgerDb::class.java)
            .allowMainThreadQueries().build()
        repo = LedgerRepository(db)
        snapDir = File(ctx.cacheDir, "mig_" + System.nanoTime())
        repo.snapshotDir = snapDir
    }

    @After
    fun tearDown() {
        db.close()
        snapDir.deleteRecursively()
    }

    // ---------- 样例读取 ----------

    private fun resource(name: String): String =
        javaClass.getResourceAsStream("/ledger/$name")!!
            .readBytes().toString(Charsets.UTF_8)

    /** 样例 1：exported_v13_new.json —— 4 账目 / 1 转账 / 预算 10000。 */
    private fun sample1(): String = resource("exported_v13_new.json")

    /** 样例 2：exported_backup.json —— 3 笔账目、支出 30 分，第二组回归。 */
    private fun sample2(): String = resource("exported_backup.json")

    /** 造一份与样例不同的“现有账本”，用于验证导入 = 完整替换。 */
    private suspend fun seedOtherLedger() {
        val acc = repo.dao.insertAccount(
            com.jsy.dayplan.ledger.data.AccountEntity(name = "旧钱包", openingFen = 888, createdAtEpochMs = 7),
        )
        val cat = repo.dao.insertCategory(
            com.jsy.dayplan.ledger.data.CategoryEntity(name = "旧分类", type = 0, sort = 9),
        )
        repo.dao.insertTxn(
            TransactionEntity(
                type = 0, amountFen = 123, categoryId = cat, accountId = acc,
                bizDate = "2025-01-01", bizTime = "08:00", createdAtEpochMs = 7, note = "旧数据",
            ),
        )
        repo.dao.upsertBudget(com.jsy.dayplan.ledger.data.BudgetEntity("2025-01", 555))
    }

    private suspend fun balancesByName(): Map<String, Long> {
        val accounts = repo.dao.allAccounts()
        val bal = repo.currentBalances().associate { it.accountId to it.balanceFen }
        return accounts.associate { it.name to (bal[it.id] ?: 0L) }
    }

    private fun okOrThrow(text: String): com.jsy.dayplan.ledger.backup.BackupV1 {
        val b = BackupCodec.decode(text)
        return when (val v = BackupValidator.validate(b)) {
            is ValidateResult.Ok -> v.backup
            is ValidateResult.Bad -> error("样例应通过校验：" + v.errors.joinToString("\n"))
        }
    }

    // ---------- 样例 1：导入全链路 ----------

    @Test
    fun `迁移1 - 样例1解析校验后 counts totals 与附录B一致`() {
        val b = okOrThrow(sample1())
        assertEquals(4, b.counts.accounts)
        assertEquals(13, b.counts.categories)
        assertEquals(4, b.counts.transactions)
        assertEquals(1, b.counts.transfers)
        assertEquals(1, b.counts.budgets)
        assertEquals(0L, b.totals.incomeFen)
        assertEquals(530L, b.totals.expenseFen)
        // 校验器预览（导入确认框展示的数据）一致
        when (val v = BackupValidator.validate(BackupCodec.decode(sample1()))) {
            is ValidateResult.Ok -> {
                assertEquals(4, v.preview.accounts)
                assertEquals(13, v.preview.categories)
                assertEquals(4, v.preview.transactions)
                assertEquals(1, v.preview.transfers)
                assertEquals(1, v.preview.budgets)
                assertEquals(0L, v.preview.incomeFen)
                assertEquals(530L, v.preview.expenseFen)
            }
            is ValidateResult.Bad -> fail(v.errors.toString())
        }
    }

    @Test
    fun `迁移2 - 样例1整库替换后 余额统计预算与预期完全一致`() = runBlocking {
        seedOtherLedger()
        val plan = BackupCodec.toEntities(okOrThrow(sample1()))
        repo.applyRestore(plan)

        // 完整替换：旧数据不残留
        assertEquals(4, repo.dao.allAccounts().size)
        assertEquals(13, repo.dao.allCategories().size)
        assertEquals(4, repo.dao.allTxns().size)
        assertEquals(1, repo.dao.allTransfers().size)
        assertEquals(1, repo.dao.allBudgets().size)
        assertTrue(repo.dao.allAccounts().none { it.name == "旧钱包" })
        assertTrue(repo.dao.allBudgets().none { it.month == "2025-01" })

        // 余额（分）：现金 -3530、微信 +3000、支付宝 0、银行卡 0
        val bal = balancesByName()
        assertEquals(-3530L, bal["现金"])
        assertEquals(3000L, bal["微信"])
        assertEquals(0L, bal["支付宝"])
        assertEquals(0L, bal["银行卡"])

        // 2026-10 统计：收入 0、支出 530、结余 -530、餐饮 530
        val stats = Calculator.computeMonthStats(
            "2026-10",
            repo.dao.allTxns().map {
                com.jsy.dayplan.ledger.core.TxnRow(
                    it.id, it.type, it.amountFen, it.categoryId, it.accountId, it.bizDate, it.note,
                )
            },
        )
        assertEquals(0L, stats.incomeFen)
        assertEquals(530L, stats.expenseFen)
        assertEquals(-530L, stats.balanceFen)
        val catName = repo.dao.allCategories().associate { it.id to it.name }
        assertEquals(1, stats.expenseByCategory.size)
        assertEquals("餐饮", catName[stats.expenseByCategory[0].first])
        assertEquals(530L, stats.expenseByCategory[0].second)

        // 预算：10000 分，已用 530，剩余 9470
        val budget = repo.dao.budgetOfMonth("2026-10")
        assertEquals(10000L, budget?.budgetFen)
        val remain = budget!!.budgetFen - stats.expenseFen
        assertEquals(9470L, remain)
    }

    @Test
    fun `迁移3 - 样例1导入保留原ID归档备注与日期时间`() = runBlocking {
        val plan = BackupCodec.toEntities(okOrThrow(sample1()))
        repo.applyRestore(plan)
        val txns = repo.dao.allTxns().sortedBy { it.id }
        assertEquals(listOf(1L, 2L, 3L, 4L), txns.map { it.id })
        assertEquals("2026-10-24", txns[0].bizDate)
        assertEquals("07:41", txns[0].bizTime)
        assertEquals(1792827756798L, txns[0].createdAtEpochMs)
        val tr = repo.dao.allTransfers().single()
        assertEquals(1L, tr.id)
        assertEquals(3000L, tr.amountFen)
        assertEquals(1L, tr.fromAccountId)
        assertEquals(2L, tr.toAccountId)
        assertEquals("2026-10-24", tr.bizDate)
        assertEquals(1792828042326L, tr.createdAtEpochMs)
        // 分类 sort 原样保留（1..8 支出、0..3 收入）
        val cats = repo.dao.allCategories().sortedBy { it.id }
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L, 13L), cats.map { it.id })
        assertEquals(9, cats.count { it.type == 0 })
        assertEquals(4, cats.count { it.type == 1 })
        assertEquals(3, cats.first { it.name == "购物" && it.type == 0 }.sort)
    }

    @Test
    fun `迁移4 - 样例1导入是替换不是合并 再次导入不叠加`() = runBlocking {
        val plan = BackupCodec.toEntities(okOrThrow(sample1()))
        repo.applyRestore(plan)
        repo.applyRestore(plan)
        assertEquals(4, repo.dao.allTxns().size)
        assertEquals(1, repo.dao.allTransfers().size)
        assertEquals(4, repo.dao.allAccounts().size)
    }

    @Test
    fun `迁移5 - 样例2导入 3笔账目支出30分 预算正常`() = runBlocking {
        val plan = BackupCodec.toEntities(okOrThrow(sample2()))
        repo.applyRestore(plan)
        assertEquals(3, repo.dao.allTxns().size)
        assertEquals(1, repo.dao.allTransfers().size)
        val stats = Calculator.computeMonthStats(
            "2026-10",
            repo.dao.allTxns().map {
                com.jsy.dayplan.ledger.core.TxnRow(
                    it.id, it.type, it.amountFen, it.categoryId, it.accountId, it.bizDate, it.note,
                )
            },
        )
        assertEquals(30L, stats.expenseFen)
        assertEquals(10000L, repo.dao.budgetOfMonth("2026-10")?.budgetFen)
        // 余额：现金 0 - 30(支出) - 3000(转出) = -3030；微信 +3000
        val bal = balancesByName()
        assertEquals(-3030L, bal["现金"])
        assertEquals(3000L, bal["微信"])
    }

    // ---------- 导出往返与快照 ----------

    @Test
    fun `迁移6 - 样例导入后导出 再导入对象相等且数据不叠加`() = runBlocking {
        val plan = BackupCodec.toEntities(okOrThrow(sample1()))
        repo.applyRestore(plan)
        // 导出（与 VM 同口径：一致性事务快照）
        val exported = repo.backupSnapshot("2026-10-29 00:00:00")
        val text = BackupCodec.encode(exported)
        val decoded = BackupCodec.decode(text)
        assertEquals(exported, decoded)
        when (val v = BackupValidator.validate(decoded)) {
            is ValidateResult.Ok -> {
                assertEquals(530L, v.preview.expenseFen)
                assertEquals(4, v.preview.transactions)
            }
            is ValidateResult.Bad -> fail(v.errors.toString())
        }
        // 导出的 JSON 再导入另一个库，逐表一致
        val db2 = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), LedgerDb::class.java,
        ).allowMainThreadQueries().build()
        val repo2 = LedgerRepository(db2)
        repo2.snapshotDir = snapDir
        try {
            repo2.applyRestore(BackupCodec.toEntities(decoded))
            assertEquals(repo.dao.allTxns().sortedBy { it.id }, repo2.dao.allTxns().sortedBy { it.id })
            assertEquals(repo.dao.allAccounts().sortedBy { it.id }, repo2.dao.allAccounts().sortedBy { it.id })
        } finally {
            db2.close()
        }
    }

    @Test
    fun `迁移7 - 导入前快照真实落盘且可回读校验`() = runBlocking {
        seedOtherLedger()
        repo.applyRestore(BackupCodec.toEntities(okOrThrow(sample1())))
        val snaps = repo.snapshots()
        assertEquals(1, snaps.size)
        val obj = BackupCodec.decode(snaps[0].readText())
        when (val v = BackupValidator.validate(obj)) {
            is ValidateResult.Ok -> {
                // 快照 = 导入前的旧账本（真实回退点）
                assertEquals(1, v.preview.accounts)
                assertEquals(1, v.preview.transactions)
            }
            is ValidateResult.Bad -> fail("快照应为有效备份：" + v.errors.first())
        }
    }

    @Test
    fun `迁移8 - 注入失败事务回滚 原账本不变`() = runBlocking {
        seedOtherLedger()
        val beforeAccounts = repo.dao.allAccounts()
        val beforeTxns = repo.dao.allTxns()
        val plan = BackupCodec.toEntities(okOrThrow(sample1()))
        try {
            repo.applyRestore(plan, failAfter = 3)
            fail("注入失败应当抛出")
        } catch (e: LedgerException) {
            assertTrue(e.message!!.contains("回滚"))
        }
        assertEquals(beforeAccounts, repo.dao.allAccounts())
        assertEquals(beforeTxns, repo.dao.allTxns())
        // 快照仍保留，可回退
        assertTrue(repo.snapshots().isNotEmpty())
    }

    // ---------- 校验拒绝 ----------

    @Test
    fun `迁移9 - counts不符 totals不符 重复ID 外键缺失均被拒绝`() {
        val base = okOrThrow(sample1())

        val badCounts = base.copy(counts = base.counts.copy(transactions = 99))
        assertTrue(BackupValidator.validate(badCounts) is ValidateResult.Bad)

        val badTotals = base.copy(totals = base.totals.copy(expenseFen = 1))
        assertTrue(BackupValidator.validate(badTotals) is ValidateResult.Bad)

        val dupId = base.copy(
            transactions = base.transactions + base.transactions.first().copy(note = "重复"),
        )
        val dupResult = BackupValidator.validate(dupId)
        assertTrue(dupResult is ValidateResult.Bad)
        assertTrue((dupResult as ValidateResult.Bad).errors.any { it.contains("重复 ID") })

        val missingFk = base.copy(
            transactions = base.transactions.map { it.copy(accountId = 999) },
        )
        val fkResult = BackupValidator.validate(missingFk)
        assertTrue(fkResult is ValidateResult.Bad)
        assertTrue((fkResult as ValidateResult.Bad).errors.any { it.contains("不存在") })
    }

    @Test
    fun `迁移10 - 日序主备份 schemaVersion=2 的 JSON 被明确拒绝`() {
        // 日序主备份 BackupV1(schemaVersion=2) 的形状：schedules/todos/countdowns
        val dayplanBackup = """
            {"schemaVersion":2,"exportedAt":"2026-10-29 00:00:00",
             "counts":{"schedules":1,"todos":1,"countdowns":1},
             "schedules":[],"todos":[],"countdowns":[]}
        """.trimIndent()
        try {
            BackupCodec.decode(dayplanBackup)
            fail("日序主备份不应能被账本解码器解析")
        } catch (e: IllegalArgumentException) {
            // 期望：缺字段导致解析失败，导入流程把 decode 异常转成错误列表，原数据不动
            assertTrue(e.message!!.contains("解析失败"))
        }
        // 非法 schemaVersion 也不放行
        val wrongVersion = okOrThrow(sample1()).copy(schemaVersion = 2)
        val r = BackupValidator.validate(wrongVersion)
        assertTrue(r is ValidateResult.Bad)
        assertTrue((r as ValidateResult.Bad).errors.any { it.contains("schemaVersion") })
    }

    // ---------- 明细月过滤（修旧 bug） ----------

    @Test
    fun `迁移11 - 上月转账不混入当月明细`() {
        val month = "2026-10"
        val transfers = listOf(
            TransferEntity(
                id = 1, amountFen = 100, fromAccountId = 1, toAccountId = 2,
                bizDate = "2026-09-15", bizTime = "10:00", createdAtEpochMs = 1,
            ),
            TransferEntity(
                id = 2, amountFen = 200, fromAccountId = 1, toAccountId = 2,
                bizDate = "2026-10-05", bizTime = "10:00", createdAtEpochMs = 1,
            ),
            TransferEntity(
                id = 3, amountFen = 300, fromAccountId = 1, toAccountId = 2,
                bizDate = "2026-11-01", bizTime = "10:00", createdAtEpochMs = 1,
            ),
        )
        val visible = LedgerFilter.filterTransfers(transfers, month, accountId = null)
        assertEquals(listOf(2L), visible.map { it.id })
        // 月末边界：10-31 属于 10 月
        val monthEnd = LedgerFilter.filterTransfers(
            listOf(
                TransferEntity(
                    id = 9, amountFen = 1, fromAccountId = 1, toAccountId = 2,
                    bizDate = Calculator.lastDayOfMonth(month), bizTime = "23:59", createdAtEpochMs = 1,
                ),
            ),
            month, accountId = null,
        )
        assertEquals(1, monthEnd.size)
    }

    @Test
    fun `迁移12 - 账户筛选对转账转出转入双向生效`() {
        val month = "2026-10"
        val transfers = listOf(
            TransferEntity(
                id = 1, amountFen = 100, fromAccountId = 1, toAccountId = 2,
                bizDate = "2026-10-05", bizTime = "10:00", createdAtEpochMs = 1,
            ),
            TransferEntity(
                id = 2, amountFen = 200, fromAccountId = 3, toAccountId = 1,
                bizDate = "2026-10-06", bizTime = "10:00", createdAtEpochMs = 1,
            ),
            TransferEntity(
                id = 3, amountFen = 300, fromAccountId = 2, toAccountId = 3,
                bizDate = "2026-10-07", bizTime = "10:00", createdAtEpochMs = 1,
            ),
        )
        val forAcc1 = LedgerFilter.filterTransfers(transfers, month, accountId = 1L)
        assertEquals(listOf(1L, 2L), forAcc1.map { it.id })
        val forAcc2 = LedgerFilter.filterTransfers(transfers, month, accountId = 2L)
        assertEquals(listOf(1L, 3L), forAcc2.map { it.id })
        val forAcc3 = LedgerFilter.filterTransfers(transfers, month, accountId = 3L)
        assertEquals(listOf(2L, 3L), forAcc3.map { it.id })
    }

    @Test
    fun `迁移13 - 账目筛选 月内加分类账户关键词 与原口径一致`() {
        val month = "2026-10"
        fun txn(id: Long, date: String, cat: Long, acc: Long, note: String) =
            TransactionEntity(
                id = id, type = 0, amountFen = 1, categoryId = cat, accountId = acc,
                bizDate = date, bizTime = "12:00", createdAtEpochMs = 1, note = note,
            )
        val txns = listOf(
            txn(1, "2026-09-30", 1, 1, "上月"),
            txn(2, "2026-10-01", 1, 1, "奶茶"),
            txn(3, "2026-10-15", 2, 2, "车票"),
            txn(4, "2026-10-31", 1, 2, "奶茶续杯"),
            txn(5, "2026-11-01", 1, 1, "下月"),
        )
        assertEquals(listOf(2L, 3L, 4L), LedgerFilter.filterTxns(txns, month).map { it.id })
        assertEquals(listOf(2L, 4L), LedgerFilter.filterTxns(txns, month, categoryId = 1).map { it.id })
        assertEquals(listOf(3L, 4L), LedgerFilter.filterTxns(txns, month, accountId = 2).map { it.id })
        assertEquals(
            listOf(2L, 4L),
            LedgerFilter.filterTxns(txns, month, keyword = "奶茶").map { it.id },
        )
    }

    // ---------- 金额口径 ----------

    @Test
    fun `迁移14 - 样例金额界面展示口径 正确`() {
        assertEquals("¥5.30", Money.formatYuan(530L))
        assertEquals("¥-35.30", Money.formatYuan(-3530L))
        assertEquals("¥30.00", Money.formatYuan(3000L))
        assertEquals("¥94.70", Money.formatYuan(9470L))
    }

    // ---------- 验收意见回归（小修的测试覆盖） ----------

    @Test
    fun `迁移15 - 501字账户与分类改名被拒绝且原名保留`() = runBlocking {
        seedOtherLedger()
        val acc = repo.dao.allAccounts().single { it.name == "旧钱包" }
        val cat = repo.dao.allCategories().single { it.name == "旧分类" }
        val longName = "超".repeat(501)
        try {
            repo.renameAccount(acc.id, longName)
            fail("501 字账户名应被拒绝")
        } catch (e: LedgerException) {
            assertTrue(e.message!!.contains("超长"))
        }
        try {
            repo.renameCategory(cat.id, longName)
            fail("501 字分类名应被拒绝")
        } catch (e: LedgerException) {
            assertTrue(e.message!!.contains("超长"))
        }
        // 原名未变；账本仍能通过自身备份校验
        assertEquals("旧钱包", repo.dao.allAccounts().single { it.id == acc.id }.name)
        assertEquals("旧分类", repo.dao.allCategories().single { it.id == cat.id }.name)
        when (val v = BackupValidator.validate(repo.backupSnapshot("t"))) {
            is ValidateResult.Ok -> Unit
            is ValidateResult.Bad -> fail("正常账本应能通过备份校验：" + v.errors.first())
        }
        // 边界：恰好 500 字允许
        repo.renameAccount(acc.id, "名".repeat(500))
        assertEquals(500, repo.dao.allAccounts().single { it.id == acc.id }.name.length)
    }

    @Test
    fun `迁移16 - defaultAccountId 不选已归档账户`() {
        fun acc(id: Long, archived: Boolean) =
            com.jsy.dayplan.ledger.data.AccountEntity(id = id, name = "A$id", archived = archived)
        val accounts = listOf(acc(1, archived = true), acc(2, archived = false), acc(3, archived = false))
        // 上次使用的是未归档账户 → 沿用
        assertEquals(2L, pickDefaultAccountId(2L, accounts))
        // 上次使用的账户已被归档 → 落到第一个未归档账户
        assertEquals(2L, pickDefaultAccountId(1L, accounts))
        // 没有历史 → 第一个未归档账户
        assertEquals(2L, pickDefaultAccountId(-1L, accounts))
        // 全部归档 → null，页面显示「选择账户」，不会静默存进归档账户
        assertEquals(null, pickDefaultAccountId(-1L, listOf(acc(1, archived = true))))
    }

    @Test
    fun `迁移17 - 备注含schedules的正常账本备份可正常导入`() = runBlocking {
        seedOtherLedger()
        // 备注 JSON 形状（含 "schedules" 甚至 "schemaVersion":2 字样）不应误触发日序主备份拒绝
        val plan = BackupCodec.toEntities(okOrThrow(sample1())).let {
            it.copy(txns = it.txns.mapIndexed { i, t ->
                if (i == 0) t.copy(note = """{"schedules":[],"schemaVersion":2}""") else t
            })
        }
        val text = BackupCodec.encode(
            BackupCodec.snapshot(plan.accounts, plan.categories, plan.txns, plan.transfers, plan.budgets, "t"),
        )
        val b = okOrThrow(text) // 解码 + 校验必须通过
        repo.applyRestore(BackupCodec.toEntities(b))
        assertEquals(4, repo.dao.allTxns().size)
        assertEquals("""{"schedules":[],"schemaVersion":2}""", repo.dao.allTxns().first { it.id == 1L }.note)
        // 余额不受影响
        val bal = balancesByName()
        assertEquals(-3530L, bal["现金"])
    }

    @Test
    fun `迁移18 - 1000笔账目规模导入 数据层完整可用`() = runBlocking {
        // 数据层规模验证（Robolectric 下更大规模会显著拖慢测试进程，取 1000 ≈ 样例 250 倍）：
        // 校验、整库替换、抽查、导出一致性全部通过（UI 懒加载另行结构保证）
        val n = 1000
        val accounts = (1L..4L).map {
            com.jsy.dayplan.ledger.backup.BackupAccount(it, "账户$it", 0, false)
        }
        val categories = (1L..13L).map {
            com.jsy.dayplan.ledger.backup.BackupCategory(it, "分类$it", 0, false, (it - 1).toInt())
        }
        val txns = (1L..n).map { i ->
            com.jsy.dayplan.ledger.backup.BackupTransaction(
                id = i, type = 0, amountFen = 1 + (i % 100),
                categoryId = 1 + (i % 13), accountId = 1 + (i % 4),
                bizDate = "2026-%02d-%02d".format(1 + (i % 12).toInt(), 1 + (i % 28).toInt()),
                bizTime = "%02d:%02d".format((i % 24).toInt(), (i % 60).toInt()),
                createdAtEpochMs = i, note = "批量$i",
            )
        }
        val income = 0L
        val expense = txns.sumOf { it.amountFen }
        val backup = com.jsy.dayplan.ledger.backup.BackupV1(
            schemaVersion = 1, exportedAt = "t",
            counts = com.jsy.dayplan.ledger.backup.BackupCounts(4, 13, n, 0, 0),
            totals = com.jsy.dayplan.ledger.backup.BackupTotals(income, expense),
            accounts = accounts, categories = categories,
            transactions = txns, transfers = emptyList(), budgets = emptyList(),
        )
        when (val v = BackupValidator.validate(backup)) {
            is ValidateResult.Bad -> fail("大规模备份应通过校验：" + v.errors.take(3).toString())
            is ValidateResult.Ok -> assertEquals(n, v.preview.transactions)
        }
        repo.applyRestore(BackupCodec.toEntities(backup))
        assertEquals(n, repo.dao.allTxns().size)
        assertEquals(4, repo.dao.allAccounts().size)
        // 抽查首尾两笔
        assertEquals(1L, repo.dao.txnById(1L)?.id)
        assertEquals(n.toLong(), repo.dao.txnById(n.toLong())?.id)
        // 导出再校验一次（counts 与 totals 一致）
        when (val v = BackupValidator.validate(repo.backupSnapshot("t"))) {
            is ValidateResult.Ok -> {
                assertEquals(n, v.preview.transactions)
                assertEquals(expense, v.preview.expenseFen)
            }
            is ValidateResult.Bad -> fail("导出应通过校验：" + v.errors.first())
        }
    }
}
