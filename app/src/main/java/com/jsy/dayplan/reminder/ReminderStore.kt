package com.jsy.dayplan.reminder

import android.content.Context

/**
 * 实际注册的提醒身份。取消时必须按这里记录的完整 key 处理，
 * 不能再用“短 key”或版本号猜测。
 */
interface ReminderKeyStore {
    fun add(key: String)
    fun removeAll(keys: Collection<String>)
    fun keysFor(type: String, id: Long): List<String>
    fun all(): List<String>
    fun clear()
}

/** 测试用：进程内记录。 */
class InMemoryReminderKeyStore : ReminderKeyStore {
    private val keys = linkedSetOf<String>()

    @Synchronized
    override fun add(key: String) { keys.add(key) }

    @Synchronized
    override fun removeAll(keys: Collection<String>) { this.keys.removeAll(keys.toSet()) }

    @Synchronized
    override fun keysFor(type: String, id: Long): List<String> =
        keys.filter { it.startsWith("$type-$id-") }

    @Synchronized
    override fun all(): List<String> = keys.toList()

    @Synchronized
    override fun clear() { keys.clear() }
}

/** 生产用：SharedPreferences 持久化，进程被杀/升级后仍能取消完整旧身份。 */
class PrefsReminderKeyStore(context: Context) : ReminderKeyStore {
    private val prefs = context.getSharedPreferences("reminder_keys", Context.MODE_PRIVATE)

    @Synchronized
    private fun snapshot(): MutableSet<String> =
        (prefs.getStringSet(KEY, emptySet()) ?: emptySet()).toMutableSet()

    @Synchronized
    private fun persist(set: Set<String>) {
        prefs.edit().putStringSet(KEY, set).apply()
    }

    @Synchronized
    override fun add(key: String) {
        val s = snapshot(); s.add(key); persist(s)
    }

    @Synchronized
    override fun removeAll(keys: Collection<String>) {
        val s = snapshot(); s.removeAll(keys.toSet()); persist(s)
    }

    @Synchronized
    override fun keysFor(type: String, id: Long): List<String> =
        snapshot().filter { it.startsWith("$type-$id-") }

    @Synchronized
    override fun all(): List<String> = snapshot().toList()

    @Synchronized
    override fun clear() = persist(emptySet())

    private companion object { const val KEY = "registered_keys" }
}

/** 提醒投递状态：供“我的”页面如实展示，权限关闭/投递失败不静默。 */
class ReminderStatusStore(context: Context) {
    private val prefs = context.getSharedPreferences("reminder_status", Context.MODE_PRIVATE)

    fun recordDelivered(title: String, atMs: Long) {
        prefs.edit()
            .putString("lastDeliveredTitle", title)
            .putLong("lastDeliveredAt", atMs)
            .putString("lastBlockedReason", null)
            .apply()
    }

    fun recordBlocked(reason: String, atMs: Long) {
        prefs.edit()
            .putString("lastBlockedReason", reason)
            .putLong("lastBlockedAt", atMs)
            .apply()
    }

    fun lastDeliveredTitle(): String? = prefs.getString("lastDeliveredTitle", null)
    fun lastDeliveredAt(): Long = prefs.getLong("lastDeliveredAt", 0)
    fun blockedReason(): String? = prefs.getString("lastBlockedReason", null)
    fun blockedAt(): Long = prefs.getLong("lastBlockedAt", 0)
}
