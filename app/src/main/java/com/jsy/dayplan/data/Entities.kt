package com.jsy.dayplan.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/** 日程：本地日期/时间表达（时区变化后仍是当地同一时刻）。 */
@Serializable
@Entity(
    tableName = "schedules",
    indices = [Index("startDate"), Index("endDate")],
)
data class ScheduleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val startDate: String,      // yyyy-MM-dd（起始业务日期）
    val endDate: String,        // yyyy-MM-dd（结束业务日期，支持跨日）
    val allDay: Boolean,
    val startTime: String?,     // "HH:mm"，全天为 null
    val endTime: String?,       // "HH:mm"；跨日结束可为次日 00:00（左闭右开）
    val location: String = "",
    val note: String = "",
    val category: Int = 3,      // 0学习 1工作 2生活 3其他
    val remindMinutesBefore: Int = 0,  // 0=无提醒；15/30/60/120
    val version: Int = 1,       // 提醒新鲜度核对
)

/** 待办：允许无日期；有截止时间才能到点提醒。 */
@Serializable
@Entity(
    tableName = "todos",
    indices = [Index("dueDate"), Index("done")],
)
data class TodoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val dueDate: String? = null,    // yyyy-MM-dd，可空
    val dueTime: String? = null,    // "HH:mm"，有 dueDate 才允许
    val priority: Int = 0,          // 0普通 1重要
    val note: String = "",
    val done: Boolean = false,
    val doneAtEpochMs: Long = 0,
    val version: Int = 1,
)

/** 倒数日：目标日期、类别、置顶、年度重复、提前提醒。 */
@Serializable
@Entity(tableName = "countdowns")
data class CountdownEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val targetDate: String,         // yyyy-MM-dd（原始目标；年度重复另算下一次）
    val category: Int = 4,          // 0考试 1截止 2生日 3纪念日 4其他
    val note: String = "",
    val colorArgb: Long = 0xFF2C6E63,
    val pinned: Boolean = false,
    val yearlyRepeat: Boolean = false,
    /** 是否启用提醒；与“提前 0/1/7 天”分开，0 天表示当天提醒。 */
    val remindEnabled: Boolean = false,
    val remindDaysBefore: Int = 0,  // 0当天 / 1 / 7（仅在 remindEnabled=true 时生效）
    val remindTime: String = "09:00",
    val originalDate: String = "",  // 年度重复时的原始日期（显示已满周年）
    val version: Int = 1,
)

/** 提醒投递去重：同一发生只通知一次。 */
@Entity(tableName = "reminder_occurrences", primaryKeys = ["occurrenceKey"])
data class ReminderOccurrenceEntity(
    val occurrenceKey: String,      // 如 "schedule-3-20261025-0900" / "countdown-7-20261101"
    val firedAtEpochMs: Long,
)

/** 错过提醒标记（超过 10 分钟宽限未补发）。 */
@Entity(tableName = "missed_reminders", primaryKeys = ["occurrenceKey"])
data class MissedReminderEntity(
    val occurrenceKey: String,
    val plannedAtEpochMs: Long,
    val detectedAtEpochMs: Long,
    val title: String,
)

/**
 * AI 批次提交事实来源：与业务写入同一 Room 事务。
 * batchId 主键保证并发/重试/重启后同一批次只提交一次；appliedJson 保存真实 ID、
 * 版本与最小撤销前值，JSON 文件只是可重建缓存。
 */
@Entity(tableName = "ai_commits")
data class AiCommitEntity(
    @PrimaryKey val batchId: String,
    val requestId: String,
    val sessionId: String,
    val dataGeneration: Int,
    val state: String,          // APPLIED / UNDONE
    val appliedJson: String,
    val actionCount: Int,
    val origin: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

/** AI 执行边界：清空对话/恢复数据时在事务内更新，提交时在同一事务核对。 */
@Entity(tableName = "ai_epoch")
data class AiEpochEntity(
    @PrimaryKey val id: Int = 1,
    val dataGeneration: Int,
    val sessionId: String,
)
