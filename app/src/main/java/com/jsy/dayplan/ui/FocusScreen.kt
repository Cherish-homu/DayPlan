package com.jsy.dayplan.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jsy.dayplan.core.FocusTimerLogic
import com.jsy.dayplan.vm.FocusTimerViewModel
import com.jsy.dayplan.vm.PlanViewModel

/** 专注倒计时：快捷 5/15/25/45 分钟、自定义 1–180；开始/暂停/继续/重置。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FocusScreen(vm: PlanViewModel, fvm: FocusTimerViewModel = androidx.lifecycle.viewmodel.compose.viewModel()) {
    val state by fvm.state.collectAsState()
    val remaining by fvm.remainingMs.collectAsState()
    val rebootNotice by fvm.rebootNotice.collectAsState()
    val alarmScheduled by fvm.alarmScheduled.collectAsState()
    val canNotify = fvm.notificationsEnabled()

    var customInput by remember { mutableStateOf("") }
    var customError by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("专注倒计时") },
                navigationIcon = {
                    IconButton(onClick = { vm.pop() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (rebootNotice) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("设备已重启，请确认后继续", fontWeight = FontWeight.SemiBold)
                        Text(
                            "之前运行中的计时器已暂停。剩余时长不会用系统时间推算冒充持续运行。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = { fvm.clearRebootNotice() }) { Text("知道了") }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(20.dp).fillMaxWidth(),
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                ) {
                    Text(
                        FocusTimerLogic.formatRemaining(remaining),
                        fontSize = 56.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (state.running && !state.paused) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        when {
                            !state.running -> "未开始"
                            state.paused -> "已暂停（剩余 ${state.pausedRemainingMs / 60000} 分钟）"
                            else -> "专注中 · ${state.durationMinutes} 分钟"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!state.running && state.endNotified) {
                        Text(
                            "计时已结束",
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }

            if (state.running && !state.paused && !alarmScheduled) {
                Text(
                    "后台结束提醒未能注册：系统可能限制精确闹钟或通知权限被关闭。请保持应用在前台，结束时仍会在页面内显示。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            if (!canNotify) {
                Text(
                    "系统通知未开启：计时结束只会在应用内显示，不会出现在通知栏。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            if (!state.running) {
                Text("快捷选择", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FocusTimerLogic.QUICK_CHOICES.forEach { m ->
                        FilterChip(
                            selected = state.durationMinutes == m,
                            onClick = { customInput = m.toString() },
                            label = { Text("$m 分") },
                        )
                    }
                }
                OutlinedButton(
                    onClick = {
                        when (val r = parseMinutes(customInput)) {
                            null -> customError = "请输入 1–180 的整数分钟"
                            else -> { customError = null; fvm.start(r) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("开始计时") }
                androidx.compose.material3.OutlinedTextField(
                    value = customInput,
                    onValueChange = { customInput = it.filter { c -> c.isDigit() } },
                    label = { Text("自定义分钟数（1–180）") },
                    supportingText = { customError?.let { Text(it, color = MaterialTheme.colorScheme.error) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (state.paused) {
                        Button(onClick = { fvm.resume() }, modifier = Modifier.weight(1f)) { Text("继续") }
                    } else {
                        Button(onClick = { fvm.pause() }, modifier = Modifier.weight(1f)) { Text("暂停") }
                    }
                    OutlinedButton(onClick = { fvm.reset() }, modifier = Modifier.weight(1f)) { Text("重置") }
                }
                Text(
                    "· 一次仅允许运行一个计时器\n" +
                        "· 剩余时长按本次开机的单调时钟计算，修改系统时间不会导致跳变\n" +
                        "· 结束时通过统一提醒通道通知；未获得提醒授权时后台提示可能不可用\n" +
                        "· 设备重启后计时器会暂停，打开应用即可确认",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun parseMinutes(s: String): Int? {
    val v = s.trim().toIntOrNull() ?: return null
    return if (v in FocusTimerLogic.MIN_MINUTES..FocusTimerLogic.MAX_MINUTES) v else null
}
