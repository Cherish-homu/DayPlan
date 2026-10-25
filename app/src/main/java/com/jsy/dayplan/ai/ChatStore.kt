package com.jsy.dayplan.ai

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** 聊天记录条目（不含 Key、请求鉴权与附件原始内容）。 */
@Serializable
data class ChatEntry(
    val role: String,          // user / assistant
    val text: String,
    val atEpochMs: Long,
    /** text / plan / result / error / question */
    val kind: String = "text",
    val batchId: String? = null,
    val attachmentNames: List<String> = emptyList(),
    val usageLine: String = "",
)

/** 聊天历史持久化：files/ai/chat_history.json，上限 200 条。 */
class ChatStore(private val file: File) {

    private val maxEntries = 200

    fun load(): List<ChatEntry> = try {
        if (!file.exists()) emptyList()
        else AiProtocol.json.decodeFromString<List<ChatEntry>>(file.readText(Charsets.UTF_8))
            .takeLast(maxEntries)
    } catch (e: Exception) {
        emptyList()
    }

    fun append(entries: List<ChatEntry>) {
        saveAll(load() + entries)
    }

    /** 覆盖保存（用于把已确认的计划条目改成“已保存结果”）。 */
    fun saveAll(entries: List<ChatEntry>) {
        val merged = entries.takeLast(maxEntries)
        file.parentFile?.mkdirs()
        val text = AiProtocol.json.encodeToString(merged)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.writeText(text, Charsets.UTF_8)
            tmp.delete()
        }
    }

    fun clear() {
        file.delete()
    }
}
