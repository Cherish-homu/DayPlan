package com.jsy.dayplan.ui.ledger

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jsy.dayplan.ledger.core.Money
import com.jsy.dayplan.ui.GlassCard
import com.jsy.dayplan.ui.countdownMarkColor
import com.jsy.dayplan.ui.glassNavColor
import com.jsy.dayplan.ui.todoMarkColor

/** 账本统一视觉口径：支出莓果、收入薄荷、转账中性、预算蜜桃（与日序主色板一致）。 */
@Composable
fun ledgerExpenseColor(): Color = countdownMarkColor()

@Composable
fun ledgerIncomeColor(): Color = todoMarkColor()

/**
 * 账本页面内容根部必须显式提供 LocalContentColor：
 * 页面 Scaffold 都是 Color.Transparent，contentColor 链会一路回落到
 * Compose 默认的 Color.Black，深色模式下文字会变成黑字（浅色模式恰好看不出）。
 */
@Composable
fun LedgerOnSurface(content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalContentColor provides MaterialTheme.colorScheme.onSurface,
        content = content,
    )
}

/** 收支金额展示：支出带负号、收入带正号，颜色区分（全页面统一此口径）。 */
fun signedAmount(type: Int, fen: Long): String =
    (if (type == 1) "+" else "-") + Money.formatYuan(fen)

/** 账本三联卡：本月收入 / 支出 / 结余。 */
@Composable
fun LedgerStatCard(
    label: String,
    fen: Long,
    valueColor: Color,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 12.dp)) {
            Text(
                label,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                Money.formatYuan(fen),
                fontSize = 17.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.Bold,
                color = valueColor,
                maxLines = 1,
            )
        }
    }
}

/** 月份切换行：‹ 2026-10 ›。 */
@Composable
fun LedgerMonthSwitch(
    month: String,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        IconButton(onClick = onPrev) {
            Icon(Icons.Filled.ChevronLeft, "上一月", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            month.replace("-", " 年 ") + " 月",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        IconButton(onClick = onNext) {
            Icon(Icons.Filled.ChevronRight, "下一月", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 分类小标签：支出/收入分别用莓果/薄荷浅底胶囊。 */
@Composable
fun LedgerCategoryChip(name: String, isIncome: Boolean, modifier: Modifier = Modifier) {
    val color = if (isIncome) ledgerIncomeColor() else ledgerExpenseColor()
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(name, fontSize = 12.sp, color = color, maxLines = 1)
    }
}

/** 账本子页切换胶囊：玻璃底、选中主色浅底。 */
@Composable
fun LedgerTabPills(
    tabs: List<Pair<String, Boolean>>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(glassNavColor())
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        tabs.forEachIndexed { i, (label, selected) ->
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.13f)
                        else Color.Transparent,
                    )
                    .clickable { onSelect(i) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    label,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}
