package com.jsy.dayplan.ai

import com.jsy.dayplan.core.AppClock
import java.util.UUID
import kotlinx.serialization.json.*

/** One chat entry point. Tools only stage proposals; confirmation still uses PlanDraftExecutor. */
internal class AssistantConversation(
    private val client: DeepSeekClient,
    private val settings: AiSettingsStore,
    private val clock: AppClock,
    private val executor: PlanDraftExecutor,
    private val store: BatchStore,
    private val history: ChatStore,
    private val nowProvider: () -> Long,
) {
    private data class Prepared(val result: ChatResult, val old: BatchRecord? = null,
        val proposal: AssistantDrafts.Proposal? = null, val cancel: Boolean = false)

    suspend fun send(raw: String, onDelta: ((String) -> Unit)?, onStage: ((String) -> Unit)?, onReset: (() -> Unit)?): ChatTurn {
        val text = AiPrompts.clampUserInput(raw.trim())
        if (text.isBlank()) throw AiException.Protocol("请输入想聊或想安排的事情")
        val config = settings.config
        val (generation, session) = executor.currentBoundary()
        val pending = store.all().filter { it.origin == "assistant" && it.isPending &&
            it.sessionId == session && it.dataGeneration == generation }.sortedBy { it.createdAtEpochMs }
        val messages = buildList {
            add(WireMessage.text("system", AssistantDrafts.system(clock, config, pending.takeLast(5)) +
                "\n每轮最多调用一次工具；多件事放进同一次 propose_draft 的 actions。历史中‘不能安排日程’的说明已过时，以当前工具能力为准。"))
            AiPrompts.trimHistory(history.load().filter { it.attachmentNames.isEmpty() }
                .map { it.role to it.text }).forEach { (role, value) -> add(WireMessage.text(role, value)) }
            add(WireMessage.text("user", text))
        }
        fun decode(result: ChatResult): Prepared {
            when (result.finishReason) {
                "stop", "tool_calls" -> Unit
                "length" -> throw AiException.Protocol("回复被截断，尚未保存；请减少单次事项后重试")
                "content_filter" -> throw AiException.Protocol("回复被服务过滤，尚未保存；请调整表述")
                else -> throw AiException.Protocol("回复未正常完成（${result.finishReason.take(40)}），尚未保存")
            }
            if (result.toolCalls.isEmpty()) {
                if (result.content.isBlank() || result.content.trim() in setOf("null", "undefined", "{}", "[]") || result.finishReason == "tool_calls")
                    throw AiException.Protocol("没有收到有效答复，尚未保存")
                return Prepared(result)
            }
            if (result.toolCalls.size != 1) throw AiException.Protocol("请把事项合并为一次草稿工具调用")
            val tool = result.toolCalls.single()
            if (tool.id.isBlank() || tool.name !in setOf("propose_draft", "cancel_draft"))
                throw AiException.Protocol("工具回复不完整或不受支持，尚未保存")
            val args = runCatching { AiProtocol.json.parseToJsonElement(tool.arguments) as? JsonObject }.getOrNull()
                ?: throw AiException.Protocol("草稿参数没有完整返回，尚未保存")
            val id = args["draftId"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (tool.name == "cancel_draft") {
                val old = pending.find { it.batchId == id } ?: throw AiException.Protocol("只能取消当前未保存草稿，不能删除已保存数据")
                return Prepared(result, old = old, cancel = true)
            }
            val operation = args["operation"]?.jsonPrimitive?.contentOrNull
            val old = when (operation) {
                "create" -> {
                    if (id.isNotBlank()) throw AiException.Protocol("新草稿不能引用已有 draftId")
                    null
                }
                "update" -> pending.find { it.batchId == id } ?: if (id.isBlank() && pending.size == 1) pending.single()
                    else throw AiException.Protocol("请明确要补充哪份未保存草稿")
                else -> throw AiException.Protocol("草稿缺少 create 或 update 操作")
            }
            return Prepared(result, old, AssistantDrafts.prepare(args, old, config))
        }
        fun prepare(result: ChatResult): Prepared = try { decode(result) }
            catch (e: AiException) { throw e }
            catch (e: Exception) { throw AiException.Protocol("草稿字段类型不正确，尚未保存") }
        suspend fun checkBoundary() {
            val current = executor.currentBoundary()
            if (current.first != generation || current.second != session || settings.dataGeneration != generation || settings.chatSessionId != session)
                throw AiException.Cancelled("对话已清空或数据已恢复，本次回复已丢弃，未保存")
        }
        onStage?.invoke("正在回复或整理草稿…")
        var firstFailure: AiException? = null
        val first = try {
            client.streamComplete(config.model, messages, tools = AssistantDrafts.tools, onDelta = { onDelta?.invoke(it) })
        } catch (e: AiException) {
            if (e !is AiException.Protocol && e !is AiException.Network && e !is AiException.Timeout) throw e
            firstFailure = e
            null
        }
        checkBoundary()
        var prepared = first?.let {
            // Terminal responses are reported immediately, without retrying an unchanged token budget or filter.
            if (it.finishReason !in setOf("stop", "tool_calls")) prepare(it)
            else try { prepare(it) } catch (e: AiException.Protocol) { firstFailure = e; null }
        }
        if (prepared == null) {
            onReset?.invoke()
            onStage?.invoke("回复未完整收到，正在用普通连接重试一次…")
            val retryMessages = messages.toMutableList()
            val firstSystem = retryMessages.first().content.jsonPrimitive.content
            retryMessages[0] = WireMessage.text("system", firstSystem +
                "\n上次回复未完成或格式不正确。请给出有效中文正文或一次完整工具调用。工具必须使用已声明的字段，不能只输出思考；不要声称已经保存。" +
                "\n需要修正的问题：${firstFailure?.message?.take(140) ?: "回复为空"}")
            val result = client.complete(config.model, retryMessages, jsonMode = false, temperature = 0.4,
                disableThinking = false, tools = AssistantDrafts.tools)
            checkBoundary()
            prepared = try { prepare(result) } catch (e: AiException.Protocol) {
                throw AiException.Protocol("这次未收到可用回复，尚未保存。原话已保留，可点重试。${e.message?.let { "（$it）" }.orEmpty()}")
            }
        }
        val done = prepared
        checkBoundary()
        done.old?.let { old ->
            if (store.load(old.batchId) != old) throw AiException.Cancelled("这份草稿刚刚已被修改或保存，请核对最新卡片")
        }
        val timestamp = maxOf(nowProvider(), (history.load().lastOrNull()?.atEpochMs ?: 0) + 1)
        val user = ChatEntry("user", text, timestamp, mode = AiConfig.MODE_ASSISTANT)
        val usage = done.result.usage?.display().orEmpty()
        if (done.cancel) {
            val old = done.old!!
            store.save(old.copy(state = "CANCELLED"))
            val reply = ChatEntry("assistant", "草稿已取消，未创建任何日程。", timestamp, usageLine = usage, mode = AiConfig.MODE_ASSISTANT)
            replaceHistory(old.batchId, user, reply)
            onReset?.invoke()
            return ChatTurn(user, reply, null, emptyList(), null, emptyList(), 0)
        }
        val proposal = done.proposal
        if (proposal == null) {
            val reply = ChatEntry("assistant", done.result.content.trim(), timestamp, usageLine = usage, mode = AiConfig.MODE_ASSISTANT)
            history.append(listOf(user, reply))
            return ChatTurn(user, reply, null, emptyList(), null, emptyList(), 0)
        }
        val label = if (proposal.questions.isEmpty()) "草稿已准备好，尚未保存；核对后点确认保存。"
            else "已保留草稿，尚未保存。补充后可以继续确认。"
        val batch = BatchRecord(
            batchId = done.old?.batchId ?: "b_" + UUID.randomUUID().toString().take(12),
            requestId = UUID.randomUUID().toString(), sessionId = session,
            createdAtEpochMs = done.old?.createdAtEpochMs ?: timestamp, dataGeneration = generation,
            origin = "assistant", reply = label, questions = proposal.questions, actions = proposal.actions, state = "PENDING",
        )
        val context = executor.loadContext()
        val reviews = executor.review(batch, context)
        checkBoundary()
        try { store.save(batch) } catch (e: Exception) { throw AiException.Protocol("草稿暂存失败，尚未写入日程；请重试") }
        val reply = ChatEntry("assistant", label, timestamp, kind = "plan", batchId = batch.batchId,
            usageLine = usage, mode = AiConfig.MODE_ASSISTANT)
        replaceHistory(batch.batchId, user, reply)
        onReset?.invoke()
        return ChatTurn(user, reply, batch.batchId, reviews, null, emptyList(), proposal.actions.size)
    }

    private fun replaceHistory(id: String, user: ChatEntry, reply: ChatEntry) {
        history.saveAll(history.load().map { entry ->
            if (entry.batchId == id && entry.kind == "plan") entry.copy(kind = "text", batchId = null,
                text = entry.text + "\n（这份草稿已有后续更新，请看最新卡片。）") else entry
        } + listOf(user, reply))
    }
}
