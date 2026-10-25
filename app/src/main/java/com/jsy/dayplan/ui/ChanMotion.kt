package com.jsy.dayplan.ui

/** 角色动效偏好；默认跟随系统。字符串写入 SharedPreferences，升级不破坏旧设置。 */
enum class ChanAnimationMode(val key: String, val label: String, val description: String) {
    SYSTEM("system", "跟随系统", "系统关闭动画时自动减少"),
    REDUCED("reduced", "减少", "停用呼吸、眨眼和位移动画"),
    FULL("full", "完整", "呼吸、眨眼和状态动效"),
    ;

    companion object {
        fun fromKey(key: String?): ChanAnimationMode =
            entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

object ChanMotion {
    /**
     * 跟随系统时读取系统 animator_duration_scale；0 表示系统关闭动画。
     * 完整模式不强制播放：这是用户显式选择，不受系统设置影响。
     */
    fun shouldReduce(mode: ChanAnimationMode, systemAnimatorScale: Float?): Boolean = when (mode) {
        ChanAnimationMode.REDUCED -> true
        ChanAnimationMode.FULL -> false
        ChanAnimationMode.SYSTEM -> (systemAnimatorScale ?: 1f) == 0f
    }
}
