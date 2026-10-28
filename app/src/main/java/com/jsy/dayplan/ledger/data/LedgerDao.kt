package com.jsy.dayplan.ledger.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface LedgerDao {

    // ---------- 账户 ----------
    @Query("SELECT * FROM accounts ORDER BY archived ASC, id ASC")
    fun accountsFlow(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts ORDER BY id ASC")
    suspend fun accountsOnce(): List<AccountEntity>

    @Insert
    suspend fun insertAccount(a: AccountEntity): Long

    @Update
    suspend fun updateAccount(a: AccountEntity)

    @Query("UPDATE accounts SET archived=1 WHERE id=:id")
    suspend fun archiveAccount(id: Long)

    @Query("SELECT COUNT(*) FROM transactions WHERE accountId=:id")
    suspend fun txnCountOfAccount(id: Long): Int

    @Query("SELECT COUNT(*) FROM transfers WHERE fromAccountId=:id OR toAccountId=:id")
    suspend fun transferCountOfAccount(id: Long): Int

    // ---------- 分类 ----------
    @Query("SELECT * FROM categories ORDER BY type ASC, sort ASC, id ASC")
    fun categoriesFlow(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE type=:type AND archived=0 ORDER BY sort ASC, id ASC")
    suspend fun activeCategories(type: Int): List<CategoryEntity>

    @Insert
    suspend fun insertCategory(c: CategoryEntity): Long

    @Update
    suspend fun updateCategory(c: CategoryEntity)

    @Query("UPDATE categories SET archived=1 WHERE id=:id")
    suspend fun archiveCategory(id: Long)

    // ---------- 账目 ----------
    @Query("SELECT * FROM transactions ORDER BY bizDate DESC, bizTime DESC, id DESC")
    fun txnsFlow(): Flow<List<TransactionEntity>>

    @Insert
    suspend fun insertTxn(t: TransactionEntity): Long

    @Update
    suspend fun updateTxn(t: TransactionEntity)

    @Query("DELETE FROM transactions WHERE id=:id")
    suspend fun deleteTxn(id: Long)

    @Query("SELECT * FROM transactions WHERE id=:id")
    suspend fun txnById(id: Long): TransactionEntity?

    @Query("SELECT COUNT(*) FROM transactions WHERE categoryId=:id")
    suspend fun txnCountOfCategory(id: Long): Int

    // ---------- 转账 ----------
    @Query("SELECT * FROM transfers ORDER BY bizDate DESC, bizTime DESC, id DESC")
    fun transfersFlow(): Flow<List<TransferEntity>>

    @Insert
    suspend fun insertTransfer(t: TransferEntity): Long

    @Update
    suspend fun updateTransfer(t: TransferEntity)

    @Query("DELETE FROM transfers WHERE id=:id")
    suspend fun deleteTransfer(id: Long)

    @Query("SELECT * FROM transfers WHERE id=:id")
    suspend fun transferById(id: Long): TransferEntity?

    // ---------- 预算 ----------
    @Query("SELECT * FROM budgets")
    fun budgetsFlow(): Flow<List<BudgetEntity>>

    @Query("SELECT * FROM budgets WHERE month=:month")
    suspend fun budgetOfMonth(month: String): BudgetEntity?

    @Upsert
    suspend fun upsertBudget(b: BudgetEntity)

    // ---------- 备份恢复（整库替换用，均在事务中调用） ----------
    @Query("SELECT * FROM accounts")
    suspend fun allAccounts(): List<AccountEntity>

    @Query("SELECT * FROM categories")
    suspend fun allCategories(): List<CategoryEntity>

    @Query("SELECT * FROM transactions")
    suspend fun allTxns(): List<TransactionEntity>

    @Query("SELECT * FROM transfers")
    suspend fun allTransfers(): List<TransferEntity>

    @Query("SELECT * FROM budgets")
    suspend fun allBudgets(): List<BudgetEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAccountsAll(list: List<AccountEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategoriesAll(list: List<CategoryEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTxnsAll(list: List<TransactionEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransfersAll(list: List<TransferEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBudgetsAll(list: List<BudgetEntity>): List<Long>

    @Query("DELETE FROM accounts")
    suspend fun clearAccounts()

    @Query("DELETE FROM categories")
    suspend fun clearCategories()

    @Query("DELETE FROM transactions")
    suspend fun clearTxns()

    @Query("DELETE FROM transfers")
    suspend fun clearTransfers()

    @Query("DELETE FROM budgets")
    suspend fun clearBudgets()
}
