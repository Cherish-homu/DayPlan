package com.jsy.dayplan.backup

import com.jsy.dayplan.data.CountdownEntity
import com.jsy.dayplan.data.ScheduleEntity
import com.jsy.dayplan.data.TodoEntity
import kotlinx.serialization.json.Json

object BackupCodec {
    val json = Json { encodeDefaults = true }

    fun encode(b: BackupV1): String = json.encodeToString(BackupV1.serializer(), b)

    /** 解析失败抛 IllegalArgumentException（含原因）。 */
    fun decode(text: String): BackupV1 = try {
        json.decodeFromString(BackupV1.serializer(), text)
    } catch (e: Exception) {
        throw IllegalArgumentException("备份文件解析失败：${e.message}", e)
    }

    fun snapshot(
        schedules: List<ScheduleEntity>,
        todos: List<TodoEntity>,
        countdowns: List<CountdownEntity>,
        exportedAt: String,
        appVersion: String,
    ): BackupV1 = BackupV1(
        schemaVersion = BACKUP_SCHEMA_VERSION,
        exportedAt = exportedAt,
        appVersion = appVersion,
        counts = BackupCounts(schedules.size, todos.size, countdowns.size),
        schedules = schedules.map {
            BackupSchedule(
                it.id, it.title, it.startDate, it.endDate, it.allDay,
                it.startTime, it.endTime, it.location, it.note,
                it.category, it.remindMinutesBefore, it.version,
            )
        },
        todos = todos.map {
            BackupTodo(
                it.id, it.title, it.dueDate, it.dueTime, it.priority,
                it.note, it.done, it.doneAtEpochMs, it.version,
            )
        },
        countdowns = countdowns.map {
            BackupCountdown(
                it.id, it.title, it.targetDate, it.category, it.note,
                it.colorArgb, it.pinned, it.yearlyRepeat, it.remindEnabled,
                it.remindDaysBefore, it.remindTime, it.originalDate, it.version,
            )
        },
    )

    fun toEntities(b: BackupV1): RestorePlan = RestorePlan(
        schedules = b.schedules.map {
            ScheduleEntity(
                it.id, it.title, it.startDate, it.endDate, it.allDay,
                it.startTime, it.endTime, it.location, it.note,
                it.category, it.remindMinutesBefore, it.version,
            )
        },
        todos = b.todos.map {
            TodoEntity(
                it.id, it.title, it.dueDate, it.dueTime, it.priority,
                it.note, it.done, it.doneAtEpochMs, it.version,
            )
        },
        countdowns = b.countdowns.map {
            CountdownEntity(
                it.id, it.title, it.targetDate, it.category, it.note,
                it.colorArgb, it.pinned, it.yearlyRepeat, it.remindEnabled,
                it.remindDaysBefore, it.remindTime, it.originalDate, it.version,
            )
        },
    )
}

data class RestorePlan(
    val schedules: List<ScheduleEntity>,
    val todos: List<TodoEntity>,
    val countdowns: List<CountdownEntity>,
)
