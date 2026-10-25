package com.jsy.dayplan.ai.attach

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/** 附件格式（扩展名 + MIME + 文件头联合判定后的结论）。 */
enum class AttachKind(val display: String) {
    IMAGE_JPEG("图片 JPEG"),
    IMAGE_PNG("图片 PNG"),
    IMAGE_WEBP("图片 WebP"),
    PDF("PDF"),
    DOCX("Word DOCX"),
    XLSX("表格 XLSX"),
    TXT("文本 TXT"),
    MD("Markdown"),
    CSV("CSV"),
    UNSUPPORTED("不支持的格式"),
}

data class PendingAttachment(
    val id: String,
    val fileName: String,
    val mime: String,
    val kind: AttachKind,
    val file: File,
    val sizeBytes: Long,
    val sha256: String,
) {
    val sizeLabel: String
        get() = if (sizeBytes >= 1024 * 1024) {
            "%.1f MiB".format(sizeBytes / (1024.0 * 1024.0))
        } else {
            "%.0f KiB".format(sizeBytes / 1024.0)
        }
}

/** 附件限额（第 4A 节）：每消息 6 个、单附件 10 MiB、合计 20 MiB。 */
object AttachLimits {
    const val MAX_COUNT = 6
    const val MAX_SINGLE_BYTES = 10L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 20L * 1024 * 1024
    /** 图片编码后（base64 data URL）单图上限。 */
    const val IMAGE_ENCODED_MAX_BYTES = 3L * 1024 * 1024
    /** 一条消息内全部图片编码后的合计上限（控制请求体与内存）。 */
    const val IMAGES_TOTAL_MAX_BYTES = 8L * 1024 * 1024
    /** 解码像素上限 1600 万。 */
    const val IMAGE_MAX_PIXELS = 16_000_000
}

class AttachmentException(message: String) : Exception(message)

/**
 * SAF/Photo Picker 的 content URI 不猜测磁盘路径：复制到 App 私有附件目录，
 * 用生成 ID 命名避免同名覆盖/路径穿越，不改用户原文件；流式计数与 SHA256。
 */
class AttachmentStore(private val dir: File) {

    fun attachmentsDir(): File = dir.apply { mkdirs() }

    fun currentTotalBytes(): Long =
        dir.listFiles()?.sumOf { it.length() } ?: 0

    /**
     * 复制并登记附件。超限在读流时实际计数发现，立即中止并删除半截文件。
     */
    fun copyIn(
        resolver: ContentResolver,
        uri: Uri,
        declaredMime: String?,
        extraBytesAlreadyUsed: Long = 0,
    ): PendingAttachment {
        val name = queryDisplayName(resolver, uri)
        val id = UUID.randomUUID().toString().take(8)
        val ext = name.substringAfterLast('.', "").lowercase().take(8)
        val target = File(attachmentsDir(), "att_$id" + if (ext.isNotBlank()) ".$ext" else "")
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        try {
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > AttachLimits.MAX_SINGLE_BYTES) {
                            throw AttachmentException("「$name」超过单附件 10 MiB 上限")
                        }
                        if (total + extraBytesAlreadyUsed > AttachLimits.MAX_TOTAL_BYTES) {
                            throw AttachmentException("附件总大小将超过 20 MiB 上限")
                        }
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                }
            } ?: throw AttachmentException("无法读取所选文件（URI 已失效或无权限）")
        } catch (e: AttachmentException) {
            target.delete(); throw e
        } catch (e: Exception) {
            target.delete()
            throw AttachmentException("读取「$name」失败：${e.message?.take(120) ?: "未知错误"}")
        }
        if (total == 0L) {
            target.delete()
            throw AttachmentException("「$name」是空文件")
        }
        val head = readHead(target)
        val kind = sniff(name, declaredMime, head)
        if (kind == AttachKind.UNSUPPORTED) {
            target.delete()
            val mimeLabel = declaredMime ?: "未知类型"
            throw AttachmentException(
                "「$name」是不支持的格式（$mimeLabel）。" +
                    "支持：JPG/PNG/WebP、PDF、TXT/Markdown、CSV、DOCX、XLSX；" +
                    "旧版 DOC/XLS、加密文档或压缩包请先另存为支持的格式",
            )
        }
        return PendingAttachment(
            id = id,
            fileName = name,
            mime = declaredMime ?: "",
            kind = kind,
            file = target,
            sizeBytes = total,
            sha256 = digest.digest().joinToString("") { "%02x".format(it) },
        )
    }

    private fun readHead(f: File): ByteArray {
        val head = ByteArray(64)
        f.inputStream().use { ins ->
            var read = 0
            while (read < head.size) {
                val n = ins.read(head, read, head.size - read)
                if (n < 0) break
                read += n
            }
            return head.copyOf(read)
        }
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String =
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx)?.take(120)?.let { return it }
                }
            }
            val fallback = uri.lastPathSegment
                ?.substringAfterLast('\\')
                ?.substringAfterLast('/')
                ?.let { Uri.decode(it) }
                ?.take(120)
                ?: "未命名文件"
            fallback
        } catch (e: Exception) {
            "未命名文件"
        }

    /** 扩展名 + MIME + 文件头联合判定；三者不一致时以文件头为准，不凭扩展名假装支持。 */
    fun sniff(fileName: String, mime: String?, head: ByteArray): AttachKind {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        fun ByteArray.startsWith(vararg bytes: Int): Boolean {
            if (this.size < bytes.size) return false
            return bytes.indices.all { this[it].toInt() and 0xFF == bytes[it] }
        }

        return when {
            head.startsWith(0x89, 0x50, 0x4E, 0x47) -> AttachKind.IMAGE_PNG
            head.startsWith(0xFF, 0xD8, 0xFF) -> AttachKind.IMAGE_JPEG
            head.size >= 12 && head[0] == 'R'.code.toByte() && head[1] == 'I'.code.toByte() &&
                head[2] == 'F'.code.toByte() && head[3] == 'F'.code.toByte() &&
                head[8] == 'W'.code.toByte() && head[9] == 'E'.code.toByte() &&
                head[10] == 'B'.code.toByte() && head[11] == 'P'.code.toByte() -> AttachKind.IMAGE_WEBP

            head.startsWith(0x25, 0x50, 0x44, 0x46) -> AttachKind.PDF // %PDF
            head.startsWith(0x50, 0x4B) -> when (ext) { // PK zip
                "docx" -> AttachKind.DOCX
                "xlsx" -> AttachKind.XLSX
                else -> when {
                    mime?.contains("wordprocessingml") == true -> AttachKind.DOCX
                    mime?.contains("spreadsheetml") == true -> AttachKind.XLSX
                    else -> AttachKind.UNSUPPORTED
                }
            }

            else -> when (ext) {
                "txt" -> AttachKind.TXT
                "md", "markdown" -> AttachKind.MD
                "csv" -> AttachKind.CSV
                else -> when (mime) {
                    "text/plain" -> AttachKind.TXT
                    "text/markdown" -> AttachKind.MD
                    "text/csv", "application/csv" -> AttachKind.CSV
                    else -> AttachKind.UNSUPPORTED
                }
            }
        }
    }

    /** 删除单个附件文件（取消/移除时）。 */
    fun delete(id: String) {
        dir.listFiles()?.filter { it.name.startsWith("att_$id") }?.forEach { it.delete() }
    }

    /** 按 id 找仍有效的缓存文件（进程回收后核对预览/来源）。 */
    fun find(id: String): File? =
        dir.listFiles()?.firstOrNull { it.name.startsWith("att_$id.") }?.takeIf { it.isFile }

    /** 清空全部附件缓存（“清空对话及附件缓存”）。 */
    fun clearAll() {
        dir.listFiles()?.forEach { it.delete() }
    }

    /** 保留期清理：删除未被待确认批次引用且超期的附件。 */
    fun prune(keepMillis: Long, nowMs: Long, referenced: Set<String>) {
        dir.listFiles()?.forEach { f ->
            val id = f.name.removePrefix("att_").substringBefore(".").take(8)
            if (id !in referenced && nowMs - f.lastModified() > keepMillis) f.delete()
        }
    }
}
