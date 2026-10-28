package com.jsy.dayplan.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jsy.dayplan.vm.Screen

/**
 * 全局马卡龙氛围背景：柔和渐变 + 三团低透明度色晕 + 专注页同款细圆环。
 * 所有主页面共用，页面自身保持透明即可透出。
 */
@Composable
fun MacaronBackdrop(modifier: Modifier = Modifier) {
    val dark = isDayPlanDark()
    val top = if (dark) Color(0xFF171324) else Color(0xFFFFF4F9)
    val middle = if (dark) Color(0xFF1D1830) else Color(0xFFF4F0FF)
    val bottom = if (dark) Color(0xFF221C33) else Color(0xFFEAF7F3)
    val pink = if (dark) Color(0xFF5A2F4E) else Color(0xFFFFD3E8)
    val lavender = if (dark) Color(0xFF3A3158) else Color(0xFFDCD2FF)
    val mint = if (dark) Color(0xFF1E4A44) else Color(0xFFCFF4E8)
    val ring = if (dark) Color(0xFFB8C2FF) else Color(0xFF8F7BD8)
    val blobAlpha = if (dark) 0.55f else 0.8f

    Canvas(modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(top, middle, bottom)))
        fun blob(color: Color, cx: Float, cy: Float, radius: Float) {
            val center = Offset(size.width * cx, size.height * cy)
            val r = size.minDimension * radius
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = blobAlpha), Color.Transparent),
                    center = center,
                    radius = r,
                ),
                radius = r,
                center = center,
            )
        }
        blob(pink, 0.94f, 0.08f, 0.62f)
        blob(lavender, 0.02f, 0.38f, 0.66f)
        blob(mint, 0.9f, 0.9f, 0.58f)

        // 与专注页呼应的装饰圆环，不抢正文
        val ringCenter = Offset(size.width * 0.98f, size.height * 0.07f)
        drawCircle(
            color = ring.copy(alpha = if (dark) 0.16f else 0.2f),
            radius = size.width * 0.46f,
            center = ringCenter,
            style = Stroke(width = 1.6.dp.toPx()),
        )
        drawCircle(
            color = ring.copy(alpha = if (dark) 0.06f else 0.07f),
            radius = size.width * 0.36f,
            center = ringCenter,
            style = Stroke(width = 12.dp.toPx()),
        )
    }
}

/** 玻璃卡片底色：浅色半透白，深色半透深李子。 */
@Composable
fun glassCardColor(): Color =
    if (isDayPlanDark()) Color(0xD9241E35) else Color(0xD9FFFFFF)

/** 更实的玻璃层，用于输入条、悬浮底栏等需要更清晰文字的区域。 */
@Composable
fun glassStrongColor(): Color =
    if (isDayPlanDark()) Color(0xF22A2340) else Color(0xF2FFFFFF)

@Composable
fun glassBorderColor(): Color =
    if (isDayPlanDark()) Color.White.copy(alpha = 0.1f) else Color.White.copy(alpha = 0.8f)

@Composable
fun glassNavColor(): Color =
    if (isDayPlanDark()) Color(0xF2241E35) else Color(0xF7FFFFFF)

/** 统一玻璃卡片：大圆角、细描边、无投影，文字与控件仍走主题色。 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = MaterialTheme.shapes.large,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = glassCardColor()),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, glassBorderColor()),
        content = content,
    )
}

/** 悬浮玻璃底栏：五等分，选中项用主色浅底胶囊承托；账本在第 2 位（今天与月历之间）。 */
@Composable
fun FloatingGlassNavBar(current: Screen, onSelect: (Screen) -> Unit) {
    val items: List<Triple<Screen, ImageVector, String>> = listOf(
        Triple(Screen.Today, Icons.Filled.Today, "今天"),
        Triple(Screen.Ledger, Icons.Filled.AccountBalanceWallet, "账本"),
        Triple(Screen.Calendar, Icons.Filled.CalendarMonth, "月历"),
        Triple(Screen.Countdowns, Icons.Filled.Event, "倒数日"),
        Triple(Screen.Settings, Icons.Filled.Settings, "我的"),
    )
    Box(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = glassNavColor(),
            border = BorderStroke(1.dp, glassBorderColor()),
            shadowElevation = 12.dp,
            modifier = Modifier.fillMaxWidth().height(64.dp),
        ) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items.forEach { (screen, icon, label) ->
                    val selected = current == screen
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(vertical = 5.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.13f)
                                else Color.Transparent,
                            )
                            .clickable { onSelect(screen) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            icon,
                            contentDescription = label,
                            tint = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(21.dp),
                        )
                        Text(
                            label,
                            fontSize = 10.sp,
                            lineHeight = 13.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
