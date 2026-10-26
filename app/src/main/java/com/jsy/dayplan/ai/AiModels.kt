package com.jsy.dayplan.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/**
 * AI 规划协议（v1.3）：模型只产生候选动作，真正的写入由
 * PlanDraftExecutor 在本地校验后完成。协议示例见需求第 5 节。
 */
object AiProtocol {
    const val SCHEMA_VERSION = 1
    val OPS_CREATE = setOf("create_schedule", "create_todo", "create_countdown")
    val OPS_UPDATE = setOf("update_schedule", "update_todo", "update_countdown")
    val OPS_ALL = OPS_CREATE + OPS_UPDATE

    /** 单批预览/保存的动作数上限；超出部分分批展示，不伪报全部完成。 */
    const val PAGE_SIZE = 20

    /** 单次响应最多接受的动作数；超过直接拒绝，要求用户分批发送。 */
    const val MAX_ACTIONS = 100

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    /**
     * 严格解析：JSON 合法不等于业务合法，只负责结构与字段类型。
     * 兼容 markdown 代码块、JSON 前后的说明文字，以及被再次包成字符串的响应。
     */
    fun parseResponse(text: String): AiPlanResponse {
        val candidate = extractJsonObject(text) ?: text.trim()
        return try {
            var element = json.parseToJsonElement(candidate)
            if (element is JsonPrimitive && element.isString) {
                element = json.parseToJsonElement(element.content)
            }
            json.decodeFromJsonElement(AiPlanResponse.serializer(), element)
        } catch (e: Exception) {
            throw AiException.Protocol("模型返回内容不是合法 JSON：${e.message?.take(160)}")
        }
    }

    /** 从带 markdown 代码块或前后说明的文本中提取第一个完整的 JSON 对象。 */
    fun extractJsonObject(text: String): String? {
        val stripped = stripCodeFence(text.trim())
        if (stripped.isBlank()) return null
        // 响应是数组（如 [{"kind":...}]）时不截取第一个对象，避免静默丢掉其余动作
        val firstBracket = stripped.indexOf('[')
        val firstBrace = stripped.indexOf('{')
        if (firstBracket >= 0 && (firstBrace < 0 || firstBracket < firstBrace)) return null
        return firstBalancedObject(stripped)
    }

    private fun stripCodeFence(text: String): String {
        if (!text.contains("```")) return text
        val fenced = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.trim()
        return fenced ?: text
    }

    private fun firstBalancedObject(text: String): String? {
        var start = -1
        var depth = 0
        var inString = false
        var escaped = false
        for (i in text.indices) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> {
                        if (depth == 0) start = i
                        depth++
                    }
                    '}' -> if (depth > 0) {
                        depth--
                        if (depth == 0 && start >= 0) return text.substring(start, i + 1)
                    }
                }
            }
        }
        return null
    }
}

@Serializable
data class AiPlanResponse(
    val schemaVersion: Int = 0,
    val kind: String = "chat",
    val reply: String = "",
    val questions: List<String> = emptyList(),
    val actions: List<AiAction> = emptyList(),
)

@Serializable
data class AiAction(
    val clientActionId: String = "",
    val op: String = "",
    val title: String = "",
    /** create/update_schedule：yyyy-MM-dd。 */
    val startDate: String? = null,
    val endDate: String? = null,
    val allDay: Boolean = false,
    val startTime: String? = null,
    val endTime: String? = null,
    /** 日程分类 0学习 1工作 2生活 3其他。 */
    val category: Int? = null,
    /** 0=关闭（保持现有语义），15/30/60/120=提前分钟。 */
    val remindMinutesBefore: Int? = null,
    val location: String = "",
    val note: String = "",
    /** create/update_todo。 */
    val dueDate: String? = null,
    val dueTime: String? = null,
    val priority: Int? = null,
    /** create/update_countdown。 */
    val targetDate: String? = null,
    val countdownCategory: Int? = null,
    val yearlyRepeat: Boolean? = null,
    val remindEnabled: Boolean? = null,
    val remindDaysBefore: Int? = null,
    val remindTime: String? = null,
    /** 修改必须携带候选目标与版本。 */
    val targetId: Long? = null,
    val expectedVersion: Int? = null,
    /** 模型声明的假设；含任何假设都不能自动保存。 */
    val assumptions: List<String> = emptyList(),
    /** 证据来源（附件段落/页/单元格 id）；本地必须能对上，否则拒绝。 */
    val sourceIds: List<String> = emptyList(),
)

/** 供 UI 展示的单条动作摘要（含本地校验结果），与 AiAction 一一对应。 */
data class ActionReview(
    val action: AiAction,
    /** ok / invalid（不可保存，需补全或取消）/ warning（可保存但有提示）。 */
    val status: String,
    val problems: List<String> = emptyList(),
    val conflicts: List<String> = emptyList(),
    val duplicateOfExisting: Boolean = false,
    /** 批内重复的其他动作 clientActionId。 */
    val duplicateInBatch: List<String> = emptyList(),
    val sourceLabel: String = "",
    /** 修改动作的变更前摘要（创建动作为空），用于界面展示“变更前后”。 */
    val beforeLine: String = "",
)

/** AI 相关错误分类：界面据此给出可读说明，不写库、不无限重试。 */
sealed class AiException(message: String) : Exception(message) {
    class NotConfigured(message: String) : AiException(message)
    class Auth(message: String) : AiException(message)
    class Quota(message: String) : AiException(message)
    class RateLimit(message: String) : AiException(message)
    class Server(message: String) : AiException(message)
    class Network(message: String) : AiException(message)
    class Timeout(message: String) : AiException(message)
    class Cancelled(message: String) : AiException(message)
    class Protocol(message: String) : AiException(message)
    /** 选择了不支持图片的模型等能力不匹配。 */
    class Capability(message: String) : AiException(message)
}
