package com.jsy.dayplan.backup

import com.jsy.dayplan.core.PlanRules
import java.io.ByteArrayOutputStream
import java.io.InputStream

class BackupTooLargeException(limit: Int) :
    IllegalArgumentException("备份文件超过上限（${limit / 1024 / 1024} MiB），已拒绝读取")

/**
 * 有界读取：边读边累计字节，超过 10 MiB 立即中止，不依赖内容提供方声明的长度，
 * 也不会先完整读入或解析超限文件。
 */
object BackupIO {
    fun readTextBounded(
        input: InputStream,
        maxBytes: Int = PlanRules.MAX_JSON_BYTES,
    ): String {
        val out = ByteArrayOutputStream(minOf(64 * 1024, maxBytes))
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > maxBytes) throw BackupTooLargeException(maxBytes)
            out.write(buf, 0, n)
        }
        return out.toByteArray().toString(Charsets.UTF_8)
    }

    fun utf8Size(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    /** 导出前核对编码后体积上限。 */
    fun requireEncodedWithinLimit(text: String) {
        if (utf8Size(text) > PlanRules.MAX_JSON_BYTES) {
            throw BackupTooLargeException(PlanRules.MAX_JSON_BYTES)
        }
    }
}
