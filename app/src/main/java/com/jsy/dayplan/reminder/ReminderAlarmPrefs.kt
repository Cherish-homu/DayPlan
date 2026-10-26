package com.jsy.dayplan.reminder

import android.content.Context

/** 到点全屏提醒与闹钟音乐设置；只影响提醒展示，不改变日程/待办数据。 */
class ReminderAlarmPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("reminder_alarm", Context.MODE_PRIVATE)

    /** 默认开启：到点由大肥鱼全屏弹出，同时保留通知。 */
    var popupEnabled: Boolean
        get() = prefs.getBoolean("popup", true)
        set(value) = prefs.edit().putBoolean("popup", value).apply()

    /** 用户通过系统文件选择器选的音频；null 表示系统闹钟声。 */
    val soundUri: String?
        get() = prefs.getString("sound_uri", null)

    val soundName: String
        get() = prefs.getString("sound_name", null) ?: "系统闹钟声"

    fun saveSound(uri: String, name: String) {
        prefs.edit()
            .putString("sound_uri", uri)
            .putString("sound_name", name.take(80))
            .apply()
    }

    fun clearSound() {
        prefs.edit().remove("sound_uri").remove("sound_name").apply()
    }
}
