package com.jsy.dayplan.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 可注入的时钟：时间核心与计算逻辑不直接依赖系统时间，
 * 测试用 FixedClock 固定“今天”，杜绝测试依赖真实日期。
 */
interface AppClock {
    /** 设备当前时区下的本地日期时间。 */
    fun now(): LocalDateTime

    /** 单调时钟毫秒（同一次开机内单调递增），用于专注倒计时。 */
    fun elapsedRealtimeMs(): Long

    /** 本次开机标识（重启后变化，用于检测设备重启）。 */
    fun bootId(): String

    /** 当前时区 ID。 */
    fun zoneId(): java.time.ZoneId
}

class SystemAppClock(private val contentResolver: android.content.ContentResolver) : AppClock {
    override fun now(): LocalDateTime = LocalDateTime.now()

    override fun elapsedRealtimeMs(): Long = android.os.SystemClock.elapsedRealtime()

    /** 本次开机标识：Settings.Global.BOOT_COUNT 每次重启递增。 */
    override fun bootId(): String = try {
        android.provider.Settings.Global.getInt(
            contentResolver, android.provider.Settings.Global.BOOT_COUNT,
        ).toString()
    } catch (e: Exception) {
        "unknown"
    }

    override fun zoneId(): java.time.ZoneId = java.time.ZoneId.systemDefault()
}

/** 固定时钟（测试与可预测演示用）。 */
class FixedClock(
    private var dateTime: LocalDateTime = LocalDateTime.of(2026, 10, 24, 9, 0),
    private var elapsedMs: Long = 1_000_000L,
    private var boot: String = "boot-1",
    private val zone: java.time.ZoneId = java.time.ZoneId.of("Asia/Shanghai"),
) : AppClock {
    override fun now(): LocalDateTime = dateTime
    override fun elapsedRealtimeMs(): Long = elapsedMs
    override fun bootId(): String = boot
    override fun zoneId(): ZoneId = zone

    fun advance(minutes: Long = 1) {
        dateTime = dateTime.plusMinutes(minutes)
        elapsedMs += minutes * 60_000
    }

    fun setDateTime(dt: LocalDateTime) { dateTime = dt }
    fun setElapsed(ms: Long) { elapsedMs = ms }
    fun reboot(newBoot: String) { boot = newBoot; elapsedMs = 500_000L }
}

/** 业务约束：UI、Repository、备份校验共用。 */
object PlanRules {
    const val TITLE_MAX = 80
    const val NOTE_MAX = 1000
    const val LOCATION_MAX = 200
    const val MIN_YEAR = 2000
    const val MAX_YEAR = 2100
    const val MAX_ITEMS = 10_000
    const val MAX_JSON_BYTES = 10 * 1024 * 1024

    const val TITLE_MIN = 1

    /** 补发宽限窗口：错过提醒超过 10 分钟不再集中补发。 */
    const val MISSED_GRACE_MINUTES: Long = 10

    fun titleOk(s: String): Boolean = s.trim().length in TITLE_MIN..TITLE_MAX
    fun noteOk(s: String): Boolean = s.length <= NOTE_MAX
    fun locationOk(s: String): Boolean = s.length <= LOCATION_MAX
    fun dateOk(d: LocalDate): Boolean = !d.isBefore(LocalDate.of(MIN_YEAR, 1, 1)) &&
        !d.isAfter(LocalDate.of(MAX_YEAR, 12, 31))

    /**
     * 统一时间格式：接受一位或两位小时（如 "9:00"），输出规范 "HH:mm"。
     * Repository 写入、备份校验、导入都走这一个口径，保证任何正常保存的数据
     * 都能通过自身备份校验。非法返回 null。
     */
    fun normalizeTime(s: String): String? {
        val m = Regex("""^(\d{1,2}):(\d{2})$""").find(s.trim()) ?: return null
        val h = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: return null
        if (h !in 0..23 || min !in 0..59) return null
        return "%02d:%02d".format(h, min)
    }
}
