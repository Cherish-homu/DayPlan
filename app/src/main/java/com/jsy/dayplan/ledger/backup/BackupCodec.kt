package com.jsy.dayplan.ledger.backup

import com.jsy.dayplan.ledger.data.AccountEntity
import com.jsy.dayplan.ledger.data.BudgetEntity
import com.jsy.dayplan.ledger.data.CategoryEntity
import com.jsy.dayplan.ledger.data.TransactionEntity
import com.jsy.dayplan.ledger.data.TransferEntity
import kotlinx.serialization.json.Json

/**
 * 备份序列化 / 反序列化。
 * 恢复流程：parse（畸形 JSON 在此失败）→ validate → 用户确认 → 事务内整库替换。
 */
object BackupCodec {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(b: BackupV1): String = json.encodeToString(BackupV1.serializer(), b)

    /** 解析失败抛 IllegalArgumentException（含原因），由调用方转为用户可读信息。 */
    fun decode(text: String): BackupV1 = try {
        json.decodeFromString(BackupV1.serializer(), text)
    } catch (e: Exception) {
        throw IllegalArgumentException("备份文件解析失败：${e.message}", e)
    }

    /** 生成恢复用的本地快照（在清空本地数据之前生成，保证可回退）。 */
    fun snapshot(
        accounts: List<AccountEntity>,
        categories: List<CategoryEntity>,
        txns: List<TransactionEntity>,
        transfers: List<TransferEntity>,
        budgets: List<BudgetEntity>,
        exportedAt: String,
    ): BackupV1 {
        val income = txns.filter { it.type == 1 }.sumOf { it.amountFen }
        val expense = txns.filter { it.type == 0 }.sumOf { it.amountFen }
        return BackupV1(
            schemaVersion = BACKUP_SCHEMA_VERSION,
            exportedAt = exportedAt,
            counts = BackupCounts(
                accounts.size, categories.size, txns.size, transfers.size, budgets.size,
            ),
            totals = BackupTotals(income, expense),
            accounts = accounts.map { BackupAccount(it.id, it.name, it.openingFen, it.archived) },
            categories = categories.map {
                BackupCategory(it.id, it.name, it.type, it.archived, it.sort)
            },
            transactions = txns.map {
                BackupTransaction(
                    it.id, it.type, it.amountFen, it.categoryId, it.accountId,
                    it.bizDate, it.bizTime, it.createdAtEpochMs, it.note,
                )
            },
            transfers = transfers.map {
                BackupTransfer(
                    it.id, it.amountFen, it.fromAccountId, it.toAccountId,
                    it.bizDate, it.bizTime, it.createdAtEpochMs, it.note,
                )
            },
            budgets = budgets.map { BackupBudget(it.month, it.budgetFen) },
        )
    }

    /** 校验通过后的备份 → 可插入的实体列表（保持原 ID）。 */
    fun toEntities(b: BackupV1): RestorePlan = RestorePlan(
        accounts = b.accounts.map { AccountEntity(it.id, it.name, it.openingFen, it.archived, 0) },
        categories = b.categories.map { CategoryEntity(it.id, it.name, it.type, it.archived, it.sort) },
        txns = b.transactions.map {
            TransactionEntity(
                it.id, it.type, it.amountFen, it.categoryId, it.accountId,
                it.bizDate, it.bizTime, it.createdAtEpochMs, it.note,
            )
        },
        transfers = b.transfers.map {
            TransferEntity(
                it.id, it.amountFen, it.fromAccountId, it.toAccountId,
                it.bizDate, it.bizTime, it.createdAtEpochMs, it.note,
            )
        },
        budgets = b.budgets.map { BudgetEntity(it.month, it.budgetFen) },
    )
}

data class RestorePlan(
    val accounts: List<AccountEntity>,
    val categories: List<CategoryEntity>,
    val txns: List<TransactionEntity>,
    val transfers: List<TransferEntity>,
    val budgets: List<BudgetEntity>,
)
