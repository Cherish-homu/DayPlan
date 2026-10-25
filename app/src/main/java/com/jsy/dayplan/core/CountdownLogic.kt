package com.jsy.dayplan.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** 倒数日类别。 */
object CountdownCategory {
    const val EXAM = 0
    const val DEADLINE = 1
    const val BIRTHDAY = 2
    const val ANNIVERSARY = 3
    const val OTHER = 4
    val NAMES = listOf("考试", "截止", "生日", "纪念日", "其他")
}

/**
 * 倒数日核心计算。日期一律用 LocalDate 与 ChronoUnit.DAYS.between，
 * 绝不用毫秒差除以 86400000（受夏令时与半天偏移影响）。
 *
 * 计数口径（第一版统一，不提供开关）：
 * - 普通目标：显示“还有 N 天”（不含当天）、“就是今天”、“已过去 N 天”；
 * - 年度重复：按“下一次发生日期”排序与计数；原始日期另存用于显示已满周年；
 *   当天仍显示“就是今天”，不提前跳到下一年。
 */
object CountdownLogic {

    /** 2 月 29 日在非闰年按 2 月 28 日处理（编辑页已写明）。 */
    fun resolveYearlyOccurrence(original: LocalDate, year: Int): LocalDate {
        return if (original.monthValue == 2 && original.dayOfMonth == 29) {
            val lastDay = YearMonth29.lengthOfMonth(year)
            LocalDate.of(year, 2, minOf(29, lastDay))
        } else {
            LocalDate.of(year, original.monthValue, original.dayOfMonth)
        }
    }

    private object YearMonth29 {
        fun lengthOfMonth(year: Int): Int {
            val leap = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
            return if (leap) 29 else 28
        }
    }

    /**
     * 下一次发生日期：
     * - 非年度：目标日期本身（无论过去与否；过期由 UI 归入“已过去”）；
     * - 年度：目标日期当年或之后下一次发生（今天返回今天，不提前跳下一年）。
     */
    fun nextOccurrence(target: LocalDate, today: LocalDate, yearly: Boolean): LocalDate {
        if (!yearly) return target
        var occ = resolveYearlyOccurrence(target, target.year)
        var year = target.year
        while (occ.isBefore(today)) {
            year += 1
            occ = resolveYearlyOccurrence(target, year)
        }
        return occ
    }

    /** 是否已过期：非年度 target < today；年度事件永不过期（定位下一周年）。 */
    fun isPast(target: LocalDate, today: LocalDate, yearly: Boolean): Boolean =
        !yearly && target.isBefore(today)

    /** 展示文案：days = ChronoUnit.DAYS.between(today, occurrence)。 */
    fun displayText(days: Long): String = when {
        days == 0L -> "就是今天"
        days > 0 -> "还有 $days 天"
        else -> "已过去 ${-days} 天"
    }

    fun daysBetween(today: LocalDate, occurrence: LocalDate): Long =
        ChronoUnit.DAYS.between(today, occurrence)

    /** 排序：置顶优先；未过期区按临近在前；已过去区按最近过去在前。 */
    fun <T> sortWith(
        items: List<T>,
        pinned: (T) -> Boolean,
        occurrence: (T) -> LocalDate,
        today: LocalDate,
        yearly: (T) -> Boolean,
    ): List<T> = items.sortedWith(
        compareByDescending<T> { pinned(it) }
            .thenBy { isPast(occurrence(it), today, yearly(it)) }   // 未过期在前，已过去在后
            .thenBy {
                // 组内排序：未过期按临近（epochDay 升序）；已过去按最近（-epochDay 升序）
                val occ = occurrence(it)
                if (isPast(occ, today, yearly(it))) -occ.toEpochDay()
                else occ.toEpochDay()
            },
    )
}
