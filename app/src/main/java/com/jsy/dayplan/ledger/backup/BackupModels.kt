package com.jsy.dayplan.ledger.backup

import kotlinx.serialization.Serializable

/** 备份文件 schema 版本。当前唯一支持 v1。 */
const val BACKUP_SCHEMA_VERSION = 1

@Serializable
data class BackupAccount(val id: Long, val name: String, val openingFen: Long, val archived: Boolean)

@Serializable
data class BackupCategory(val id: Long, val name: String, val type: Int, val archived: Boolean, val sort: Int)

@Serializable
data class BackupTransaction(
    val id: Long,
    val type: Int,
    val amountFen: Long,
    val categoryId: Long,
    val accountId: Long,
    val bizDate: String,
    val bizTime: String,
    val createdAtEpochMs: Long,
    val note: String,
)

@Serializable
data class BackupTransfer(
    val id: Long,
    val amountFen: Long,
    val fromAccountId: Long,
    val toAccountId: Long,
    val bizDate: String,
    val bizTime: String,
    val createdAtEpochMs: Long,
    val note: String,
)

@Serializable
data class BackupBudget(val month: String, val budgetFen: Long)

@Serializable
data class BackupCounts(
    val accounts: Int,
    val categories: Int,
    val transactions: Int,
    val transfers: Int,
    val budgets: Int,
)

@Serializable
data class BackupTotals(val incomeFen: Long, val expenseFen: Long)

@Serializable
data class BackupV1(
    val schemaVersion: Int,
    val exportedAt: String,
    val counts: BackupCounts,
    val totals: BackupTotals,
    val accounts: List<BackupAccount>,
    val categories: List<BackupCategory>,
    val transactions: List<BackupTransaction>,
    val transfers: List<BackupTransfer>,
    val budgets: List<BackupBudget>,
)
