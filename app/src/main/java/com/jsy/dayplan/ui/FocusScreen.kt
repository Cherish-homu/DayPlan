package com.jsy.dayplan.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jsy.dayplan.R
import com.jsy.dayplan.core.FocusSound
import com.jsy.dayplan.core.FocusTimerLogic
import com.jsy.dayplan.data.FocusSession
import com.jsy.dayplan.data.FocusStats
import com.jsy.dayplan.reminder.FocusSoundService
import com.jsy.dayplan.vm.FocusTimerViewModel
import com.jsy.dayplan.vm.PlanViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private data class FocusScene(@DrawableRes val image: Int, val label: String)

private val FocusScenes = listOf(
    FocusScene(R.drawable.focus_canopy, "花树"),
    FocusScene(R.drawable.focus_alpine, "山湖"),
    FocusScene(R.drawable.focus_maple, "红枫"),
    FocusScene(R.drawable.focus_dusk, "日落"),
    FocusScene(R.drawable.focus_coast, "海岸"),
)

private val Ink = Color(0xFF2B1B12)
private val PhotoWhite = Color(0xFFFDFBF7)
private val Accent = Color(0xFFFFD9A0)

/**
 * 专注页：全幅照片背景 + 暗色遮罩，圆环倒计时，专注完成统计与本地白噪音。
 * 主视觉始终使用深色遮罩上的浅色文字，深浅色主题下观感一致。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FocusScreen(vm: PlanViewModel, fvm: FocusTimerViewModel = androidx.lifecycle.viewmodel.compose.viewModel()) {
    val state by fvm.state.collectAsState()
    val remaining by fvm.remainingMs.collectAsState()
    val appearance by fvm.appearance.collectAsState()
    val stats by fvm.stats.collectAsState()
    val rebootNotice by fvm.rebootNotice.collectAsState()
    val alarmScheduled by fvm.alarmScheduled.collectAsState()
    val playing by fvm.playingSound.collectAsState()
    val soundError by fvm.soundError.collectAsState()
    val canNotify = remember(state.running) { fvm.notificationsEnabled() }

    var showSoundSheet by remember { mutableStateOf(false) }
    var showCustomDialog by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scroll = rememberScrollState()

    LaunchedEffect(Unit) { fvm.refreshStats() }

    val scene = FocusScenes[appearance.scene.coerceIn(0, FocusScenes.lastIndex)]
    // 已结束但用户刚改了时长：圆环预览新时长，避免一直停在 00:00。
    val previewDuration = !state.running &&
        (!state.endNotified || appearance.minutes != state.durationMinutes)
    val displayRemaining = when {
        state.running -> remaining
        previewDuration -> appearance.minutes * 60_000L
        else -> 0L
    }
    val progress = when {
        state.running -> (remaining.toFloat() / (state.durationMinutes * 60_000f)).coerceIn(0f, 1f)
        previewDuration -> 1f
        else -> 0f
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        androidx.compose.foundation.Image(
            painter = painterResource(scene.image),
            contentDescription = "专注背景 · ${scene.label}",
            contentScale = ContentScale.Crop,
            alignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = 1f }
                .then(if (appearance.blur > 0.05f) Modifier.blur(appearance.blur.dp) else Modifier),
        )
        // 上深下更深的遮罩：保证状态栏区域与底部控件都清晰
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = appearance.scrim * 0.72f),
                    0.45f to Color.Black.copy(alpha = appearance.scrim * 0.9f),
                    1f to Color.Black.copy(alpha = appearance.scrim),
                ),
            ),
        )

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 20.dp)
                .padding(top = 6.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            FocusTopBar(
                sound = appearance.sound,
                playing = playing,
                onBack = { vm.pop() },
                onOpenSound = {
                    FocusSoundService.clearError()
                    showSoundSheet = true
                },
            )

            FocusStatsBar(stats)

            TimerDial(
                remainingMs = displayRemaining,
                progress = progress,
                label = when {
                    !state.running && !previewDuration -> "本轮完成"
                    !state.running -> "准备开始"
                    state.paused -> "已暂停"
                    else -> "专注中"
                },
                subtitle = when {
                    state.running -> "本次 ${state.durationMinutes} 分钟"
                    !previewDuration -> "本轮计时已结束"
                    else -> "本次 ${appearance.minutes} 分钟"
                },
            )

            if (state.running) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { if (state.paused) fvm.resume() else fvm.pause() },
                        modifier = Modifier.weight(1f).height(54.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PhotoWhite, contentColor = Ink),
                    ) {
                        Icon(
                            if (state.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            contentDescription = null,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.paused) "继续" else "暂停", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(
                        onClick = { fvm.reset() },
                        modifier = Modifier.weight(0.55f).height(54.dp),
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.dp, PhotoWhite.copy(alpha = 0.65f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = PhotoWhite),
                    ) { Text("结束", fontSize = 16.sp) }
                }
            } else {
                Button(
                    onClick = { fvm.start(appearance.minutes) },
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PhotoWhite, contentColor = Ink),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (state.endNotified) "再来一轮" else "开始专注",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            if (!state.running) {
                DurationPicker(
                    minutes = appearance.minutes,
                    onChoose = { fvm.chooseDuration(it) },
                    onCustom = { showCustomDialog = true },
                )
            } else {
                Text(
                    if (state.paused) "已暂停 · 剩余 ${state.pausedRemainingMs / 60_000} 分钟"
                    else "本轮 ${state.durationMinutes} 分钟 · 离开页面或锁屏后计时继续",
                    color = PhotoWhite.copy(alpha = 0.72f),
                    fontSize = 12.sp,
                )
            }

            ScenePicker(
                selected = appearance.scene,
                onSelect = { fvm.setScene(it) },
            )

            Effects(
                blur = appearance.blur,
                scrim = appearance.scrim,
                onBlur = { fvm.setBlur(it) },
                onScrim = { fvm.setScrim(it) },
            )

            SoundEntry(
                sound = appearance.sound,
                playing = playing,
                running = state.running && !state.paused,
                onClick = {
                    FocusSoundService.clearError()
                    showSoundSheet = true
                },
            )

            soundError?.let { message ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0x40FF7A7A))
                        .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(message, color = PhotoWhite, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { FocusSoundService.clearError() }) {
                        Text("知道了", color = PhotoWhite, fontSize = 12.sp)
                    }
                }
            }

            if (rebootNotice) {
                NoticeCard(
                    title = "设备已重启，请确认后继续",
                    body = "之前运行中的计时器已暂停。剩余时长不会用系统时间推算冒充持续运行。",
                    action = "知道了",
                    onAction = { fvm.clearRebootNotice() },
                )
            }
            if (state.running && !state.paused && !alarmScheduled) {
                Text(
                    "后台结束提醒未能注册：系统可能限制精确闹钟。请保持应用在前台，结束时仍会在页面内显示。",
                    color = Accent.copy(alpha = 0.9f),
                    fontSize = 11.sp,
                )
            }
            if (!canNotify) {
                Text(
                    "系统通知未开启：计时结束只会在应用内显示，不会出现在通知栏。",
                    color = Accent.copy(alpha = 0.9f),
                    fontSize = 11.sp,
                )
            }

            if (stats.recent.isNotEmpty()) {
                RecentSessions(stats.recent)
            }
        }
    }

    if (showCustomDialog) {
        CustomMinutesDialog(
            initial = appearance.minutes,
            onDismiss = { showCustomDialog = false },
            onConfirm = {
                fvm.chooseDuration(it)
                showCustomDialog = false
            },
        )
    }

    if (showSoundSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSoundSheet = false },
            sheetState = sheetState,
        ) {
            SoundSheet(
                sound = appearance.sound,
                volume = appearance.volume,
                playing = playing,
                running = state.running && !state.paused,
                error = soundError,
                onSound = { fvm.setSound(it) },
                onVolume = { fvm.setVolume(it) },
                onVolumeFinished = { fvm.refreshSound() },
            )
        }
    }
}

@Composable
private fun FocusTopBar(
    sound: FocusSound,
    playing: FocusSound,
    onBack: () -> Unit,
    onOpenSound: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = PhotoWhite,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text("专注", color = PhotoWhite, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        if (playing != FocusSound.OFF) {
            Text(
                "♪ ${playing.title}",
                color = PhotoWhite.copy(alpha = 0.8f),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(6.dp))
        }
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(onClick = onOpenSound) {
                Icon(
                    if (sound == FocusSound.OFF) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = "背景白噪音",
                    tint = PhotoWhite,
                )
            }
        }
    }
}

@Composable
private fun FocusStatsBar(stats: FocusStats) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Black.copy(alpha = 0.24f))
            .border(1.dp, PhotoWhite.copy(alpha = 0.12f), RoundedCornerShape(18.dp))
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatCell("今日次数", "${stats.todayCount}", "次", Modifier.weight(1f))
        StatDivider()
        StatCell("今日时长", "${stats.todayMinutes}", "分", Modifier.weight(1f))
        StatDivider()
        StatCell("累计次数", "${stats.totalCount}", "次", Modifier.weight(1f))
        StatDivider()
        StatCell("累计时长", "${stats.totalMinutes}", "分", Modifier.weight(1f))
    }
}

@Composable
private fun StatCell(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = PhotoWhite, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(unit, color = PhotoWhite.copy(alpha = 0.6f), fontSize = 10.sp)
        }
        Text(label, color = PhotoWhite.copy(alpha = 0.62f), fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
private fun StatDivider() {
    Box(Modifier.width(1.dp).height(24.dp).background(PhotoWhite.copy(alpha = 0.12f)))
}

@Composable
private fun TimerDial(
    remainingMs: Long,
    progress: Float,
    label: String,
    subtitle: String,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        val ring = minOf(maxWidth * 0.82f, 300.dp)
        Box(Modifier.size(ring), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 11.dp.toPx()
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                val topLeft = Offset(inset, inset)
                drawArc(
                    color = PhotoWhite.copy(alpha = 0.16f),
                    startAngle = 0f, sweepAngle = 360f, useCenter = false,
                    topLeft = topLeft, size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
                val sweep = 360f * progress.coerceIn(0f, 1f)
                if (sweep > 0.1f) {
                    drawArc(
                        color = Accent.copy(alpha = 0.22f),
                        startAngle = -90f, sweepAngle = sweep, useCenter = false,
                        topLeft = topLeft, size = arcSize,
                        style = Stroke(stroke * 2.6f, cap = StrokeCap.Round),
                    )
                    drawArc(
                        color = Accent,
                        startAngle = -90f, sweepAngle = sweep, useCenter = false,
                        topLeft = topLeft, size = arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    label,
                    color = PhotoWhite.copy(alpha = 0.72f),
                    fontSize = 12.sp,
                    letterSpacing = 2.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    FocusTimerLogic.formatRemaining(remainingMs),
                    color = PhotoWhite,
                    fontSize = (ring.value * 0.2f).sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    color = PhotoWhite.copy(alpha = 0.62f),
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun DurationPicker(minutes: Int, onChoose: (Int) -> Unit, onCustom: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("专注时长", color = PhotoWhite.copy(alpha = 0.7f), fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Text("$minutes 分钟", color = PhotoWhite, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FocusTimerLogic.QUICK_CHOICES.forEach { m ->
                DarkChip(text = "$m 分", selected = minutes == m, onClick = { onChoose(m) })
            }
            DarkChip(
                text = if (minutes in FocusTimerLogic.QUICK_CHOICES) "自定义" else "$minutes 分 · 自定义",
                selected = minutes !in FocusTimerLogic.QUICK_CHOICES,
                onClick = onCustom,
            )
        }
    }
}

@Composable
private fun DarkChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(if (selected) PhotoWhite else Color.Black.copy(alpha = 0.26f))
            .border(
                1.dp,
                if (selected) Color.Transparent else PhotoWhite.copy(alpha = 0.24f),
                CircleShape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(
            text,
            color = if (selected) Ink else PhotoWhite,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun ScenePicker(selected: Int, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("专注背景", color = PhotoWhite.copy(alpha = 0.7f), fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Text(
                FocusScenes.getOrNull(selected)?.label ?: "",
                color = PhotoWhite.copy(alpha = 0.55f),
                fontSize = 11.sp,
            )
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FocusScenes.forEachIndexed { index, scene ->
                val isSelected = index == selected
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable { onSelect(index) },
                ) {
                    Box(
                        Modifier
                            .size(width = 58.dp, height = 78.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .border(
                                if (isSelected) 2.dp else 1.dp,
                                if (isSelected) PhotoWhite else PhotoWhite.copy(alpha = 0.28f),
                                RoundedCornerShape(14.dp),
                            ),
                    ) {
                        androidx.compose.foundation.Image(
                            painter = painterResource(scene.image),
                            contentDescription = scene.label,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (isSelected) {
                            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)))
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = "已选择",
                                tint = PhotoWhite,
                                modifier = Modifier.align(Alignment.Center).size(22.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        scene.label,
                        color = if (isSelected) PhotoWhite else PhotoWhite.copy(alpha = 0.6f),
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun Effects(
    blur: Float,
    scrim: Float,
    onBlur: (Float) -> Unit,
    onScrim: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("画面效果", color = PhotoWhite.copy(alpha = 0.7f), fontSize = 13.sp)
        EffectSlider(
            label = "虚化",
            valueText = "${blur.roundToInt()}",
            value = blur,
            range = 0f..12f,
            onValue = onBlur,
        )
        EffectSlider(
            label = "遮罩",
            valueText = "${(scrim * 100).roundToInt()}%",
            value = scrim,
            range = 0.15f..0.85f,
            onValue = onScrim,
        )
    }
}

@Composable
private fun EffectSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValue: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = PhotoWhite.copy(alpha = 0.72f), fontSize = 12.sp, modifier = Modifier.width(34.dp))
        Slider(
            value = value,
            onValueChange = onValue,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = PhotoWhite,
                activeTrackColor = PhotoWhite,
                inactiveTrackColor = PhotoWhite.copy(alpha = 0.25f),
            ),
            modifier = Modifier.weight(1f),
        )
        Text(valueText, color = PhotoWhite.copy(alpha = 0.7f), fontSize = 11.sp, modifier = Modifier.width(38.dp))
    }
}

@Composable
private fun SoundEntry(sound: FocusSound, playing: FocusSound, running: Boolean, onClick: () -> Unit) {
    val status = when {
        sound == FocusSound.OFF -> "未开启 · 支持细雨、海浪、白噪音"
        playing == sound -> "${sound.title} · 播放中"
        running -> "${sound.title} · 已关闭，可在设置中重开"
        else -> "${sound.title} · 开始专注后播放"
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = Color.Black.copy(alpha = 0.24f),
        border = BorderStroke(1.dp, PhotoWhite.copy(alpha = 0.12f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = PhotoWhite)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("背景白噪音", color = PhotoWhite, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(status, color = PhotoWhite.copy(alpha = 0.6f), fontSize = 11.sp, maxLines = 1)
            }
            Text("设置", color = PhotoWhite.copy(alpha = 0.7f), fontSize = 12.sp)
        }
    }
}

@Composable
private fun NoticeCard(title: String, body: String, action: String, onAction: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.28f))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, color = PhotoWhite, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(body, color = PhotoWhite.copy(alpha = 0.7f), fontSize = 12.sp)
        TextButton(onClick = onAction, modifier = Modifier.align(Alignment.End)) {
            Text(action, color = Accent, fontSize = 12.sp)
        }
    }
}

private val sessionTimeFormat = DateTimeFormatter.ofPattern("MM-dd HH:mm")

@Composable
private fun RecentSessions(recent: List<FocusSession>) {
    val today = LocalDate.now()
    val zone = ZoneId.systemDefault()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("最近完成", color = PhotoWhite.copy(alpha = 0.7f), fontSize = 13.sp)
        recent.take(3).forEach { session ->
            val time = Instant.ofEpochMilli(session.completedAt).atZone(zone)
            val label = if (time.toLocalDate() == today) "今天 ${time.format(DateTimeFormatter.ofPattern("HH:mm"))}"
            else time.format(sessionTimeFormat)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.18f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, color = PhotoWhite.copy(alpha = 0.75f), fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Text("${session.minutes} 分钟", color = PhotoWhite, fontSize = 12.sp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SoundSheet(
    sound: FocusSound,
    volume: Float,
    playing: FocusSound,
    running: Boolean,
    error: String?,
    onSound: (FocusSound) -> Unit,
    onVolume: (Float) -> Unit,
    onVolumeFinished: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("背景白噪音", style = MaterialTheme.typography.titleLarge)
        Text(
            if (sound == FocusSound.OFF) "选择一种声音，开始专注后自动播放。"
            else if (running && playing == sound) "${sound.title} 正在播放，锁屏或退到后台仍会继续。"
            else if (running && playing == FocusSound.OFF) "${sound.title} 已关闭；重新选择音效即可开启。"
            else "${sound.title} 将在开始/继续专注后播放。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SoundChip("关闭", sound == FocusSound.OFF, Modifier.weight(1f)) { onSound(FocusSound.OFF) }
            SoundChip("细雨", sound == FocusSound.RAIN, Modifier.weight(1f)) { onSound(FocusSound.RAIN) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SoundChip("海浪", sound == FocusSound.WAVES, Modifier.weight(1f)) { onSound(FocusSound.WAVES) }
            SoundChip("白噪音", sound == FocusSound.WHITE, Modifier.weight(1f)) { onSound(FocusSound.WHITE) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("音量", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = volume,
                onValueChange = onVolume,
                onValueChangeFinished = onVolumeFinished,
                enabled = sound != FocusSound.OFF,
                valueRange = 0f..1f,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
            )
            Text(
                "${(volume * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.width(40.dp),
            )
        }
        Text(
            "本地实时合成，不联网、不下载音频；播放时会有低优先级通知，便于随时关闭。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (error != null) {
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun SoundChip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text, maxLines = 1) },
        modifier = modifier,
    )
}

@Composable
private fun CustomMinutesDialog(initial: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var text by remember { mutableStateOf(initial.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义专注时长") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { input ->
                        text = input.filter { it.isDigit() }.take(3)
                        error = null
                    },
                    label = { Text("分钟（1–180）") },
                    singleLine = true,
                    isError = error != null,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val value = text.toIntOrNull()
                if (value == null || value !in FocusTimerLogic.MIN_MINUTES..FocusTimerLogic.MAX_MINUTES) {
                    error = "请输入 1–180 的整数分钟"
                } else {
                    onConfirm(value)
                }
            }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
