package com.jsy.dayplan.ledger.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        AccountEntity::class, CategoryEntity::class,
        TransactionEntity::class, TransferEntity::class, BudgetEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class LedgerDb : RoomDatabase() {
    abstract fun dao(): LedgerDao

    companion object {
        @Volatile
        private var instance: LedgerDb? = null

        fun get(context: Context): LedgerDb =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LedgerDb::class.java,
                    "ledger.db",
                )
                    // 不使用破坏性重建：schema 变更必须提供迁移，保护用户数据
                    .build()
                    .also { instance = it }
            }
    }
}
