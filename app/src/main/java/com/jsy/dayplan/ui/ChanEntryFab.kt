package com.jsy.dayplan.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jsy.dayplan.R

/**
 * DeepSeek 娘入口：展开为全身立绘（约 80×120dp），收起为头部头像（约 60dp）。
 * 点击进聊天页；长按整只隐藏（设置里可恢复）；待机轻微呼吸；
 * thinking 时显示三点气泡。动画只作表现，不触发网络或数据写入。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChanEntryFab(
    expanded: Boolean,
    thinking: Boolean,
    onToggleExpanded: () -> Unit,
    onClick: () -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "chanBreath")
    val breath by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.035f,
        animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Reverse),
        label = "chanScale",
    )

    Box(modifier, contentAlignment = Alignment.BottomEnd) {
        Column(horizontalAlignment = Alignment.End) {
            // 收起/展开小开关 + 思考气泡
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 2.dp,
                modifier = Modifier
                    .size(22.dp)
                    .combinedClickable(
                        onClick = onToggleExpanded,
                    ),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        if (expanded) "▾" else "▴",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (thinking) {
                ThinkingBubble(Modifier.padding(top = 4.dp, bottom = 2.dp))
            }
            if (expanded) {
                // 全身立绘：80×120dp，保持比例，透明背景
                Image(
                    painter = painterResource(R.drawable.chan_portrait),
                    contentDescription = "AI 助手 DeepSeek 娘（点击进入聊天）",
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .size(width = 80.dp, height = 120.dp)
                        .scale(breath)
                        .combinedClickable(
                            onClick = onClick,
                            onLongClick = onHide,
                        ),
                )
            } else {
                Image(
                    painter = painterResource(R.drawable.chan_head),
                    contentDescription = "AI 助手 DeepSeek 娘（点击进入聊天）",
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .size(60.dp)
                        .clip(CircleShape)
                        .scale(breath)
                        .combinedClickable(
                            onClick = onClick,
                            onLongClick = onHide,
                        ),
                )
            }
        }
    }
}

/** 思考中三点气泡（纯动画，不请求网络）。 */
@Composable
private fun ThinkingBubble(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "dots")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(900)),
        label = "dotPhase",
    )
    val dots = (0 until 3).joinToString("") { if (it == phase.toInt() % 3) "●" else "·" }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier,
    ) {
        Text(
            dots,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}
