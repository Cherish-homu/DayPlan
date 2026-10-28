package com.jsy.dayplan.ledger.data

import androidx.room.withTransaction
import com.jsy.dayplan.ledger.backup.BackupCodec
import com.jsy.dayplan.ledger.backup.BackupValidator
import com.jsy.dayplan.ledger.backup.RestorePlan
import com.jsy.dayplan.ledger.backup.ValidateResult
import com.jsy.dayplan.ledger.core.AccountBalance
import com.jsy.dayplan.ledger.core.AccountRow
import com.jsy.dayplan.ledger.core.BudgetRow
import com.jsy.dayplan.ledger.core.Calculator
import com.jsy.dayplan.ledger.core.CategoryRow
import com.jsy.dayplan.ledger.core.LedgerRules
import com.jsy.dayplan.ledger.core.TransferRow
import com.jsy.dayplan.ledger.core.TxnRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.withLock
import java.io.File

/** 业务异常：保存/恢复失败时的用户可读原因。 */
class LedgerException(message: String) : Exception(message)

class LedgerRepository(
    private val db: LedgerDb,
    val dao: LedgerDao = db.dao(),
) {

    // ---------- Flow ----------
    val accounts: Flow<List<AccountEntity>> = dao.accountsFlow()
    val categories: Flow<List<CategoryEntity>> = dao.categoriesFlow()
    val txns: Flow<List<TransactionEntity>> = dao.txnsFlow()
    val transfers: Flow<List<TransferEntity>> = dao.transfersFlow()
    val budgets: Flow<List<BudgetEntity>> = dao.budgetsFlow()

    // ---------- 首次启动初始化 ----------
    suspend fun seedDefaultsIfEmpty() {
        if (dao.accountsOnce().isNotEmpty()) return
        db.withTransaction {
            val now = System.currentTimeMillis()
            val accounts = listOf("现金", "微信", "支付宝", "银行卡")
                .map { AccountEntity(name = it, createdAtEpochMs = now) }
            dao.insertAccountsAll(accounts)
            val expenseCats = listOf(
                "餐饮", "交通", "日用品", "购物", "学习", "娱乐", "订阅", "医疗", "其它",
            )
            val incomeCats = listOf("生活费", "工资/兼职", "奖学金", "其它")
            dao.insertCategoriesAll(expenseCats.mapIndexed { i, n ->
                CategoryEntity(name = n, type = 0, sort = i)
            })
            dao.insertCategoriesAll(incomeCats.mapIndexed { i, n ->
                CategoryEntity(name = n, type = 1, sort = i)
            })
        }
    }

    // ---------- 账目 ----------
    suspend fun saveTxn(t: TransactionEntity) {
        if (!LedgerRules.amountOk(t.amountFen)) {
            throw LedgerException(
                if (t.amountFen <= 0) "金额必须大于 0" else "金额超出上限",
            )
        }
        if (!LedgerRules.textOk(t.note)) {
            throw LedgerException("备注超长（最多 ${LedgerRules.MAX_TEXT_CHARS} 字），请精简后再保存")
        }
        db.withTransaction {
            if (dao.txnById(t.id) != null) {
                dao.updateTxn(t)   // 编辑：原账目只更新一次
            } else {
                dao.insertTxn(t)
            }
        }
    }

    suspend fun deleteTxn(id: Long) {
        db.withTransaction { dao.deleteTxn(id) }
    }

    suspend fun txnById(id: Long): TransactionEntity? = dao.txnById(id)

    // ---------- 转账 ----------
    suspend fun saveTransfer(t: TransferEntity) {
        if (!LedgerRules.amountOk(t.amountFen)) {
            throw LedgerException(
                if (t.amountFen <= 0) "转账金额必须大于 0" else "转账金额超出上限",
            )
        }
        if (!LedgerRules.textOk(t.note)) {
            throw LedgerException("转账备注超长（最多 ${LedgerRules.MAX_TEXT_CHARS} 字）")
        }
        if (t.fromAccountId == t.toAccountId) throw LedgerException("转出与转入账户不能相同")
        db.withTransaction {
            val names = dao.accountsOnce().associate { it.id to it.name }
            if (t.fromAccountId !in names || t.toAccountId !in names) {
                throw LedgerException("账户不存在")
            }
            if (dao.transferById(t.id) != null) dao.updateTransfer(t) else dao.insertTransfer(t)
        }
    }

    suspend fun deleteTransfer(id: Long) {
        db.withTransaction { dao.deleteTransfer(id) }
    }

    suspend fun transferById(id: Long): TransferEntity? = dao.transferById(id)

    // ---------- 账户 ----------
    suspend fun addAccount(name: String, openingFen: Long) {
        if (name.isBlank()) throw LedgerException("账户名称不能为空")
        if (!LedgerRules.textOk(name)) throw LedgerException("账户名称超长")
        if (!LedgerRules.openingOk(openingFen)) throw LedgerException("期初余额超出上限")
        db.withTransaction {
            dao.insertAccount(AccountEntity(name = name, openingFen = openingFen, createdAtEpochMs = System.currentTimeMillis()))
        }
    }

    suspend fun renameAccount(id: Long, newName: String) {
        if (newName.isBlank()) throw LedgerException("账户名称不能为空")
        if (!LedgerRules.textOk(newName)) throw LedgerException("账户名称超长（最多 ${LedgerRules.MAX_TEXT_CHARS} 字）")
        db.withTransaction {
            val a = dao.accountsOnce().firstOrNull { it.id == id }
                ?: throw LedgerException("账户不存在")
            dao.updateAccount(a.copy(name = newName))
        }
    }

    suspend fun setOpening(id: Long, openingFen: Long) {
        if (!LedgerRules.openingOk(openingFen)) throw LedgerException("期初余额超出上限")
        db.withTransaction {
            val a = dao.accountsOnce().firstOrNull { it.id == id }
                ?: throw LedgerException("账户不存在")
            dao.updateAccount(a.copy(openingFen = openingFen))
        }
    }

    /** 有账目的账户不能通过删除抹掉历史：只允许归档。 */
    suspend fun archiveAccount(id: Long) {
        db.withTransaction { dao.archiveAccount(id) }
    }

    suspend fun txnCountOfAccount(id: Long): Int = dao.txnCountOfAccount(id)
    suspend fun transferCountOfAccount(id: Long): Int = dao.transferCountOfAccount(id)

    // ---------- 分类 ----------
    suspend fun addCategory(name: String, type: Int) {
        if (name.isBlank()) throw LedgerException("分类名称不能为空")
        if (!LedgerRules.textOk(name)) throw LedgerException("分类名称超长")
        if (type !in 0..1) throw LedgerException("分类类型非法")
        db.withTransaction {
            val maxSort = dao.allCategories().filter { it.type == type }.maxOfOrNull { it.sort } ?: 0
            dao.insertCategory(CategoryEntity(name = name, type = type, sort = maxSort + 1))
        }
    }

    suspend fun renameCategory(id: Long, newName: String) {
        if (newName.isBlank()) throw LedgerException("分类名称不能为空")
        if (!LedgerRules.textOk(newName)) throw LedgerException("分类名称超长（最多 ${LedgerRules.MAX_TEXT_CHARS} 字）")
        db.withTransaction {
            val c = dao.allCategories().firstOrNull { it.id == id }
                ?: throw LedgerException("分类不存在")
            dao.updateCategory(c.copy(name = newName))
        }
    }

    /** 已有账目的分类被归档后仍可查看，不级联删除历史账目。 */
    suspend fun archiveCategory(id: Long) {
        db.withTransaction { dao.archiveCategory(id) }
    }

    // ---------- 预算 ----------
    suspend fun setBudget(month: String, budgetFen: Long) {
        if (!LedgerRules.budgetOk(budgetFen)) {
            throw LedgerException(
                if (budgetFen < 0) "预算不能为负" else "预算金额超出上限",
            )
        }
        db.withTransaction { dao.upsertBudget(BudgetEntity(month, budgetFen)) }
    }

    suspend fun budgetOfMonth(month: String): BudgetEntity? = dao.budgetOfMonth(month)

    // ---------- 一致性快照查询 ----------
    suspend fun loadCore(): CoreSnapshot = db.withTransaction {
        CoreSnapshot(
            accounts = dao.allAccounts().map { AccountRow(it.id, it.name, it.openingFen, it.archived) },
            categories = dao.allCategories().map { CategoryRow(it.id, it.name, it.type, it.archived) },
            txns = dao.allTxns().map { TxnRow(it.id, it.type, it.amountFen, it.categoryId, it.accountId, it.bizDate, it.note) },
            transfers = dao.allTransfers().map { TransferRow(it.id, it.amountFen, it.fromAccountId, it.toAccountId, it.bizDate, it.note) },
            budgets = dao.allBudgets().map { BudgetRow(it.month, it.budgetFen) },
        )
    }

    /** 一致性事务快照：普通导出与恢复前置快照共用同一读取口径。 */
    suspend fun backupSnapshot(exportedAt: String): com.jsy.dayplan.ledger.backup.BackupV1 =
        db.withTransaction {
            BackupCodec.snapshot(
                dao.allAccounts(),
                dao.allCategories(),
                dao.allTxns(),
                dao.allTransfers(),
                dao.allBudgets(),
                exportedAt = exportedAt,
            )
        }

    suspend fun balancesOf(snapshot: CoreSnapshot): List<AccountBalance> =
        Calculator.computeBalances(snapshot.accounts, snapshot.txns, snapshot.transfers)

    suspend fun currentBalances(): List<AccountBalance> =
        balancesOf(loadCore())

    // ---------- 恢复快照（真实落盘，可回退） ----------

    /** 恢复快照存储目录。测试可指向隔离目录；未配置则拒绝恢复。 */
    var snapshotDir: File? = null

    fun snapshots(): List<File> {
        val dir = snapshotDir ?: return emptyList()
        return dir.listFiles { f -> f.name.startsWith("restore_snapshot_") && f.name.endsWith(".json") }
            ?.sortedByDescending { it.name } ?: emptyList()
    }

    /**
     * 恢复流程（全程持 restoreMutex：并发触发导入时串行执行，快照/替换互不交错）：
     * 1) 事务外从当前数据库取一致性快照并序列化为有效备份；
     * 2) 原子写入独立快照文件（tmp → rename），回读并校验内容完整且一致；
     *    写入/回读失败 -> 抛异常，不进入任何清空/写入（原账本不变）；
     * 3) Room 事务内整库替换（保持原 ID）；
     * 4) 插入完成后重新查询 DAO，逐表核对实际库内数据与恢复计划一致，
     *    并重算余额/总额；不一致抛异常回滚。
     *
     * @param failAfter 测试注入：在第 N 个阶段后抛异常（0=清空后，1..5=各插入阶段后）。
     */
    private val restoreMutex = kotlinx.coroutines.sync.Mutex()

    suspend fun applyRestore(plan: RestorePlan, failAfter: Int? = null) =
        restoreMutex.withLock { applyRestoreLocked(plan, failAfter) }

    private suspend fun applyRestoreLocked(plan: RestorePlan, failAfter: Int?) {
        val dir = snapshotDir
            ?: throw LedgerException("未配置恢复快照目录，已停止恢复")
        // ---- 第一步：一致性快照 -> 原子落盘 -> 回读验证（全部在替换之前） ----
        val snapshot = db.withTransaction {
            BackupCodec.snapshot(
                dao.allAccounts(), dao.allCategories(), dao.allTxns(),
                dao.allTransfers(), dao.allBudgets(), exportedAt = "local-snapshot",
            )
        }
        when (val v = BackupValidator.validate(snapshot)) {
            is ValidateResult.Bad ->
                throw LedgerException("本地快照校验失败，已停止恢复：" + v.errors.first())
            is ValidateResult.Ok -> Unit
        }
        val snapshotText = BackupCodec.encode(snapshot)
        if (!dir.exists()) dir.mkdirs()
        // 文件名带递增序号：同一毫秒内连续恢复（双击/并发）也不会互相覆盖快照
        val base = "restore_snapshot_${System.currentTimeMillis()}"
        var finalFile = File(dir, "$base.json")
        var seq = 0
        while (finalFile.exists()) {
            seq++
            finalFile = File(dir, "${base}_$seq.json")
        }
        val tmp = File(dir, finalFile.name + ".tmp")
        try {
            tmp.writeText(snapshotText, Charsets.UTF_8)
            // 回读验证：必须能完整解析并再次通过校验，且内容一致
            val readBack = tmp.readText(Charsets.UTF_8)
            val obj = BackupCodec.decode(readBack)
            when (val v2 = BackupValidator.validate(obj)) {
                is ValidateResult.Bad ->
                    throw LedgerException("恢复快照回读校验失败，已停止恢复：" + v2.errors.first())
                is ValidateResult.Ok -> {
                    if (obj != snapshot) throw LedgerException("恢复快照回读内容不一致，已停止恢复")
                }
            }
            if (!tmp.renameTo(finalFile)) {
                throw LedgerException("恢复快照落盘失败，已停止恢复")
            }
        } catch (e: LedgerException) {
            tmp.delete()
            throw e
        } catch (e: Exception) {
            tmp.delete()
            throw LedgerException("恢复快照写入失败，已停止恢复：${e.message}")
        }

        // ---- 第二步：Room 事务内整库替换 + 重新查询核对 ----
        try {
            db.withTransaction {
                dao.clearTxns()
                dao.clearTransfers()
                dao.clearBudgets()
                dao.clearCategories()
                dao.clearAccounts()
                if (failAfter == 0) throw LedgerException("注入失败：清空后中止")

                dao.insertAccountsAll(plan.accounts)
                if (failAfter == 1) throw LedgerException("注入失败：账户插入后中止")
                dao.insertCategoriesAll(plan.categories)
                if (failAfter == 2) throw LedgerException("注入失败：分类插入后中止")
                dao.insertTxnsAll(plan.txns)
                if (failAfter == 3) throw LedgerException("注入失败：账目插入后中止")
                dao.insertTransfersAll(plan.transfers)
                if (failAfter == 4) throw LedgerException("注入失败：转账插入后中止")
                dao.insertBudgetsAll(plan.budgets)
                if (failAfter == 5) throw LedgerException("注入失败：预算插入后中止")

                // ---- 重新查询 DAO，逐表核对实际数据库内容（不是 plan 与自己比较）----
                val accDb = dao.allAccounts().sortedBy { it.id }
                val catDb = dao.allCategories().sortedBy { it.id }
                val txnDb = dao.allTxns().sortedBy { it.id }
                val trDb = dao.allTransfers().sortedBy { it.id }
                val budDb = dao.allBudgets().sortedBy { it.month }
                check(accDb == plan.accounts.sortedBy { it.id }) { "核对失败：账户实际写入与计划不一致" }
                check(catDb == plan.categories.sortedBy { it.id }) { "核对失败：分类实际写入与计划不一致" }
                check(txnDb == plan.txns.sortedBy { it.id }) { "核对失败：账目实际写入与计划不一致" }
                check(trDb == plan.transfers.sortedBy { it.id }) { "核对失败：转账实际写入与计划不一致" }
                check(budDb == plan.budgets.sortedBy { it.month }) { "核对失败：预算实际写入与计划不一致" }

                // 重算余额与总额（受检运算），证明恢复后的库在业务上自洽
                Calculator.computeBalances(
                    accDb.map { AccountRow(it.id, it.name, it.openingFen, it.archived) },
                    txnDb.map { TxnRow(it.id, it.type, it.amountFen, it.categoryId, it.accountId, it.bizDate, it.note) },
                    trDb.map { TransferRow(it.id, it.amountFen, it.fromAccountId, it.toAccountId, it.bizDate, it.note) },
                )
                val restoredIncome = txnDb.filter { it.type == 1 }
                    .fold(0L) { a, v -> Math.addExact(a, v.amountFen) }
                val restoredExpense = txnDb.filter { it.type == 0 }
                    .fold(0L) { a, v -> Math.addExact(a, v.amountFen) }
                check(restoredIncome == plan.txns.filter { it.type == 1 }
                    .fold(0L) { a, v -> Math.addExact(a, v.amountFen) }) { "核对失败：恢复后收入总额不一致" }
                check(restoredExpense == plan.txns.filter { it.type == 0 }
                    .fold(0L) { a, v -> Math.addExact(a, v.amountFen) }) { "核对失败：恢复后支出总额不一致" }
            }
        } catch (e: Exception) {
            // 事务回滚 = 原账本不变；快照文件保留供回退
            throw LedgerException(
                (e.message ?: "恢复失败") + "（事务已回滚，原数据未变；恢复前快照已保留）",
            )
        }
    }
}

data class CoreSnapshot(
    val accounts: List<AccountRow>,
    val categories: List<CategoryRow>,
    val txns: List<TxnRow>,
    val transfers: List<TransferRow>,
    val budgets: List<BudgetRow>,
)
