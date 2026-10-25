package com.jsy.dayplan.ai.attach

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

/** PDF 解析限额：单次最多 20 页文本或 6 页扫描图。 */
object PdfLimits {
    const val MAX_TEXT_PAGES = 20
    const val MAX_IMAGE_PAGES = 6
    /** 渲染扫描页的长边像素上限。 */
    const val RENDER_MAX_DIMENSION = 1400
}

/** 单页内容：提取的文本或“需视觉识别”标记。 */
data class PdfPage(
    val pageIndex: Int,   // 0-based
    val text: String,
    val needsVision: Boolean,
)

data class PdfExtract(
    val pageCount: Int,
    val pages: List<PdfPage>,
    val truncated: Boolean,
    val warnings: List<String>,
)

/** 渲染接口：生产用系统 PdfRenderer；测试注入假实现。 */
interface PdfPageRenderer {
    /** 渲染指定页（0-based）为 Bitmap；长边不超过 maxDimension。 */
    fun renderPage(file: File, pageIndex: Int, maxDimension: Int): Bitmap?
}

/** 生产实现：打开文件句柄及时释放。 */
class AndroidPdfPageRenderer : PdfPageRenderer {
    override fun renderPage(file: File, pageIndex: Int, maxDimension: Int): Bitmap? {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                if (pageIndex >= renderer.pageCount) return null
                renderer.openPage(pageIndex).use { page ->
                    val scale = minOf(
                        maxDimension.toFloat() / page.width,
                        maxDimension.toFloat() / page.height,
                        2f,
                    )
                    val w = (page.width * scale).toInt().coerceAtLeast(1)
                    val h = (page.height * scale).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    // PdfRenderer 不预填背景，先铺白底避免透明被当黑图
                    Canvas(bmp).drawColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return bmp
                }
            }
        }
    }
}

/**
 * PDF 文本提取：pdfbox-android（Apache-2.0）逐页提取；
 * 文本为空/极少的页标记 needsVision，由上层决定渲染成图片走视觉模型。
 * 加密、损坏文件给可读错误。
 */
object PdfTextExtractor {

    /** 每页至少这么少字符才算“无文本”（扫描件）。 */
    private const val MIN_TEXT_CHARS = 12

    fun extract(
        file: File,
        maxTextPages: Int = PdfLimits.MAX_TEXT_PAGES,
        selectedPages: Set<Int>? = null,
    ): PdfExtract {
        try {
            PDDocument.load(file).use { doc ->
                if (doc.isEncrypted) {
                    throw AttachmentException("「${file.name}」是加密 PDF，请先解密后再发送")
                }
                val count = doc.numberOfPages
                if (count <= 0) throw AttachmentException("「${file.name}」没有页面")
                val stripper = PDFTextStripper()
                val pages = mutableListOf<PdfPage>()
                val warnings = mutableListOf<String>()
                val requested = selectedPages
                    ?.filter { it in 1..count }
                    ?.sorted()
                    ?: (1..minOf(count, maxTextPages)).toList()
                val truncated = requested.size > maxTextPages ||
                    (selectedPages == null && count > maxTextPages)
                val chosen = requested.take(maxTextPages)
                if (selectedPages != null) {
                    if (requested.size < selectedPages.size) {
                        warnings.add("所选页码范围超出 PDF 实际页数（共 $count 页），已忽略越界页")
                    }
                    warnings.add("仅分析所选页：${chosen.joinToString("、")}")
                }
                if (truncated) {
                    warnings.add("本次仅分析前 $maxTextPages 页文本；可分批发送其余页")
                }
                chosen.forEach { pageNo ->
                    val i = pageNo - 1
                    stripper.setStartPage(pageNo)
                    stripper.setEndPage(pageNo)
                    val text = try {
                        stripper.getText(doc).trim()
                    } catch (e: Exception) {
                        ""
                    }
                    val needsVision = text.length < MIN_TEXT_CHARS
                    if (needsVision) {
                        warnings.add("第 $pageNo 页没有可提取文字（可能是扫描页），将以图片识别")
                    }
                    pages.add(PdfPage(i, text, needsVision))
                }
                return PdfExtract(count, pages, truncated, warnings)
            }
        } catch (e: AttachmentException) {
            throw e
        } catch (e: Exception) {
            throw pdfError(file, e)
        }
    }

    /** 只读页数，供“选择页”界面使用。 */
    fun pageCount(file: File): Int {
        try {
            PDDocument.load(file).use { doc ->
                if (doc.isEncrypted) {
                    throw AttachmentException("「${file.name}」是加密 PDF，请先解密后再发送")
                }
                return doc.numberOfPages
            }
        } catch (e: AttachmentException) {
            throw e
        } catch (e: Exception) {
            throw pdfError(file, e)
        }
    }

    private fun pdfError(file: File, e: Exception): AttachmentException {
        val name = e.javaClass.simpleName
        val msg = e.message ?: ""
        return if (name.contains("InvalidPassword", true) || msg.contains("password", true)) {
            AttachmentException("「${file.name}」是加密 PDF，请先解密后再发送")
        } else {
            AttachmentException("「${file.name}」PDF 解析失败：$name")
        }
    }
}
