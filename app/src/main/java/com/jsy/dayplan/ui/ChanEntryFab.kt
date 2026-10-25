package com.jsy.dayplan.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jsy.dayplan.R
import kotlin.math.abs
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

enum class ChanMood { IDLE, THINKING, DRAFT_READY, SAVED, ERROR }

data class ChanStatus(
    val mood: ChanMood = ChanMood.IDLE,
    val count: Int = 0,
    val message: String = "",
    val retryable: Boolean = false,
)

/** 角色在底部栏内占位，不覆盖正文、列表操作或新增按钮。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChanEntryBar(
    expanded: Boolean,
    status: ChanStatus,
    reduceMotion: Boolean,
    playAppear: Boolean,
    onAppearFinished: () -> Unit,
    onToggleExpanded: () -> Unit,
    onClick: () -> Unit,
    onHide: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val visible = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val animate = !reduceMotion && visible
    val appearance = remember { Animatable(if (playAppear && !reduceMotion) 0f else 1f) }
    val haptics = LocalHapticFeedback.current
    var petReaction by remember { mutableStateOf<PetReaction?>(null) }
    var petSignal by remember { mutableIntStateOf(0) }

    LaunchedEffect(petReaction) {
        if (petReaction != null) {
            delay(3500)
            petReaction = null
        }
    }

    fun pet() {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        petReaction = PET_REACTIONS.random()
        petSignal += 1
    }

    LaunchedEffect(playAppear, reduceMotion) {
        if (playAppear && !reduceMotion) {
            appearance.animateTo(
                1f,
                animationSpec = tween(220, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)),
            )
        } else {
            appearance.snapTo(1f)
        }
        onAppearFinished()
    }

    val expandMs = if (reduceMotion) 0 else 180
    Column(
        modifier
            .graphicsLayer {
                alpha = appearance.value
                translationY = (1f - appearance.value) * 8.dp.toPx()
                val s = 0.96f + 0.04f * appearance.value
                scaleX = s
                scaleY = s
                transformOrigin = TransformOrigin(0.5f, 1f)
            }
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.End,
    ) {
        // 只有任务处理中/结果或摸头回应时展开文字；待机保持紧凑。
        if (petReaction != null) {
            PetBubble(petReaction!!, onClick = onClick)
            Spacer(Modifier.height(4.dp))
        } else if (status.mood != ChanMood.IDLE) {
            StatusBubble(
                status = status,
                animate = animate,
                onClick = onClick,
                onRetry = onRetry,
            )
            Spacer(Modifier.height(4.dp))
        }
        Surface(
            shape = if (expanded) MaterialTheme.shapes.medium else CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 3.dp,
        ) {
            Row(
                Modifier.padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    AnimatedContent(
                        targetState = expanded,
                        transitionSpec = {
                            (fadeIn(tween(if (reduceMotion) 0 else 120)) +
                                scaleIn(initialScale = 0.97f, animationSpec = tween(if (reduceMotion) 0 else 180))) togetherWith
                                (fadeOut(tween(if (reduceMotion) 0 else 100)) +
                                    scaleOut(targetScale = 0.97f, animationSpec = tween(if (reduceMotion) 0 else 160))) using
                                SizeTransform(clip = false) { _, _ -> tween(expandMs) }
                        },
                        label = "chanExpand",
                    ) { isExpanded ->
                        ChanBody(
                            expanded = isExpanded,
                            animate = animate,
                            mood = status.mood,
                            petSignal = petSignal,
                            onPet = { pet() },
                            onHide = onHide,
                        )
                    }
                }
                IconButton(
                    onClick = onToggleExpanded,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "收起助手" else "展开助手",
                    )
                }
            }
        }
    }
}

private enum class PetMood { SLEEPY, LOVE, HUNGRY }

private data class PetReaction(val mood: PetMood, val text: String)

private val PET_REACTIONS = listOf(
    PetReaction(PetMood.SLEEPY, "困了…"),
    PetReaction(PetMood.LOVE, "爱你～"),
    PetReaction(PetMood.HUNGRY, "想吃大白饭…"),
)

@Composable
private fun PetBubble(reaction: PetReaction, onClick: () -> Unit) {
    val icon = when (reaction.mood) {
        PetMood.SLEEPY -> Icons.Filled.Bedtime
        PetMood.LOVE -> Icons.Filled.Favorite
        PetMood.HUNGRY -> Icons.Filled.Restaurant
    }
    val tint = when (reaction.mood) {
        PetMood.SLEEPY -> MaterialTheme.colorScheme.primary
        PetMood.LOVE -> countdownMarkColor()
        PetMood.HUNGRY -> peachMarkColor()
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = petBubbleColor(),
        tonalElevation = 1.dp,
        modifier = Modifier
            .widthIn(max = 300.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, Modifier.size(18.dp), tint = tint)
            Spacer(Modifier.width(8.dp))
            Text(
                reaction.text,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                maxLines = 2,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(8.dp))
            Text("聊天 ›", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatusBubble(
    status: ChanStatus,
    animate: Boolean,
    onClick: () -> Unit,
    onRetry: () -> Unit,
) {
    val error = status.mood == ChanMood.ERROR
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 1.dp,
        modifier = Modifier
            .widthIn(max = 280.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                status.mood == ChanMood.THINKING -> {
                    ThinkingDots(animate)
                    Spacer(Modifier.width(8.dp))
                }

                else -> {
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(
                                if (error) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary,
                            ),
                    )
                    Spacer(Modifier.width(8.dp))
                }
            }
            Text(
                statusText(status),
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = if (error) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (error && status.retryable) {
                TextButton(
                    onClick = onRetry,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 8.dp, vertical = 0.dp,
                    ),
                ) { Text("重试", fontSize = 12.sp) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChanBody(
    expanded: Boolean,
    animate: Boolean,
    mood: ChanMood,
    petSignal: Int,
    onPet: () -> Unit,
    onHide: () -> Unit,
) {
    val blink = rememberChanBlink(animate)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reaction = remember { Animatable(0f) }
    val petPulse = remember { Animatable(0f) }
    LaunchedEffect(petSignal) {
        if (petSignal > 0 && animate) {
            petPulse.snapTo(0f)
            petPulse.animateTo(1f, tween(90))
            petPulse.animateTo(0f, tween(140))
        }
    }
    LaunchedEffect(mood, animate) {
        if (!animate) {
            reaction.snapTo(0f)
            return@LaunchedEffect
        }
        when (mood) {
            ChanMood.SAVED -> {
                reaction.snapTo(0f)
                reaction.animateTo(1f, tween(140))
                reaction.animateTo(0f, tween(140))
            }

            ChanMood.ERROR -> {
                reaction.snapTo(0f)
                reaction.animateTo(1f, tween(120))
                reaction.animateTo(-1f, tween(120))
                reaction.animateTo(0f, tween(80))
            }

            ChanMood.DRAFT_READY -> {
                reaction.snapTo(0f)
                reaction.animateTo(1f, tween(160))
                reaction.animateTo(0f, tween(160))
            }

            else -> reaction.snapTo(0f)
        }
    }
    Breathy(active = animate) {
        Image(
            painter = painterResource(
                when {
                    expanded && blink -> R.drawable.chan_idle_blink
                    expanded -> R.drawable.chan_idle_open
                    blink -> R.drawable.chan_head_blink
                    else -> R.drawable.chan_head_open
                },
            ),
            contentDescription = "助手 DeepSeek 娘，点击互动，长按隐藏",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .width(if (expanded) 72.dp else 48.dp)
                .height(if (expanded) 104.dp else 48.dp)
                .graphicsLayer {
                    val press = if (pressed && animate) 0.96f else 1f
                    scaleX = press * (1f + 0.07f * petPulse.value)
                    scaleY = press * (1f - 0.05f * petPulse.value)
                    transformOrigin = TransformOrigin(0.5f, 1f)
                    if (animate) {
                        when (mood) {
                            ChanMood.SAVED ->
                                translationY = -3.dp.toPx() * reaction.value

                            ChanMood.ERROR ->
                                rotationZ = 2f * reaction.value

                            ChanMood.DRAFT_READY ->
                                rotationZ = 3f * reaction.value

                            else -> Unit
                        }
                    }
                }
                .clip(if (expanded) MaterialTheme.shapes.medium else CircleShape)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onPet,
                    onLongClick = onHide,
                ),
        )
    }
}

@Composable
private fun Breathy(active: Boolean, content: @Composable () -> Unit) {
    if (!active) {
        Box { content() }
        return
    }
    val transition = rememberInfiniteTransition(label = "chanBreath")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "chanBreathPhase",
    )
    Box(
        Modifier.graphicsLayer {
            translationY = -1.5.dp.toPx() * phase
            scaleY = 1f + 0.012f * phase
            transformOrigin = TransformOrigin(0.5f, 1f)
        },
    ) { content() }
}

/** 4.5–7.5 秒随机眨眼，闭眼 100–120ms；15% 概率补一次 80ms 眨眼。 */
@Composable
fun rememberChanBlink(active: Boolean): Boolean {
    var blink by remember { mutableStateOf(false) }
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val run = active && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(run) {
        if (!run) {
            blink = false
            return@LaunchedEffect
        }
        while (isActive) {
            delay(Random.nextLong(4500, 7501))
            blink = true
            delay(Random.nextLong(100, 121))
            blink = false
            if (Random.nextFloat() < 0.15f) {
                delay(180)
                blink = true
                delay(80)
                blink = false
            }
        }
    }
    return blink
}

@Composable
fun ChanAvatar(
    size: androidx.compose.ui.unit.Dp,
    animate: Boolean,
    modifier: Modifier = Modifier,
    contentDescription: String? = "DeepSeek 娘助手",
) {
    val blink = rememberChanBlink(animate)
    Image(
        painter = painterResource(
            if (blink) R.drawable.chan_head_blink else R.drawable.chan_head_open,
        ),
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        modifier = modifier.size(size).clip(CircleShape),
    )
}

@Composable
private fun StatusPill(text: String, error: Boolean = false) {
    Surface(
        shape = CircleShape,
        color = if (error) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.padding(start = 6.dp),
    ) {
        Text(
            text,
            fontSize = 10.sp,
            color = if (error) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

@Composable
fun ThinkingDots(animate: Boolean) {
    if (!animate) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            repeat(3) { i ->
                Box(
                    Modifier
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(
                                alpha = if (i == 0) 0.85f else 0.45f,
                            ),
                        ),
                )
            }
        }
        return
    }
    val transition = rememberInfiniteTransition(label = "chanDots")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "chanDotPhase",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(3) { i ->
            val distance = abs(((phase - i + 3f) % 3f))
            val alpha = 0.35f + 0.65f * (1f - distance / 3f)
            Box(
                Modifier
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha)),
            )
        }
    }
}

fun statusText(status: ChanStatus): String = when (status.mood) {
    ChanMood.IDLE -> ""
    ChanMood.THINKING -> "正在整理安排…"
    ChanMood.DRAFT_READY -> "整理出 ${status.count} 项，待你确认"
    ChanMood.SAVED -> "已保存 ${status.count} 项"
    ChanMood.ERROR -> status.message.ifBlank { "这次没整理好，内容已保留" }
}
