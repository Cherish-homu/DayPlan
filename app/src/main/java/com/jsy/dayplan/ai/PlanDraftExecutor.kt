package com.jsy.dayplan.ai

import com.jsy.dayplan.core.PlanRules
import com.jsy.dayplan.core.ScheduleLogic
import com.jsy.dayplan.data.AiCommitEntity
import com.jsy.dayplan.data.AiCommitInput
import com.jsy.dayplan.data.AiCommitResult
import com.jsy.dayplan.data.AiDbOp
import com.jsy.dayplan.data.AiExecutionGate
import com.jsy.dayplan.data.AiUndoAffected
import com.jsy.dayplan.data.AiUndoCommitResult
import com.jsy.dayplan.data.AiUndoOp
import com.jsy.dayplan.data.CountdownEntity
import com.jsy.dayplan.data.PlanRepository
import com.jsy.dayplan.data.ScheduleEntity
import com.jsy.dayplan.data.TodoEntity
import com.jsy.dayplan.reminder.ReminderCoordinator
import java.time.LocalDate
import java.time.LocalDateTime

/** 保存/撤销结果。 */
sealed class ApplyOutcome {
    data class Success(
        val batch: BatchRecord,
        val remindersPending: Boolean,
        val reminderError: String,
    ) : ApplyOutcome()

    data class Failed(val message: String) : ApplyOutcome()
    data object Stale : ApplyOutcome()
    data class AlreadyApplied(val batch: BatchRecord) : ApplyOutcome()
    /** 自动保存最终核验失败：整批未写，保留为可编辑预览。 */
    data class PreviewRequired(val message: String) : ApplyOutcome()
}

sealed class UndoOutcome {
    data class Success(val batch: BatchRecord) : UndoOutcome()
    data class Failed(val message: String) : UndoOutcome()
    data object Stale : UndoOutcome()

    /** 撤销完成，但部分条目因用户后续编辑而保留（版本保护生效）。 */
    data class Partial(val keptTitles: List<String>) : UndoOutcome()
}

/**
 * 草稿执行器：模型只产生候选，写入由本地校验与单事务执行决定。
 * 职责：动作审查（review）→ 自动保存资格判定 → 幂等批量保存 → 版本保护撤销。
 */
class PlanDraftExecutor(
    private val repo: PlanRepository,
    private val coordinator: ReminderCoordinator,
    private val settings: AiSettingsStore,
    private val store: BatchStore,
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
) {

    // ---------- 动作审查 ----------

    data class ReviewContext(
        val schedules: List<ScheduleEntity>,
        val todos: List<TodoEntity>,
        val countdowns: List<CountdownEntity>,
    )

    suspend fun loadContext(): ReviewContext {
        val snap = repo.loadAll()
        return ReviewContext(snap.schedules, snap.todos, snap.countdowns)
    }

    /**
     * 解析当前权威 AI 边界：Room ai_epoch 优先于偏好缓存；
     * 清空对话尚未落库时使用内存 gate；恢复已落库时 DB 世代更高者胜出。
     */
    suspend fun currentBoundary(): Pair<Int, String> {
        val db = repo.currentAiEpoch()
        val (gen, session) = AiExecutionGate.resolve(
            db?.dataGeneration,
            db?.sessionId,
            settings.dataGeneration,
            settings.chatSessionId,
        )
        if (AiExecutionGate.generation != gen || AiExecutionGate.sessionId != session) {
            AiExecutionGate.set(gen, session, bumpToken = false)
        }
        if (settings.dataGeneration != gen || settings.chatSessionId != session) {
            settings.cacheBoundary(gen, session)
        }
        return gen to session
    }

    /**
     * 对整批动作做本地审查：字段合法性、身份唯一性、目标存在性、来源有效性、
     * 批内/库内冲突与重复。不写库；任何疑点都会阻止自动保存。
     */
    fun review(batch: BatchRecord, ctx: ReviewContext): List<ActionReview> {
        val merged = batch.actions.map { mergedAction(it, ctx) }
        val sigs = merged.mapIndexed { i, a -> if (a != null) actionSignature(a) else "invalid|$i" }
        val spans = merged.mapIndexedNotNull { i, a ->
            if (a != null && a.op == "create_schedule") {
                spanOf(a, (i + 1).toLong())?.let { i to it }
            } else null
        }
        val idCounts = batch.actions.groupingBy { it.clientActionId }.eachCount()
        return batch.actions.mapIndexed { index, action ->
            reviewOne(index, action, merged[index], batch, ctx, sigs, spans, idCounts)
        }
    }

    private fun reviewOne(
        index: Int,
        action: AiAction,
        merged: AiAction?,
        batch: BatchRecord,
        ctx: ReviewContext,
        sigs: List<String>,
        spans: List<Pair<Int, ScheduleLogic.ScheduleSpan>>,
        idCounts: Map<String, Int>,
    ): ActionReview {
        val problems = mutableListOf<String>()
        if (action.clientActionId.isBlank()) {
            problems.add("clientActionId 不能为空")
        } else if ((idCounts[action.clientActionId] ?: 0) > 1) {
            problems.add("clientActionId 重复：${action.clientActionId}（同一批次内必须唯一）")
        }
        if (batch.actions.size > AiProtocol.PAGE_SIZE) {
            problems.add("单批动作超过 ${AiProtocol.PAGE_SIZE} 条，请分批预览")
        }
        val sourceLabel = buildString {
            val unknown = action.sourceIds.filterNot { it in batch.validSourceIds }
            if (unknown.isNotEmpty()) {
                problems.add("引用了不存在的来源 id：${unknown.joinToString("、")}（不能作为证据）")
            }
            if (batch.origin == "attachment") {
                val valid = action.sourceIds.filter { it in batch.validSourceIds }
                if (valid.isEmpty()) problems.add("缺少可核对的附件来源，请补全或取消该项")
                else append("来源：").append(valid.joinToString("、"))
            } else if (unknown.isNotEmpty()) {
                append("（忽略无效来源 ${unknown.joinToString("、")}）")
            }
        }

        when {
            action.op !in AiProtocol.OPS_ALL -> problems.add("不支持的操作类型：${action.op}")
            action.op in AiProtocol.OPS_CREATE -> reviewCreate(action, problems, ctx)
            else -> reviewUpdate(action, problems, ctx)
        }

        // 批内重复：用合并后的完整候选签名，按位置比较，不靠 clientActionId 排除自己
        val dupInBatch = if (sigs[index].startsWith("invalid|")) {
            emptyList()
        } else {
            batch.actions.indices
                .filter { it != index && sigs[it] == sigs[index] }
                .map { batch.actions[it].clientActionId.ifBlank { "#${it + 1}" } }
        }

        // 与现有数据重复（仅新增；修改的目标本身不算）
        val m = merged ?: action
        val dupExisting = when (m.op) {
            "create_schedule" -> ctx.schedules.any {
                it.title.trim() == m.title.trim() &&
                    it.startDate == m.startDate && it.endDate == (m.endDate ?: m.startDate) &&
                    it.startTime == normOpt(m.startTime) && it.allDay == m.allDay
            }

            "create_todo" -> ctx.todos.any {
                !it.done && it.title.trim() == m.title.trim() && it.dueDate == m.dueDate &&
                    it.dueTime == normOpt(m.dueTime)
            }

            "create_countdown" -> ctx.countdowns.any {
                it.title.trim() == m.title.trim() && it.targetDate == m.targetDate
            }

            else -> false
        }

        // 日程冲突：库内 + 本批其他候选；修改动作先合并成完整目标
        val conflicts = if (m.op == "create_schedule") {
            val dbConflicts = conflictsOf(m, ctx)
            val otherSpans = spans.filter { it.first != index }
            val batchConflicts = spans.firstOrNull { it.first == index }?.let { (_, span) ->
                ScheduleLogic.conflictingWith(span, otherSpans.map { it.second })
                    .map { id -> otherSpans.firstOrNull { it.second.id == id }?.second?.title ?: "本批其他日程" }
            } ?: emptyList()
            (dbConflicts + batchConflicts).distinct()
        } else emptyList()

        val status = if (problems.isEmpty()) "ok" else "invalid"
        return ActionReview(
            action = action,
            status = status,
            problems = problems,
            conflicts = conflicts,
            duplicateOfExisting = dupExisting,
            duplicateInBatch = dupInBatch,
            sourceLabel = sourceLabel,
            beforeLine = if (action.op in AiProtocol.OPS_UPDATE) describeCurrent(action, ctx) else "",
        )
    }

    /** 修改动作合并为完整候选；用于批内冲突/重复与自动保存判断。 */
    private fun mergedAction(action: AiAction, ctx: ReviewContext): AiAction? = when (action.op) {
        "create_schedule", "create_todo", "create_countdown" -> action
        "update_schedule" -> ctx.schedules.find { it.id == action.targetId }?.let { s ->
            action.copy(
                op = "create_schedule",
                title = action.title.ifBlank { s.title },
                startDate = action.startDate ?: s.startDate,
                endDate = action.endDate ?: (if (action.startDate != null) action.startDate else s.endDate),
                allDay = action.allDay,
                startTime = if (action.allDay) null else (action.startTime ?: s.startTime),
                endTime = if (action.allDay) null else (action.endTime ?: s.endTime),
                category = action.category ?: s.category,
                remindMinutesBefore = action.remindMinutesBefore ?: s.remindMinutesBefore,
            )
        }

        "update_todo" -> ctx.todos.find { it.id == action.targetId }?.let { t ->
            action.copy(
                op = "create_todo",
                title = action.title.ifBlank { t.title },
                dueDate = action.dueDate ?: t.dueDate,
                dueTime = action.dueTime ?: t.dueTime,
                priority = action.priority ?: t.priority,
            )
        }

        "update_countdown" -> ctx.countdowns.find { it.id == action.targetId }?.let { c ->
            action.copy(
                op = "create_countdown",
                title = action.title.ifBlank { c.title },
                targetDate = action.targetDate ?: c.targetDate,
                countdownCategory = action.countdownCategory ?: c.category,
                yearlyRepeat = action.yearlyRepeat ?: c.yearlyRepeat,
            )
        }

        else -> null
    }

    private fun spanOf(a: AiAction, spanId: Long): ScheduleLogic.ScheduleSpan? {
        val sd = parseDate(a.startDate) ?: return null
        val ed = parseDate(a.endDate ?: a.startDate) ?: return null
        return ScheduleLogic.ScheduleSpan(
            spanId, a.title, sd, ed, a.allDay,
            a.startTime?.let { PlanRules.normalizeTime(it) },
            a.endTime?.let { PlanRules.normalizeTime(it) },
        )
    }

    /** 修改前的当前值摘要（界面用于“变更前/变更后”核对）。 */
    private fun describeCurrent(action: AiAction, ctx: ReviewContext): String {
        val id = action.targetId ?: return ""
        return when (action.op) {
            "update_schedule" -> ctx.schedules.find { it.id == id }?.let { s ->
                "${s.title} · ${s.startDate}" +
                    (if (s.endDate != s.startDate) " 至 ${s.endDate}" else "") +
                    (if (s.allDay) " 全天" else " ${s.startTime ?: "?"}-${s.endTime ?: "?"}")
            } ?: ""

            "update_todo" -> ctx.todos.find { it.id == id }?.let { t ->
                "${t.title} · " + (t.dueDate?.let { d -> "截止 $d ${t.dueTime ?: ""}".trim() } ?: "无截止日期")
            } ?: ""

            "update_countdown" -> ctx.countdowns.find { it.id == id }?.let { c ->
                "${c.title} · 目标 ${c.targetDate}" + if (c.yearlyRepeat) " · 每年" else ""
            } ?: ""

            else -> ""
        }
    }

    private fun normOpt(t: String?): String? = t?.let { PlanRules.normalizeTime(it) ?: it }

    private fun reviewCreate(action: AiAction, problems: MutableList<String>, ctx: ReviewContext) {
        if (!PlanRules.titleOk(action.title)) {
            problems.add("标题需 1–${PlanRules.TITLE_MAX} 字符")
        }
        if (action.note.length > PlanRules.NOTE_MAX) problems.add("备注超长")
        when (action.op) {
            "create_schedule" -> {
                val sd = parseDate(action.startDate) ?: run {
                    problems.add("缺少开始日期（yyyy-MM-dd）"); null
                }
                val ed = parseDate(action.endDate ?: action.startDate) ?: run {
                    problems.add("缺少结束日期"); null
                }
                if (sd != null && ed != null) {
                    if (ed.isBefore(sd)) problems.add("结束日期早于开始日期")
                    if (!PlanRules.dateOk(sd) || !PlanRules.dateOk(ed)) problems.add("日期须在 2000–2100 年")
                }
                if (!action.allDay) {
                    val st = action.startTime?.let { PlanRules.normalizeTime(it) }
                    val et = action.endTime?.let { PlanRules.normalizeTime(it) }
                    if (st == null || et == null) {
                        problems.add("非全天日程需要开始与结束时间（HH:mm）")
                    } else if (action.startDate != null && action.startDate == action.endDate &&
                        ScheduleLogic.toMinutes(et)!! <= ScheduleLogic.toMinutes(st)!!
                    ) {
                        problems.add("开始时间必须早于结束时间")
                    }
                }
                val cat = action.category
                if (cat != null && cat !in 0..3) problems.add("日程分类非法")
                val rem = action.remindMinutesBefore
                if (rem != null && rem !in listOf(0, 15, 30, 60, 120)) {
                    problems.add("提醒仅支持 0/15/30/60/120 分钟")
                }
                if (action.location.length > PlanRules.LOCATION_MAX) problems.add("地点超长")
            }

            "create_todo" -> {
                action.dueDate?.let { d ->
                    val ld = parseDate(d) ?: run { problems.add("截止日期非法"); return@let }
                    if (!PlanRules.dateOk(ld)) problems.add("截止日期须在 2000–2100 年")
                }
                if (action.dueTime != null) {
                    if (action.dueDate == null) problems.add("需先有截止日期才能设置时间")
                    if (PlanRules.normalizeTime(action.dueTime!!) == null) problems.add("截止时间非法")
                }
                if (action.priority != null && action.priority !in 0..1) problems.add("优先级非法")
            }

            "create_countdown" -> {
                val td = parseDate(action.targetDate) ?: run {
                    problems.add("缺少目标日期（yyyy-MM-dd）"); return
                }
                if (!PlanRules.dateOk(td)) problems.add("目标日期须在 2000–2100 年")
                val cat = action.countdownCategory
                if (cat != null && cat !in 0..4) problems.add("倒数日类别非法")
                val days = action.remindDaysBefore
                if (days != null && days !in listOf(0, 1, 7)) problems.add("提醒仅支持当天/提前1天/提前7天")
                if (action.remindTime != null && PlanRules.normalizeTime(action.remindTime!!) == null) {
                    problems.add("提醒时间非法")
                }
            }
        }
    }

    private fun reviewUpdate(action: AiAction, problems: MutableList<String>, ctx: ReviewContext) {
        val targetId = action.targetId
        if (targetId == null || action.targetId!! <= 0) {
            problems.add("修改缺少 targetId")
            return
        }
        val expected = action.expectedVersion
        if (expected == null) {
            problems.add("修改缺少 expectedVersion")
            return
        }
        val currentSchedule = if (action.op == "update_schedule") ctx.schedules.find { it.id == targetId } else null
        val currentTodo = if (action.op == "update_todo") ctx.todos.find { it.id == targetId } else null
        val currentCountdown = if (action.op == "update_countdown") ctx.countdowns.find { it.id == targetId } else null
        val currentTitle = currentSchedule?.title ?: currentTodo?.title ?: currentCountdown?.title
        val currentVersion = currentSchedule?.version ?: currentTodo?.version ?: currentCountdown?.version
        if (currentTitle == null || currentVersion == null) {
            problems.add("修改目标不存在（id=$targetId）")
            return
        }
        if (currentVersion != expected) {
            problems.add("「$currentTitle」已被修改（版本不一致），请刷新后重试")
            return
        }
        // 修改后的字段也要通过创建级校验（用合成动作复用 create 校验）
        val synthetic = when (action.op) {
            "update_schedule" -> {
                val s = currentSchedule!!
                action.copy(
                    op = "create_schedule",
                    title = action.title.ifBlank { s.title },
                    startDate = action.startDate ?: s.startDate,
                    endDate = action.endDate ?: s.endDate,
                    allDay = action.allDay,
                    startTime = action.startTime ?: if (action.allDay) null else s.startTime,
                    endTime = action.endTime ?: if (action.allDay) null else s.endTime,
                    category = action.category ?: s.category,
                    remindMinutesBefore = action.remindMinutesBefore ?: s.remindMinutesBefore,
                )
            }

            "update_todo" -> {
                val t = currentTodo!!
                action.copy(
                    op = "create_todo",
                    title = action.title.ifBlank { t.title },
                    dueDate = action.dueDate ?: t.dueDate,
                    dueTime = action.dueTime ?: t.dueTime,
                    priority = action.priority ?: t.priority,
                )
            }

            else -> {
                val c = currentCountdown!!
                action.copy(
                    op = "create_countdown",
                    title = action.title.ifBlank { c.title },
                    targetDate = action.targetDate ?: c.targetDate,
                    countdownCategory = action.countdownCategory ?: c.category,
                    yearlyRepeat = action.yearlyRepeat ?: c.yearlyRepeat,
                )
            }
        }
        reviewCreate(synthetic, problems, ctx)
    }

    private fun conflictsOf(action: AiAction, ctx: ReviewContext): List<String> {
        val sd = parseDate(action.startDate) ?: return emptyList()
        val ed = parseDate(action.endDate ?: action.startDate!!) ?: return emptyList()
        val span = ScheduleLogic.ScheduleSpan(
            action.targetId ?: -1,
            action.title, sd, ed, action.allDay,
            action.startTime?.let { PlanRules.normalizeTime(it) },
            action.endTime?.let { PlanRules.normalizeTime(it) },
        )
        if (ScheduleLogic.validateSpan(span) != null) return emptyList()
        val others = ctx.schedules
            .filter { it.id != action.targetId }
            .map {
                ScheduleLogic.ScheduleSpan(
                    it.id, it.title,
                    LocalDate.parse(it.startDate), LocalDate.parse(it.endDate),
                    it.allDay, it.startTime, it.endTime,
                )
            }
        return ScheduleLogic.conflictingWith(span, others)
            .map { id -> ctx.schedules.find { it.id == id }?.title ?: "日程 #$id" }
    }

    private fun parseDate(s: String?): LocalDate? {
        if (s == null) return null
        return try { LocalDate.parse(s) } catch (e: Exception) { null }
    }

    /** 去重签名：类型+标题+日期时间（规范化后）。 */
    private fun actionSignature(a: AiAction): String = buildString {
        append(a.op).append('|').append(a.title.trim())
        when (a.op.endsWith("schedule")) {
            true -> append('|').append(a.startDate).append('|').append(a.endDate)
                .append('|').append(a.allDay).append('|')
                .append(normOpt(a.startTime)).append('|').append(normOpt(a.endTime))
            else -> append('|').append(a.dueDate).append('|').append(normOpt(a.dueTime))
                .append('|').append(a.targetDate)
        }
    }

    // ---------- 自动保存资格 ----------

    /**
     * 仅当整批均为明确新增、时间完整、无假设、无冲突、无重复、无澄清问题、
     * 通过本地校验且 ≤20 条时才允许自动保存；附件批次一律先预览。
     */
    fun autoSaveEligible(
        batch: BatchRecord,
        reviews: List<ActionReview>,
        config: AiConfig,
    ): Boolean {
        if (!config.autoSave) return false
        if (batch.origin == "assistant") return false
        if (batch.origin == "attachment") return false
        if (batch.questions.isNotEmpty()) return false
        val acts = batch.actions
        if (acts.isEmpty() || acts.size > AiProtocol.PAGE_SIZE) return false
        if (acts.any { it.op !in AiProtocol.OPS_CREATE }) return false
        if (acts.any { it.assumptions.isNotEmpty() }) return false
        if (acts.any { it.sourceIds.isNotEmpty() }) return false
        if (reviews.any { it.status != "ok" }) return false
        if (reviews.any { it.conflicts.isNotEmpty() }) return false
        if (reviews.any { it.duplicateOfExisting || it.duplicateInBatch.isNotEmpty() }) return false
        // 时间完整：日程需有日期与钟点（或明确全天）；待办需有截止日期；倒数日需有目标日期
        acts.forEach { a ->
            when (a.op) {
                "create_schedule" ->
                    if (!a.allDay && (a.startTime == null || a.endTime == null)) return false

                "create_todo" -> if (a.dueDate == null) return false
                "create_countdown" -> if (a.targetDate == null) return false
            }
        }
        return true
    }

    // ---------- 保存 ----------

    /**
     * 保存选中的动作：先整批判错（零写入），再单事务提交，随后注册提醒。
     * 幂等：同一批次重复调用返回已保存结果，不重复写入。
     */
    suspend fun applySelected(
        batchId: String,
        selectedActionIds: Collection<String>,
        /** true=自动提交：最终事务内用最新 DB/批内候选重新判定资格；false=用户预览确认。 */
        automatic: Boolean = false,
    ): ApplyOutcome {
        val executionToken = AiExecutionGate.current()
        val (boundaryGen, boundarySession) = currentBoundary()
        val batchJson = store.load(batchId)
        // 提交事实在 Room 中；文件丢失/损坏/写失败时仍能识别已提交批次并返回真实结果
        val existingCommit = repo.aiCommit(batchId)
        if (existingCommit != null) {
            if (existingCommit.dataGeneration != boundaryGen ||
                existingCommit.sessionId != boundarySession
            ) {
                return ApplyOutcome.Stale
            }
            val rec = recordFromCommit(existingCommit, batchJson)
            return if (existingCommit.state == "APPLIED") {
                ApplyOutcome.AlreadyApplied(rec)
            } else {
                ApplyOutcome.Failed("该批次已撤销（${existingCommit.state}），不能再次保存")
            }
        }
        val batch = batchJson ?: return ApplyOutcome.Failed("找不到该批次（可能已清理）")
        if (batch.dataGeneration != boundaryGen || batch.sessionId != boundarySession) {
            return ApplyOutcome.Stale
        }
        // v1.3 旧批次：已有 applied 但没有 DB 提交事实，只补录事实，不重复写业务
        batch.applied?.let { applied ->
            repo.backfillAiCommit(legacyCommit(batch, applied))
            return ApplyOutcome.AlreadyApplied(batch)
        }
        if (!batch.isPending) return ApplyOutcome.Failed("该批次当前状态（${batch.state}）不能保存")

        val selected = batch.actions.filter { it.clientActionId in selectedActionIds }
        if (selected.isEmpty()) return ApplyOutcome.Failed("没有选中的条目")

        // 保存前重新读取现有数据并审查（处理“AI 请求期间用户已改日历”）
        val ctx = loadContext()
        // 入口检查后可能发生恢复/清空：DB 边界与内存令牌都要再次核对
        val (freshGen, freshSession) = currentBoundary()
        if (batch.dataGeneration != freshGen ||
            batch.sessionId != freshSession ||
            AiExecutionGate.current() != executionToken
        ) {
            return ApplyOutcome.Stale
        }
        val reviews = review(batch, ctx)
        val byId = reviews.associateBy { it.action.clientActionId }
        val invalid = selected.mapNotNull { a ->
            byId[a.clientActionId]?.takeIf { it.status != "ok" }?.let { r ->
                "「${a.title.ifBlank { "(无标题)" }}」：${r.problems.joinToString("；")}"
            }
        }
        if (invalid.isNotEmpty()) {
            return ApplyOutcome.Failed("以下条目未通过本地校验，本批未写入：\n" + invalid.joinToString("\n"))
        }

        val ops = mutableListOf<AiDbOp>()
        selected.forEach { a ->
            val op = buildOp(a, ctx) ?: return ApplyOutcome.Failed("「${a.title}」无法构造成写入操作")
            ops.add(op)
        }

        val input = AiCommitInput(
            batchId = batch.batchId,
            requestId = batch.requestId,
            sessionId = freshSession,
            dataGeneration = freshGen,
            origin = batch.origin,
            createdAtEpochMs = batch.createdAtEpochMs,
        )
        // 自动提交的最终资格：在写事务内读取最新日历，重新 review + autoSaveEligible；
        // 事务本身与普通保存互斥，避免“检查后写入”窗口。
        val autoGuard: ((List<ScheduleEntity>, List<TodoEntity>, List<CountdownEntity>) -> String?)? =
            if (automatic) {
                { schedules, todos, countdowns ->
                    val freshCtx = ReviewContext(schedules, todos, countdowns)
                    val freshReviews = review(batch, freshCtx)
                    when {
                        selected.size != batch.actions.size ->
                            "自动保存必须整批满足条件；当前仅选中 ${selected.size}/${batch.actions.size} 项"

                        !autoSaveEligible(batch, freshReviews, settings.config) ->
                            "请求期间日历或配置发生变化，自动保存条件已不满足；已转为待确认预览"

                        else -> null
                    }
                }
            } else null
        val commitResult = try {
            repo.commitAiBatch(ops, input, executionToken, autoGuard) { outcomes ->
                encodeApplied(buildApplied(selected, outcomes, false, ""))
            }
        } catch (e: Exception) {
            // 并发提交可能已成功：重新读取事实，绝不再写一次
            val committed = repo.aiCommit(batchId)
            if (committed != null && committed.state == "APPLIED") {
                if (committed.dataGeneration != freshGen || committed.sessionId != freshSession) {
                    return ApplyOutcome.Stale
                }
                return ApplyOutcome.AlreadyApplied(recordFromCommit(committed, batch))
            }
            return ApplyOutcome.Failed("整批未写入：${e.message ?: "未知错误"}")
        }

        val committed = when (commitResult) {
            is AiCommitResult.Stale -> return ApplyOutcome.Stale
            is AiCommitResult.Rejected -> return ApplyOutcome.PreviewRequired(commitResult.message)
            is AiCommitResult.AlreadyCommitted ->
                return ApplyOutcome.AlreadyApplied(recordFromCommit(commitResult.commit, batch))

            is AiCommitResult.Committed -> commitResult
        }
        settings.markBoundaryPersisted(freshGen, freshSession)

        // 事务已提交：注册提醒（失败不回滚数据，写入提交事实标记待重建）
        var remindersPending = false
        var reminderError = ""
        committed.outcomes.forEach { r ->
            try {
                r.schedule?.let { coordinator.onScheduleSaved(it) }
                r.todo?.let { coordinator.onTodoSaved(it) }
                r.countdown?.let { coordinator.onCountdownSaved(it, LocalDateTime.now()) }
            } catch (e: Exception) {
                remindersPending = true
                reminderError = e.message ?: e.javaClass.simpleName
            }
        }

        val applied = buildApplied(selected, committed.outcomes, remindersPending, reminderError)
        val finalJson = encodeApplied(applied)
        if (finalJson != committed.appliedJson) {
            runCatching { repo.updateAiCommitApplied(batchId, finalJson) }
        }
        batch.state = "APPLIED"
        batch.applied = applied
        // JSON 只是缓存；写失败不能谎报整批未写，也不能让重试新增数据
        runCatching { store.save(batch) }
        return ApplyOutcome.Success(batch, remindersPending, reminderError)
    }

    private fun buildApplied(
        selected: List<AiAction>,
        results: List<com.jsy.dayplan.data.AiOpOutcome>,
        remindersPending: Boolean,
        reminderError: String,
    ): AppliedRecord {
        val items = results.map { r ->
            val action = selected.getOrNull(r.index)
            SavedRef(
                clientActionId = action?.clientActionId ?: "",
                kind = r.kind,
                id = r.id,
                versionAfter = r.version,
                title = r.schedule?.title ?: r.todo?.title ?: r.countdown?.title ?: "",
            )
        }
        val previous = results.mapNotNull { r ->
            val action = selected.getOrNull(r.index) ?: return@mapNotNull null
            if (r.previousSchedule != null || r.previousTodo != null || r.previousCountdown != null) {
                PrevEntity(
                    clientActionId = action.clientActionId,
                    kind = r.kind,
                    id = r.id,
                    versionAfter = r.version,
                    schedule = r.previousSchedule,
                    todo = r.previousTodo,
                    countdown = r.previousCountdown,
                )
            } else null
        }
        return AppliedRecord(
            atEpochMs = nowProvider(),
            appliedActionIds = selected.map { it.clientActionId },
            items = items,
            previous = previous,
            remindersPending = remindersPending,
            reminderError = reminderError,
        )
    }

    private fun encodeApplied(a: AppliedRecord): String =
        AiProtocol.json.encodeToString(AppliedRecord.serializer(), a)

    private fun decodeApplied(json: String): AppliedRecord? = try {
        AiProtocol.json.decodeFromString(AppliedRecord.serializer(), json)
    } catch (e: Exception) {
        null
    }

    private fun legacyCommit(batch: BatchRecord, applied: AppliedRecord): AiCommitEntity =
        AiCommitEntity(
            batchId = batch.batchId,
            requestId = batch.requestId,
            sessionId = batch.sessionId,
            dataGeneration = batch.dataGeneration,
            state = "APPLIED",
            appliedJson = encodeApplied(applied),
            actionCount = batch.actions.size,
            origin = batch.origin,
            createdAtEpochMs = batch.createdAtEpochMs,
            updatedAtEpochMs = nowProvider(),
        )

    fun recordFromCommit(commit: AiCommitEntity, base: BatchRecord?): BatchRecord {
        val applied = decodeApplied(commit.appliedJson)
        return (base ?: BatchRecord(
            batchId = commit.batchId,
            requestId = commit.requestId,
            sessionId = commit.sessionId,
            createdAtEpochMs = commit.createdAtEpochMs,
            dataGeneration = commit.dataGeneration,
            origin = commit.origin,
            reply = "",
            state = commit.state,
        )).copy(state = commit.state, applied = applied)
    }

    /** 把审查通过的动作转换成数据库操作。 */
    private fun buildOp(a: AiAction, ctx: ReviewContext): AiDbOp? = when (a.op) {
        "create_schedule" -> AiDbOp.CreateSchedule(
            ScheduleEntity(
                title = a.title.trim(),
                startDate = a.startDate!!,
                endDate = a.endDate ?: a.startDate!!,
                allDay = a.allDay,
                startTime = if (a.allDay) null else PlanRules.normalizeTime(a.startTime!!),
                endTime = if (a.allDay) null else PlanRules.normalizeTime(a.endTime!!),
                location = a.location.trim(),
                note = a.note.take(PlanRules.NOTE_MAX),
                category = a.category ?: 3,
                remindMinutesBefore = a.remindMinutesBefore ?: 0,
            ),
        )

        "create_todo" -> AiDbOp.CreateTodo(
            TodoEntity(
                title = a.title.trim(),
                dueDate = a.dueDate,
                dueTime = a.dueTime?.let { PlanRules.normalizeTime(it) },
                priority = a.priority ?: 0,
                note = a.note.take(PlanRules.NOTE_MAX),
            ),
        )

        "create_countdown" -> AiDbOp.CreateCountdown(
            CountdownEntity(
                title = a.title.trim(),
                targetDate = a.targetDate!!,
                category = a.countdownCategory ?: 4,
                note = a.note.take(PlanRules.NOTE_MAX),
                yearlyRepeat = a.yearlyRepeat ?: false,
                remindEnabled = a.remindEnabled ?: false,
                remindDaysBefore = a.remindDaysBefore ?: 0,
                remindTime = a.remindTime?.let { PlanRules.normalizeTime(it) } ?: "09:00",
            ),
        )

        "update_schedule" -> {
            val old = ctx.schedules.find { it.id == a.targetId } ?: return null
            AiDbOp.UpdateSchedule(
                targetId = old.id,
                expectedVersion = a.expectedVersion!!,
                entity = old.copy(
                    title = a.title.ifBlank { old.title }.trim(),
                    startDate = a.startDate ?: old.startDate,
                    endDate = a.endDate ?: (if (a.startDate != null) a.startDate!! else old.endDate),
                    allDay = a.allDay,
                    startTime = if (a.allDay) null else a.startTime?.let { PlanRules.normalizeTime(it) } ?: old.startTime,
                    endTime = if (a.allDay) null else a.endTime?.let { PlanRules.normalizeTime(it) } ?: old.endTime,
                    location = if (a.location.isBlank()) old.location else a.location.trim(),
                    note = if (a.note.isBlank()) old.note else a.note.take(PlanRules.NOTE_MAX),
                    category = a.category ?: old.category,
                    remindMinutesBefore = a.remindMinutesBefore ?: old.remindMinutesBefore,
                ),
            )
        }

        "update_todo" -> {
            val old = ctx.todos.find { it.id == a.targetId } ?: return null
            AiDbOp.UpdateTodo(
                targetId = old.id,
                expectedVersion = a.expectedVersion!!,
                entity = old.copy(
                    title = a.title.ifBlank { old.title }.trim(),
                    dueDate = a.dueDate ?: old.dueDate,
                    dueTime = a.dueTime?.let { PlanRules.normalizeTime(it) } ?: old.dueTime,
                    priority = a.priority ?: old.priority,
                    note = if (a.note.isBlank()) old.note else a.note.take(PlanRules.NOTE_MAX),
                ),
            )
        }

        "update_countdown" -> {
            val old = ctx.countdowns.find { it.id == a.targetId } ?: return null
            AiDbOp.UpdateCountdown(
                targetId = old.id,
                expectedVersion = a.expectedVersion!!,
                entity = old.copy(
                    title = a.title.ifBlank { old.title }.trim(),
                    targetDate = a.targetDate ?: old.targetDate,
                    category = a.countdownCategory ?: old.category,
                    note = if (a.note.isBlank()) old.note else a.note.take(PlanRules.NOTE_MAX),
                    yearlyRepeat = a.yearlyRepeat ?: old.yearlyRepeat,
                    remindEnabled = a.remindEnabled ?: old.remindEnabled,
                    remindDaysBefore = a.remindDaysBefore ?: old.remindDaysBefore,
                    remindTime = a.remindTime?.let { PlanRules.normalizeTime(it) } ?: old.remindTime,
                ),
            )
        }

        else -> null
    }

    // ---------- 撤销 ----------

    /**
     * 撤销本批：新增→删除；修改→恢复前值。
     * 提交事实、版本保护与业务回退在同一 Room 事务；任何条目在 AI 写入后又被编辑
     * （版本不匹配）则保留该条目并报告冲突。
     */
    suspend fun undo(batchId: String): UndoOutcome {
        val executionToken = AiExecutionGate.current()
        val (boundaryGen, boundarySession) = currentBoundary()
        val batch = store.load(batchId)
        var commit = repo.aiCommit(batchId)
        val appliedFromJson = batch?.applied
        if (commit == null && batch != null && appliedFromJson != null) {
            // 兼容 v1.3：没有提交事实的旧批次，边界仍一致时补录后再撤销
            if (batch.dataGeneration != boundaryGen || batch.sessionId != boundarySession) {
                return UndoOutcome.Stale
            }
            repo.backfillAiCommit(legacyCommit(batch, appliedFromJson))
            commit = repo.aiCommit(batchId)
        }
        val applied = commit?.let { decodeApplied(it.appliedJson) } ?: appliedFromJson
            ?: return UndoOutcome.Failed("该批次尚未保存或找不到提交记录")
        if (commit == null) return UndoOutcome.Failed("找不到该批次的提交记录")

        if (commit.dataGeneration != boundaryGen || commit.sessionId != boundarySession) {
            return UndoOutcome.Stale
        }
        if (batch != null && (batch.dataGeneration != boundaryGen ||
                batch.sessionId != boundarySession)
        ) {
            return UndoOutcome.Stale
        }
        if (commit.state != "APPLIED") {
            return UndoOutcome.Failed("该批次当前状态（${commit.state}）不能撤销")
        }

        // 倒序撤销，尽量还原保存前的次序状态
        val prevById = applied.previous.associateBy { it.clientActionId }
        val ops = mutableListOf<AiUndoOp>()
        applied.items.reversed().forEach { ref ->
            val prev = prevById[ref.clientActionId]
            when (ref.kind) {
                "schedule" -> ops.add(
                    if (prev?.schedule != null) {
                        AiUndoOp.RestoreSchedule(prev.schedule.copy(id = ref.id), ref.versionAfter, ref.title)
                    } else {
                        AiUndoOp.DeleteSchedule(ref.id, ref.versionAfter, ref.title)
                    },
                )

                "todo" -> ops.add(
                    if (prev?.todo != null) {
                        AiUndoOp.RestoreTodo(prev.todo.copy(id = ref.id), ref.versionAfter, ref.title)
                    } else {
                        AiUndoOp.DeleteTodo(ref.id, ref.versionAfter, ref.title)
                    },
                )

                "countdown" -> ops.add(
                    if (prev?.countdown != null) {
                        AiUndoOp.RestoreCountdown(prev.countdown.copy(id = ref.id), ref.versionAfter, ref.title)
                    } else {
                        AiUndoOp.DeleteCountdown(ref.id, ref.versionAfter, ref.title)
                    },
                )
            }
        }

        val result = repo.undoAiCommit(
            batchId = batchId,
            expectedGeneration = boundaryGen,
            expectedSessionId = boundarySession,
            expectedToken = executionToken,
            fallbackCommit = commit,
            ops = ops,
        )
        when (result) {
            is AiUndoCommitResult.Stale -> return UndoOutcome.Stale
            is AiUndoCommitResult.NotApplied -> return UndoOutcome.Failed(result.message)
            is AiUndoCommitResult.Done -> {
                // 事务已提交：提醒重建失败不回滚数据，下次前台重建可修复
                result.affected.forEach { a ->
                    runCatching {
                        when (a.kind) {
                            "schedule" -> if (a.deleted) {
                                coordinator.onScheduleDeleted(a.id, a.versionAfter)
                            } else {
                                a.schedule?.let { coordinator.onScheduleSaved(it) }
                            }

                            "todo" -> if (a.deleted) {
                                coordinator.onTodoDeleted(a.id, a.versionAfter)
                            } else {
                                a.todo?.let { coordinator.onTodoSaved(it) }
                            }

                            "countdown" -> if (a.deleted) {
                                coordinator.onCountdownDeleted(a.id, a.versionAfter)
                            } else {
                                a.countdown?.let { coordinator.onCountdownSaved(it, LocalDateTime.now()) }
                            }
                        }
                    }
                }
                val rec = recordFromCommit(result.commit, batch)
                batch?.let {
                    it.state = "UNDONE"
                    runCatching { store.save(it) }
                }
                return if (result.keptTitles.isEmpty()) {
                    UndoOutcome.Success(rec)
                } else {
                    UndoOutcome.Partial(result.keptTitles)
                }
            }
        }
    }
}
