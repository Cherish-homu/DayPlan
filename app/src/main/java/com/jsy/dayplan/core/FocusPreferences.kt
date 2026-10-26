package com.jsy.dayplan.core

import android.content.Context

enum class FocusSound(val title: String) {
    OFF("关闭"), RAIN("细雨"), WAVES("海浪"), WHITE("白噪音");
    companion object { fun from(raw: String?) = entries.find { it.name == raw } ?: OFF }
}

data class FocusAppearance(
    val scene: Int = 3,
    val blur: Float = 3f,
    /** 背景暗色遮罩强度，0.15–0.85；越大文字越清晰。 */
    val scrim: Float = 0.55f,
    val sound: FocusSound = FocusSound.OFF,
    val volume: Float = 0.5f,
    val minutes: Int = 25,
)

class FocusPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("focus_appearance", Context.MODE_PRIVATE)
    fun load() = FocusAppearance(
        scene = prefs.getInt("scene", 3).coerceIn(0, 4),
        blur = prefs.getFloat("blur", 3f).coerceIn(0f, 12f),
        scrim = prefs.getFloat("scrim", 0.55f).coerceIn(0.15f, 0.85f),
        sound = FocusSound.from(prefs.getString("sound", null)),
        volume = prefs.getFloat("volume", 0.5f).coerceIn(0f, 1f),
        minutes = prefs.getInt("minutes", 25).coerceIn(1, 180),
    )
    fun save(value: FocusAppearance) {
        prefs.edit().putInt("scene", value.scene.coerceIn(0, 4))
            .putFloat("blur", value.blur.coerceIn(0f, 12f))
            .putFloat("scrim", value.scrim.coerceIn(0.15f, 0.85f))
            .putString("sound", value.sound.name).putFloat("volume", value.volume.coerceIn(0f, 1f))
            .putInt("minutes", value.minutes.coerceIn(1, 180)).apply()
    }
}
