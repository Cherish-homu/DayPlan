package com.jsy.dayplan.reminder

import com.jsy.dayplan.data.AiCommitEntity
import com.jsy.dayplan.data.AiEpochEntity
import com.jsy.dayplan.data.CountdownEntity
import com.jsy.dayplan.data.MissedReminderEntity
import com.jsy.dayplan.data.PlanDao
import com.jsy.dayplan.data.ReminderOccurrenceEntity
import com.jsy.dayplan.data.ScheduleEntity
import com.jsy.dayplan.data.TodoEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** 测试用内存 DAO（独立实现，不依赖 Room）。 */
class FakePlanDao(
    schedules: List<ScheduleEntity> = emptyList(),
    todos: List<TodoEntity> = emptyList(),
    countdowns: List<CountdownEntity> = emptyList(),
) : PlanDao {
    private val _schedules = MutableStateFlow(schedules.associateBy { it.id }.toMutableMap())
    private val _todos = MutableStateFlow(todos.associateBy { it.id }.toMutableMap())
    private val _countdowns = MutableStateFlow(countdowns.associateBy { it.id }.toMutableMap())
    private val occurrences = linkedMapOf<String, ReminderOccurrenceEntity>()
    private val missed = linkedMapOf<String, MissedReminderEntity>()
    private val aiCommits = linkedMapOf<String, AiCommitEntity>()
    private var aiEpoch: AiEpochEntity? = null

    override fun schedulesFlow(): Flow<List<ScheduleEntity>> =
        kotlinx.coroutines.flow.flowOf(_schedules.value.values.sortedBy { it.id })

    override suspend fun allSchedules(): List<ScheduleEntity> = _schedules.value.values.sortedBy { it.id }
    override suspend fun scheduleById(id: Long): ScheduleEntity? = _schedules.value[id]

    override suspend fun insertSchedule(s: ScheduleEntity): Long {
        _schedules.value[s.id] = s; return s.id
    }

    override suspend fun updateSchedule(s: ScheduleEntity) { _schedules.value[s.id] = s }

    override suspend fun deleteSchedule(id: Long) { _schedules.value.remove(id) }

    override fun todosFlow(): Flow<List<TodoEntity>> =
        kotlinx.coroutines.flow.flowOf(_todos.value.values.sortedBy { it.id })

    override suspend fun allTodos(): List<TodoEntity> = _todos.value.values.sortedBy { it.id }
    override suspend fun todoById(id: Long): TodoEntity? = _todos.value[id]

    override suspend fun insertTodo(t: TodoEntity): Long { _todos.value[t.id] = t; return t.id }

    override suspend fun updateTodo(t: TodoEntity) { _todos.value[t.id] = t }

    override suspend fun deleteTodo(id: Long) { _todos.value.remove(id) }

    override fun countdownsFlow(): Flow<List<CountdownEntity>> =
        kotlinx.coroutines.flow.flowOf(_countdowns.value.values.sortedBy { it.id })

    override suspend fun allCountdowns(): List<CountdownEntity> = _countdowns.value.values.sortedBy { it.id }
    override suspend fun countdownById(id: Long): CountdownEntity? = _countdowns.value[id]

    override suspend fun insertCountdown(c: CountdownEntity): Long { _countdowns.value[c.id] = c; return c.id }

    override suspend fun updateCountdown(c: CountdownEntity) { _countdowns.value[c.id] = c }

    override suspend fun deleteCountdown(id: Long) { _countdowns.value.remove(id) }

    override suspend fun occurrence(key: String): ReminderOccurrenceEntity? = occurrences[key]

    override suspend fun insertOccurrence(o: ReminderOccurrenceEntity) { occurrences[o.occurrenceKey] = o }

    override suspend fun insertOccurrenceIgnore(o: ReminderOccurrenceEntity): Long =
        if (occurrences.containsKey(o.occurrenceKey)) -1L
        else { occurrences[o.occurrenceKey] = o; 1L }

    override suspend fun deleteOccurrence(key: String) { occurrences.remove(key) }

    override suspend fun occurrenceCount(): Int = occurrences.size

    override suspend fun clearOccurrencesByPrefix(prefix: String) {
        occurrences.keys.removeAll { it.startsWith(prefix) }
    }

    override suspend fun allMissed(): List<MissedReminderEntity> = missed.values.toList()

    override suspend fun insertMissed(m: MissedReminderEntity) { missed[m.occurrenceKey] = m }

    override suspend fun clearMissed() { missed.clear() }

    override suspend fun clearSchedules() { _schedules.value.clear() }

    override suspend fun clearTodos() { _todos.value.clear() }

    override suspend fun clearCountdowns() { _countdowns.value.clear() }

    override suspend fun clearOccurrences() { occurrences.clear() }

    override suspend fun clearMissedAll() { missed.clear() }

    override suspend fun missedCount(): Int = missed.size

    override suspend fun totalItemCount(): Int =
        _schedules.value.size + _todos.value.size + _countdowns.value.size

    override suspend fun insertSchedulesAll(list: List<ScheduleEntity>): List<Long> {
        list.forEach { _schedules.value[it.id] = it }
        return list.map { it.id }
    }

    override suspend fun insertTodosAll(list: List<TodoEntity>): List<Long> {
        list.forEach { _todos.value[it.id] = it }
        return list.map { it.id }
    }

    override suspend fun insertCountdownsAll(list: List<CountdownEntity>): List<Long> {
        list.forEach { _countdowns.value[it.id] = it }
        return list.map { it.id }
    }

    override suspend fun aiCommit(batchId: String): AiCommitEntity? = aiCommits[batchId]

    override suspend fun aiCommitsForSession(sessionId: String): List<AiCommitEntity> =
        aiCommits.values.filter { it.sessionId == sessionId }.sortedBy { it.createdAtEpochMs }

    override suspend fun insertAiCommit(c: AiCommitEntity): Long =
        if (aiCommits.containsKey(c.batchId)) -1L
        else { aiCommits[c.batchId] = c; 1L }

    override suspend fun updateAiCommit(
        batchId: String,
        state: String,
        appliedJson: String,
        updatedAt: Long,
    ) {
        aiCommits[batchId]?.let {
            aiCommits[batchId] = it.copy(state = state, appliedJson = appliedJson, updatedAtEpochMs = updatedAt)
        }
    }

    override suspend fun aiEpoch(): AiEpochEntity? = aiEpoch

    override suspend fun upsertAiEpoch(e: AiEpochEntity) { aiEpoch = e }

    override suspend fun pruneAiCommits(before: Long) {
        aiCommits.values.removeAll { it.updatedAtEpochMs < before }
    }

}

/** 组装协调器的便捷入口。 */
object FakeDaoHolder {
    fun create(
        schedules: List<ScheduleEntity> = emptyList(),
        todos: List<TodoEntity> = emptyList(),
        countdowns: List<CountdownEntity> = emptyList(),
    ): PlanDao = FakePlanDao(schedules, todos, countdowns)
}
