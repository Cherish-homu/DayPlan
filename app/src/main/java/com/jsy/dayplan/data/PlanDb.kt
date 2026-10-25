package com.jsy.dayplan.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ScheduleEntity::class,
        TodoEntity::class,
        CountdownEntity::class,
        ReminderOccurrenceEntity::class,
        MissedReminderEntity::class,
        AiCommitEntity::class,
        AiEpochEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class PlanDb : RoomDatabase() {
    abstract fun dao(): PlanDao

    companion object {
        /**
         * v1 → v2：倒数日提醒从“0=关闭”改为显式开关 + 0/1/7 天。
         * 旧库中提前 1/7 天视为启用；旧 0 保持关闭，数据无损。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE countdowns ADD COLUMN remindEnabled INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "UPDATE countdowns SET remindEnabled = 1 " +
                        "WHERE remindDaysBefore IN (1, 7)",
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS ai_commits (" +
                        "batchId TEXT NOT NULL PRIMARY KEY, " +
                        "requestId TEXT NOT NULL, " +
                        "sessionId TEXT NOT NULL, " +
                        "dataGeneration INTEGER NOT NULL, " +
                        "state TEXT NOT NULL, " +
                        "appliedJson TEXT NOT NULL, " +
                        "actionCount INTEGER NOT NULL, " +
                        "origin TEXT NOT NULL, " +
                        "createdAtEpochMs INTEGER NOT NULL, " +
                        "updatedAtEpochMs INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS ai_epoch (" +
                        "id INTEGER NOT NULL PRIMARY KEY, " +
                        "dataGeneration INTEGER NOT NULL, " +
                        "sessionId TEXT NOT NULL)",
                )
            }
        }

        @Volatile
        private var instance: PlanDb? = null

        fun get(context: Context): PlanDb =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PlanDb::class.java,
                    "dayplan.db",
                )
                    // 不使用破坏性重建：schema 变更必须提供迁移
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }
    }
}
