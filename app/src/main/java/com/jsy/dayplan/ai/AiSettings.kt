package com.jsy.dayplan.ai

import android.content.Context
import java.util.UUID

/** AI 助手运行配置（不含 Key）。 */
data class AiConfig(
    val enabled: Boolean = false,
    val model: String = DEFAULT_MODEL,
    val autoSave: Boolean = false,
    /** 发送所选日期范围的忙碌时间摘要与候选条目（标题/ID/版本）。 */
    val sendExistingSummary: Boolean = true,
    /** 规划偏好：默认时长（分钟）。 */
    val defaultDurationMinutes: Int = 30,
    /** 可安排时段（本地时间）。 */
    val planWindowStart: String = "08:00",
    val planWindowEnd: String = "22:00",
    val showWhale: Boolean = true,
    val noticeAcknowledged: Boolean = false,
) {
    companion object {
        const val DEFAULT_MODEL = "deepseek-flash"

        /** 官方文档（2026-10-25 核对）：Flash 支持 text+image，Pro 仅 text。 */
        val KNOWN_MODALITIES = mapOf(
            "deepseek-flash" to setOf("text", "image"),
            "deepseek-v4-pro" to setOf("text"),
        )
    }
}

/**
 * AI 设置与状态存取。设置位于 SharedPreferences；
 * 数据世代（dataGeneration）在备份恢复/快照回退后递增，使旧草稿与撤销失效。
 */
class AiSettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)
    private val state = context.getSharedPreferences("ai_state", Context.MODE_PRIVATE)

    var config: AiConfig
        get() = AiConfig(
            enabled = prefs.getBoolean("enabled", false),
            model = prefs.getString("model", AiConfig.DEFAULT_MODEL) ?: AiConfig.DEFAULT_MODEL,
            autoSave = prefs.getBoolean("autoSave", false),
            sendExistingSummary = prefs.getBoolean("sendExisting", true),
            defaultDurationMinutes = prefs.getInt("duration", 30).coerceIn(10, 240),
            planWindowStart = prefs.getString("winStart", "08:00") ?: "08:00",
            planWindowEnd = prefs.getString("winEnd", "22:00") ?: "22:00",
            showWhale = prefs.getBoolean("showWhale", true),
            noticeAcknowledged = prefs.getBoolean("noticeAck", false),
        )
        set(value) {
            prefs.edit()
                .putBoolean("enabled", value.enabled)
                .putString("model", value.model)
                .putBoolean("autoSave", value.autoSave)
                .putBoolean("sendExisting", value.sendExistingSummary)
                .putInt("duration", value.defaultDurationMinutes)
                .putString("winStart", value.planWindowStart)
                .putString("winEnd", value.planWindowEnd)
                .putBoolean("showWhale", value.showWhale)
                .putBoolean("noticeAck", value.noticeAcknowledged)
                .apply()
        }

    /** 模型能力缓存（GET /models 的 input_modalities）。 */
    var cachedModalities: Map<String, Set<String>>
        get() {
            val raw = prefs.getString("modalities", null) ?: return emptyMap()
            return try {
                val obj = org.json.JSONObject(raw)
                obj.keys().asSequence().associateWith { k ->
                    obj.getJSONArray(k).let { arr -> (0 until arr.length()).map { arr.getString(it) }.toSet() }
                }
            } catch (e: Exception) {
                emptyMap()
            }
        }
        set(value) {
            val obj = org.json.JSONObject()
            value.forEach { (k, v) -> obj.put(k, org.json.JSONArray(v)) }
            prefs.edit().putString("modalities", obj.toString()).apply()
        }

    fun modalitiesOf(model: String): Set<String>? =
        cachedModalities[model] ?: AiConfig.KNOWN_MODALITIES[model]

    // ---------- 数据世代与聊天会话 ----------

    /** 备份恢复/快照回退/清空对话后递增；旧的待执行草稿与撤销随之失效。 */
    fun bumpDataGeneration() {
        state.edit().putInt("dataGeneration", dataGeneration + 1).apply()
    }

    val dataGeneration: Int get() = state.getInt("dataGeneration", 1)

    /**
     * 边界尚未写入 Room 的标记：清空对话/本地会话变化时置位。
     * 启动时仅在 DB 没有更高世代时才把本地缓存提升为 DB 边界，绝不用旧偏好覆盖新 DB。
     */
    var boundaryDirty: Boolean
        get() = state.getBoolean("boundaryDirty", false)
        set(value) { state.edit().putBoolean("boundaryDirty", value).apply() }

    /** 只更新本地缓存（不改变 dirty）。 */
    fun cacheBoundary(generation: Int, sessionId: String) {
        state.edit()
            .putInt("dataGeneration", generation)
            .putString("chatSession", sessionId)
            .apply()
    }

    /** 边界已经与 DB 一致后调用。 */
    fun markBoundaryPersisted(generation: Int, sessionId: String) {
        state.edit()
            .putInt("dataGeneration", generation)
            .putString("chatSession", sessionId)
            .putBoolean("boundaryDirty", false)
            .apply()
    }

    /** 清空对话：新会话标记为待持久化；调用方同步更新内存 gate。 */
    fun newChatSessionDirty(generation: Int): String {
        val id = UUID.randomUUID().toString()
        state.edit()
            .putInt("dataGeneration", generation)
            .putString("chatSession", id)
            .putBoolean("boundaryDirty", true)
            .apply()
        return id
    }

    /** 当前聊天会话；清空对话后更换，旧会话的晚到响应不再写库。 */
    fun newChatSession(): String {
        val id = UUID.randomUUID().toString()
        state.edit().putString("chatSession", id).apply()
        return id
    }

    val chatSessionId: String
        get() = state.getString("chatSession", null) ?: newChatSession()
}
