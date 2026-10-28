package com.jsy.dayplan.ledger.core

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 金额一律用 Long 整数“分”存储和计算，绝不用 Float/Double。
 *
 * parseYuanToFen 对**任意**输入都返回 Ok 或可读的 Bad，绝不抛异常到 Compose：
 * - 长度上限：输入超过 MAX_INPUT_CHARS 视为非法（防长文本卡顿/内存暴涨）；
 * - 只接受普通十进制（可带一个前导正负号——负号用于期初余额场景），
 *   不接受科学计数法/十六进制/全角等（BigDecimal 的 E 指数被显式拒绝）；
 * - 两位小数策略不变；绝对值超过 MAX_SINGLE_FEN 视为“金额超出上限”；
 * - longValueExact 的 ArithmeticException 一并捕获兜底。
 */
object Money {
    /** 单笔金额上限 99,999,999.99 元（期初余额允许负值，绝对值同上限）。 */
    const val MAX_SINGLE_FEN: Long = 9_999_999_999L

    /** 上限的“元”表示（与 MAX_SINGLE_FEN 同一数值口径），用于解析阶段的同单位比较。 */
    val MAX_SINGLE_YUAN: BigDecimal = BigDecimal.valueOf(MAX_SINGLE_FEN, 2)

    /** 输入字符数上限：足够表达上限金额，同时防长文本。 */
    const val MAX_INPUT_CHARS: Int = 15

    sealed class ParseResult {
        data class Ok(val fen: Long) : ParseResult()
        data class Bad(val reason: String) : ParseResult()
    }

    /**
     * 三类入口的统一解析：
     * - parseYuanToFen：普通收支/转账，必须 > 0；
     * - parseBudgetYuanToFen：预算，必须 >= 0（显式 0 = 本月不计划支出）；
     * - parseOpeningYuanToFen：期初余额，允许 0 与负值。
     * 绝对值上限一致，均为 MAX_SINGLE_FEN（同单位比较）。
     */
    fun parseYuanToFen(input: String): ParseResult =
        parseInternal(input, allowZero = false, allowNegative = false)

    fun parseBudgetYuanToFen(input: String): ParseResult =
        parseInternal(input, allowZero = true, allowNegative = false)

    fun parseOpeningYuanToFen(input: String): ParseResult =
        parseInternal(input, allowZero = true, allowNegative = true)

    /** 兼容旧调用名：有符号（0 与负值不允许，供无符号场景使用）。 */
    fun parseSignedYuanToFen(input: String): ParseResult =
        parseInternal(input, allowZero = false, allowNegative = true)

    private fun parseInternal(
        input: String,
        allowZero: Boolean,
        allowNegative: Boolean,
    ): ParseResult {
        try {
            val raw = input.trim()
            if (raw.isEmpty()) return ParseResult.Bad("请输入金额")
            if (raw.length > MAX_INPUT_CHARS) return ParseResult.Bad("金额位数过多")
            if (raw.any { it.code > 127 }) return ParseResult.Bad("金额格式不正确")
            if (raw.any { it == 'e' || it == 'E' }) return ParseResult.Bad("不支持科学计数法")
            // 只允许：可选符号 + 数字 + 至多一个小数点
            val body = raw.removePrefix("-").removePrefix("+")
            if (body.isEmpty()) return ParseResult.Bad("金额格式不正确")
            val parts = body.split(".")
            if (parts.size > 2) return ParseResult.Bad("金额格式不正确")
            val intPart = parts[0]
            val fracPart = parts.getOrElse(1) { "" }
            if (intPart.isEmpty() && fracPart.isEmpty()) return ParseResult.Bad("金额格式不正确")
            if (intPart.any { !it.isDigit() } || fracPart.any { !it.isDigit() }) {
                return ParseResult.Bad("金额格式不正确")
            }
            if (fracPart.length > 2) {
                val stripped = fracPart.trimEnd('0')
                if (stripped.length > 2) return ParseResult.Bad("最多两位小数")
            }
            val b = try {
                BigDecimal(raw)
            } catch (e: NumberFormatException) {
                return ParseResult.Bad("金额格式不正确")
            }
            if (!allowNegative && b.signum() < 0) return ParseResult.Bad("金额必须大于 0")
            val scaled = try {
                b.setScale(2, RoundingMode.UNNECESSARY)
            } catch (e: ArithmeticException) {
                return ParseResult.Bad("最多两位小数")
            }
            // 同单位比较：元（scaled）与元（MAX_SINGLE_YUAN）
            if (scaled.abs() > MAX_SINGLE_YUAN) {
                return ParseResult.Bad("金额超出上限")
            }
            val fen = try {
                scaled.movePointRight(2).longValueExact()
            } catch (e: ArithmeticException) {
                return ParseResult.Bad("金额超出上限")
            }
            if (fen == 0L && !allowZero) return ParseResult.Bad("金额必须大于 0")
            return ParseResult.Ok(fen)
        } catch (e: Exception) {
            // 任何未预期异常也转为可读 Bad，绝不上抛
            return ParseResult.Bad("金额格式不正确")
        }
    }

    /** 分 -> 元字符串，如 1234 -> "12.34"；负数 -5 -> "-0.05"。 */
    fun formatFen(fen: Long): String {
        val negative = fen < 0
        val abs = if (negative) -fen else fen
        val s = "%d.%02d".format(abs / 100, abs % 100)
        return if (negative) "-$s" else s
    }

    /** 界面展示：¥12.34 */
    fun formatYuan(fen: Long): String = "¥" + formatFen(fen)
}
