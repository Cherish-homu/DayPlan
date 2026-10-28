package com.jsy.dayplan.ledger.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val openingFen: Long = 0,
    val archived: Boolean = false,
    val createdAtEpochMs: Long = 0,
)

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: Int,          // 0=支出 1=收入
    val archived: Boolean = false,
    val sort: Int = 0,
)

@Entity(
    tableName = "transactions",
    indices = [Index("bizDate"), Index("accountId"), Index("categoryId")],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: Int,              // 0=支出 1=收入
    val amountFen: Long,        // > 0
    val categoryId: Long,
    val accountId: Long,
    val bizDate: String,        // yyyy-MM-dd（业务日期）
    val bizTime: String,        // HH:mm
    val createdAtEpochMs: Long, // 记录创建时间，与业务日期分开保存
    val note: String = "",
)

@Entity(
    tableName = "transfers",
    indices = [Index("bizDate"), Index("fromAccountId"), Index("toAccountId")],
)
data class TransferEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amountFen: Long,
    val fromAccountId: Long,
    val toAccountId: Long,      // 必须与 fromAccountId 不同
    val bizDate: String,
    val bizTime: String,
    val createdAtEpochMs: Long,
    val note: String = "",
)

@Entity(tableName = "budgets")
data class BudgetEntity(
    @PrimaryKey val month: String,   // yyyy-MM
    val budgetFen: Long,             // 可为 0：明确设置的零预算（区别于未设置）
)
