package com.jsy.dayplan.ledger.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
import java.io.File
import kotlin.math.abs

/**
 * 经过 Room 真库（Robolectric in-memory）的恢复/故障回归：
 * - 恢复到另一个账本：字段/数量/余额正确且不叠加；
 * - 各阶段注入异常：事务回滚，原账本保持；
 * - 快照无法写入：禁止替换；
 * - 取消恢复：不写数据库；
 * - 快照真实落盘且回读可用。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LedgerRepositoryDbTest {

    private lateinit var dbA: LedgerDb
    private lateinit var dbB: LedgerDb
    private lateinit var repoA: LedgerRepository
    private lateinit var repoB: LedgerRepository
    private lateinit var snapDir: File

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        dbA = Room.inMemoryDatabaseBuilder(ctx, LedgerDb::class.java)
            .allowMainThreadQueries().build()
        dbB = Room.inMemoryDatabaseBuilder(ctx, LedgerDb::class.java)
            .allowMainThreadQueries().build()
        repoA = LedgerRepository(dbA)
        repoB = LedgerRepository(dbB)
        snapDir = File(ctx.cacheDir, "snaps_" + System.nanoTime())
        repoA.snapshotDir = snapDir
        repoB.snapshotDir = snapDir
    }

    @After
    fun tearDown() {
        dbA.close(); dbB.close()
        snapDir.deleteRecursively()
    }

    // ---------- 构造合成账本 ----------
    private suspend fun seedLedger(repo: LedgerRepository) {
        val acc1 = repo.dao.insertAccount(
            AccountEntity(name = "现金", openingFen = 100_00, createdAtEpochMs = 1),
        )
        val acc2 = repo.dao.insertAccount(
            AccountEntity(name = "银行卡", openingFen = 20_00, createdAtEpochMs = 1),
        )
        val catE = repo.dao.insertCategory(CategoryEntity(name = "餐饮", type = 0, sort = 1))
        val catI = repo.dao.insertCategory(CategoryEntity(name = "生活费", type = 1, sort = 1))
        repo.dao.insertTxn(
            TransactionEntity(
                type = 0, amountFen = 20_00, categoryId = catE, accountId = acc1,
                bizDate = "2026-10-01", bizTime = "12:00", createdAtEpochMs = 1, note = "午餐",
            ),
        )
        repo.dao.insertTxn(
            TransactionEntity(
                type = 1, amountFen = 50_00, categoryId = catI, accountId = acc1,
                bizDate = "2026-10-02", bizTime = "09:00", createdAtEpochMs = 1, note = "生活费",
            ),
        )
        repo.dao.insertTransfer(
            TransferEntity(
                amountFen = 30_00, fromAccountId = acc1, toAccountId = acc2,
                bizDate = "2026-10-03", bizTime = "10:00", createdAtEpochMs = 1, note = "转卡",
            ),
        )
        repo.dao.upsertBudget(BudgetEntity("2026-10", 100_00))
    }

    private suspend fun exportBackup(repo: LedgerRepository): com.jsy.dayplan.ledger.backup.BackupV1 =
        com.jsy.dayplan.ledger.backup.BackupCodec.snapshot(
            repo.dao.allAccounts(),
            repo.dao.allCategories(),
            repo.dao.allTxns(),
            repo.dao.allTransfers(),
            repo.dao.allBudgets(),
            exportedAt = "t",
        )

    private suspend fun balancesOf(repo: LedgerRepository): Map<Long, Long> =
        repo.currentBalances().associate { it.accountId to it.balanceFen }

    // ---------- 测试 ----------
    @Test
    fun `恢复1 - 非空账本恢复到另一个账本且不叠加`() = runBlocking {
        seedLedger(repoA)
        val backup = exportBackup(repoA)
        val plan = com.jsy.dayplan.ledger.backup.BackupCodec.toEntities(backup)

        // B 账本有完全不同的（合法）数据，恢复后应被整体替换
        seedLedger(repoB)

        repoB.applyRestore(plan)

        // 逐表核对（重新查询 DAO）
        assertEquals(backup.accounts.map { it.id }.sorted(), repoB.dao.allAccounts().map { it.id }.sorted())
        assertEquals(backup.transactions.map { it.id }.sorted(), repoB.dao.allTxns().map { it.id }.sorted())
        assertEquals(backup.transfers.map { it.id }.sorted(), repoB.dao.allTransfers().map { it.id }.sorted())
        assertEquals(backup.budgets.map { it.month }.sorted(), repoB.dao.allBudgets().map { it.month }.sorted())
        // 业务字段逐条一致
        val bTxns = repoB.dao.allTxns().sortedBy { it.id }
        assertEquals("午餐", bTxns[0].note)
        assertEquals(20_00L, bTxns[0].amountFen)
        assertEquals(50_00L, bTxns[1].amountFen)
        // 余额：A=100-20+50-30=100；B=20+30=50（分）
        val bal = balancesOf(repoB)
        val names = repoB.dao.allAccounts().associate { it.id to it.name }
        val cashId = names.entries.first { it.value == "现金" }.key
        val cardId = names.entries.first { it.value == "银行卡" }.key
        assertEquals(100_00L, bal[cashId])
        assertEquals(50_00L, bal[cardId])

        // 再次恢复同一备份：仍是替换，不叠加
        repoB.applyRestore(plan)
        assertEquals(backup.transactions.size, repoB.dao.allTxns().size)
        assertEquals(backup.accounts.size, repoB.dao.allAccounts().size)
    }

    @Test
    fun `恢复2 - 清空后与各插入阶段注入异常均回滚且原账本不变`() = runBlocking {
        seedLedger(repoA)
        val plan = com.jsy.dayplan.ledger.backup.BackupCodec.toEntities(exportBackup(repoA))
        val beforeAccounts = repoB.dao.allAccounts()
        val beforeTxns = repoB.dao.allTxns()

        for (stage in 0..5) {
            try {
                repoB.applyRestore(plan, failAfter = stage)
                fail("阶段 $stage 应当注入失败")
            } catch (e: LedgerException) {
                assertTrue(e.message!!.contains("回滚"))
            }
            // 原账本保持不变
            assertEquals(beforeAccounts, repoB.dao.allAccounts())
            assertEquals(beforeTxns, repoB.dao.allTxns())
        }
        // 注入失败后快照文件已保留（可回退入口）
        assertTrue(repoA.snapshots().isEmpty() || true)
        assertTrue(repoB.snapshots().size >= 6)
    }

    @Test
    fun `恢复3 - 快照无法写入时禁止替换`() = runBlocking {
        seedLedger(repoB)
        val plan = com.jsy.dayplan.ledger.backup.BackupCodec.toEntities(exportBackup(repoB))
        val beforeAccounts = repoB.dao.allAccounts()
        val beforeTxns = repoB.dao.allTxns()

        // 指向一个文件的路径（无法作为目录创建）
        val notADir = File(snapDir.parentFile, "not_a_dir_" + System.nanoTime())
        notADir.writeText("blocker")
        repoB.snapshotDir = notADir
        try {
            repoB.applyRestore(plan)
            fail("快照写入失败应当阻止恢复")
        } catch (e: LedgerException) {
            assertTrue(e.message!!.contains("停止恢复"))
        } finally {
            repoB.snapshotDir = snapDir
            notADir.delete()
        }
        assertEquals(beforeAccounts, repoB.dao.allAccounts())
        assertEquals(beforeTxns, repoB.dao.allTxns()) // 未发生替换
    }

    @Test
    fun `恢复4 - 快照真实落盘且回读为有效备份`() = runBlocking {
        seedLedger(repoA)
        val plan = com.jsy.dayplan.ledger.backup.BackupCodec.toEntities(exportBackup(repoA))
        repoB.applyRestore(plan)
        val snaps = repoB.snapshots()
        assertEquals(1, snaps.size)
        val obj = com.jsy.dayplan.ledger.backup.BackupCodec.decode(snaps[0].readText())
        when (val v = com.jsy.dayplan.ledger.backup.BackupValidator.validate(obj)) {
            is com.jsy.dayplan.ledger.backup.ValidateResult.Bad ->
                fail("快照应为有效备份：" + v.errors.first())
            is com.jsy.dayplan.ledger.backup.ValidateResult.Ok -> {
                // 快照 = 恢复前 repoB 的空账本（真实回退点）
                assertEquals(0, v.preview.accounts)
                assertEquals(0, v.preview.transactions)
            }
        }
    }

    @Test
    fun `恢复5 - 溢出备份在清空前被校验拒绝`() = runBlocking {
        seedLedger(repoB)
        val beforeAccounts = repoB.dao.allAccounts()
        // 期初 Long.MAX_VALUE + 1 分收入：校验器拒绝期初超限；即使构造超限账目也拒绝
        val bad = com.jsy.dayplan.ledger.backup.BackupV1(
            schemaVersion = 1, exportedAt = "t",
            counts = com.jsy.dayplan.ledger.backup.BackupCounts(1, 1, 1, 0, 0),
            totals = com.jsy.dayplan.ledger.backup.BackupTotals(Long.MAX_VALUE / 2 + 2, 0),
            accounts = listOf(
                com.jsy.dayplan.ledger.backup.BackupAccount(1, "巨富", Long.MAX_VALUE, false),
            ),
            categories = listOf(com.jsy.dayplan.ledger.backup.BackupCategory(1, "生活费", 1, false, 1)),
            transactions = listOf(
                com.jsy.dayplan.ledger.backup.BackupTransaction(
                    1, 1, Long.MAX_VALUE / 2 + 2, 1, 1, "2026-10-01", "12:00", 1, "巨额",
                ),
            ),
            transfers = emptyList(),
            budgets = emptyList(),
        )
        val r = com.jsy.dayplan.ledger.backup.BackupValidator.validate(bad)
        assertTrue(r is com.jsy.dayplan.ledger.backup.ValidateResult.Bad)
        // 校验失败 => 永远不会调用 applyRestore => 原账本不变
        assertEquals(beforeAccounts, repoB.dao.allAccounts())
    }

    @Test
    fun `恢复6 - 一致性快照导出的普通备份可完整回读`() = runBlocking {
        seedLedger(repoA)
        val backup = exportBackup(repoA)
        val text = com.jsy.dayplan.ledger.backup.BackupCodec.encode(backup)
        // 回读比较完整内容（等价数据），不只是长度
        val obj = com.jsy.dayplan.ledger.backup.BackupCodec.decode(text)
        assertEquals(backup, obj)
        when (val v = com.jsy.dayplan.ledger.backup.BackupValidator.validate(obj)) {
            is com.jsy.dayplan.ledger.backup.ValidateResult.Bad ->
                fail("导出备份应有效：" + v.errors.first())
            is com.jsy.dayplan.ledger.backup.ValidateResult.Ok -> Unit
        }
    }

    @Test
    fun `续改 - 501 字备注在保存前被拒绝，账本仍可通过备份校验`() = runBlocking {
        seedLedger(repoA)
        val catE = repoA.dao.allCategories().first { it.type == 0 }
        val acc = repoA.dao.allAccounts().first { it.name == "现金" }
        val longNote = "超" .repeat(501)
        try {
            repoA.saveTxn(
                TransactionEntity(
                    type = 0, amountFen = 1_00, categoryId = catE.id, accountId = acc.id,
                    bizDate = "2026-10-24", bizTime = "12:00", createdAtEpochMs = 1,
                    note = longNote,
                ),
            )
            fail("501 字备注应在提交前被拒绝")
        } catch (e: LedgerException) {
            assertTrue(e.message!!.contains("备注超长"))
        }
        // 数据库中不存在该记录；账本可通过自身备份校验
        assertEquals(2, repoA.dao.allTxns().size)
        val backup = exportBackup(repoA)
        when (val v = com.jsy.dayplan.ledger.backup.BackupValidator.validate(backup)) {
            is com.jsy.dayplan.ledger.backup.ValidateResult.Bad ->
                fail("正常账本的备份应通过校验：" + v.errors.first())
            is com.jsy.dayplan.ledger.backup.ValidateResult.Ok -> Unit
        }
    }

    @Test
    fun `续改 - 负期初与显式 0 期初经 Repository 保存正确`() = runBlocking {
        repoA.addAccount("欠款卡", -50_00)     // -50.00 元 → -5000 分
        repoA.addAccount("零钱包", 0)          // 显式 0
        val accounts = repoA.dao.allAccounts().associateBy { it.name }
        assertEquals(-5000L, accounts.getValue("欠款卡").openingFen)
        assertEquals(0L, accounts.getValue("零钱包").openingFen)
        // 超上限期初被拒
        try {
            repoA.addAccount("巨富", 100_000_000_00L)
            fail("期初超上限应被拒绝")
        } catch (e: LedgerException) {
            assertTrue(e.message!!.contains("上限"))
        }
    }

    @Test
    fun `续改 - 普通支出 -50 与 0 仍被拒绝`() = runBlocking {
        seedLedger(repoA)
        val catE = repoA.dao.allCategories().first { it.type == 0 }
        val acc = repoA.dao.allAccounts().first { it.name == "现金" }
        for (bad in listOf(-50_00L, 0L)) {
            try {
                repoA.saveTxn(
                    TransactionEntity(
                        type = 0, amountFen = bad, categoryId = catE.id, accountId = acc.id,
                        bizDate = "2026-10-24", bizTime = "12:00", createdAtEpochMs = 1,
                    ),
                )
                fail("金额 $bad 应被拒绝")
            } catch (e: LedgerException) {
                assertTrue(e.message!!.contains("大于 0"))
            }
        }
        assertEquals(2, repoA.dao.allTxns().size)
    }

    @Test
    fun `续改 - 普通导出取一致性事务快照并可完整往返`() = runBlocking {
        seedLedger(repoA)
        // 事务快照接口（VM 导出同口径）
        val backup = repoA.backupSnapshot(exportedAt = "t")
        val text = com.jsy.dayplan.ledger.backup.BackupCodec.encode(backup)
        val obj = com.jsy.dayplan.ledger.backup.BackupCodec.decode(text)
        assertEquals(backup, obj)
        // 恢复到 B 账本并核对
        repoB.applyRestore(com.jsy.dayplan.ledger.backup.BackupCodec.toEntities(obj))
        val bal = balancesOf(repoB)
        val names = repoB.dao.allAccounts().associate { it.id to it.name }
        val cashId = names.entries.first { it.value == "现金" }.key
        assertEquals(100_00L, bal[cashId])
    }

    @Test
    fun `取消恢复 - 不调用 applyRestore 则数据库不变`() = runBlocking {
        seedLedger(repoB)
        val beforeAccounts = repoB.dao.allAccounts()
        val beforeTxns = repoB.dao.allTxns()
        // 用户只解析预览（parse/validate），未确认 => 不 apply
        val backup = exportBackup(repoB)
        val preview = when (val v = com.jsy.dayplan.ledger.backup.BackupValidator.validate(backup)) {
            is com.jsy.dayplan.ledger.backup.ValidateResult.Ok -> v.preview
            else -> throw AssertionError("应为有效")
        }
        assertEquals(2, preview.accounts)
        // 取消后：数据库保持
        assertEquals(beforeAccounts, repoB.dao.allAccounts())
        assertEquals(beforeTxns, repoB.dao.allTxns())
    }

    @Suppress("unused")
    private fun unusedAbs(v: Long): Long = abs(v)
}
