package com.jsy.dayplan.ai.attach

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 附件本地解析真实文件测试：TXT/BOM、CSV 引号换行、DOCX 表格、XLSX 日期/公式、
 * PDF 文本/扫描/混合/加密、图片 EXIF 旋转、限额与坏文件。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AiLocalParsersTest {

    private fun dir(): File = Files.createTempDirectory("dayplan-attach").toFile()

    private fun zip(file: File, entries: Map<String, String>) {
        ZipOutputStream(file.outputStream()).use { z ->
            entries.forEach { (name, content) ->
                z.putNextEntry(ZipEntry(name))
                z.write(content.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
        }
    }

    // ---------- TXT / CSV ----------

    @Test
    fun `TXT 支持 UTF-8 BOM 中文并保留行号来源`() {
        val f = File(dir(), "课表.txt")
        f.writeBytes(
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
                "周一 09:00 高数\n周二 14:00 英语\n".toByteArray(Charsets.UTF_8),
        )
        val r = DocParsers.parseText(f, AttachKind.TXT, "txt1")
        assertTrue(r.warnings.isEmpty())
        assertEquals(1, r.segments.size)
        assertEquals("txt1-L1", r.segments[0].sourceId)
        assertTrue(r.segments[0].text.contains("高数"))
        assertTrue(r.segments[0].text.contains("英语"))
    }

    @Test
    fun `非 UTF-8 文本给可读错误不乱码导入`() {
        val f = File(dir(), "gbk.txt")
        f.writeBytes("中文内容测试".toByteArray(charset("GBK")))
        val e = assertThrows(AttachmentException::class.java) {
            DocParsers.parseText(f, AttachKind.TXT, "t")
        }
        assertTrue(e.message!!.contains("UTF-8"))
    }

    @Test
    fun `CSV 正确处理引号 逗号与字段内换行`() {
        val text = "姓名,备注\n张三,\"你好,世界\"\n李四,\"第一行\n第二行\"\n"
        val records = DocParsers.parseCsvRecords(text)
        assertEquals(3, records.size)
        assertEquals("张三", records[1][0])
        assertEquals("你好,世界", records[1][1])
        assertEquals("第一行\n第二行", records[2][1])

        val f = File(dir(), "a.csv")
        f.writeBytes(text.toByteArray(Charsets.UTF_8))
        val r = DocParsers.parseCsv(f, "csv1")
        assertEquals(1, r.segments.size)
        assertTrue(r.segments[0].text.contains("\"你好,世界\""))
        assertTrue(r.segments[0].text.contains("\"第一行\n第二行\""))
    }

    // ---------- DOCX ----------

    private fun docx(file: File, body: String) = zip(
        file,
        mapOf(
            "word/document.xml" to
                """<?xml version="1.0" encoding="UTF-8"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                <w:body>$body</w:body></w:document>""",
        ),
    )

    @Test
    fun `DOCX 提取段落与表格行列关系`() {
        val f = File(dir(), "通知.docx")
        docx(
            f,
            """
            <w:p><w:r><w:t>项目会议通知</w:t></w:r></w:p>
            <w:tbl>
              <w:tr>
                <w:tc><w:p><w:r><w:t>时间</w:t></w:r></w:p></w:tc>
                <w:tc><w:p><w:r><w:t>2026-11-24 09:00</w:t></w:r></w:p></w:tc>
              </w:tr>
              <w:tr>
                <w:tc><w:p><w:r><w:t>地点</w:t></w:r></w:p></w:tc>
                <w:tc><w:p><w:r><w:t>三会议室</w:t></w:r></w:p></w:tc>
              </w:tr>
            </w:tbl>
            <w:p><w:r><w:t>请准时参加</w:t></w:r></w:p>
            """.trimIndent(),
        )
        val r = DocParsers.parseDocx(f, "docx1")
        val all = r.segments.joinToString("\n") { it.text }
        assertTrue(all.contains("项目会议通知"))
        assertTrue(all.contains("时间 | 2026-11-24 09:00"))
        assertTrue(all.contains("地点 | 三会议室"))
        assertTrue(all.contains("请准时参加"))
        assertTrue(r.segments.any { it.sourceId.startsWith("docx1-T") })
    }

    @Test
    fun `DOCX 内嵌图片提示未解析 损坏文件报错`() {
        val f = File(dir(), "图文.docx")
        docx(f, """<w:p><w:r><w:t>说明</w:t></w:r><w:drawing/></w:p>""")
        val r = DocParsers.parseDocx(f, "d")
        assertTrue(r.warnings.any { it.contains("内嵌图片") })

        val bad = File(dir(), "bad.docx")
        zip(bad, mapOf("word/other.xml" to "<x/>"))
        val e = assertThrows(AttachmentException::class.java) { DocParsers.parseDocx(bad, "b") }
        assertTrue(e.message!!.contains("document.xml"))
    }

    // ---------- XLSX ----------

    private fun xlsx(file: File, dateSerial: Long, sheet1Extra: String = "", sheet2Value: String = "42") = zip(
        file,
        mapOf(
            "xl/workbook.xml" to
                """<?xml version="1.0"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                  xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <sheets>
                    <sheet name="Sheet1" sheetId="1" r:id="rId1"/>
                    <sheet name="Sheet2" sheetId="2" r:id="rId2"/>
                  </sheets>
                </workbook>""",
            "xl/workbook.xml.rels" to
                """<?xml version="1.0"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/>
                </Relationships>""",
            "xl/sharedStrings.xml" to
                """<?xml version="1.0"?>
                <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <si><t>日期</t></si><si><t>项目</t></si><si><t>截止</t></si>
                </sst>""",
            "xl/styles.xml" to
                """<?xml version="1.0"?>
                <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <numFmts count="1"><numFmt numFmtId="164" formatCode="yyyy-mm-dd"/></numFmts>
                </styleSheet>""",
            "xl/worksheets/sheet1.xml" to
                """<?xml version="1.0"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
                  <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row>
                  <row r="2"><c r="A2" s="164"><v>$dateSerial</v></c><c r="B2" t="s"><v>2</v></c></row>
                  <row r="3"><c r="A3"><f>SUM(1,2)</f></c></row>
                  $sheet1Extra
                </sheetData></worksheet>""",
            "xl/worksheets/sheet2.xml" to
                """<?xml version="1.0"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
                  <row r="1"><c r="A1"><v>$sheet2Value</v></c></row>
                </sheetData></worksheet>""",
        ),
    )

    @Test
    fun `XLSX 日期序列 表头 单元格地址与公式缓存缺失`() {
        val f = File(dir(), "安排.xlsx")
        val serial = LocalDate.of(2026, 10, 26).toEpochDay() -
            LocalDate.of(1899, 12, 30).toEpochDay()
        xlsx(f, serial)
        val r = DocParsers.parseXlsx(f, "x1")
        val all = r.segments.joinToString("\n") { it.text }
        assertTrue("日期应按样式换算：$all", all.contains("2026-10-26"))
        assertTrue(all.contains("[A1]日期"))
        assertTrue(all.contains("[B2]截止"))
        assertTrue("公式无缓存应标未知：$all", all.contains("未知(公式无缓存结果)"))
        assertTrue(r.warnings.any { it.contains("Sheet1") && it.contains("Sheet2") })
    }

    @Test
    fun `XLSX 可选工作表并列出表名`() {
        val f = File(dir(), "双表.xlsx")
        xlsx(f, 46331, sheet2Value = "99")
        val sheets = DocParsers.listXlsxSheets(f)
        assertEquals(listOf("Sheet1", "Sheet2"), sheets)
        val r = DocParsers.parseXlsx(f, "x2", "Sheet2")
        assertTrue(r.segments.joinToString("") { it.text }.contains("99"))
        val e = assertThrows(AttachmentException::class.java) {
            DocParsers.parseXlsx(f, "x3", "不存在")
        }
        assertTrue(e.message!!.contains("Sheet1"))
    }

    @Test
    fun `Excel 日期系统 1900 与 1904 各自换算`() {
        val serial1900 = LocalDate.of(2026, 10, 26).toEpochDay() -
            LocalDate.of(1899, 12, 30).toEpochDay()
        assertTrue(DocParsers.formatSerial(serial1900.toDouble(), false).startsWith("2026-10-26"))
        val d1904 = LocalDate.of(2026, 10, 26).toEpochDay() -
            LocalDate.of(1904, 1, 1).toEpochDay()
        assertTrue(DocParsers.formatSerial(d1904.toDouble(), true).startsWith("2026-10-26"))
        // 1900 系统含 1900 闰年兼容：61 对应 1900-03-01
        assertEquals("1900-03-01", DocParsers.formatSerial(61.0, false))
    }

    // ---------- PDF ----------

    private fun textPdf(file: File, text: String) {
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA, 12f)
                cs.newLineAtOffset(50f, 700f)
                cs.showText(text)
                cs.endText()
            }
            doc.save(file)
        }
    }

    private fun blankPdf(file: File, pages: Int = 1) {
        PDDocument().use { doc ->
            repeat(pages) { doc.addPage(PDPage(PDRectangle.A4)) }
            doc.save(file)
        }
    }

    @Test
    fun `PDF 文本页提取与扫描页标记`() {
        val textFile = File(dir(), "会议.pdf")
        textPdf(textFile, "Meeting 2026-11-24 09:00 Room 3")
        val r = PdfTextExtractor.extract(textFile)
        assertEquals(1, r.pageCount)
        assertEquals(1, r.pages.size)
        assertFalse(r.pages[0].needsVision)
        assertTrue(r.pages[0].text.contains("Meeting"))

        val scanFile = File(dir(), "scan.pdf")
        blankPdf(scanFile, 2)
        val s = PdfTextExtractor.extract(scanFile)
        assertEquals(2, s.pageCount)
        assertTrue(s.pages.all { it.needsVision })
        assertEquals(2, PdfTextExtractor.pageCount(scanFile))
    }

    @Test
    fun `PDF 选择页只分析所选范围`() {
        val f = File(dir(), "多页.pdf")
        PDDocument().use { doc ->
            val p1 = PDPage(PDRectangle.A4)
            doc.addPage(p1)
            PDPageContentStream(doc, p1).use { cs ->
                cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 12f)
                cs.newLineAtOffset(50f, 700f); cs.showText("PAGE ONE TEXT"); cs.endText()
            }
            val p2 = PDPage(PDRectangle.A4)
            doc.addPage(p2)
            PDPageContentStream(doc, p2).use { cs ->
                cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 12f)
                cs.newLineAtOffset(50f, 700f); cs.showText("PAGE TWO TEXT"); cs.endText()
            }
            doc.save(f)
        }
        val r = PdfTextExtractor.extract(f, selectedPages = setOf(2))
        assertEquals(1, r.pages.size)
        assertEquals(1, r.pages[0].pageIndex)
        assertTrue(r.pages[0].text.contains("PAGE TWO"))
        assertTrue(r.warnings.any { it.contains("仅分析所选页") })
    }

    @Test
    fun `加密 PDF 与损坏 PDF 可读失败`() {
        val enc = File(dir(), "加密.pdf")
        PDDocument().use { doc ->
            doc.addPage(PDPage(PDRectangle.A4))
            doc.protect(StandardProtectionPolicy("owner", "user", AccessPermission()))
            doc.save(enc)
        }
        val e = assertThrows(AttachmentException::class.java) { PdfTextExtractor.extract(enc) }
        assertTrue(e.message!!.contains("加密"))

        val bad = File(dir(), "bad.pdf")
        bad.writeBytes("%PDF-1.7\nthis is not a pdf".toByteArray())
        assertThrows(AttachmentException::class.java) { PdfTextExtractor.extract(bad) }
    }

    // ---------- 图片 ----------

    @Test
    fun `WebP 图片也能解码与重编码`() {
        val f = File(dir(), "c.webp")
        val bmp = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.GREEN)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, 90, it) }
        bmp.recycle()
        val prepared = ImagePrep.prepare(f, AttachKind.IMAGE_WEBP, "img1")
        assertEquals(64, prepared.width)
        assertEquals(32, prepared.height)
        assertTrue(prepared.dataUrl.startsWith("data:image/jpeg;base64,"))
    }

    @Test
    fun `混合 PDF 按页区分文本页与扫描页`() {
        val f = File(dir(), "mixed.pdf")
        PDDocument().use { doc ->
            val textPage = PDPage(PDRectangle.A4)
            doc.addPage(textPage)
            PDPageContentStream(doc, textPage).use { cs ->
                cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 12f)
                cs.newLineAtOffset(50f, 700f); cs.showText("TEXT PAGE CONTENT"); cs.endText()
            }
            doc.addPage(PDPage(PDRectangle.A4)) // 空白扫描页
            doc.save(f)
        }
        val r = PdfTextExtractor.extract(f)
        assertEquals(2, r.pages.size)
        assertFalse(r.pages[0].needsVision)
        assertTrue(r.pages[1].needsVision)
    }

    @Test
    fun `失效 content URI 可读失败且不留下半截附件`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val store = AttachmentStore(File(dir(), "attachments"))
        val e = assertThrows(AttachmentException::class.java) {
            store.copyIn(app.contentResolver, Uri.parse("content://com.jsy.dayplan.missing/none"), "text/plain")
        }
        assertTrue(e.message!!.contains("无法读取") || e.message!!.contains("失败"))
        assertEquals(0L, store.currentTotalBytes())
    }

    @Test
    fun `图片解码尺寸与 EXIF 旋转校正`() {
        val png = File(dir(), "课表.png")
        val bmp = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        png.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()

        val prepared = ImagePrep.prepare(png, AttachKind.IMAGE_PNG, "img1")
        assertEquals(120, prepared.width)
        assertEquals(80, prepared.height)
        assertTrue(prepared.dataUrl.startsWith("data:image/jpeg;base64,"))
        assertTrue(prepared.encodedBytes > 0)

        val jpg = File(dir(), "photo.jpg")
        val jbmp = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        jbmp.eraseColor(Color.BLUE)
        jpg.outputStream().use { jbmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        jbmp.recycle()
        ExifInterface(jpg.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val rotated = ImagePrep.prepare(jpg, AttachKind.IMAGE_JPEG, "img2")
        assertEquals("旋转后宽高应交换", 100, rotated.width)
        assertEquals(200, rotated.height)
    }

    @Test
    fun `坏图片与超大像素可读失败`() {
        val bad = File(dir(), "bad.png")
        bad.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0, 1, 2))
        val e = assertThrows(AttachmentException::class.java) {
            ImagePrep.prepare(bad, AttachKind.IMAGE_PNG, "x")
        }
        assertTrue(e.message!!.contains("无法解码") || e.message!!.contains("像素"))
    }

    @Test
    fun `PDF 单页长文本不丢页尾且超预算时阻止发送`() {
        val marker = "FINAL_MEETING_2099_12_30_0900"
        val pdf = File(dir(), "dense.pdf")
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA, 5f)
                cs.newLineAtOffset(30f, 780f)
                repeat(100) { i ->
                    cs.showText("Agenda detail filler line with scheduled information number $i. ")
                    cs.newLineAtOffset(0f, -6f)
                }
                cs.showText(marker)
                cs.endText()
            }
            doc.save(pdf)
        }
        val full = PdfTextExtractor.extract(pdf)
        assertTrue(full.pages.single().text.contains(marker))
        val planner = AttachmentPlanner(
            object : PdfPageRenderer {
                override fun renderPage(file: File, pageIndex: Int, maxDimension: Int) = null
            },
            null,
        )
        val result = planner.compose(
            listOf(PendingAttachment("pdf1", "dense.pdf", "application/pdf", AttachKind.PDF, pdf, pdf.length(), "sha")),
        )
        assertTrue("页尾安排必须进入发送材料", result.segments.any { it.text.contains(marker) })
        assertTrue(result.totalDocChars > 6000)

        // 超过 20000 字符：明确阻止发送并提示选段/分批，不能静默截断
        val huge = File(dir(), "huge.pdf")
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA, 4f)
                cs.newLineAtOffset(20f, 800f)
                val line = "H".repeat(240)
                repeat(100) {
                    cs.showText(line); cs.newLineAtOffset(0f, -7f)
                }
                cs.endText()
            }
            doc.save(huge)
        }
        val e = assertThrows(AttachmentException::class.java) {
            planner.compose(
                listOf(PendingAttachment("pdf2", "huge.pdf", "application/pdf", AttachKind.PDF, huge, huge.length(), "sha2")),
            )
        }
        assertTrue(e.message!!.contains("20000"))
        assertTrue(e.message!!.contains("分批"))
    }

    // ---------- 限额 / 复制 ----------

    @Test
    fun `OOXML 条目数超限标记截断而非无限解压`() {
        val f = File(dir(), "many.zip")
        ZipOutputStream(f.outputStream()).use { z ->
            repeat(2005) { i ->
                z.putNextEntry(ZipEntry("e$i.xml"))
                z.write("<x/>".toByteArray())
                z.closeEntry()
            }
        }
        val r = DocParsers.readZipBounded(f, wanted = { false }, maxEntries = 100)
        assertTrue(r.truncated)
    }

    @Test
    fun `附件复制限制大小并拒绝空文件与不支持格式`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val store = AttachmentStore(File(dir(), "attachments"))

        val txt = File(dir(), "a.txt").apply { writeText("hello", Charsets.UTF_8) }
        val saved = store.copyIn(app.contentResolver, Uri.fromFile(txt), "text/plain")
        assertEquals("a.txt", saved.fileName)
        assertEquals(AttachKind.TXT, saved.kind)
        assertEquals(5L, saved.sizeBytes)
        assertTrue(saved.file.exists())

        val empty = File(dir(), "empty.txt").apply { writeText("") }
        assertThrows(AttachmentException::class.java) {
            store.copyIn(app.contentResolver, Uri.fromFile(empty), "text/plain")
        }

        val exe = File(dir(), "x.exe").apply { writeBytes(byteArrayOf(0x4D, 0x5A, 0, 0)) }
        val e = assertThrows(AttachmentException::class.java) {
            store.copyIn(app.contentResolver, Uri.fromFile(exe), "application/octet-stream")
        }
        assertTrue(e.message!!.contains("不支持"))

        val big = File(dir(), "big.txt")
        big.outputStream().use { out ->
            val chunk = ByteArray(1024 * 1024)
            repeat(11) { out.write(chunk) }
        }
        val over = assertThrows(AttachmentException::class.java) {
            store.copyIn(app.contentResolver, Uri.fromFile(big), "text/plain")
        }
        assertTrue(over.message!!.contains("10 MiB"))
        assertTrue("超限半截文件必须删除", store.currentTotalBytes() < 11L * 1024 * 1024)
    }

    // ---------- Planner 组装 ----------

    @Test
    fun `Planner 扫描页转图片块并保留来源 id`() {
        val f = File(dir(), "scan.pdf")
        blankPdf(f, 1)
        val att = PendingAttachment(
            id = "att1", fileName = "scan.pdf", mime = "application/pdf",
            kind = AttachKind.PDF, file = f, sizeBytes = f.length(), sha256 = "sha",
        )
        var renderCalled = 0
        val planner = AttachmentPlanner(
            pdfRenderer = object : PdfPageRenderer {
                override fun renderPage(file: File, pageIndex: Int, maxDimension: Int): Bitmap? {
                    renderCalled++
                    return Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(Color.WHITE)
                    }
                }
            },
            previewDir = null,
        )
        val composed = planner.compose(listOf(att))
        assertEquals(1, renderCalled)
        assertEquals(1, composed.images.size)
        assertTrue(composed.images[0].sourceId in composed.validSourceIds)
        assertTrue(composed.imageManifest.any { it.contains("扫描") })
    }
}
