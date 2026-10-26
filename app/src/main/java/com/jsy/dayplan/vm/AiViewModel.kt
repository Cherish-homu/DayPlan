package com.jsy.dayplan.vm

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jsy.dayplan.DayPlanApp
import com.jsy.dayplan.ai.ActionReview
import com.jsy.dayplan.ai.AiConfig
import com.jsy.dayplan.ai.AiException
import com.jsy.dayplan.ai.AiSettingsStore
import com.jsy.dayplan.ai.ApplyOutcome
import com.jsy.dayplan.ai.BatchRecord
import com.jsy.dayplan.ai.ChatEntry
import com.jsy.dayplan.ai.ModelInfo
import com.jsy.dayplan.ai.UndoOutcome
import com.jsy.dayplan.ai.attach.AttachKind
import com.jsy.dayplan.ai.attach.AttachLimits
import com.jsy.dayplan.ai.attach.AttachmentException
import com.jsy.dayplan.ai.attach.AttachmentOptions
import com.jsy.dayplan.ai.attach.DocParsers
import com.jsy.dayplan.ai.attach.PendingAttachment
import com.jsy.dayplan.ai.attach.PdfTextExtractor
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 界面里的一个批次（含勾选状态与审查结果）。 */
data class UiBatch(
    val record: BatchRecord,
    val reviews: List<ActionReview>,
    /** clientActionId → 是否勾选（默认排除无效与重复项）。 */
    val selection: Map<String, Boolean>,
    val onlyIssues: Boolean = false,
)

sealed class SendState {
    data object Idle : SendState()
    data class Sending(
        val attachmentsDone: Int,
        val attachmentsTotal: Int,
        val stage: String = "",
    ) : SendState()

    data class Failed(val message: String, val retryable: Boolean) : SendState()
}

/** 附件可选项（异步探测页数/工作表，不联网）。 */
data class AttachMeta(
    val pdfPageCount: Int? = null,
    val sheets: List<String> = emptyList(),
    val error: String? = null,
)

class AiViewModel @JvmOverloads constructor(
    app: Application,
    containerOverride: com.jsy.dayplan.ai.AiContainer? = null,
) : AndroidViewModel(app) {

    private val app0 = app as DayPlanApp
    val ai = containerOverride ?: app0.ai

    private val _entries = MutableStateFlow<List<ChatEntry>>(emptyList())
    val entries: StateFlow<List<ChatEntry>> = _entries

    private val _batches = MutableStateFlow<List<UiBatch>>(emptyList())
    val batches: StateFlow<List<UiBatch>> = _batches

    /** Room 提交事实的可显示副本；JSON 写失败/丢失时结果卡仍可用。 */
    val appliedRecords = MutableStateFlow<Map<String, BatchRecord>>(emptyMap())
    /** 当前会话仍可续聊的附件材料（文本/图片均含范围引用）。 */
    val contextAttachments = MutableStateFlow<List<com.jsy.dayplan.ai.attach.StoredAttachment>>(emptyList())
    /** 正在提交/撤销的批次，界面据此防连点。 */
    val busyBatchIds = MutableStateFlow<Set<String>>(emptySet())

    val input = MutableStateFlow("")
    val attachments = MutableStateFlow<List<PendingAttachment>>(emptyList())
    val attachmentMeta = MutableStateFlow<Map<String, AttachMeta>>(emptyMap())
    val pdfPages = MutableStateFlow<Map<String, Set<Int>>>(emptyMap())
    val xlsxSheets = MutableStateFlow<Map<String, String>>(emptyMap())
    val sendState = MutableStateFlow<SendState>(SendState.Idle)
    /** 最近一次真实本地写入成功的数量；仅用于角色“已保存N项”反馈，不参与业务判断。 */
    val lastSavedNotice = MutableStateFlow<Int?>(null)
    /** 普通聊天流式正文：仅 UI 临时展示，成功后才写入历史。 */
    val streamingReply = MutableStateFlow("")
    val configVersion = MutableStateFlow(0) // 设置变化后自增，驱动界面刷新

    private var sendJob: Job? = null
    val sending: Boolean get() = sendState.value is SendState.Sending

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch(Dispatchers.IO) {
            val entries = ai.chatStore.load()
            val ctx = ai.executor.loadContext()
            val epoch = ai.repo.currentAiEpoch()
            val (generation, session) = com.jsy.dayplan.data.AiExecutionGate.resolve(
                epoch?.dataGeneration,
                epoch?.sessionId,
                ai.settings.dataGeneration,
                ai.settings.chatSessionId,
            )
            if (com.jsy.dayplan.data.AiExecutionGate.generation != generation ||
                com.jsy.dayplan.data.AiExecutionGate.sessionId != session
            ) {
                com.jsy.dayplan.data.AiExecutionGate.set(generation, session, bumpToken = false)
            }
            if (ai.settings.dataGeneration != generation || ai.settings.chatSessionId != session) {
                ai.settings.cacheBoundary(generation, session)
            }
            val commits = ai.repo.aiCommitsForSession(session)
            val commitById = commits.associateBy { it.batchId }
            val applied = commits.associate { c ->
                c.batchId to ai.executor.recordFromCommit(c, ai.batchStore.load(c.batchId))
            }
            val list = if (ai.settings.config.chatMode == com.jsy.dayplan.ai.AiConfig.MODE_PLAIN) {
                // 单一纯聊天不再展示历史待确认草稿，避免出现无法操作的旧计划卡。
                emptyList()
            } else {
                ai.batchStore.all()
                    .filter {
                        it.sessionId == session &&
                            it.dataGeneration == generation &&
                            it.isPending &&
                            !commitById.containsKey(it.batchId)
                    }
                    .sortedBy { it.createdAtEpochMs }
                    .map { rec ->
                        val reviews = ai.executor.review(rec, ctx)
                        UiBatch(
                            record = rec,
                            reviews = reviews,
                            selection = defaultSelection(reviews),
                        )
                    }
            }
            val context = ai.controller.contextAttachments()
            withContext(Dispatchers.Main) {
                _entries.value = entries
                _batches.value = list
                appliedRecords.value = applied
                contextAttachments.value = context
            }
        }
    }

    private fun defaultSelection(reviews: List<ActionReview>): Map<String, Boolean> =
        reviews.associate {
            it.action.clientActionId to
                (it.status == "ok" && !it.duplicateOfExisting && it.duplicateInBatch.isEmpty())
        }

    // ---------- 设置 ----------

    fun config() = ai.settings.config

    fun saveConfig(c: com.jsy.dayplan.ai.AiConfig) {
        ai.settings.config = c
        configVersion.value += 1
    }

    fun chatMode(): String = ai.settings.config.chatMode

    /** 切换聊天模式；普通聊天不使用附件，切换时清理已选附件。 */
    fun setChatMode(mode: String) {
        if (sending) return
        val normalized = AiConfig.normalizedChatMode(mode)
        if (normalized == ai.settings.config.chatMode) return
        if (normalized == AiConfig.MODE_PLAIN) {
            attachments.value.forEach { ai.attachmentStore.delete(it.id) }
            attachments.value = emptyList()
            attachmentMeta.value = emptyMap()
            pdfPages.value = emptyMap()
            xlsxSheets.value = emptyMap()
        }
        streamingReply.value = ""
        sendState.value = SendState.Idle
        ai.settings.config = ai.settings.config.copy(chatMode = normalized)
        configVersion.value += 1
    }

    fun readiness(): com.jsy.dayplan.ai.ChatController.Readiness = ai.controller.readiness()

    fun mockAvailable(): Boolean = com.jsy.dayplan.ai.MockMode.AVAILABLE

    fun setMockMode(on: Boolean) {
        if (!mockAvailable()) return
        if (on) {
            val demo = com.jsy.dayplan.ai.MockMode.demoTransport() ?: return
            ai.overrideTransportForDebug(demo)
        } else {
            ai.overrideTransportForDebug(com.jsy.dayplan.ai.HttpTransport())
        }
        configVersion.value += 1
    }

    fun isMockActive(): Boolean = ai.transport is com.jsy.dayplan.ai.DemoChatTransport

    /** 当前 Key 的掩码显示；未配置返回 null。 */
    fun maskedKey(): String? = ai.keyStore.loadApiKey()?.let { ai.keyStore.mask(it) }

    fun saveKey(plain: String): Pair<Boolean, String> {
        val trimmed = plain.trim()
        if (!ai.keyStore.looksLikeKey(trimmed)) {
            return false to "Key 格式应为 sk- 开头的字符串（仅保存在本机，不写入日志/备份）"
        }
        return try {
            ai.keyStore.saveApiKey(trimmed)
            configVersion.value += 1
            true to "已保存到本机加密存储"
        } catch (e: Exception) {
            false to "保存失败：${e.message ?: "Keystore 不可用"}"
        }
    }

    fun clearKey() {
        ai.keyStore.clear()
        configVersion.value += 1
    }

    fun keyLooksConfigured(): Boolean = ai.keyStore.loadApiKey() != null

    fun testConnection(cb: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { ai.client.testConnection() }
            if (r.first) refreshModalities()
            cb(r.first, r.second)
        }
    }

    fun refreshModalities() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val models = ai.client.listModels()
                ai.settings.cachedModalities = models.associate { it.id to it.modalities }
                configVersion.value += 1
            } catch (e: Exception) {
                // 拉取失败保持缓存/内置默认
            }
        }
    }

    fun availableModels(): List<ModelInfo> {
        val cached = ai.settings.cachedModalities.map { (id, m) -> ModelInfo(id, m) }
        return cached.ifEmpty {
            com.jsy.dayplan.ai.AiConfig.KNOWN_MODALITIES.map { (id, m) -> ModelInfo(id, m) }
        }
    }

    fun modalitiesOf(model: String): Set<String>? = ai.settings.modalitiesOf(model)

    // ---------- 附件 ----------

    fun addAttachment(uri: Uri?, mime: String?) {
        if (uri == null) return
        viewModelScope.launch(Dispatchers.IO) {
            val used = attachments.value.sumOf { it.sizeBytes }
            try {
                val att = ai.attachmentStore.copyIn(
                    getApplication<Application>().contentResolver, uri, mime,
                    extraBytesAlreadyUsed = used,
                )
                withContext(Dispatchers.Main) {
                    val cur = attachments.value
                    if (cur.size >= AttachLimits.MAX_COUNT) {
                        sendState.value = SendState.Failed("每条消息最多 ${AttachLimits.MAX_COUNT} 个附件", false)
                        ai.attachmentStore.delete(att.id)
                        return@withContext
                    }
                    attachments.value = cur + att
                    probeAttachment(att)
                }
            } catch (e: AttachmentException) {
                withContext(Dispatchers.Main) {
                    sendState.value = SendState.Failed(e.message ?: "附件读取失败", false)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    sendState.value = SendState.Failed("读取附件失败：${e.message ?: "未知错误"}", false)
                }
            }
        }
    }

    fun removeAttachment(id: String) {
        attachments.value = attachments.value.filter { it.id != id }
        attachmentMeta.value = attachmentMeta.value - id
        pdfPages.value = pdfPages.value - id
        xlsxSheets.value = xlsxSheets.value - id
        ai.attachmentStore.delete(id)
    }

    /** 探测 PDF 页数 / XLSX 工作表（本地快速读取，失败只提示不阻断移除）。 */
    private fun probeAttachment(att: PendingAttachment) {
        viewModelScope.launch(Dispatchers.IO) {
            val meta = when (att.kind) {
                AttachKind.PDF -> try {
                    AttachMeta(pdfPageCount = PdfTextExtractor.pageCount(att.file))
                } catch (e: Exception) {
                    AttachMeta(error = e.message ?: "PDF 读取失败")
                }

                AttachKind.XLSX -> try {
                    AttachMeta(sheets = DocParsers.listXlsxSheets(att.file))
                } catch (e: Exception) {
                    AttachMeta(error = e.message ?: "XLSX 读取失败")
                }

                else -> null
            } ?: return@launch
            withContext(Dispatchers.Main) { attachmentMeta.value = attachmentMeta.value + (att.id to meta) }
        }
    }

    fun setPdfPages(id: String, pages: Set<Int>) {
        pdfPages.value = pdfPages.value + (id to pages)
    }

    fun setXlsxSheet(id: String, sheet: String?) {
        xlsxSheets.value = if (sheet == null) xlsxSheets.value - id
        else xlsxSheets.value + (id to sheet)
    }

    // ---------- 发送 ----------

    fun send() {
        if (sending) return
        lastSavedNotice.value = null
        streamingReply.value = ""
        val text = input.value.trim()
        val atts = attachments.value
        val plain = ai.settings.config.chatMode == AiConfig.MODE_PLAIN
        if (text.isEmpty() && atts.isEmpty()) return
        if (plain && atts.isNotEmpty()) {
            sendState.value = SendState.Failed(
                "普通聊天模式不使用附件；请切换到“日程助手”再发送附件", false,
            )
            return
        }
        sendState.value = SendState.Sending(
            0, atts.size,
            when {
                plain -> "正在回复…"
                atts.isEmpty() -> "发送中…"
                else -> "准备附件…"
            },
        )
        sendJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val turn = ai.controller.send(
                    text,
                    atts,
                    AttachmentOptions(pdfPages.value, xlsxSheets.value),
                    onDelta = { delta -> streamingReply.update { it + delta } },
                ) { stage ->
                    sendState.value = SendState.Sending(0, atts.size, stage)
                }
                withContext(Dispatchers.Main) {
                    // 请求中仍可起草下一条消息；只清空本次已发送的文字。
                    if (input.value.trim() == text) input.value = ""
                    attachments.value = emptyList()
                    attachmentMeta.value = emptyMap()
                    pdfPages.value = emptyMap()
                    xlsxSheets.value = emptyMap()
                    streamingReply.value = ""
                    sendState.value = SendState.Idle
                    reload()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                withContext(Dispatchers.Main) {
                    streamingReply.value = ""
                    sendState.value = SendState.Idle
                }
                throw e
            } catch (e: AiException.Cancelled) {
                withContext(Dispatchers.Main) {
                    streamingReply.value = ""
                    sendState.value = SendState.Failed(e.message ?: "已取消", false)
                    reload()
                }
            } catch (e: AiException) {
                withContext(Dispatchers.Main) {
                    streamingReply.value = ""
                    sendState.value = SendState.Failed(
                        friendlyError(e),
                        retryable = e !is AiException.NotConfigured && e !is AiException.Capability,
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    streamingReply.value = ""
                    sendState.value = SendState.Failed("发送失败：${e.message ?: "未知错误"}", true)
                }
            }
        }
    }

    fun cancelSend() {
        sendJob?.cancel()
        streamingReply.value = ""
        sendState.value = SendState.Idle
    }

    private fun friendlyError(e: AiException): String = when (e) {
        is AiException.Auth -> "API Key 无效：${e.message}"
        is AiException.Quota -> "账户余额不足，请到 DeepSeek 平台充值后重试"
        is AiException.RateLimit -> "请求过于频繁，请稍等片刻再试"
        is AiException.Server -> e.message ?: "服务异常"
        is AiException.Timeout -> e.message ?: "请求超时"
        is AiException.Network -> e.message ?: "网络不可用"
        is AiException.NotConfigured -> e.message ?: "请先完成设置"
        is AiException.Capability -> e.message ?: "模型能力不匹配"
        is AiException.Protocol -> e.message ?: "返回内容异常"
        is AiException.Cancelled -> e.message ?: "已取消"
    }

    // ---------- 批次操作 ----------

    fun toggleSelect(batchId: String, actionId: String) = updateBatch(batchId) { ui ->
        ui.copy(selection = ui.selection + (actionId to !(ui.selection[actionId] ?: false)))
    }

    fun selectAll(batchId: String, all: Boolean) = updateBatch(batchId) { ui ->
        ui.copy(
            selection = ui.selection.mapValues { (id, _) ->
                all && (ui.reviews.find { it.action.clientActionId == id }?.status == "ok")
            },
        )
    }

    fun setOnlyIssues(batchId: String, only: Boolean) = updateBatch(batchId) { it.copy(onlyIssues = only) }

    /** 编辑草稿条目后重新保存批次并重审（后台执行，不阻塞输入）。 */
    fun updateAction(batchId: String, edited: com.jsy.dayplan.ai.AiAction) {
        viewModelScope.launch(Dispatchers.IO) {
            val rec = ai.batchStore.load(batchId) ?: return@launch
            val updated = rec.copy(
                actions = rec.actions.map {
                    if (it.clientActionId == edited.clientActionId) edited else it
                },
            )
            ai.batchStore.save(updated)
            val ctx = ai.executor.loadContext()
            val reviews = ai.executor.review(updated, ctx)
            withContext(Dispatchers.Main) {
                val list = _batches.value.toMutableList()
                val idx = list.indexOfFirst { it.record.batchId == batchId }
                if (idx >= 0) {
                    list[idx] = list[idx].copy(
                        record = updated,
                        reviews = reviews,
                        selection = list[idx].selection + (edited.clientActionId to true),
                    )
                    _batches.value = list
                }
            }
        }
    }

    fun applyBatch(batchId: String, onDone: (ApplyOutcome) -> Unit) {
        // 同步占位，双击时第二次直接返回；执行器/DB 仍做最终幂等
        if (batchId in busyBatchIds.value) return
        busyBatchIds.value = busyBatchIds.value + batchId
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ui = _batches.value.find { it.record.batchId == batchId }
                val selected = ui?.selection?.filterValues { it }?.keys ?: emptySet()
                val outcome = ai.executor.applySelected(batchId, selected)
                if (outcome is ApplyOutcome.Success) ai.controller.markBatchApplied(batchId)
                withContext(Dispatchers.Main) {
                    if (outcome is ApplyOutcome.Success) {
                        lastSavedNotice.value = outcome.batch.applied?.items?.size ?: 0
                    }
                    onDone(outcome)
                    reload()
                    app0.refreshWidgets()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    busyBatchIds.value = busyBatchIds.value - batchId
                }
            }
        }
    }

    fun undoBatch(batchId: String, onDone: (UndoOutcome) -> Unit) {
        if (batchId in busyBatchIds.value) return
        busyBatchIds.value = busyBatchIds.value + batchId
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val outcome = ai.executor.undo(batchId)
                withContext(Dispatchers.Main) {
                    onDone(outcome)
                    reload()
                    app0.refreshWidgets()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    busyBatchIds.value = busyBatchIds.value - batchId
                }
            }
        }
    }

    /** 已保存批次的结果展示（优先 Room 提交事实，JSON 丢失仍可撤销）。 */
    fun appliedBatch(batchId: String): BatchRecord? =
        appliedRecords.value[batchId] ?: ai.batchStore.load(batchId)

    fun removeContextAttachment(id: String) {
        ai.controller.removeContextAttachment(id)
        contextAttachments.value = ai.controller.contextAttachments()
    }

    fun clearConversation() {
        viewModelScope.launch(Dispatchers.IO) {
            ai.controller.clearConversation(ai.attachmentStore)
            withContext(Dispatchers.Main) {
                attachments.value = emptyList()
                attachmentMeta.value = emptyMap()
                pdfPages.value = emptyMap()
                xlsxSheets.value = emptyMap()
                contextAttachments.value = emptyList()
                appliedRecords.value = emptyMap()
                lastSavedNotice.value = null
                streamingReply.value = ""
                reload()
            }
        }
    }

    private fun updateBatch(batchId: String, transform: (UiBatch) -> UiBatch) {
        val list = _batches.value.toMutableList()
        val idx = list.indexOfFirst { it.record.batchId == batchId }
        if (idx < 0) return
        list[idx] = transform(list[idx])
        _batches.value = list
    }
}
