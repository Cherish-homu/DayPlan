package com.jsy.dayplan.ai

import com.jsy.dayplan.data.CountdownEntity
import com.jsy.dayplan.data.ScheduleEntity
import com.jsy.dayplan.data.TodoEntity
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** 已保存条目引用（真实 ID + AI 写入后的版本，撤销时核对）。 */
@Serializable
data class SavedRef(
    val clientActionId: String,
    val kind: String,
    val id: Long,
    val versionAfter: Int,
    val title: String,
)

/** 修改动作的前值（撤销快照的最小必要内容）。 */
@Serializable
data class PrevEntity(
    val clientActionId: String,
    val kind: String,
    val id: Long,
    val versionAfter: Int,
    val schedule: ScheduleEntity? = null,
    val todo: TodoEntity? = null,
    val countdown: CountdownEntity? = null,
)

@Serializable
data class AppliedRecord(
    val atEpochMs: Long,
    val appliedActionIds: List<String>,
    val items: List<SavedRef>,
    val previous: List<PrevEntity>,
    val remindersPending: Boolean,
    val reminderError: String = "",
)

/** 一批候选计划：持久化为 JSON 文件，进程回收后状态一致。 */
@Serializable
data class BatchRecord(
    val batchId: String,
    val requestId: String,
    val sessionId: String,
    val createdAtEpochMs: Long,
    val dataGeneration: Int,
    /** text：纯文字对话；attachment：含附件来源（始终先预览，不自动保存）。 */
    val origin: String,
    val reply: String,
    val questions: List<String> = emptyList(),
    val actions: List<AiAction> = emptyList(),
    /** 本批有效的来源 id（附件段落/图片）；空表示纯文字批次。 */
    val validSourceIds: Set<String> = emptySet(),
    /** 本批引用的附件 SHA256（重复导入提示用）。 */
    val attachmentShas: Set<String> = emptySet(),
    /** 本批附件缓存 id 与显示名（进程回收后仍能提示来源/是否已清理）。 */
    val attachmentIds: List<String> = emptyList(),
    val attachmentNames: List<String> = emptyList(),
    /** 附件解析/范围/预算提示（界面必须展示，不能静默丢内容）。 */
    val warnings: List<String> = emptyList(),
    var state: String, // PENDING / APPLIED / UNDONE / CANCELLED
    var applied: AppliedRecord? = null,
) {
    val isApplied: Boolean get() = state == "APPLIED"
    val isPending: Boolean get() = state == "PENDING"
}

/** 批次文件存储：files/ai/batches/<batchId>.json；可恢复原子替换 + 回读校验。 */
class BatchStore(private val dir: File) {

    fun save(record: BatchRecord) {
        dir.mkdirs()
        val text = AiProtocol.json.encodeToString(record)
        val f = File(dir, record.batchId + ".json")
        val tmp = File(dir, record.batchId + ".json.tmp")
        val backup = File(dir, record.batchId + ".json.bak")
        tmp.writeText(text, Charsets.UTF_8)
        // 回读校验，防半截文件
        val back = AiProtocol.json.decodeFromString<BatchRecord>(tmp.readText(Charsets.UTF_8))
        if (back.batchId != record.batchId) throw IllegalStateException("批次回读不一致")
        // 保留旧文件可回退；优先原子替换，不支持时再普通替换
        if (f.exists()) f.copyTo(backup, overwrite = true)
        try {
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
            }
        } catch (e: Exception) {
            if (!f.exists() && backup.exists()) backup.copyTo(f, overwrite = true)
            tmp.delete()
            throw e
        } finally {
            backup.delete()
        }
    }

    fun load(batchId: String): BatchRecord? {
        val f = File(dir, "$batchId.json")
        val backup = File(dir, "$batchId.json.bak")
        fun read(file: File): BatchRecord? = try {
            if (!file.exists()) null
            else AiProtocol.json.decodeFromString<BatchRecord>(file.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            null
        }
        return read(f) ?: read(backup)
    }

    fun all(): List<BatchRecord> =
        dir.listFiles { f -> f.name.endsWith(".json") }
            ?.mapNotNull { load(it.name.removeSuffix(".json")) }
            ?: emptyList()

    /** 清理：已撤销/取消的批次与过期批次（保留近期供重复导入检测）。 */
    fun prune(keepMillis: Long = 14L * 24 * 3600 * 1000, nowMs: Long) {
        dir.listFiles { f -> f.name.endsWith(".json") }?.forEach { f ->
            val r = load(f.name.removeSuffix(".json")) ?: run { f.delete(); return@forEach }
            val terminal = r.state == "UNDONE" || r.state == "CANCELLED"
            if ((terminal && nowMs - r.createdAtEpochMs > keepMillis) ||
                (r.isPending && nowMs - r.createdAtEpochMs > keepMillis)
            ) {
                f.delete()
            }
        }
    }

    fun clearAll() {
        dir.listFiles()?.forEach { it.delete() }
    }

    /** 同一附件 SHA 是否曾成功导入（重复导入提示，不禁止再次导入）。 */
    fun findAppliedByAttachmentSha(sha256: String, batches: List<BatchRecord> = all()): BatchRecord? =
        batches.firstOrNull { b -> sha256 in b.attachmentShas && b.state == "APPLIED" }
}
