package com.jsy.dayplan.ai

import com.jsy.dayplan.ai.attach.AttachmentContextStore
import com.jsy.dayplan.ai.attach.AttachmentException
import com.jsy.dayplan.ai.attach.AttachmentOptions
import com.jsy.dayplan.ai.attach.AttachmentPlanner
import com.jsy.dayplan.ai.attach.ComposedAttachments
import com.jsy.dayplan.ai.attach.PendingAttachment
import com.jsy.dayplan.ai.attach.StoredAttachment
import com.jsy.dayplan.ai.attach.StoredContext
import com.jsy.dayplan.core.AppClock
import com.jsy.dayplan.data.AiExecutionGate
import com.jsy.dayplan.data.PlanRepository
import java.io.File
import java.time.LocalDate
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 一次发送的完整结果（供 UI 渲染）。 */
data class ChatTurn(
    val userEntry: ChatEntry,
    val assistantEntry: ChatEntry,
    val batchId: String?,
    val reviews: List<ActionReview>,
    val applied: AppliedRecord?,
    val warnings: List<String>,
    val totalActions: Int,
)

/**
 * 聊天编排器：组装请求（当前时间/忙碌摘要/历史/附件）→ 调用客户端 →
 * 解析协议 → 生成待确认批次或自动保存。幂等与会话/世代保护在此收口。
 */
class ChatController(
    private val client: DeepSeekClient,
    private val settings: AiSettingsStore,
    private val keyStore: SecureKeyStore,
    private val clock: AppClock,
    private val repo: PlanRepository,
    private val executor: PlanDraftExecutor,
    private val batchStore: BatchStore,
    private val history: ChatStore,
    private val planner: AttachmentPlanner,
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
    private val contextStore: AttachmentContextStore = AttachmentContextStore(null),
) {

    /** 当前是否具备发送条件（未配置给 UI 跳设置入口）。 */
    fun readiness(): Readiness {
        val config = settings.config
        val key = keyStore.loadApiKey()
        return when {
            !config.enabled -> Readiness.DISABLED
            key == null -> Readiness.NO_KEY
            else -> Readiness.READY
        }
    }

    enum class Readiness { READY, DISABLED, NO_KEY }

    /**
     * 发送一条消息（文字 + 可选附件）。挂起函数，调用方负责取消；
     * 会话已更换（清空对话）或数据世代变化时拒绝写入。
     */
    suspend fun send(
        rawText: String,
        attachments: List<PendingAttachment> = emptyList(),
        options: AttachmentOptions = AttachmentOptions(),
        onStage: ((String) -> Unit)? = null,
    ): ChatTurn {
        val config = settings.config
        val key = keyStore.loadApiKey()
        if (!config.enabled || key == null) {
            throw AiException.NotConfigured("请先在“AI 助手设置”中启用助手并填写 API Key")
        }
        val text = AiPrompts.clampUserInput(rawText.trim())
        // DB ai_epoch 是权威；恢复已提交而偏好尚未更新时不能继续用旧偏好世代
        val (generationAtSend, sessionId) = executor.currentBoundary()
        val requestId = UUID.randomUUID().toString()

        // 上一轮附件材料：纯文字追问（如“2099 年”“只导入三场”）仍能引用原材料
        val stored: StoredContext? = if (attachments.isEmpty()) {
            contextStore.load(sessionId, generationAtSend)
        } else null
        val contextAttachments = stored?.attachments ?: emptyList()
        val materials = if (attachments.isNotEmpty()) {
            attachments
        } else {
            val restored = contextAttachments.mapNotNull { storedPending(it) }
            if (restored.size < contextAttachments.size) {
                contextStore.clear(sessionId)
                throw AttachmentException(
                    "此前的附件缓存已清理或文件缺失，请重新选择文件后再继续；" +
                        "不会用文件名冒充已读取内容",
                )
            }
            restored
        }
        if (text.isEmpty() && materials.isEmpty()) {
            throw AiException.Protocol("请输入内容或添加附件")
        }
        if (attachments.isEmpty() && materials.isNotEmpty()) {
            onStage?.invoke("继续读取此前的附件…")
        }

        val effectiveOptions = if (attachments.isNotEmpty()) options else optionsOf(stored)
        val today = clock.now().toLocalDate()

        // 1) 附件本地解析（不联网）；此处把真实进度回传界面
        val composed: ComposedAttachments? = if (materials.isNotEmpty()) {
            planner.compose(materials, effectiveOptions) { index, total, name ->
                onStage?.invoke("读取附件 $index/$total：$name")
            }
        } else null
        if (materials.isNotEmpty()) onStage?.invoke("识别/整理中…")

        if (attachments.isNotEmpty()) {
            saveContext(sessionId, generationAtSend, attachments, effectiveOptions)
        }
        val afterCompose = executor.currentBoundary()
        if (afterCompose.first != generationAtSend || afterCompose.second != sessionId ||
            settings.dataGeneration != generationAtSend || settings.chatSessionId != sessionId
        ) {
            throw AiException.Cancelled("数据已恢复或对话已清空，本次附件/请求已丢弃")
        }

        // 2) 模型能力分流：图片只发给支持 image 的模型
        var model = config.model
        if (composed != null && composed.images.isNotEmpty()) {
            model = ensureVisionCapable(model)
        }

        // 3) 读取现有数据（参考现有安排 / 本地冲突检查都会用到）
        val snap = repo.loadAll()
        val busy = AiPrompts.busySummary(snap.schedules, today)
        val existing = AiPrompts.existingItemsLine(
            snap.schedules, snap.todos, snap.countdowns, today,
        )

        // 4) 组装消息
        val system = AiPrompts.buildSystemPrompt(
            clock, config, busy, existing, attachmentMode = materials.isNotEmpty(),
        )
        val priorMessages = AiPrompts.trimHistory(
            history.load().map { it.role to it.text }.filter { it.second.isNotBlank() },
        )
        val userContent = buildUserContent(text, composed)

        val messages = buildList {
            add(WireMessage.text("system", system))
            priorMessages.forEach { (role, t) -> add(WireMessage.text(role, t)) }
            add(WireMessage("user", userContent))
        }

        // 5) 调用（非流式；取消经由 transport 的 ensureActive 传播）
        val result = client.complete(model, messages, jsonMode = true)

        // 会话/数据世代在请求期间变化 → 丢弃结果，不写任何东西。
        // 同时看持久边界与本地缓存：恢复可能已提交 DB 而偏好尚未同步，或调用方已 bump。
        if (settings.chatSessionId != sessionId || settings.dataGeneration != generationAtSend) {
            throw AiException.Cancelled("对话已清空或数据已恢复/替换，本次结果已丢弃")
        }
        val afterResponse = executor.currentBoundary()
        if (afterResponse.second != sessionId) {
            throw AiException.Cancelled("对话已被清空，本次结果已丢弃")
        }
        if (afterResponse.first != generationAtSend) {
            throw AiException.Cancelled("数据已恢复/替换，恢复前的请求结果已丢弃")
        }
        if (result.finishReason == "length") {
            throw AiException.Protocol("模型输出被截断（超出 max_tokens），请减少一次发送的内容后重试")
        }
        if (result.content.isBlank()) {
            throw AiException.Protocol("模型返回了空内容，请重试或换一种问法")
        }
        val response = AiProtocol.parseResponse(result.content)
        if (response.schemaVersion !in 0..AiProtocol.SCHEMA_VERSION) {
            throw AiException.Protocol("不支持的协议版本：${response.schemaVersion}")
        }
        if (response.kind !in setOf("chat", "clarify", "plan")) {
            throw AiException.Protocol("模型返回未知的 kind：${response.kind}")
        }
        val usageLine = result.usage?.display() ?: ""

        // 动作身份在进入批次前校验；空/重复 ID 的批次会被本地审查判无效，
        // 不会自动保存，也不会绕过批内去重。
        val actionIds = response.actions.map { it.clientActionId }
        if (actionIds.any { it.isBlank() } || actionIds.size != actionIds.toSet().size) {
            // 保留批次供用户看到具体问题，但绝不允许自动保存或勾选
            onStage?.invoke("模型返回的动作身份无效，需人工核对")
        }
        if (response.actions.size > AiProtocol.MAX_ACTIONS) {
            throw AiException.Protocol(
                "模型返回 ${response.actions.size} 条动作，超过单次 ${AiProtocol.MAX_ACTIONS} 条上限；请分批发送",
            )
        }

        // 6) 生成批次或纯聊天回复
        val nowMs = nowProvider()
        val userEntry = ChatEntry(
            role = "user",
            text = text.ifBlank { "（附件：${materials.joinToString("、") { it.fileName }}）" },
            atEpochMs = nowMs,
            attachmentNames = materials.map { it.fileName },
        )
        if (response.kind != "plan" || response.actions.isEmpty()) {
            val replyText = buildString {
                append(response.reply.ifBlank { if (response.questions.isEmpty()) "（无回复内容）" else "" })
                if (response.questions.isNotEmpty()) {
                    if (isNotEmpty()) append("\n")
                    append(response.questions.joinToString("\n") { "❓ $it" })
                }
                composed?.warnings?.forEach { w ->
                    if (isNotEmpty()) append("\n")
                    append("⚠ $w")
                }
            }
            val assistantEntry = ChatEntry(
                role = "assistant", text = replyText,
                atEpochMs = nowMs,
                kind = if (response.kind == "clarify") "question" else "text",
                usageLine = usageLine,
            )
            history.append(listOf(userEntry, assistantEntry))
            return ChatTurn(userEntry, assistantEntry, null, emptyList(), null, composed?.warnings ?: emptyList(), 0)
        }

        // 计划批次：超过单批 20 条时拆成多批分别确认，避免只保存前 20 条却显示“全部完成”
        val chunks = response.actions.chunked(AiProtocol.PAGE_SIZE)
        val origin = if (materials.isEmpty()) "text" else "attachment"
        val warnings = mutableListOf<String>()
        warnings.addAll(composed?.warnings ?: emptyList())
        if (stored != null && materials.isNotEmpty()) {
            warnings.add("本轮继续参考此前附件：${materials.joinToString("、") { it.fileName }}；如不再需要可在输入区移除")
        }
        // 重复导入提示（不禁止）
        materials.forEach { att ->
            batchStore.findAppliedByAttachmentSha(att.sha256)?.let {
                warnings.add("「${att.fileName}」此前已导入过一批计划；如需再次导入请核对日期避免重复")
            }
        }
        if (chunks.size > 1) {
            warnings.add(
                "共 ${response.actions.size} 条，已分为 ${chunks.size} 批（每批最多 ${AiProtocol.PAGE_SIZE} 条）；" +
                    "逐批确认导入，未确认的批次仍保持待确认",
            )
        }

        val batches = chunks.mapIndexed { idx, chunk ->
            BatchRecord(
                batchId = "b_" + UUID.randomUUID().toString().take(12),
                requestId = requestId,
                sessionId = sessionId,
                createdAtEpochMs = nowMs + idx,
                dataGeneration = generationAtSend,
                origin = origin,
                reply = if (idx == 0) response.reply
                else "同一请求的第 ${idx + 1}/${chunks.size} 批，共 ${chunk.size} 项（上一批已单独确认）。",
                questions = if (idx == 0) response.questions else emptyList(),
                actions = chunk,
                validSourceIds = composed?.validSourceIds ?: emptySet(),
                attachmentShas = materials.map { it.sha256 }.toSet(),
                attachmentIds = materials.map { it.id },
                attachmentNames = materials.map { it.fileName },
                warnings = warnings.toList(),
                state = "PENDING",
            )
        }
        try {
            batches.forEach { batchStore.save(it) }
        } catch (e: Exception) {
            throw AiException.Protocol("无法保存待确认计划（磁盘/权限）：${e.message ?: "未知错误"}，本次未写入")
        }
        val batch = batches.first()

        val ctx = PlanDraftExecutor.ReviewContext(snap.schedules, snap.todos, snap.countdowns)
        val reviews = executor.review(batch, ctx)

        // 自动保存资格（附件批次与多批次一律先预览）
        var applied: AppliedRecord? = null
        if (chunks.size == 1 && executor.autoSaveEligible(batch, reviews, config)) {
            when (
                val outcome = executor.applySelected(
                    batch.batchId,
                    batch.actions.map { it.clientActionId },
                    automatic = true,
                )
            ) {
                is com.jsy.dayplan.ai.ApplyOutcome.Success -> applied = outcome.batch.applied
                is com.jsy.dayplan.ai.ApplyOutcome.PreviewRequired ->
                    warnings.add(outcome.message + "（本批保持待确认，可逐项核对后手动导入）")
                is com.jsy.dayplan.ai.ApplyOutcome.Failed ->
                    warnings.add("自动保存未执行：${outcome.message}")

                is com.jsy.dayplan.ai.ApplyOutcome.Stale ->
                    warnings.add("数据已恢复/替换，本批保持待确认但不能自动写入")

                is com.jsy.dayplan.ai.ApplyOutcome.AlreadyApplied -> applied = outcome.batch.applied
            }
        }

        // 自动保存转预览/失败的原因要进入批次卡片，不能只留在返回值里
        val latestBatch = batchStore.load(batch.batchId) ?: batch
        if (latestBatch.warnings != warnings.toList()) {
            runCatching { batchStore.save(latestBatch.copy(warnings = warnings.toList())) }
        }

        val assistantEntry = ChatEntry(
            role = "assistant",
            text = response.reply.ifBlank { "已整理 ${response.actions.size} 条计划。" },
            atEpochMs = nowMs,
            kind = if (applied != null) "result" else "plan",
            batchId = batch.batchId,
            usageLine = usageLine,
        )
        history.append(listOf(userEntry, assistantEntry))
        return ChatTurn(
            userEntry, assistantEntry, batch.batchId, reviews, applied, warnings,
            totalActions = response.actions.size,
        )
    }

    /** 唯一允许的静默行为：无。用户选了纯文本模型时给可读错误，让用户在设置切换。 */
    private suspend fun ensureVisionCapable(model: String): String {
        val known = settings.modalitiesOf(model)
        if (known != null) {
            if ("image" in known) return model
            throw AiException.Capability(
                "当前模型 $model 不支持图片识别。请在“AI 助手设置”切换到支持视觉的模型（如 deepseek-flash）后重试",
            )
        }
        // 未知能力：尝试 GET /models 刷新缓存（Key 已存在）
        try {
            val models = client.listModels()
            val map = models.associate { it.id to it.modalities }
            settings.cachedModalities = map
            val refreshed = map[model]
            if (refreshed != null) {
                if ("image" in refreshed) return model
                throw AiException.Capability(
                    "当前模型 $model 不支持图片识别（官方模型能力：${model}=${refreshed.joinToString("/")}）。" +
                        "请在设置中切换模型后重试",
                )
            }
            // 列表没有该模型：按默认 Flash 支持 image 处理，交服务端最终校验
            return model
        } catch (e: AiException.Capability) {
            throw e
        } catch (e: AiException) {
            // 列表拉取失败不阻塞：使用默认知识（Flash 支持 image）
            return model
        }
    }

    /** 组装 user content：纯文本或 content 块数组（视觉）。 */
    private fun buildUserContent(text: String, composed: ComposedAttachments?): kotlinx.serialization.json.JsonElement {
        if (composed == null) return kotlinx.serialization.json.JsonPrimitive(text)
        val docPart = buildString {
            if (text.isNotBlank()) {
                append(text).append("\n\n")
            }
            append("【附件提取内容（每段开头的 id 是来源编号，引用时填入 sourceIds）】\n")
            composed.segments.forEach { seg ->
                append("[${seg.sourceId}]（${seg.label}）\n").append(seg.text).append("\n\n")
            }
            if (composed.images.isNotEmpty()) {
                append("【本次同步发送的图片】\n")
                composed.imageManifest.forEach { append(it).append('\n') }
            }
        }
        val blocks = mutableListOf<kotlinx.serialization.json.JsonElement>(
            buildJsonObject {
                put("type", "text")
                put("text", docPart)
            },
        )
        composed.images.forEach { img ->
            blocks.add(
                buildJsonObject {
                    put("type", "image_url")
                    put(
                        "image_url",
                        buildJsonObject {
                            put("url", img.dataUrl)
                            put("detail", "high")
                        },
                    )
                },
            )
        }
        return JsonArray(blocks)
    }

    /** 用户手动确认保存后，把对应的“计划”消息改为“已保存结果”卡片（可撤销）。 */
    fun markBatchApplied(batchId: String) {
        runCatching {
            val list = history.load().map {
                if (it.batchId == batchId && it.kind == "plan") it.copy(kind = "result") else it
            }
            history.saveAll(list)
        }
    }

    /** 当前会话仍可引用的附件（UI 展示/移除用）。 */
    fun contextAttachments(): List<StoredAttachment> {
        val (gen, session) = inMemoryBoundary()
        return contextStore.currentAttachments(session, gen)
    }

    fun removeContextAttachment(attachmentId: String) {
        val (_, session) = inMemoryBoundary()
        contextStore.remove(session, attachmentId)
    }

    private fun inMemoryBoundary(): Pair<Int, String> {
        val gen = AiExecutionGate.generation
        val session = AiExecutionGate.sessionId
        return if (gen >= 0 && session.isNotBlank()) {
            gen to session
        } else {
            settings.dataGeneration to settings.chatSessionId
        }
    }

    /** 恢复/替换业务数据后：旧材料上下文与旧执行任务立即失效。 */
    fun invalidateAfterRestore() {
        AiExecutionGate.invalidate()
        contextStore.clearAll()
    }

    /** 清空对话：换会话 id、更新执行边界、取消待确认批次、清附件与材料缓存。 */
    suspend fun clearConversation(attachmentStore: com.jsy.dayplan.ai.attach.AttachmentStore) {
        // 清理路径不查询/写入 Room：可能正有事务持有连接，同步写会与之互等。
        // 内存 gate 是进程内权威；启动 pruneOnStart 再与 DB 对齐。
        val boundaryGen = if (AiExecutionGate.generation >= 0) {
            AiExecutionGate.generation
        } else {
            settings.dataGeneration
        }
        val newSession = settings.newChatSessionDirty(boundaryGen)
        // 同步内存边界：正在等待数据库的旧保存/撤销会在最终提交前看到变化；
        // boundaryDirty=true 让启动时优先采用本次本地会话，而不是旧 DB 会话。
        AiExecutionGate.set(boundaryGen, newSession, bumpToken = true)
        contextStore.clearAll()
        history.clear()
        batchStore.all().filter { it.isPending }.forEach {
            it.state = "CANCELLED"
            runCatching { batchStore.save(it) }
        }
        attachmentStore.clearAll()
        // 持久边界由下一次提交或启动 pruneOnStart 写入；旧 commit/undo 已因会话不一致失效。
    }

    private fun saveContext(
        sessionId: String,
        generation: Int,
        attachments: List<PendingAttachment>,
        options: AttachmentOptions,
    ) {
        runCatching {
            contextStore.save(
                StoredContext(
                    sessionId = sessionId,
                    dataGeneration = generation,
                    attachments = attachments.map { att ->
                        StoredAttachment(
                            id = att.id,
                            fileName = att.fileName,
                            mime = att.mime,
                            kindName = att.kind.name,
                            sha256 = att.sha256,
                            sizeBytes = att.sizeBytes,
                            filePath = att.file.absolutePath,
                            pdfPages = options.pdfPages[att.id] ?: emptySet(),
                            xlsxSheet = options.xlsxSheet[att.id],
                            addedAtEpochMs = nowProvider(),
                        )
                    },
                    updatedAtEpochMs = nowProvider(),
                ),
            )
        }
    }

    private fun storedPending(s: StoredAttachment): PendingAttachment? = try {
        val kind = com.jsy.dayplan.ai.attach.AttachKind.valueOf(s.kindName)
        val f = File(s.filePath)
        if (!f.isFile || f.length() != s.sizeBytes) null
        else PendingAttachment(s.id, s.fileName, s.mime, kind, f, s.sizeBytes, s.sha256)
    } catch (e: Exception) {
        null
    }

    private fun optionsOf(stored: StoredContext?): AttachmentOptions {
        if (stored == null) return AttachmentOptions()
        return AttachmentOptions(
            pdfPages = stored.attachments
                .filter { it.pdfPages.isNotEmpty() }
                .associate { it.id to it.pdfPages },
            xlsxSheet = stored.attachments
                .mapNotNull { a -> a.xlsxSheet?.let { a.id to it } }
                .toMap(),
        )
    }

    companion object {
        /** 视觉 content 数组构造（供测试检查 image_url 块）。 */
        fun countImageBlocks(content: kotlinx.serialization.json.JsonElement): Int =
            (content as? JsonArray)?.count { el ->
                el.toString().contains("\"type\":\"image_url\"")
            } ?: 0
    }
}
