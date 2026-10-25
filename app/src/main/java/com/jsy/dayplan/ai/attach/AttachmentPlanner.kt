package com.jsy.dayplan.ai.attach

import java.io.File

/** 单个附件的处理进度（界面显示“读取附件→识别/整理→待确认→已导入”）。 */
enum class AttachStage { SELECTED, READING, PARSED, SENT, DONE, FAILED }

/** 组装结果：文档文本段 + 待上传图片 + 有效来源集合 + 提示。 */
data class ComposedAttachments(
    val segments: List<DocSegment>,
    val images: List<PreparedImage>,
    val validSourceIds: Set<String>,
    val warnings: List<String>,
    /** 参与视觉识别的附件描述（证明图片被真正编码发送）。 */
    val imageManifest: List<String>,
    val totalDocChars: Int,
)

/** 用户为本次附件选择的解析范围。 */
data class AttachmentOptions(
    /** 附件 id → 要分析的 PDF 页码（1-based，按用户选择；空表示默认前若干页）。 */
    val pdfPages: Map<String, Set<Int>> = emptyMap(),
    /** 附件 id → XLSX 工作表名（空表示第一个工作表）。 */
    val xlsxSheet: Map<String, String> = emptyMap(),
)

/**
 * 附件规划器：把图片/文件转成“文本段（带 sourceId）+ 视觉图片块”。
 * 文档在本地解析，只发送提取结果；扫描 PDF 页渲染为图片走视觉模型。
 */
class AttachmentPlanner(
    private val pdfRenderer: PdfPageRenderer,
    private val previewDir: File?,
) {

    /**
     * @param progress 每开始处理一个附件时回调 (已完成前的序号, 总数, 文件名)。
     */
    fun compose(
        attachments: List<PendingAttachment>,
        options: AttachmentOptions = AttachmentOptions(),
        progress: ((Int, Int, String) -> Unit)? = null,
    ): ComposedAttachments {
        val segments = mutableListOf<DocSegment>()
        val images = mutableListOf<PreparedImage>()
        val warnings = mutableListOf<String>()
        val manifest = mutableListOf<String>()
        var imgIndex = 0
        var encodedTotal = 0L

        fun addImage(prepared: PreparedImage, manifestLine: String): Boolean {
            if (encodedTotal + prepared.encodedBytes > AttachLimits.IMAGES_TOTAL_MAX_BYTES) {
                warnings.add("图片合计编码后超过 ${AttachLimits.IMAGES_TOTAL_MAX_BYTES / 1024 / 1024} MiB，已跳过：$manifestLine")
                return false
            }
            encodedTotal += prepared.encodedBytes
            images.add(prepared)
            manifest.add(manifestLine)
            return true
        }

        attachments.forEachIndexed { index, att ->
            progress?.invoke(index + 1, attachments.size, att.fileName)
            when (att.kind) {
                AttachKind.IMAGE_JPEG, AttachKind.IMAGE_PNG, AttachKind.IMAGE_WEBP -> {
                    imgIndex++
                    val id = "img$imgIndex"
                    val prepared = ImagePrep.prepare(
                        att.file, att.kind, id,
                        savePreviewTo = previewDir?.let { File(it, "preview_${att.id}.jpg") },
                    )
                    addImage(
                        prepared,
                        "${att.fileName} → $id（${prepared.width}×${prepared.height}，${prepared.encodedBytes / 1024} KiB）",
                    )
                }

                AttachKind.PDF -> {
                    val extract = PdfTextExtractor.extract(
                        att.file,
                        selectedPages = options.pdfPages[att.id],
                    )
                    warnings.addAll(extract.warnings)
                    val visionPages = extract.pages.filter { it.needsVision }
                        .take(PdfLimits.MAX_IMAGE_PAGES)
                    if (extract.pages.count { it.needsVision } > PdfLimits.MAX_IMAGE_PAGES) {
                        warnings.add("扫描页超过 ${PdfLimits.MAX_IMAGE_PAGES} 页，仅识别前 ${PdfLimits.MAX_IMAGE_PAGES} 页")
                    }
                    extract.pages.forEach { page ->
                        if (!page.needsVision && page.text.isNotBlank()) {
                            segments.add(
                                DocSegment(
                                    sourceId = "pdf${index + 1}-p${page.pageIndex + 1}",
                                    label = "${att.fileName} 第 ${page.pageIndex + 1} 页",
                                    excerpt = page.text.lineSequence().firstOrNull()?.take(40) ?: "",
                                    // 单页不静默截断：总预算在下面统一核对，超限直接阻止发送
                                    text = page.text,
                                ),
                            )
                        }
                    }
                    visionPages.forEach { page ->
                        val bmp = pdfRenderer.renderPage(att.file, page.pageIndex, PdfLimits.RENDER_MAX_DIMENSION)
                        if (bmp != null) {
                            val id = "pdf${index + 1}-p${page.pageIndex + 1}"
                            val prepared = encodeBitmap(bmp, id)
                            bmp.recycle()
                            addImage(
                                prepared,
                                "${att.fileName} 第 ${page.pageIndex + 1} 页(扫描) → $id（${prepared.encodedBytes / 1024} KiB）",
                            )
                        } else {
                            warnings.add("${att.fileName} 第 ${page.pageIndex + 1} 页渲染失败，未识别")
                        }
                    }
                }

                AttachKind.DOCX -> {
                    val r = DocParsers.parseDocx(att.file, "docx${index + 1}")
                    segments.addAll(r.segments)
                    warnings.addAll(r.warnings)
                    if (r.truncated) warnings.add("「${att.fileName}」内容超长，已截断；可拆分文件或指定范围")
                }

                AttachKind.XLSX -> {
                    val r = DocParsers.parseXlsx(att.file, "xlsx${index + 1}", options.xlsxSheet[att.id])
                    segments.addAll(r.segments)
                    warnings.addAll(r.warnings)
                    if (r.truncated) warnings.add("「${att.fileName}」单元格/行数超限，已截断")
                }

                AttachKind.TXT, AttachKind.MD -> {
                    val r = DocParsers.parseText(att.file, att.kind, "txt${index + 1}")
                    segments.addAll(r.segments)
                    warnings.addAll(r.warnings)
                    if (r.truncated) warnings.add("「${att.fileName}」内容超长，已截断")
                }

                AttachKind.CSV -> {
                    val r = DocParsers.parseCsv(att.file, "csv${index + 1}")
                    segments.addAll(r.segments)
                    warnings.addAll(r.warnings)
                    if (r.truncated) warnings.add("「${att.fileName}」行数超限，已截断")
                }

                AttachKind.UNSUPPORTED -> warnings.add("「${att.fileName}」格式不支持，已跳过")
            }
        }

        // 文档文本总预算：超限直接拒绝发送并给出范围，绝不静默截掉后半页
        val total = segments.sumOf { it.text.length.toLong() }
        if (total > com.jsy.dayplan.ai.AiLimits.DOC_TEXT_MAX_CHARS) {
            val labels = segments.joinToString("、") { it.label }.take(300)
            throw AttachmentException(
                "附件提取文本合计 $total 字符，超过单次 ${com.jsy.dayplan.ai.AiLimits.DOC_TEXT_MAX_CHARS} 字符上限；" +
                    "请减少 PDF 页范围/工作表行范围，或分批发送。涉及：$labels",
            )
        }

        return ComposedAttachments(
            segments = segments,
            images = images,
            validSourceIds = segments.map { it.sourceId }.toSet() + images.map { it.sourceId },
            warnings = warnings,
            imageManifest = manifest,
            totalDocChars = total.toInt(),
        )
    }

    private fun encodeBitmap(bmp: android.graphics.Bitmap, id: String): PreparedImage {
        var quality = 85
        var bytes: ByteArray
        while (true) {
            val bos = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, bos)
            bytes = bos.toByteArray()
            if (bytes.size.toLong() <= AttachLimits.IMAGE_ENCODED_MAX_BYTES || quality <= 50) break
            quality -= 15
        }
        val b64 = java.util.Base64.getEncoder().encodeToString(bytes)
        return PreparedImage(
            sourceId = id,
            dataUrl = "data:image/jpeg;base64,$b64",
            encodedBytes = bytes.size,
            width = bmp.width,
            height = bmp.height,
            previewPath = null,
        )
    }
}
