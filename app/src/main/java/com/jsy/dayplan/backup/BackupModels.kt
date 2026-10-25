package com.jsy.dayplan.backup

import kotlinx.serialization.Serializable

/** v2：倒数日增加 remindEnabled（0 天=当天提醒与“不提醒”分离）。v1 文件仍可导入。 */
const val BACKUP_SCHEMA_VERSION = 2
const val BACKUP_SCHEMA_VERSION_MIN = 1

@Serializable
data class BackupSchedule(
    val id: Long,
    val title: String,
    val startDate: String,
    val endDate: String,
    val allDay: Boolean,
    val startTime: String?,
    val endTime: String?,
    val location: String,
    val note: String,
    val category: Int,
    val remindMinutesBefore: Int,
    val version: Int,
)

@Serializable
data class BackupTodo(
    val id: Long,
    val title: String,
    val dueDate: String?,
    val dueTime: String?,
    val priority: Int,
    val note: String,
    val done: Boolean,
    val doneAtEpochMs: Long,
    val version: Int,
)

@Serializable
data class BackupCountdown(
    val id: Long,
    val title: String,
    val targetDate: String,
    val category: Int,
    val note: String,
    val colorArgb: Long,
    val pinned: Boolean,
    val yearlyRepeat: Boolean,
    /** v1 文件无此字段；解码默认 false，校验时按提醒天数无损升级。 */
    val remindEnabled: Boolean = false,
    val remindDaysBefore: Int,
    val remindTime: String,
    val originalDate: String,
    val version: Int,
)

@Serializable
data class BackupCounts(
    val schedules: Int,
    val todos: Int,
    val countdowns: Int,
)

@Serializable
data class BackupV1(
    val schemaVersion: Int,
    val exportedAt: String,
    val appVersion: String,
    val counts: BackupCounts,
    val schedules: List<BackupSchedule>,
    val todos: List<BackupTodo>,
    val countdowns: List<BackupCountdown>,
)
