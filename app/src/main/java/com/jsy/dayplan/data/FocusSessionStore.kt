package com.jsy.dayplan.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow

data class FocusSession(val id: String, val minutes: Int, val completedAt: Long)
data class FocusDay(val date: LocalDate, val count: Int)
data class FocusStats(
    val todayCount: Int = 0,
    val todayMinutes: Long = 0,
    val totalCount: Int = 0,
    val totalMinutes: Long = 0,
    val week: List<FocusDay> = emptyList(),
    val recent: List<FocusSession> = emptyList(),
)

/** 独立的本地专注账本；轮次主键去重，不改变既有日程数据库或实体 ID。 */
class FocusSessionStore(context: Context, name: String? = "focus_sessions.db") :
    SQLiteOpenHelper(context, name, null, 1) {
    val revision = MutableStateFlow(0L)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE sessions (id TEXT PRIMARY KEY NOT NULL, minutes INTEGER NOT NULL, completedAt INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX sessions_completedAt ON sessions(completedAt)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun record(session: FocusSession): Boolean {
        require(session.id.isNotBlank() && session.minutes in 1..180)
        val inserted = writableDatabase.insertWithOnConflict("sessions", null, ContentValues().apply {
            put("id", session.id)
            put("minutes", session.minutes)
            put("completedAt", session.completedAt)
        }, SQLiteDatabase.CONFLICT_IGNORE) != -1L
        if (inserted) revision.value += 1
        return inserted
    }

    @Synchronized
    fun snapshot(now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): FocusStats {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        fun start(day: LocalDate) = day.atStartOfDay(zone).toInstant().toEpochMilli()
        fun aggregate(from: Long?, until: Long? = null): Pair<Int, Long> {
            val where = if (from == null) "" else " WHERE completedAt >= ? AND completedAt < ?"
            val args = if (from == null) emptyArray() else arrayOf(from.toString(), until.toString())
            return readableDatabase.rawQuery("SELECT COUNT(*), COALESCE(SUM(minutes),0) FROM sessions$where", args).use {
                it.moveToFirst()
                it.getInt(0) to it.getLong(1)
            }
        }
        val total = aggregate(null)
        val daily = aggregate(start(today), start(today.plusDays(1)))
        val week = (6 downTo 0).map { offset ->
            val day = today.minusDays(offset.toLong())
            FocusDay(day, aggregate(start(day), start(day.plusDays(1))).first)
        }
        val recent = readableDatabase.rawQuery(
            "SELECT id, minutes, completedAt FROM sessions ORDER BY completedAt DESC, id DESC LIMIT 50", null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(FocusSession(cursor.getString(0), cursor.getInt(1), cursor.getLong(2)))
            }
        }
        return FocusStats(daily.first, daily.second, total.first, total.second, week, recent)
    }
}
