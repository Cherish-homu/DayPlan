package com.jsy.dayplan.data

import androidx.room.withTransaction
import com.jsy.dayplan.backup.BackupCodec
import com.jsy.dayplan.backup.BackupV1
import com.jsy.dayplan.backup.RestorePlan
import com.jsy.dayplan.core.PlanRules
import java.io.File
import kotlinx.coroutines.flow.Flow

/** 业务异常：用户可读原因。 */
class PlanException(message: String) : Exception(message)

/**
 * 数据仓库：写入入口统一校验与规范化（与备份校验共用 PlanRules）、提醒协调由调用方
 * （ViewModel/Receiver）通过 ReminderCoordinator 完成；此处负责持久化、
 * 一致性事务快照与恢复（先落盘可回退快照 → 事务替换 → 重新查询核对）。
 */
class PlanRepository(
    private val db: PlanDb,
    val dao: PlanDao = db.dao(),
) {
    /** 数据变更后回调（由 App 注入：刷新桌面小组件）。不在 Room 事务内执行。 */
    var onDataChanged: (() -> Unit)? = null

    private fun notifyChanged() {
        // 组件刷新等后续动作失败不能影响已提交的业务事实
        runCatching { onDataChanged?.invoke() }
    }

    // ---------- Flow ----------
    val schedules: Flow<List<ScheduleEntity>> = dao.schedulesFlow()
    val todos: Flow<List<TodoEntity>> = dao.todosFlow()
    val countdowns: Flow<List<CountdownEntity>> = dao.countdownsFlow()

    // ---------- 日程 ----------
    /** 新增返回带真实自增 ID 的实体；编辑返回版本 +1 的实体（调用方必须使用返回值）。 */
    suspend fun saveSchedule(input: ScheduleEntity): ScheduleEntity {
        val s = normalizeSchedule(input)
        validateSchedule(s)
        val saved = db.withTransaction {
            val old = dao.scheduleById(s.id)
            if (old != null) {
                val updated = s.copy(id = old.id, version = old.version + 1)
                dao.updateSchedule(updated)
                updated
            } else {
                ensureCapacityForNewRow()
                val created = s.copy(id = 0, version = 1)
                created.copy(id = dao.insertSchedule(created))
            }
        }
        notifyChanged()
        return saved
    }

    private fun normalizeSchedule(s: ScheduleEntity): ScheduleEntity {
        val st = s.startTime?.let {
            PlanRules.normalizeTime(it) ?: throw PlanException("开始时间非法")
        }
        val et = s.endTime?.let {
            PlanRules.normalizeTime(it) ?: throw PlanException("结束时间非法")
        }
        return s.copy(
            title = s.title.trim(),
            location = s.location.trim(),
            startTime = st,
            endTime = et,
        )
    }

    fun validateSchedule(s: ScheduleEntity) {
        if (!PlanRules.titleOk(s.title)) {
            throw PlanException("标题需 1–${PlanRules.TITLE_MAX} 字符")
        }
        if (!PlanRules.noteOk(s.note)) throw PlanException("备注超长（最多 ${PlanRules.NOTE_MAX} 字）")
        if (!PlanRules.locationOk(s.location)) throw PlanException("地点超长（最多 ${PlanRules.LOCATION_MAX} 字）")
        val start = parseDate(s.startDate) ?: throw PlanException("开始日期非法")
        val end = parseDate(s.endDate) ?: throw PlanException("结束日期非法")
        if (!PlanRules.dateOk(start) || !PlanRules.dateOk(end)) throw PlanException("日期须在 2000–2100 年")
        if (end.isBefore(start)) throw PlanException("结束日期不能早于开始日期")
        if (!s.allDay) {
            val sm = toMinutes(s.startTime ?: throw PlanException("请填写开始时间"))
                ?: throw PlanException("开始时间非法")
            val em = toMinutes(s.endTime ?: throw PlanException("请填写结束时间"))
                ?: throw PlanException("结束时间非法")
            if (s.startDate == s.endDate && em <= sm) throw PlanException("开始必须早于结束")
        }
        if (s.category !in 0..3) throw PlanException("日程分类非法")
        if (s.remindMinutesBefore !in listOf(0, 15, 30, 60, 120)) {
            throw PlanException("提前提醒仅支持 0/15/30/60/120 分钟")
        }
    }

    suspend fun deleteSchedule(id: Long) {
        db.withTransaction {
            dao.deleteSchedule(id)
            dao.clearOccurrencesByPrefix("sched-$id-")
        }
        notifyChanged()
    }

    // ---------- 待办 ----------
    suspend fun saveTodo(input: TodoEntity): TodoEntity {
        val t = normalizeTodo(input)
        validateTodo(t)
        val saved = db.withTransaction {
            val old = dao.todoById(t.id)
            if (old != null) {
                val updated = t.copy(id = old.id, version = old.version + 1)
                dao.updateTodo(updated)
                updated
            } else {
                ensureCapacityForNewRow()
                val created = t.copy(id = 0, version = 1)
                created.copy(id = dao.insertTodo(created))
            }
        }
        notifyChanged()
        return saved
    }

    private fun normalizeTodo(t: TodoEntity): TodoEntity = t.copy(
        title = t.title.trim(),
        dueTime = t.dueTime?.let {
            PlanRules.normalizeTime(it) ?: throw PlanException("截止时间非法")
        },
    )

    fun validateTodo(t: TodoEntity) {
        if (!PlanRules.titleOk(t.title)) {
            throw PlanException("标题需 1–${PlanRules.TITLE_MAX} 字符")
        }
        if (!PlanRules.noteOk(t.note)) throw PlanException("备注超长（最多 ${PlanRules.NOTE_MAX} 字）")
        val due = t.dueDate?.let { parseDate(it) ?: throw PlanException("截止日期非法") }
        if (due != null && !PlanRules.dateOk(due)) throw PlanException("截止日期须在 2000–2100 年")
        if (t.dueTime != null && t.dueDate == null) throw PlanException("需先选择截止日期才能设置时间")
        if (t.dueTime != null && toMinutes(t.dueTime) == null) throw PlanException("截止时间非法")
        if (t.priority !in 0..1) throw PlanException("优先级非法")
    }

    suspend fun deleteTodo(id: Long) {
        db.withTransaction {
            dao.deleteTodo(id)
            dao.clearOccurrencesByPrefix("todo-$id-")
        }
        notifyChanged()
    }

    // ---------- 倒数日 ----------
    suspend fun saveCountdown(input: CountdownEntity): CountdownEntity {
        val c = normalizeCountdown(input)
        validateCountdown(c)
        val saved = db.withTransaction {
            val old = dao.countdownById(c.id)
            if (old != null) {
                val updated = c.copy(id = old.id, version = old.version + 1)
                dao.updateCountdown(updated)
                updated
            } else {
                ensureCapacityForNewRow()
                val created = c.copy(id = 0, version = 1)
                created.copy(id = dao.insertCountdown(created))
            }
        }
        notifyChanged()
        return saved
    }

    private fun normalizeCountdown(c: CountdownEntity): CountdownEntity = c.copy(
        title = c.title.trim(),
        remindTime = PlanRules.normalizeTime(c.remindTime)
            ?: throw PlanException("提醒时间非法"),
    )

    fun validateCountdown(c: CountdownEntity) {
        if (!PlanRules.titleOk(c.title)) {
            throw PlanException("标题需 1–${PlanRules.TITLE_MAX} 字符")
        }
        if (!PlanRules.noteOk(c.note)) throw PlanException("备注超长（最多 ${PlanRules.NOTE_MAX} 字）")
        val target = parseDate(c.targetDate) ?: throw PlanException("目标日期非法")
        if (!PlanRules.dateOk(target)) throw PlanException("目标日期须在 2000–2100 年")
        if (c.category !in 0..4) throw PlanException("类别非法")
        if (c.remindDaysBefore !in listOf(0, 1, 7)) throw PlanException("提醒仅支持当天/提前1天/提前7天")
        if (PlanRules.normalizeTime(c.remindTime) == null) throw PlanException("提醒时间非法")
        if (c.originalDate.isNotEmpty()) {
            val original = parseDate(c.originalDate) ?: throw PlanException("原始日期非法")
            if (!PlanRules.dateOk(original)) throw PlanException("原始日期须在 2000–2100 年")
        }
    }

    suspend fun deleteCountdown(id: Long) {
        db.withTransaction {
            dao.deleteCountdown(id)
            dao.clearOccurrencesByPrefix("cd-$id-")
        }
        notifyChanged()
    }

    /** 新增前在事务内核对合计上限；达到上限仍允许编辑现有记录。 */
    private suspend fun ensureCapacityForNewRow() {
        if (dao.totalItemCount() >= PlanRules.MAX_ITEMS) {
            throw PlanException("数据条目已达上限（${PlanRules.MAX_ITEMS}），请先删除部分记录")
        }
    }

    /**
     * AI 批量执行：所有动作在同一 Room 事务内先校验后写入，
     * 任一动作失败整批回滚；返回真实落库 ID/版本与修改前实体（供撤销）。
     * 注意：正式 AI 保存使用 [commitAiBatch]，它会同时写入批次提交事实。
     */
    suspend fun applyAiBatch(ops: List<AiDbOp>): List<AiOpOutcome> {
        val outcomes = db.withTransaction { executeAiOps(ops) }
        notifyChanged()
        return outcomes
    }

    /** 必须在 Room 事务内调用。 */
    private suspend fun executeAiOps(ops: List<AiDbOp>): List<AiOpOutcome> {
        val creates = ops.count {
            it is AiDbOp.CreateSchedule || it is AiDbOp.CreateTodo || it is AiDbOp.CreateCountdown
        }
        if (creates > 0 && dao.totalItemCount() + creates > PlanRules.MAX_ITEMS) {
            throw PlanException("本批新增 ${creates} 条将超出上限（${PlanRules.MAX_ITEMS}）")
        }
        return ops.mapIndexed { index, op ->
            when (op) {
                is AiDbOp.CreateSchedule -> {
                    val s = normalizeSchedule(op.entity)
                    validateSchedule(s)
                    val id = dao.insertSchedule(s.copy(id = 0, version = 1))
                    val saved = s.copy(id = id, version = 1)
                    AiOpOutcome(index, "schedule", id, 1, schedule = saved)
                }

                is AiDbOp.UpdateSchedule -> {
                    val old = dao.scheduleById(op.targetId)
                        ?: throw PlanException("修改目标不存在（日程 ${op.targetId}）")
                    if (old.version != op.expectedVersion) {
                        throw PlanException("日程「${old.title}」已被修改（版本不一致），请刷新后重试")
                    }
                    val merged = normalizeSchedule(op.entity.copy(id = old.id))
                    validateSchedule(merged)
                    val updated = merged.copy(version = old.version + 1)
                    dao.updateSchedule(updated)
                    AiOpOutcome(index, "schedule", old.id, updated.version, schedule = updated, previousSchedule = old)
                }

                is AiDbOp.CreateTodo -> {
                    val t = normalizeTodo(op.entity)
                    validateTodo(t)
                    val id = dao.insertTodo(t.copy(id = 0, version = 1))
                    val saved = t.copy(id = id, version = 1)
                    AiOpOutcome(index, "todo", id, 1, todo = saved)
                }

                is AiDbOp.UpdateTodo -> {
                    val old = dao.todoById(op.targetId)
                        ?: throw PlanException("修改目标不存在（待办 ${op.targetId}）")
                    if (old.version != op.expectedVersion) {
                        throw PlanException("待办「${old.title}」已被修改（版本不一致），请刷新后重试")
                    }
                    val merged = normalizeTodo(op.entity.copy(id = old.id))
                    validateTodo(merged)
                    val updated = merged.copy(version = old.version + 1)
                    dao.updateTodo(updated)
                    AiOpOutcome(index, "todo", old.id, updated.version, todo = updated, previousTodo = old)
                }

                is AiDbOp.CreateCountdown -> {
                    val c = normalizeCountdown(op.entity)
                    validateCountdown(c)
                    val id = dao.insertCountdown(c.copy(id = 0, version = 1))
                    val saved = c.copy(id = id, version = 1)
                    AiOpOutcome(index, "countdown", id, 1, countdown = saved)
                }

                is AiDbOp.UpdateCountdown -> {
                    val old = dao.countdownById(op.targetId)
                        ?: throw PlanException("修改目标不存在（倒数日 ${op.targetId}）")
                    if (old.version != op.expectedVersion) {
                        throw PlanException("倒数日「${old.title}」已被修改（版本不一致），请刷新后重试")
                    }
                    val merged = normalizeCountdown(op.entity.copy(id = old.id))
                    validateCountdown(merged)
                    val updated = merged.copy(version = old.version + 1)
                    dao.updateCountdown(updated)
                    AiOpOutcome(index, "countdown", old.id, updated.version, countdown = updated, previousCountdown = old)
                }
            }
        }
    }

    // ---------- AI 提交事实（与业务写入同一事务） ----------

    suspend fun aiCommit(batchId: String): AiCommitEntity? = dao.aiCommit(batchId)

    suspend fun aiCommitsForSession(sessionId: String): List<AiCommitEntity> =
        dao.aiCommitsForSession(sessionId)

    /** 仅清理很旧的提交事实；近期记录用于重复导入识别与撤销。 */
    suspend fun pruneAiCommits(beforeEpochMs: Long) = dao.pruneAiCommits(beforeEpochMs)

    suspend fun currentAiEpoch(): AiEpochEntity? = dao.aiEpoch()

    /** 清空对话/恢复数据后由调用方在同一时刻更新；写入事务会核对。 */
    suspend fun setAiEpoch(generation: Int, sessionId: String) {
        dao.upsertAiEpoch(AiEpochEntity(1, generation, sessionId))
    }

    /**
     * 提交 AI 批次：epoch 核对、业务写入、提交事实 insert 在同一事务内。
     * 并发/失败重试只有一方能写入业务数据；appliedJson 由调用方根据真实 outcomes 生成。
     */
    suspend fun commitAiBatch(
        ops: List<AiDbOp>,
        input: AiCommitInput,
        expectedToken: Long,
        /** 自动提交专用：在写事务内用最新 DB 数据做最终资格校验；返回非空即整批不写。 */
        automaticGuard: ((List<ScheduleEntity>, List<TodoEntity>, List<CountdownEntity>) -> String?)? = null,
        buildApplied: (List<AiOpOutcome>) -> String,
    ): AiCommitResult {
        val result = db.withTransaction {
            if (AiExecutionGate.current() != expectedToken) {
                return@withTransaction AiCommitResult.Stale(
                    "数据已恢复或对话已清空（执行边界变化），本批未写入",
                )
            }
            val existing = dao.aiCommit(input.batchId)
            if (existing != null) return@withTransaction AiCommitResult.AlreadyCommitted(existing)

            val epoch = dao.aiEpoch()
            if (epoch == null) {
                dao.upsertAiEpoch(AiEpochEntity(1, input.dataGeneration, input.sessionId))
            } else if (epoch.dataGeneration != input.dataGeneration ||
                epoch.sessionId != input.sessionId
            ) {
                // 只有“当前内存边界就是本请求期望值”时才允许修正滞后的 DB 边界；
                // DB 更新（恢复）或请求更旧时一律拒绝，绝不把旧期望值写成权威边界。
                val gateMatches = AiExecutionGate.generation == input.dataGeneration &&
                    AiExecutionGate.sessionId == input.sessionId
                if (!gateMatches) {
                    return@withTransaction AiCommitResult.Stale(
                        "AI 执行边界已变化（DB 世代/会话与请求不一致），本批未写入",
                    )
                }
                if (epoch.dataGeneration > input.dataGeneration) {
                    return@withTransaction AiCommitResult.Stale(
                        "数据已恢复（DB 世代更新），本批未写入",
                    )
                }
                if (epoch.dataGeneration < input.dataGeneration) {
                    return@withTransaction AiCommitResult.Stale(
                        "请求世代高于已持久化边界，本批未写入",
                    )
                }
                // 同世代、不同会话：清空对话的本地边界尚未落库，用内存权威值修正
                dao.upsertAiEpoch(AiEpochEntity(1, input.dataGeneration, input.sessionId))
            }

            if (automaticGuard != null) {
                val reason = automaticGuard(
                    dao.allSchedules(),
                    dao.allTodos(),
                    dao.allCountdowns(),
                )
                if (reason != null) return@withTransaction AiCommitResult.Rejected(reason)
            }

            val outcomes = executeAiOps(ops)
            if (AiExecutionGate.current() != expectedToken) {
                throw PlanException("提交期间数据已恢复/对话已清空，本批已回滚")
            }
            val appliedJson = buildApplied(outcomes)
            val now = System.currentTimeMillis()
            val entity = AiCommitEntity(
                batchId = input.batchId,
                requestId = input.requestId,
                sessionId = input.sessionId,
                dataGeneration = input.dataGeneration,
                state = "APPLIED",
                appliedJson = appliedJson,
                actionCount = ops.size,
                origin = input.origin,
                createdAtEpochMs = input.createdAtEpochMs,
                updatedAtEpochMs = now,
            )
            if (dao.insertAiCommit(entity) == -1L) {
                throw PlanException("同一批次已由另一请求提交，本批已回滚")
            }
            AiCommitResult.Committed(outcomes, appliedJson, dao.aiCommit(input.batchId)!!)
        }
        if (result is AiCommitResult.Committed) notifyChanged()
        return result
    }

    /** 回填 v1.3 已有 JSON 撤销记录为提交事实（只读补录，不改业务数据）。 */
    suspend fun backfillAiCommit(commit: AiCommitEntity) {
        dao.insertAiCommit(commit)
    }

    suspend fun updateAiCommitApplied(batchId: String, appliedJson: String) {
        val c = dao.aiCommit(batchId) ?: return
        dao.updateAiCommit(batchId, c.state, appliedJson, System.currentTimeMillis())
    }

    /**
     * 撤销 AI 批次：版本保护、业务回退与提交状态更新在同一事务。
     * 用户后续编辑过的条目保留并计入 kept。
     */
    suspend fun undoAiCommit(
        batchId: String,
        expectedGeneration: Int,
        expectedSessionId: String,
        expectedToken: Long,
        fallbackCommit: AiCommitEntity?,
        ops: List<AiUndoOp>,
    ): AiUndoCommitResult {
        val result = db.withTransaction {
            if (AiExecutionGate.current() != expectedToken) {
                return@withTransaction AiUndoCommitResult.Stale
            }
            val epoch = dao.aiEpoch()
            if (epoch != null &&
                (epoch.dataGeneration != expectedGeneration || epoch.sessionId != expectedSessionId)
            ) {
                return@withTransaction AiUndoCommitResult.Stale
            }
            var commit = dao.aiCommit(batchId)
            if (commit == null && fallbackCommit != null) {
                if (fallbackCommit.dataGeneration != expectedGeneration ||
                    fallbackCommit.sessionId != expectedSessionId
                ) {
                    return@withTransaction AiUndoCommitResult.Stale
                }
                dao.insertAiCommit(fallbackCommit)
                commit = dao.aiCommit(batchId)
            }
            if (commit == null) {
                return@withTransaction AiUndoCommitResult.NotApplied("找不到该批次的提交记录")
            }
            if (commit.dataGeneration != expectedGeneration || commit.sessionId != expectedSessionId) {
                return@withTransaction AiUndoCommitResult.Stale
            }
            if (commit.state != "APPLIED") {
                return@withTransaction AiUndoCommitResult.NotApplied("该批次当前状态（${commit.state}）不能撤销")
            }

            val kept = mutableListOf<String>()
            val affected = mutableListOf<AiUndoAffected>()
            ops.forEach { op ->
                when (op) {
                    is AiUndoOp.DeleteSchedule -> {
                        val cur = dao.scheduleById(op.id) ?: return@forEach
                        if (cur.version != op.expectedVersion) {
                            kept.add(op.title); return@forEach
                        }
                        dao.deleteSchedule(op.id)
                        dao.clearOccurrencesByPrefix("sched-${op.id}-")
                        affected.add(AiUndoAffected("schedule", op.id, op.expectedVersion, deleted = true))
                    }

                    is AiUndoOp.RestoreSchedule -> {
                        val cur = dao.scheduleById(op.entity.id) ?: return@forEach
                        if (cur.version != op.expectedVersion) {
                            kept.add(op.title); return@forEach
                        }
                        val restored = op.entity.copy(id = cur.id, version = cur.version + 1)
                        dao.updateSchedule(restored)
                        affected.add(
                            AiUndoAffected("schedule", restored.id, restored.version, deleted = false, schedule = restored),
                        )
                    }

                    is AiUndoOp.DeleteTodo -> {
                        val cur = dao.todoById(op.id) ?: return@forEach
                        if (cur.version != op.expectedVersion) {
                            kept.add(op.title); return@forEach
                        }
                        dao.deleteTodo(op.id)
                        dao.clearOccurrencesByPrefix("todo-${op.id}-")
                        affected.add(AiUndoAffected("todo", op.id, op.expectedVersion, deleted = true))
                    }

                    is AiUndoOp.RestoreTodo -> {
                        val cur = dao.todoById(op.entity.id) ?: return@forEach
                        if (cur.version != op.expectedVersion) {
                            kept.add(op.title); return@forEach
                        }
                        val restored = op.entity.copy(id = cur.id, version = cur.version + 1)
                        dao.updateTodo(restored)
                        affected.add(AiUndoAffected("todo", restored.id, restored.version, deleted = false, todo = restored))
                    }

                    is AiUndoOp.DeleteCountdown -> {
                        val cur = dao.countdownById(op.id) ?: return@forEach
                        if (cur.version != op.expectedVersion) {
                            kept.add(op.title); return@forEach
                        }
                        dao.deleteCountdown(op.id)
                        dao.clearOccurrencesByPrefix("cd-${op.id}-")
                        affected.add(AiUndoAffected("countdown", op.id, op.expectedVersion, deleted = true))
                    }

                    is AiUndoOp.RestoreCountdown -> {
                        val cur = dao.countdownById(op.entity.id) ?: return@forEach
                        if (cur.version != op.expectedVersion) {
                            kept.add(op.title); return@forEach
                        }
                        val restored = op.entity.copy(id = cur.id, version = cur.version + 1)
                        dao.updateCountdown(restored)
                        affected.add(
                            AiUndoAffected("countdown", restored.id, restored.version, deleted = false, countdown = restored),
                        )
                    }
                }
            }
            if (AiExecutionGate.current() != expectedToken) {
                throw PlanException("撤销期间数据已恢复/对话已清空，本批撤销已回滚")
            }
            val now = System.currentTimeMillis()
            dao.updateAiCommit(batchId, "UNDONE", commit.appliedJson, now)
            AiUndoCommitResult.Done(dao.aiCommit(batchId)!!, kept, affected)
        }
        if (result is AiUndoCommitResult.Done) notifyChanged()
        return result
    }

    // ---------- 恢复快照（回退入口） ----------
    /** 生产环境在 DayPlanApp 中统一指向 files/restore_snapshots；写入与列举使用同一目录。 */
    var snapshotDir: java.io.File? = null

    fun snapshots(): List<java.io.File> {
        val dir = snapshotDir ?: return emptyList()
        return dir.listFiles { f ->
            f.isFile && f.name.startsWith("restore_snapshot_") && f.name.endsWith(".json")
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    fun latestSnapshot(): java.io.File? = snapshots().firstOrNull()

    fun setBudget(month: String, budgetFen: Long) {
        if (budgetFen < 0) throw PlanException("预算不能为负")
        if (budgetFen > PlanRules.MAX_ITEMS) throw PlanException("预算金额过大")
        // 预算按月存于偏好（第一版单月预算），此处在 VM 层持久化
    }

    // ---------- 一致性事务快照 ----------
    suspend fun loadAll(): DbSnapshot = db.withTransaction {
        DbSnapshot(dao.allSchedules(), dao.allTodos(), dao.allCountdowns())
    }

    /** 一致性事务快照：普通导出与恢复前置快照共用同一读取口径。 */
    suspend fun backupSnapshot(exportedAt: String, appVersion: String): BackupV1 =
        db.withTransaction {
            val schedules = dao.allSchedules()
            val todos = dao.allTodos()
            val countdowns = dao.allCountdowns()
            if (schedules.size + todos.size + countdowns.size > PlanRules.MAX_ITEMS) {
                throw PlanException("数据条目超出备份上限（${PlanRules.MAX_ITEMS}）")
            }
            BackupCodec.snapshot(schedules, todos, countdowns, exportedAt, appVersion)
        }

    // ---------- 恢复 ----------
    /**
     * 恢复流程：
     * 1) 事务外取当前一致性快照，序列化 → 原子写入快照目录 → 回读校验一致；
     *    失败则停止恢复，原数据不变；
     * 2) Room 事务内整库替换（保持原 ID 与完成状态/置顶）；
     * 3) 重新查询 DAO，与恢复计划逐表核对；不一致抛异常回滚。
     *
     * 快照目录会成为 repo.snapshotDir，设置页列举到的是同一份文件。
     *
     * @param failAfter 测试注入：0=清空后，1..3=各插入阶段后抛异常。
     */
    suspend fun applyRestore(plan: RestorePlan, snapshotDir: File, failAfter: Int? = null): File =
        applyRestoreWithBoundary(plan, snapshotDir, failAfter = failAfter).snapshot

    /**
     * 恢复业务数据并在**同一 Room 事务**推进 AI 权威边界（ai_epoch）。
     * 业务与边界共同提交/回滚；aiGeneration 为空时在 DB 当前世代上 +1。
     * 事务成功后即使偏好/文件/提醒后处理中断，旧草稿与旧撤销也不能重新有效。
     */
    suspend fun applyRestoreWithBoundary(
        plan: RestorePlan,
        snapshotDir: File,
        aiGeneration: Int? = null,
        aiSessionId: String? = null,
        failAfter: Int? = null,
    ): RestoreCommitResult {
        this.snapshotDir = snapshotDir
        val current = db.withTransaction { backupSnapshot("local-snapshot", "app") }
        val text = BackupCodec.encode(current)
        if (!snapshotDir.exists()) snapshotDir.mkdirs()
        if (!snapshotDir.isDirectory) {
            throw PlanException("恢复快照目录不可写，已停止恢复")
        }

        // 排他生成文件名，绝不覆盖已有快照。
        val stamp = System.currentTimeMillis()
        var index = 0
        val finalFile: File
        val tmpFile: File
        while (true) {
            val suffix = if (index == 0) "" else "_$index"
            val candidate = File(snapshotDir, "restore_snapshot_${stamp}$suffix.json")
            val candidateTmp = File(snapshotDir, "restore_snapshot_${stamp}$suffix.json.tmp")
            if (!candidate.exists() && !candidateTmp.exists()) {
                finalFile = candidate; tmpFile = candidateTmp; break
            }
            index++
            if (index > 1000) throw PlanException("无法生成排他快照文件名，已停止恢复")
        }

        try {
            tmpFile.writeText(text, Charsets.UTF_8)
            val readBack = tmpFile.readText(Charsets.UTF_8)
            if (BackupCodec.decode(readBack) != current) {
                throw PlanException("恢复快照回读内容不一致，已停止恢复")
            }
            if (!tmpFile.renameTo(finalFile)) {
                throw PlanException("恢复快照落盘失败，已停止恢复")
            }
            // 落盘后再次回读最终文件，确认快照可实际用于回退。
            if (BackupCodec.decode(finalFile.readText(Charsets.UTF_8)) != current) {
                throw PlanException("恢复快照最终文件回读不一致，已停止恢复")
            }
        } catch (e: PlanException) {
            tmpFile.delete(); throw e
        } catch (e: Exception) {
            tmpFile.delete()
            throw PlanException("恢复快照写入失败，已停止恢复：${e.message}")
        }

        var committedEpoch: AiEpochEntity? = null
        try {
            db.withTransaction {
                dao.clearSchedules()
                dao.clearTodos()
                dao.clearCountdowns()
                dao.clearOccurrences()
                dao.clearMissedAll()
                if (failAfter == 0) throw PlanException("注入失败：清空后中止")
                dao.insertSchedulesAll(plan.schedules)
                if (failAfter == 1) throw PlanException("注入失败：日程插入后中止")
                dao.insertTodosAll(plan.todos)
                if (failAfter == 2) throw PlanException("注入失败：待办插入后中止")
                dao.insertCountdownsAll(plan.countdowns)
                if (failAfter == 3) throw PlanException("注入失败：倒数日插入后中止")

                // 重新查询 DAO，逐表核对实际数据库内容（不是 plan 与自己比较）
                check(dao.allSchedules().sortedBy { it.id } == plan.schedules.sortedBy { it.id }) {
                    "核对失败：日程实际写入与计划不一致"
                }
                check(dao.allTodos().sortedBy { it.id } == plan.todos.sortedBy { it.id }) {
                    "核对失败：待办实际写入与计划不一致"
                }
                check(dao.allCountdowns().sortedBy { it.id } == plan.countdowns.sortedBy { it.id }) {
                    "核对失败：倒数日实际写入与计划不一致"
                }

                // 与业务替换同一事务：推进权威 AI 边界（旧批次/旧撤销从此无效）
                val existing = dao.aiEpoch()
                val requested = aiGeneration ?: ((existing?.dataGeneration ?: 0) + 1)
                // 单调推进：并发/连续恢复不能把边界降回旧值
                val newGeneration = maxOf(requested, (existing?.dataGeneration ?: 0) + 1)
                val newSession = aiSessionId?.takeIf { it.isNotBlank() }
                    ?: existing?.sessionId
                    ?: ""
                val epoch = AiEpochEntity(1, newGeneration, newSession)
                dao.upsertAiEpoch(epoch)
                committedEpoch = epoch
            }
        } catch (e: Exception) {
            throw PlanException((e.message ?: "恢复失败") + "（事务已回滚，原数据未变；恢复前快照已保留）")
        }
        return RestoreCommitResult(finalFile, committedEpoch!!)
    }

    // ---------- 工具 ----------
    private fun toMinutes(hhmm: String): Int? =
        PlanRules.normalizeTime(hhmm)?.let {
            val parts = it.split(":")
            parts[0].toInt() * 60 + parts[1].toInt()
        }

    private fun parseDate(s: String): java.time.LocalDate? =
        try { java.time.LocalDate.parse(s) } catch (e: Exception) { null }
}

// ---------- AI 批量执行（单一 Room 事务：全部通过才写入，任一失败整批回滚） ----------

sealed class AiDbOp {
    data class CreateSchedule(val entity: ScheduleEntity) : AiDbOp()
    data class CreateTodo(val entity: TodoEntity) : AiDbOp()
    data class CreateCountdown(val entity: CountdownEntity) : AiDbOp()
    data class UpdateSchedule(
        val targetId: Long,
        val expectedVersion: Int,
        val entity: ScheduleEntity,
    ) : AiDbOp()

    data class UpdateTodo(
        val targetId: Long,
        val expectedVersion: Int,
        val entity: TodoEntity,
    ) : AiDbOp()

    data class UpdateCountdown(
        val targetId: Long,
        val expectedVersion: Int,
        val entity: CountdownEntity,
    ) : AiDbOp()
}

/** 单个动作的落库结果：真实 ID 与版本 + 修改前的实体（撤销用）。 */
data class AiOpOutcome(
    val index: Int,
    val kind: String, // schedule / todo / countdown
    val id: Long,
    val version: Int,
    val schedule: ScheduleEntity? = null,
    val todo: TodoEntity? = null,
    val countdown: CountdownEntity? = null,
    val previousSchedule: ScheduleEntity? = null,
    val previousTodo: TodoEntity? = null,
    val previousCountdown: CountdownEntity? = null,
)

data class DbSnapshot(
    val schedules: List<ScheduleEntity>,
    val todos: List<TodoEntity>,
    val countdowns: List<CountdownEntity>,
)

/** 一次 AI 提交的不可变身份与世代。 */
data class AiCommitInput(
    val batchId: String,
    val requestId: String,
    val sessionId: String,
    val dataGeneration: Int,
    val origin: String,
    val createdAtEpochMs: Long,
)

sealed class AiCommitResult {
    data class Committed(
        val outcomes: List<AiOpOutcome>,
        val appliedJson: String,
        val commit: AiCommitEntity,
    ) : AiCommitResult()

    data class AlreadyCommitted(val commit: AiCommitEntity) : AiCommitResult()
    data class Stale(val message: String) : AiCommitResult()
    /** 自动提交在最终事务中不再满足条件：整批不写，转回人工预览。 */
    data class Rejected(val message: String) : AiCommitResult()
}

/** 撤销动作：删除新增或恢复修改前实体，均带写入时版本。 */
sealed class AiUndoOp {
    data class DeleteSchedule(val id: Long, val expectedVersion: Int, val title: String) : AiUndoOp()
    data class RestoreSchedule(val entity: ScheduleEntity, val expectedVersion: Int, val title: String) : AiUndoOp()
    data class DeleteTodo(val id: Long, val expectedVersion: Int, val title: String) : AiUndoOp()
    data class RestoreTodo(val entity: TodoEntity, val expectedVersion: Int, val title: String) : AiUndoOp()
    data class DeleteCountdown(val id: Long, val expectedVersion: Int, val title: String) : AiUndoOp()
    data class RestoreCountdown(val entity: CountdownEntity, val expectedVersion: Int, val title: String) : AiUndoOp()
}

data class AiUndoAffected(
    val kind: String,
    val id: Long,
    val versionAfter: Int,
    val deleted: Boolean,
    val schedule: ScheduleEntity? = null,
    val todo: TodoEntity? = null,
    val countdown: CountdownEntity? = null,
)

sealed class AiUndoCommitResult {
    data class Done(
        val commit: AiCommitEntity,
        val keptTitles: List<String>,
        val affected: List<AiUndoAffected>,
    ) : AiUndoCommitResult()

    data object Stale : AiUndoCommitResult()
    data class NotApplied(val message: String) : AiUndoCommitResult()
}

/** 恢复提交结果：快照文件 + 与业务同事务写入的权威 AI 边界。 */
data class RestoreCommitResult(
    val snapshot: File,
    val aiEpoch: AiEpochEntity,
)
