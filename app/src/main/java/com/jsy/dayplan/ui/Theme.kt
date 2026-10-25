package com.jsy.dayplan.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.jsy.dayplan.vm.ThemeMode

// ---------- 蓝白主题色板 ----------
object DayColors {
    val Primary = Color(0xFF2563EB)          // 清爽蓝
    val OnPrimary = Color(0xFFFFFFFF)
    val Background = Color(0xFFF5F8FF)       // 浅蓝白
    val Surface = Color(0xFFFFFFFF)          // 纯白卡片
    val SelectedBg = Color(0xFFDBEAFE)       // 浅蓝选中底
    val OnSelectedBg = Color(0xFF1E3A8A)
    val TextMain = Color(0xFF172B4D)         // 深蓝灰正文
    val TextSecondary = Color(0xFF64748B)    // 次要文字
    val Border = Color(0xFFE2E8F0)           // 淡蓝灰边框
    val ErrorRed = Color(0xFFB3261E)

    // 深色：深蓝灰背景 + 亮蓝强调
    val DarkBackground = Color(0xFF101828)
    val DarkSurface = Color(0xFF1D2939)
    val DarkPrimary = Color(0xFF6EA8FE)
    val DarkOnPrimary = Color(0xFF0A2A66)
    val DarkTextMain = Color(0xFFE7EEF8)
    val DarkTextSecondary = Color(0xFF9AA7BA)
    val DarkSelectedBg = Color(0xFF24344D)
}

private val LightColors = lightColorScheme(
    primary = DayColors.Primary,
    onPrimary = DayColors.OnPrimary,
    primaryContainer = DayColors.SelectedBg,
    onPrimaryContainer = DayColors.OnSelectedBg,
    secondary = DayColors.Primary,
    onSecondary = DayColors.OnPrimary,
    // Material3 默认 secondaryContainer/tertiary 是紫色；统一改为蓝白，避免控件偏紫。
    secondaryContainer = DayColors.SelectedBg,
    onSecondaryContainer = DayColors.OnSelectedBg,
    tertiary = Color(0xFF1D4ED8),
    onTertiary = DayColors.OnPrimary,
    background = DayColors.Background,
    onBackground = DayColors.TextMain,
    surface = DayColors.Surface,
    onSurface = DayColors.TextMain,
    onSurfaceVariant = DayColors.TextSecondary,
    surfaceVariant = DayColors.SelectedBg,
    surfaceTint = DayColors.Primary,
    surfaceContainerLowest = DayColors.Surface,
    surfaceContainerLow = Color(0xFFF8FAFF),
    surfaceContainer = Color(0xFFF1F5FD),
    surfaceContainerHigh = Color(0xFFEBF1FC),
    surfaceContainerHighest = Color(0xFFE3ECFB),
    inverseSurface = DayColors.TextMain,
    inverseOnSurface = DayColors.Surface,
    outline = DayColors.Border,
    outlineVariant = DayColors.Border,
    error = DayColors.ErrorRed,
)

private val DarkColors = darkColorScheme(
    primary = DayColors.DarkPrimary,
    onPrimary = DayColors.DarkOnPrimary,
    primaryContainer = DayColors.DarkSelectedBg,
    onPrimaryContainer = DayColors.DarkTextMain,
    secondary = DayColors.DarkPrimary,
    onSecondary = DayColors.DarkOnPrimary,
    secondaryContainer = DayColors.DarkSelectedBg,
    onSecondaryContainer = DayColors.DarkTextMain,
    tertiary = DayColors.DarkPrimary,
    onTertiary = DayColors.DarkOnPrimary,
    background = DayColors.DarkBackground,
    onBackground = DayColors.DarkTextMain,
    surface = DayColors.DarkSurface,
    onSurface = DayColors.DarkTextMain,
    onSurfaceVariant = DayColors.DarkTextSecondary,
    surfaceVariant = DayColors.DarkSelectedBg,
    surfaceTint = DayColors.DarkPrimary,
    surfaceContainerLowest = Color(0xFF0B1220),
    surfaceContainerLow = Color(0xFF141D2B),
    surfaceContainer = Color(0xFF182333),
    surfaceContainerHigh = Color(0xFF1D2939),
    surfaceContainerHighest = Color(0xFF24344D),
    inverseSurface = DayColors.DarkTextMain,
    inverseOnSurface = DayColors.DarkBackground,
    outline = DayColors.DarkTextSecondary,
    outlineVariant = DayColors.DarkSelectedBg,
    error = Color(0xFFF2B8B5),
)

@Composable
fun DayPlanTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content,
    )
}
