package com.jsy.dayplan.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow

data class DayNote(val date: String, val text: String, val updatedAt: Long)

/**
 * 按日期保存的便签/日记。独立数据库，不改变日程 Room schema 与实体 ID。
 * 空白内容视为删除；日期必须是合法 yyyy-MM-dd，长度上限 20000 字符。
 */
class DayNoteStore(context: Context, name: String? = "day_notes.db") :
    SQLiteOpenHelper(context, name, null, 1) {

    val revision = MutableStateFlow(0L)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE notes (" +
                "date TEXT PRIMARY KEY NOT NULL, " +
                "text TEXT NOT NULL, " +
                "updatedAt INTEGER NOT NULL)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun load(date: String): DayNote? {
        require(validDate(date)) { "日期格式须为 yyyy-MM-dd" }
        return readableDatabase.rawQuery(
            "SELECT date, text, updatedAt FROM notes WHERE date = ?", arrayOf(date),
        ).use { c ->
            if (c.moveToFirst()) DayNote(c.getString(0), c.getString(1), c.getLong(2)) else null
        }
    }

    /** 保存便签；空白文本删除该日便签并返回 null。 */
    @Synchronized
    fun save(date: String, text: String): DayNote? {
        require(validDate(date)) { "日期格式须为 yyyy-MM-dd" }
        val body = text.trim()
        require(body.length <= MAX_CHARS) { "便签不能超过 $MAX_CHARS 字符" }
        if (body.isEmpty()) {
            writableDatabase.delete("notes", "date = ?", arrayOf(date))
            revision.value += 1
            return null
        }
        val now = System.currentTimeMillis()
        writableDatabase.insertWithOnConflict(
            "notes",
            null,
            ContentValues().apply {
                put("date", date)
                put("text", body)
                put("updatedAt", now)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        revision.value += 1
        return DayNote(date, body, now)
    }

    private fun validDate(raw: String): Boolean = runCatching {
        LocalDate.parse(raw)
        raw.length == 10
    }.getOrDefault(false)

    companion object {
        const val MAX_CHARS = 20_000
    }
}
