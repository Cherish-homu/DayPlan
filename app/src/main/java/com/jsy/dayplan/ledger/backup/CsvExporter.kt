package com.jsy.dayplan.ledger.backup

/** CSV 导出：UTF-8（带 BOM，便于 Excel 识别中文）。
 *  转义规则：引号加倍；含逗号/引号/换行的字段加引号；
 *  以 = + - @ 开头的用户文本前置单引号，防止被 Excel 当作公式执行。 */
object CsvExporter {
    const val BOM = "\uFEFF"

    fun escape(field: String): String {
        var s = field
        if (s.isNotEmpty() && s[0] in "=@+-") s = "'$s"
        return if (s.contains(',') || s.contains('"') || s.contains('\n') || s.contains('\r')) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else s
    }

    fun transactionsCsv(
        header: List<String>,
        rows: List<List<String>>,
    ): String {
        val sb = StringBuilder()
        sb.append(BOM)
        sb.append(header.joinToString(",") { escape(it) }).append("\r\n")
        for (r in rows) {
            sb.append(r.joinToString(",") { escape(it) }).append("\r\n")
        }
        return sb.toString()
    }
}
