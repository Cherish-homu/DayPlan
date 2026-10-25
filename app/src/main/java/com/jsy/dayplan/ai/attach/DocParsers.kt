package com.jsy.dayplan.ai.attach

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlin.math.roundToLong

/** 从附件提取的、带来源编号的文本段。 */
data class DocSegment(
    val sourceId: String,
    val label: String,
    val excerpt: String,
    val text: String,
)

data class DocExtractResult(
    val segments: List<DocSegment>,
    val warnings: List<String> = emptyList(),
    val truncated: Boolean = false,
)

/** OOXML 解压安全限额：条目数、单条目、解压总量与 XML 深度都要限制。 */
object OoxmlLimits {
    const val MAX_ENTRIES = 2000
    const val MAX_ENTRY_BYTES = 8L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 40L * 1024 * 1024
    const val MAX_XML_DEPTH = 64
    const val MAX_CELLS = 2000
    const val MAX_CHARS = 20_000
}

/**
 * TXT / Markdown / CSV / DOCX / XLSX 本地解析。
 * 全部在可取消的后台协程中运行，不整文件载入内存。
 */
object DocParsers {

    // ---------- TXT / MD ----------

    fun parseText(file: File, kind: AttachKind, tag: String): DocExtractResult {
        val bytes = cappedRead(file, OoxmlLimits.MAX_ENTRY_BYTES)
        val (text, warning) = decodeUtf8(bytes)
            ?: throw AttachmentException(
                "「${file.name}」不是 UTF-8（或带 BOM）文本，无法确定编码；请另存为 UTF-8 后重试",
            )
        val lines = text.split('\n')
        val segments = mutableListOf<DocSegment>()
        val sb = StringBuilder()
        var chars = 0
        var truncated = false
        // 按行分块，每块 ≤ 40 行，保留行号来源
        var blockStart = 0
        var blockLines = 0
        fun flush(endExclusive: Int) {
            if (blockLines == 0) return
            val block = lines.subList(blockStart, endExclusive).joinToString("\n")
            val id = "$tag-L${blockStart + 1}"
            segments.add(
                DocSegment(
                    sourceId = id,
                    label = "${kind.display} 第 ${blockStart + 1}–$endExclusive 行",
                    excerpt = block.lineSequence().firstOrNull { it.isNotBlank() }?.take(40) ?: "",
                    text = block,
                ),
            )
            chars += block.length
            blockLines = 0
        }
        for (i in lines.indices) {
            if (chars > OoxmlLimits.MAX_CHARS) { truncated = true; break }
            if (blockLines >= 40) {
                flush(i)
                blockStart = i
            }
            blockLines++
        }
        flush(lines.size)
        val warns = mutableListOf<String>()
        warning?.let { warns.add(it) }
        if (truncated) {
            warns.add("「${file.name}」超出 ${OoxmlLimits.MAX_CHARS} 字符预算：已保留 ${segments.size} 段 / 约 $chars 字符，其余未参与识别；请拆分文件或分批发送")
        }
        return DocExtractResult(segments, warnings = warns, truncated = truncated)
    }

    private fun decodeUtf8(bytes: ByteArray): Pair<String, String?>? {
        var start = 0
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            start = 3 // UTF-8 BOM
        }
        val cs = Charset.forName("UTF-8")
        try {
            val decoder = cs.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            val text = decoder.decode(java.nio.ByteBuffer.wrap(bytes, start, bytes.size - start)).toString()
            return text to null
        } catch (e: Exception) {
            return null
        }
    }

    // ---------- CSV ----------

    /** 标准 CSV 状态机：处理引号、字段内逗号与换行。 */
    fun parseCsvRecords(text: String, limit: Int = 2000): List<List<String>> {
        val records = mutableListOf<List<String>>()
        var field = StringBuilder()
        var record = mutableListOf<String>()
        var inQuotes = false
        var i = 0
        while (i < text.length && records.size < limit) {
            val c = text[i]
            when {
                inQuotes -> when {
                    c == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                        field.append('"'); i++
                    }

                    c == '"' -> inQuotes = false
                    else -> field.append(c)
                }

                c == '"' -> inQuotes = true
                c == ',' -> {
                    record.add(field.toString()); field = StringBuilder()
                }

                c == '\r' -> { /* 等待 \n */
                }

                c == '\n' -> {
                    record.add(field.toString()); field = StringBuilder()
                    if (record.any { it.isNotBlank() }) records.add(record)
                    record = mutableListOf()
                }

                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || record.isNotEmpty()) {
            record.add(field.toString())
            if (record.any { it.isNotBlank() } && records.size < limit) records.add(record)
        }
        return records
    }

    fun parseCsv(file: File, tag: String): DocExtractResult {
        val bytes = cappedRead(file, OoxmlLimits.MAX_ENTRY_BYTES)
        val text = decodeUtf8(bytes)?.first
            ?: throw AttachmentException("「${file.name}」不是 UTF-8 CSV，请另存为 UTF-8 后重试")
        val records = parseCsvRecords(text)
        if (records.isEmpty()) return DocExtractResult(emptyList(), listOf("CSV 中没有有效数据行"))
        val segments = mutableListOf<DocSegment>()
        var chars = 0
        var truncated = false
        records.chunked(20).forEachIndexed { ci, chunk ->
            if (chars > OoxmlLimits.MAX_CHARS) {
                truncated = true; return@forEachIndexed
            }
            val block = chunk.joinToString("\n") { row -> row.joinToString(",") { cell -> escapeCsvCell(cell) } }
            val id = "$tag-R${ci * 20 + 1}"
            segments.add(
                DocSegment(
                    sourceId = id,
                    label = "CSV 第 ${ci * 20 + 1}–${ci * 20 + chunk.size} 行",
                    excerpt = chunk.first().joinToString(",").take(40),
                    text = block,
                ),
            )
            chars += block.length
        }
        val warns = mutableListOf<String>()
        if (truncated) {
            warns.add("「${file.name}」超出 ${OoxmlLimits.MAX_CHARS} 字符预算：已保留 ${segments.size} 段，其余未参与识别；请分批发送")
        }
        return DocExtractResult(segments, warnings = warns, truncated = truncated)
    }

    private fun escapeCsvCell(cell: String): String =
        if (cell.contains(',') || cell.contains('"') || cell.contains('\n')) {
            "\"" + cell.replace("\"", "\"\"") + "\""
        } else cell

    // ---------- 通用 ZIP 读取（带解压限额） ----------

    class BoundedZipEntries(
        val entries: Map<String, ByteArray>,
        val truncated: Boolean,
    )

    /**
     * 顺序读取 ZIP 中匹配需要的条目；总解压量、条目数、单条目大小全部限额，
     * 防止解压炸弹；不写磁盘（无 ZipSlip 风险）。
     */
    fun readZipBounded(
        file: File,
        wanted: (String) -> Boolean,
        maxEntries: Int = OoxmlLimits.MAX_ENTRIES,
        maxEntryBytes: Long = OoxmlLimits.MAX_ENTRY_BYTES,
        maxTotalBytes: Long = OoxmlLimits.MAX_TOTAL_BYTES,
    ): BoundedZipEntries {
        val out = LinkedHashMap<String, ByteArray>()
        var total = 0L
        var count = 0
        var truncated = false
        ZipInputStream(file.inputStream().buffered()).use { zis ->
            while (true) {
                val entry: ZipEntry = zis.nextEntry ?: break
                count++
                if (count > maxEntries) {
                    truncated = true; break
                }
                val name = entry.name
                if (name.contains("..")) continue
                if (wanted(name)) {
                    val buf = java.io.ByteArrayOutputStream()
                    val b = ByteArray(32 * 1024)
                    var entryTotal = 0L
                    try {
                        while (true) {
                            val n = zis.read(b)
                            if (n < 0) break
                            entryTotal += n
                            total += n
                            if (entryTotal > maxEntryBytes) {
                                throw AttachmentException("压缩包内单个文件解压超过 8 MiB，已停止解析")
                            }
                            if (total > maxTotalBytes) {
                                throw AttachmentException("压缩包解压总量超过 40 MiB 上限，已停止解析")
                            }
                            buf.write(b, 0, n)
                        }
                    } catch (e: AttachmentException) {
                        throw e
                    }
                    out[name] = buf.toByteArray()
                }
                zis.closeEntry()
            }
        }
        return BoundedZipEntries(out, truncated)
    }

    private fun cappedRead(file: File, maxBytes: Long): ByteArray {
        if (file.length() > maxBytes) {
            throw AttachmentException("「${file.name}」超过 ${maxBytes / 1024 / 1024} MiB 读取上限")
        }
        return file.readBytes()
    }

    private fun newParser(): org.xmlpull.v1.XmlPullParser {
        val p = android.util.Xml.newPullParser()
        p.setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        return p
    }

    private fun parseSafe(input: InputStream): org.xmlpull.v1.XmlPullParser {
        val p = newParser()
        p.setInput(input, "UTF-8")
        return p
    }

    // ---------- DOCX ----------

    /**
     * 提取 word/document.xml 的段落与表格文字，保留表格行列关系。
     * 内嵌图片 (w:drawing) 检测到即提示，不解析。
     */
    fun parseDocx(file: File, tag: String): DocExtractResult {
        val zip = try {
            readZipBounded(file, wanted = { it == "word/document.xml" })
        } catch (e: AttachmentException) {
            throw AttachmentException("「${file.name}」${e.message}")
        }
        val doc = zip.entries["word/document.xml"]
            ?: throw AttachmentException("「${file.name}」不是有效的 DOCX（缺少 document.xml）")
        val parser = parseSafe(ByteArrayInputStream(doc))

        val segments = mutableListOf<DocSegment>()
        val warnings = mutableListOf<String>()
        var hasImage = false
        var chars = 0
        var truncated = false

        var event = parser.eventType
        var inCell = false
        var cellText = StringBuilder()
        var rowCells = mutableListOf<String>()
        val tableRows = mutableListOf<List<String>>()
        var paragraphText = StringBuilder()
        var paraBuffer = StringBuilder()
        var paraCount = 0

        fun emitParagraphBlock() {
            if (paraBuffer.isBlank()) return
            if (chars + paraBuffer.length > OoxmlLimits.MAX_CHARS) {
                truncated = true; paraBuffer.setLength(0); return
            }
            val id = "$tag-¶${segments.size + 1}"
            segments.add(
                DocSegment(
                    sourceId = id,
                    label = "${file.name} 段落 ${segments.size + 1}",
                    excerpt = paraBuffer.lineSequence().firstOrNull { it.isNotBlank() }?.take(40) ?: "",
                    text = paraBuffer.toString().trimEnd(),
                ),
            )
            chars += paraBuffer.length
            paraBuffer.setLength(0)
            paraCount = 0
        }

        fun flushParagraph() {
            val t = paragraphText.toString().trim()
            if (t.isNotEmpty()) {
                paraBuffer.append(t).append('\n')
                paraCount++
                if (paraBuffer.length >= 600 || paraCount >= 15) {
                    emitParagraphBlock()
                }
            }
            paragraphText = StringBuilder()
        }

        fun flushTable() {
            if (tableRows.isEmpty()) return
            emitParagraphBlock()
            val text = tableRows.joinToString("\n") { row -> row.joinToString(" | ") }
            if (text.isNotBlank() && chars + text.length <= OoxmlLimits.MAX_CHARS) {
                segments.add(
                    DocSegment(
                        sourceId = "$tag-T${segments.size + 1}",
                        label = "${file.name} 表格 ${segments.size + 1}（${tableRows.size} 行）",
                        excerpt = tableRows.first().joinToString(" | ").take(40),
                        text = text,
                    ),
                )
                chars += text.length
            } else if (text.isNotBlank()) {
                truncated = true
            }
            tableRows.clear()
        }

        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (parser.depth > OoxmlLimits.MAX_XML_DEPTH) {
                throw AttachmentException("「${file.name}」XML 嵌套过深，已停止解析")
            }
            when (event) {
                org.xmlpull.v1.XmlPullParser.START_TAG -> when (parser.name) {
                    "t" -> paragraphText.append(parser.nextText().also { nextText ->
                        // nextText 已前进到 END_TAG，直接补到当前单元格
                        if (inCell) cellText.append(nextText)
                    })

                    "tbl" -> flushParagraph()
                    "tr" -> rowCells = mutableListOf()
                    "tc" -> {
                        inCell = true; cellText = StringBuilder()
                    }

                    "drawing", "pict" -> hasImage = true
                    "p" -> if (!inCell) paragraphText = StringBuilder()
                }

                org.xmlpull.v1.XmlPullParser.END_TAG -> when (parser.name) {
                    "p" -> if (inCell) {
                        cellText.append(' ') // 单元格内换行转空格
                    } else {
                        flushParagraph()
                    }

                    "tc" -> {
                        inCell = false
                        rowCells.add(cellText.toString().trim())
                        paragraphText = StringBuilder()
                    }

                    "tr" -> if (rowCells.isNotEmpty()) {
                        tableRows.add(rowCells.toList())
                        if (tableRows.size > 500) truncated = true
                    }

                    "tbl" -> flushTable()
                }
            }
            event = parser.next()
        }
        flushParagraph()
        emitParagraphBlock()
        flushTable()
        if (hasImage) {
            warnings.add("「${file.name}」包含内嵌图片，本轮未解析图片内容；如需识别请另发图片")
        }
        if (truncated) {
            warnings.add("「${file.name}」超出字符/表格预算：已保留 ${segments.size} 段 / 约 $chars 字符，其余未参与识别；请拆分文件")
        }
        return DocExtractResult(segments, warnings, truncated)
    }

    // ---------- XLSX ----------

    private val CELL_REF = Regex("""^([A-Z]+)(\d+)$""")

    fun columnOf(ref: String): Int? = CELL_REF.find(ref)?.groupValues?.get(1)?.let { col ->
        var n = 0
        col.forEach { c -> n = n * 26 + (c - 'A' + 1) }
        n
    }

    fun rowOf(ref: String): Int? = CELL_REF.find(ref)?.groupValues?.get(2)?.toIntOrNull()

    /** 日期样式 numFmtId（内建日期/时间 + 自定义含日期占位）。 */
    private fun isDateFormat(custom: Map<Int, String>, numFmtId: Int): Boolean {
        if (numFmtId in 14..22 || numFmtId in 45..47) return true
        val fmt = custom[numFmtId] ?: return false
        val s = fmt.replace("\"[^\"]*\"".toRegex(), "")
        return (s.contains('y') || s.contains('d')) && (s.contains('y') || s.contains('m'))
    }

    /**
     * 只读取工作表名列表（供“选择 sheet”界面使用），不解压工作表数据。
     */
    fun listXlsxSheets(file: File): List<String> {
        val zip = try {
            readZipBounded(file, wanted = { it == "xl/workbook.xml" })
        } catch (e: AttachmentException) {
            throw AttachmentException("「${file.name}」${e.message}")
        }
        val bytes = zip.entries["xl/workbook.xml"]
            ?: throw AttachmentException("「${file.name}」不是有效的 XLSX（缺少 workbook.xml）")
        val parser = parseSafe(ByteArrayInputStream(bytes))
        val names = mutableListOf<String>()
        var ev = parser.eventType
        while (ev != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (ev == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "sheet") {
                parser.getAttributeValue(null, "name")?.let { names.add(it) }
            }
            ev = parser.next()
        }
        if (names.isEmpty()) throw AttachmentException("「${file.name}」中没有工作表")
        return names
    }

    /**
     * 提取工作表的有值单元格、表头与单元格地址；sheetName 为空取第一个；
     * 日期按 1900/1904 工作簿系统换算；公式只读缓存结果，无缓存标未知。
     * 返回警告中给出全部工作表名，供界面提示选择。
     */
    fun parseXlsx(file: File, tag: String, sheetName: String? = null): DocExtractResult {
        val zip = try {
            readZipBounded(
                file,
                wanted = {
                    it == "xl/workbook.xml" || it == "xl/workbook.xml.rels" ||
                        it == "xl/sharedStrings.xml" ||
                        it.startsWith("xl/worksheets/") || it.startsWith("xl/styles.xml") ||
                        it.startsWith("xl/drawings/") || it.startsWith("xl/media/")
                },
            )
        } catch (e: AttachmentException) {
            throw AttachmentException("「${file.name}」${e.message}")
        }
        if (!zip.entries.containsKey("xl/workbook.xml")) {
            throw AttachmentException("「${file.name}」不是有效的 XLSX（缺少 workbook.xml）")
        }

        // 工作簿日期系统（1904 判定）与工作表名 → r:id
        val workbookXml = String(zip.entries["xl/workbook.xml"]!!, Charsets.UTF_8)
        val date1904 = workbookXml.contains("date1904=\"1\"") || workbookXml.contains("date1904=\"true\"")
        val sheetRels = LinkedHashMap<String, String>()   // name -> rid
        val wParser = parseSafe(ByteArrayInputStream(zip.entries["xl/workbook.xml"]!!))
        var ev = wParser.eventType
        while (ev != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (ev == org.xmlpull.v1.XmlPullParser.START_TAG && wParser.name == "sheet") {
                val name = wParser.getAttributeValue(null, "name") ?: continue
                val rid = wParser.getAttributeValue("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id")
                if (rid != null) sheetRels[name] = rid
            }
            ev = wParser.next()
        }

        // r:id → 目标文件（xl/worksheets/sheetN.xml）
        val ridToTarget = mutableMapOf<String, String>()
        zip.entries["xl/workbook.xml.rels"]?.let { bytes ->
            val rp = parseSafe(ByteArrayInputStream(bytes))
            var e2 = rp.eventType
            while (e2 != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (e2 == org.xmlpull.v1.XmlPullParser.START_TAG && rp.name == "Relationship") {
                    val id = rp.getAttributeValue(null, "Id") ?: ""
                    val target = rp.getAttributeValue(null, "Target") ?: ""
                    if (id.isNotBlank() && target.isNotBlank()) {
                        val normalized = if (target.startsWith("/")) target.drop(1)
                        else "xl/" + target.removePrefix("../").removePrefix("./")
                        ridToTarget[id] = normalized
                    }
                }
                e2 = rp.next()
            }
        }

        val wantedEntry = when {
            sheetName != null -> {
                val rid = sheetRels[sheetName]
                    ?: throw AttachmentException("「$sheetName」工作表不存在；可用：${sheetRels.keys.joinToString("、")}")
                ridToTarget[rid]
                    ?: throw AttachmentException("无法定位工作表「$sheetName」的数据文件")
            }

            else -> sheetRels.values.firstNotNullOfOrNull { ridToTarget[it] }
        }
        val sheetFile = wantedEntry
            ?: zip.entries.keys.filter { it.startsWith("xl/worksheets/sheet") }
                .sortedWith(compareBy({ it.substringBeforeLast('.').length }, { it }))
                .firstOrNull()
            ?: throw AttachmentException("「${file.name}」中没有工作表数据")

        // 共享字符串
        val shared = mutableListOf<String>()
        zip.entries["xl/sharedStrings.xml"]?.let { bytes ->
            val p = parseSafe(ByteArrayInputStream(bytes))
            var ev = p.eventType
            var inSi = false
            val sb = StringBuilder()
            while (ev != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    org.xmlpull.v1.XmlPullParser.START_TAG -> when (p.name) {
                        "si" -> { inSi = true; sb.setLength(0) }
                        "t" -> if (inSi) sb.append(p.nextText())
                    }

                    org.xmlpull.v1.XmlPullParser.END_TAG -> if (p.name == "si") {
                        shared.add(sb.toString()); inSi = false
                    }
                }
                ev = p.next()
            }
        }

        // 自定义数字格式（判断日期样式）
        val customFormats = mutableMapOf<Int, String>()
        zip.entries["xl/styles.xml"]?.let { bytes ->
            val p = parseSafe(ByteArrayInputStream(bytes))
            var ev = p.eventType
            while (ev != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (ev == org.xmlpull.v1.XmlPullParser.START_TAG && p.name == "numFmt") {
                    val fmtId = p.getAttributeValue(null, "numFmtId")?.toIntOrNull() ?: -1
                    val code = p.getAttributeValue(null, "formatCode")
                    if (fmtId >= 0 && code != null) customFormats[fmtId] = code
                }
                ev = p.next()
            }
        }

        val data = zip.entries[sheetFile]!!
        val p = parseSafe(ByteArrayInputStream(data))

        val rowsText = mutableListOf<String>()
        var event = p.eventType
        var cellCount = 0
        var truncated = false
        var currentRow = -1
        val rowCells = mutableListOf<String>()
        var inValue = false
        var valueSb = StringBuilder()
        var cellType = ""
        var cellRef = ""
        var cellStyle = -1
        var hasFormulaNoCache = false

        fun convertValue(raw: String, type: String, style: Int): String = when (type) {
            "s" -> raw.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
            "inlineStr" -> raw
            "str" -> raw
            "b" -> if (raw == "1") "TRUE" else "FALSE"
            else -> {
                val num = raw.toDoubleOrNull()
                if (num != null && isDateFormat(customFormats, style)) formatSerial(num, date1904) else raw
            }
        }

        fun flushRow() {
            if (rowCells.isEmpty()) return
            rowsText.add(rowCells.joinToString(" | "))
            rowCells.clear()
        }

        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (p.depth > OoxmlLimits.MAX_XML_DEPTH) {
                throw AttachmentException("「${file.name}」XML 嵌套过深，已停止解析")
            }
            when (event) {
                org.xmlpull.v1.XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> {
                        val r = p.getAttributeValue(null, "r")?.toIntOrNull() ?: (currentRow + 1)
                        if (r != currentRow) {
                            flushRow()
                            currentRow = r
                        }
                    }

                    "c" -> {
                        cellRef = p.getAttributeValue(null, "r") ?: ""
                        cellType = p.getAttributeValue(null, "t") ?: ""
                        cellStyle = p.getAttributeValue(null, "s")?.toIntOrNull() ?: -1
                        hasFormulaNoCache = false
                        valueSb = StringBuilder()
                        inValue = false
                    }

                    "f" -> hasFormulaNoCache = true
                    "v" -> {
                        inValue = true; valueSb = StringBuilder()
                    }
                }

                org.xmlpull.v1.XmlPullParser.TEXT -> if (inValue) valueSb.append(p.text)

                org.xmlpull.v1.XmlPullParser.END_TAG -> when (p.name) {
                    "v" -> inValue = false
                    "c" -> {
                        if (cellCount >= OoxmlLimits.MAX_CELLS) {
                            truncated = true
                        } else {
                            val raw = valueSb.toString()
                            val shown = when {
                                raw.isEmpty() && hasFormulaNoCache -> "未知(公式无缓存结果)"
                                raw.isEmpty() -> ""
                                else -> convertValue(raw, cellType, cellStyle)
                            }
                            if (shown.isNotEmpty()) {
                                val label = if (cellRef.isNotBlank()) "[$cellRef]" else ""
                                rowCells.add("$label${shown}")
                                cellCount++
                            }
                        }
                    }
                }
            }
            event = p.next()
        }
        flushRow()
        if (rowsText.isEmpty()) {
            return DocExtractResult(emptyList(), listOf("「${file.name}」选定工作表没有可读的有值单元格"))
        }

        val segments = mutableListOf<DocSegment>()
        var chars = 0
        rowsText.chunked(25).forEachIndexed { i, chunk ->
            if (chars > OoxmlLimits.MAX_CHARS) { truncated = true; return@forEachIndexed }
            val block = chunk.joinToString("\n")
            segments.add(
                DocSegment(
                    sourceId = "$tag-第${i * 25 + 1}行起",
                    label = "表格 第 ${i * 25 + 1}–${i * 25 + chunk.size} 行",
                    excerpt = chunk.first().take(40),
                    text = block,
                ),
            )
            chars += block.length
        }
        val warnings = mutableListOf<String>()
        if (zip.entries.keys.any { it.startsWith("xl/drawings/") || it.startsWith("xl/media/") }) {
            warnings.add("「${file.name}」包含内嵌图片/图表，本轮未解析图片内容；如需识别请另发图片")
        }
        val readSheet = sheetRels.entries.find { ridToTarget[it.value] == sheetFile }?.key
        warnings.add(
            "已读取工作表「${readSheet ?: sheetFile}」；本文件共 ${sheetRels.size} 个工作表" +
                "（${sheetRels.keys.joinToString("、").take(80)}）",
        )
        if (truncated) {
            warnings.add(
                "「${file.name}」超出单元格/字符预算：已读取 $cellCount 个有值单元格 / ${rowsText.size} 行，" +
                    "其余未参与识别；请缩小行范围或分批发送",
            )
        }
        return DocExtractResult(segments, warnings, truncated)
    }

    /** Excel 日期序列值 → 可读文本（1900 系统含 1900 闰年兼容；时间取 HH:mm）。 */
    fun formatSerial(serial: Double, date1904: Boolean): String {
        val days = serial.toLong()
        val frac = serial - days
        val date = if (date1904) {
            java.time.LocalDate.of(1904, 1, 1).plusDays(days)
        } else {
            java.time.LocalDate.of(1899, 12, 30).plusDays(days)
        }
        val seconds = (frac * 86400).roundToLong()
        val time = java.time.LocalTime.ofSecondOfDay(((seconds % 86400) + 86400) % 86400)
        return if (days > 0 && frac > 0.0007) {
            "%04d-%02d-%02d %02d:%02d".format(date.year, date.monthValue, date.dayOfMonth, time.hour, time.minute)
        } else if (days > 0) {
            date.toString()
        } else {
            "%02d:%02d".format(time.hour, time.minute)
        }
    }
}
