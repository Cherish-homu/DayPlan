package com.jsy.dayplan.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jsy.dayplan.vm.ThemeMode

// ---------- 马卡龙手帐主题色板：蓝紫为主动作，薄荷/莓果/蜜桃作小面积辅助 ----------
object DayColors {
    val Primary = Color(0xFF4A5ACB)          // 马卡龙蓝紫，主操作（白底/渐变均可读）
    val OnPrimary = Color(0xFFFFFFFF)
    val Background = Color(0xFFFFF7FB)       // 奶白偏粉页面
    val Surface = Color(0xFFFFFFFF)          // 纯白卡片
    val SelectedBg = Color(0xFFEAE4FF)       // 薰衣草选中底
    val OnSelectedBg = Color(0xFF352B5E)
    val TextMain = Color(0xFF2E2742)         // 李子墨色正文
    val TextSecondary = Color(0xFF786F8E)    // 灰紫次要文字
    val Border = Color(0xFFEDE4F5)
    val ErrorRed = Color(0xFFB23A48)

    /** 重点卡渐变：薰衣草 → 草莓奶昔。 */
    val FocusStart = Color(0xFFE9E2FF)
    val FocusEnd = Color(0xFFFFE2F0)

    /** 类型辅助色：薄荷=待办，莓果=倒数日，蜜桃=提醒/注意。 */
    val TodoMark = Color(0xFF0B7D6C)
    val TodoMarkDark = Color(0xFF7BE8D3)
    val CountdownMark = Color(0xFFC23C7A)
    val CountdownMarkDark = Color(0xFFFFB0CE)
    val PeachMark = Color(0xFFB45309)
    val PeachMarkDark = Color(0xFFFDBA74)

    val MintBg = Color(0xFFDDF7EF)
    val PinkBg = Color(0xFFFFE1EE)
    val LavenderBg = Color(0xFFEAE4FF)
    val PeachBg = Color(0xFFFFE8D6)

    // 深色：深李子底 + 亮马卡龙强调
    val DarkBackground = Color(0xFF171324)
    val DarkSurface = Color(0xFF241E35)
    val DarkPrimary = Color(0xFFB8C2FF)
    val DarkOnPrimary = Color(0xFF20234D)
    val DarkTextMain = Color(0xFFF3EEFF)
    val DarkTextSecondary = Color(0xFFC4B8DB)
    val DarkSelectedBg = Color(0xFF342C4F)
    val DarkFocusStart = Color(0xFF2C2547)
    val DarkFocusEnd = Color(0xFF3A2440)
}

/** 系统字体、统一层级：页面 28 / 分区 20 / 行标题 16 / 副文 13。 */
private val DayTypography = Typography(
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 15.sp),
)

private val DayShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(30.dp),
)

private val LightColors = lightColorScheme(
    primary = DayColors.Primary,
    onPrimary = DayColors.OnPrimary,
    primaryContainer = DayColors.SelectedBg,
    onPrimaryContainer = DayColors.OnSelectedBg,
    secondary = DayColors.Primary,
    onSecondary = DayColors.OnPrimary,
    secondaryContainer = DayColors.SelectedBg,
    onSecondaryContainer = DayColors.OnSelectedBg,
    tertiary = Color(0xFF7C5BD6),              // 薰衣草紫
    onTertiary = DayColors.OnPrimary,
    background = DayColors.Background,
    onBackground = DayColors.TextMain,
    surface = DayColors.Surface,
    onSurface = DayColors.TextMain,
    onSurfaceVariant = DayColors.TextSecondary,
    surfaceVariant = DayColors.SelectedBg,
    surfaceTint = DayColors.Primary,
    surfaceContainerLowest = DayColors.Surface,
    surfaceContainerLow = Color(0xFFFDF8FF),
    surfaceContainer = Color(0xFFF7F1FF),
    surfaceContainerHigh = Color(0xFFF2EAFE),
    surfaceContainerHighest = Color(0xFFEAE0FA),
    inverseSurface = DayColors.TextMain,
    inverseOnSurface = DayColors.Surface,
    outline = Color(0xFFD9CCE8),
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
    tertiary = Color(0xFFD8B4FE),
    onTertiary = Color(0xFF2A1B3D),
    background = DayColors.DarkBackground,
    onBackground = DayColors.DarkTextMain,
    surface = DayColors.DarkSurface,
    onSurface = DayColors.DarkTextMain,
    onSurfaceVariant = DayColors.DarkTextSecondary,
    surfaceVariant = DayColors.DarkSelectedBg,
    surfaceTint = DayColors.DarkPrimary,
    surfaceContainerLowest = Color(0xFF120F1D),
    surfaceContainerLow = Color(0xFF1D182B),
    surfaceContainer = Color(0xFF221C33),
    surfaceContainerHigh = Color(0xFF2A2340),
    surfaceContainerHighest = Color(0xFF342C4F),
    inverseSurface = DayColors.DarkTextMain,
    inverseOnSurface = DayColors.DarkBackground,
    outline = Color(0xFF8A7DA8),
    outlineVariant = DayColors.DarkSelectedBg,
    error = Color(0xFFFFB3C6),
)

/** 按当前实际主题判断深浅，而不是只看系统设置（用户可强制浅/深色）。 */
@Composable
fun isDayPlanDark(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

@Composable
fun todoMarkColor(): Color = if (isDayPlanDark()) DayColors.TodoMarkDark else DayColors.TodoMark

@Composable
fun countdownMarkColor(): Color =
    if (isDayPlanDark()) DayColors.CountdownMarkDark else DayColors.CountdownMark

@Composable
fun peachMarkColor(): Color =
    if (isDayPlanDark()) DayColors.PeachMarkDark else DayColors.PeachMark

@Composable
fun petBubbleColor(): Color =
    if (isDayPlanDark()) DayColors.DarkFocusEnd else DayColors.PinkBg

@Composable
fun focusGradient(): List<Color> = if (isDayPlanDark()) {
    listOf(DayColors.DarkFocusStart, DayColors.DarkFocusEnd)
} else {
    listOf(DayColors.FocusStart, DayColors.FocusEnd)
}

@Composable
fun DayPlanTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = DayTypography,
        shapes = DayShapes,
        content = content,
    )
}
