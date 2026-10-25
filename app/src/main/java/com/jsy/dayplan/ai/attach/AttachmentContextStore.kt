package com.jsy.dayplan.ai.attach

import com.jsy.dayplan.ai.AiProtocol
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** 会话内仍可用于续聊的附件材料（只存引用与范围，不存 Key/业务数据）。 */
@Serializable
data class StoredAttachment(
    val id: String,
    val fileName: String,
    val mime: String,
    val kindName: String,
    val sha256: String,
    val sizeBytes: Long,
    val filePath: String,
    val pdfPages: Set<Int> = emptySet(),
    val xlsxSheet: String? = null,
    val addedAtEpochMs: Long,
)

@Serializable
data class StoredContext(
    val sessionId: String,
    val dataGeneration: Int,
    val attachments: List<StoredAttachment> = emptyList(),
    val updatedAtEpochMs: Long,
)

/**
 * 附件续聊上下文：按会话有界保存最近一轮材料引用/选页/选表。
 * 文件位于应用私有目录，不参与业务 JSON 备份；缓存缺失时必须要求重选。
 * dir=null 时使用内存实现（仅测试/未注入的场景）。
 */
class AttachmentContextStore(
    private val dir: File?,
    private val maxAttachments: Int = AttachLimits.MAX_COUNT,
) {
    private val memory = mutableMapOf<String, StoredContext>()

    private fun file(sessionId: String): File? {
        val root = dir ?: return null
        return File(root, "ctx_$sessionId.json")
    }

    fun load(sessionId: String, generation: Int): StoredContext? {
        val ctx = read(sessionId) ?: return null
        if (ctx.sessionId != sessionId || ctx.dataGeneration != generation) {
            clear(sessionId)
            return null
        }
        return ctx
    }

    private fun read(sessionId: String): StoredContext? {
        val f = file(sessionId)
        if (f == null) return memory[sessionId]
        if (!f.exists()) return null
        return try {
            AiProtocol.json.decodeFromString<StoredContext>(f.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            f.delete()
            null
        }
    }

    fun save(ctx: StoredContext) {
        val bounded = ctx.copy(attachments = ctx.attachments.distinctBy { it.id }.take(maxAttachments))
        val f = file(ctx.sessionId)
        if (f == null) {
            memory[ctx.sessionId] = bounded
            return
        }
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(AiProtocol.json.encodeToString(bounded), Charsets.UTF_8)
        try {
            java.nio.file.Files.move(
                tmp.toPath(), f.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(
                tmp.toPath(), f.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            tmp.delete()
        }
    }

    fun remove(sessionId: String, attachmentId: String) {
        val ctx = read(sessionId) ?: return
        save(ctx.copy(attachments = ctx.attachments.filterNot { it.id == attachmentId }))
    }

    fun clear(sessionId: String) {
        memory.remove(sessionId)
        file(sessionId)?.delete()
    }

    fun clearAll() {
        memory.clear()
        dir?.listFiles { f -> f.name.startsWith("ctx_") }?.forEach { it.delete() }
    }

    /** 待确认批次/上下文引用的附件 id，清理时保留。 */
    fun referencedIds(): Set<String> {
        val ids = mutableSetOf<String>()
        memory.values.forEach { c -> c.attachments.forEach { ids.add(it.id) } }
        dir?.listFiles { f -> f.name.startsWith("ctx_") && f.name.endsWith(".json") }?.forEach { f ->
            runCatching {
                AiProtocol.json.decodeFromString<StoredContext>(f.readText(Charsets.UTF_8))
                    .attachments.forEach { ids.add(it.id) }
            }
        }
        return ids
    }

    fun currentAttachments(sessionId: String, generation: Int): List<StoredAttachment> =
        load(sessionId, generation)?.attachments ?: emptyList()

    /** 只保留当前会话与保留期内的上下文文件。 */
    fun prune(nowMs: Long, keepMillis: Long, currentSessionId: String?) {
        val list = dir?.listFiles { f -> f.name.startsWith("ctx_") && f.name.endsWith(".json") }
            ?: return
        list.forEach { f ->
            val name = f.name.removePrefix("ctx_").removeSuffix(".json")
            if (name != currentSessionId && nowMs - f.lastModified() > keepMillis) f.delete()
        }
    }
}
