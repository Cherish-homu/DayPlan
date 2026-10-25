package com.jsy.dayplan.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PlanDao {

    // ---------- 日程 ----------
    @Query("SELECT * FROM schedules ORDER BY startDate ASC, startTime ASC, id ASC")
    fun schedulesFlow(): Flow<List<ScheduleEntity>>

    @Query("SELECT * FROM schedules")
    suspend fun allSchedules(): List<ScheduleEntity>

    @Query("SELECT * FROM schedules WHERE id=:id")
    suspend fun scheduleById(id: Long): ScheduleEntity?

    @Insert
    suspend fun insertSchedule(s: ScheduleEntity): Long

    @Update
    suspend fun updateSchedule(s: ScheduleEntity)

    @Query("DELETE FROM schedules WHERE id=:id")
    suspend fun deleteSchedule(id: Long)

    // ---------- 待办 ----------
    @Query("SELECT * FROM todos ORDER BY done ASC, priority DESC, dueDate ASC, id ASC")
    fun todosFlow(): Flow<List<TodoEntity>>

    @Query("SELECT * FROM todos")
    suspend fun allTodos(): List<TodoEntity>

    @Query("SELECT * FROM todos WHERE id=:id")
    suspend fun todoById(id: Long): TodoEntity?

    @Insert
    suspend fun insertTodo(t: TodoEntity): Long

    @Update
    suspend fun updateTodo(t: TodoEntity)

    @Query("DELETE FROM todos WHERE id=:id")
    suspend fun deleteTodo(id: Long)

    // ---------- 倒数日 ----------
    @Query("SELECT * FROM countdowns ORDER BY pinned DESC, targetDate ASC, id ASC")
    fun countdownsFlow(): Flow<List<CountdownEntity>>

    @Query("SELECT * FROM countdowns")
    suspend fun allCountdowns(): List<CountdownEntity>

    @Query("SELECT * FROM countdowns WHERE id=:id")
    suspend fun countdownById(id: Long): CountdownEntity?

    @Insert
    suspend fun insertCountdown(c: CountdownEntity): Long

    @Update
    suspend fun updateCountdown(c: CountdownEntity)

    @Query("DELETE FROM countdowns WHERE id=:id")
    suspend fun deleteCountdown(id: Long)

    // ---------- 提醒去重 / 错过 ----------
    @Query("SELECT * FROM reminder_occurrences WHERE occurrenceKey=:key")
    suspend fun occurrence(key: String): ReminderOccurrenceEntity?

    @Upsert
    suspend fun insertOccurrence(o: ReminderOccurrenceEntity)

    /** 原子去重：已存在返回 -1（并发触发时只有一方得到非负值）。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOccurrenceIgnore(o: ReminderOccurrenceEntity): Long

    @Query("DELETE FROM reminder_occurrences WHERE occurrenceKey=:key")
    suspend fun deleteOccurrence(key: String)

    @Query("SELECT COUNT(*) FROM reminder_occurrences")
    suspend fun occurrenceCount(): Int

    @Query("DELETE FROM reminder_occurrences WHERE occurrenceKey LIKE :prefix || '%'")
    suspend fun clearOccurrencesByPrefix(prefix: String)

    @Query("SELECT * FROM missed_reminders")
    suspend fun allMissed(): List<MissedReminderEntity>

    @Upsert
    suspend fun insertMissed(m: MissedReminderEntity)

    @Query("DELETE FROM missed_reminders")
    suspend fun clearMissed()

    // ---------- 备份（事务内整库读取/替换） ----------
    @Query("DELETE FROM schedules")
    suspend fun clearSchedules()

    @Query("DELETE FROM todos")
    suspend fun clearTodos()

    @Query("DELETE FROM countdowns")
    suspend fun clearCountdowns()

    @Query("DELETE FROM reminder_occurrences")
    suspend fun clearOccurrences()

    @Query("DELETE FROM missed_reminders")
    suspend fun clearMissedAll()

    @Query("SELECT COUNT(*) FROM missed_reminders")
    suspend fun missedCount(): Int

    /** 三类业务记录合计（写入、导入、导出共用 10000 上限）。 */
    @Query(
        "SELECT (SELECT COUNT(*) FROM schedules) + (SELECT COUNT(*) FROM todos) + " +
            "(SELECT COUNT(*) FROM countdowns)",
    )
    suspend fun totalItemCount(): Int

    @Insert
    suspend fun insertSchedulesAll(list: List<ScheduleEntity>): List<Long>

    @Insert
    suspend fun insertTodosAll(list: List<TodoEntity>): List<Long>

    @Insert
    suspend fun insertCountdownsAll(list: List<CountdownEntity>): List<Long>

    // ---------- AI 提交事实与执行边界 ----------
    @Query("SELECT * FROM ai_commits WHERE batchId=:batchId")
    suspend fun aiCommit(batchId: String): AiCommitEntity?

    @Query("SELECT * FROM ai_commits WHERE sessionId=:sessionId ORDER BY createdAtEpochMs ASC")
    suspend fun aiCommitsForSession(sessionId: String): List<AiCommitEntity>

    /** 主键冲突返回 -1：并发提交同一批次时只有一方成功。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAiCommit(c: AiCommitEntity): Long

    @Query("UPDATE ai_commits SET state=:state, appliedJson=:appliedJson, updatedAtEpochMs=:updatedAt WHERE batchId=:batchId")
    suspend fun updateAiCommit(batchId: String, state: String, appliedJson: String, updatedAt: Long)

    @Query("SELECT * FROM ai_epoch WHERE id=1")
    suspend fun aiEpoch(): AiEpochEntity?

    @Upsert
    suspend fun upsertAiEpoch(e: AiEpochEntity)

    @Query("DELETE FROM ai_commits WHERE updatedAtEpochMs < :before")
    suspend fun pruneAiCommits(before: Long)
}
