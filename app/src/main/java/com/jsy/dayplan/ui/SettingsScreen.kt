package com.jsy.dayplan.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jsy.dayplan.vm.PlanViewModel
import com.jsy.dayplan.vm.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: PlanViewModel,
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    onOpenAiSettings: () -> Unit,
    snapshotCount: Int,
    reminderStatus: String,
    onExportJson: () -> Unit,
    onExportSnapshot: () -> Unit,
    onRestore: () -> Unit,
    onRestoreLatestSnapshot: () -> Unit,
    onRefreshReminders: () -> Unit,
    onRequestNotification: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenExactAlarmSettings: () -> Unit,
    onSendTestNotification: () -> Unit,
) {
    val notifEnabled = vm.notificationsEnabled()
    val exactAllowed = vm.exactAlarmAllowed()
    val lastExport by vm.lastExportAtState.collectAsState()
    val missed by vm.missedCount.collectAsState()

    LaunchedEffect(Unit) {
        vm.refreshReminderStatus()
        vm.refreshSnapshots()
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("我的") }) },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 主题：按钮真实接到 setThemeMode，重启后仍保留
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("外观", style = MaterialTheme.typography.titleMedium)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        listOf("跟随系统" to ThemeMode.SYSTEM, "浅色" to ThemeMode.LIGHT, "深色" to ThemeMode.DARK)
                            .forEachIndexed { i, (label, mode) ->
                                SegmentedButton(
                                    selected = themeMode == mode,
                                    onClick = { onThemeChange(mode) },
                                    shape = SegmentedButtonDefaults.itemShape(i, 3),
                                ) { Text(label) }
                            }
                    }
                }
            }

            // AI 助手入口（Key、模型、自动保存、入口显示）
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("AI 助手", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "在聊天中整理日程/待办/倒数日，可发送课程表截图、PDF、表格；" +
                            "使用你自己的 DeepSeek API Key，离线功能不受影响。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onOpenAiSettings, modifier = Modifier.fillMaxWidth()) {
                        Text("AI 助手设置")
                    }
                }
            }

            // 提醒状态
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("提醒状态", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (notifEnabled) "· 通知：已开启" else "· 通知未开启：提醒将只在应用内可见，请开启系统通知",
                        color = if (notifEnabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.tertiary,
                    )
                    Text(
                        if (exactAllowed) "· 精确提醒：已允许（整点提醒准点触发）"
                        else "· 精确提醒：未允许（提醒可能延迟几分钟送达，不影响数据）",
                        color = if (exactAllowed) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.tertiary,
                    )
                    if (reminderStatus.isNotBlank()) {
                        Text(
                            reminderStatus,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (missed > 0) {
                        Text(
                            "错过提醒：$missed 条（超过 10 分钟宽限，不集中补发）",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onRequestNotification) { Text(if (notifEnabled) "通知设置" else "开启通知") }
                        OutlinedButton(onClick = onOpenNotificationSettings) { Text("系统设置") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onOpenExactAlarmSettings) { Text("精确提醒授权") }
                        OutlinedButton(onClick = onSendTestNotification) { Text("发送测试通知") }
                    }
                    OutlinedButton(onClick = onRefreshReminders) {
                        Text("重新协调提醒（权限变化后使用）")
                    }
                    Text(
                        "拒绝授权后仍可正常保存与使用日程；恢复授权后从本页重新协调即可。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 备份
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("备份与恢复", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "最近一次验证成功的导出：$lastExport",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onExportJson, modifier = Modifier.fillMaxWidth()) {
                        Text("导出 JSON 备份（完整数据，明文）")
                    }
                    if (snapshotCount > 0) {
                        Text(
                            "本机保留 $snapshotCount 份恢复前自动快照（可用于回退）",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(onClick = onExportSnapshot, modifier = Modifier.fillMaxWidth()) {
                            Text("导出恢复前快照")
                        }
                        OutlinedButton(onClick = onRestoreLatestSnapshot, modifier = Modifier.fillMaxWidth()) {
                            Text("用最近一次快照回退")
                        }
                    } else {
                        Text(
                            "尚无恢复前快照；完成一次恢复后会自动保留，可在此回退。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = onRestore, modifier = Modifier.fillMaxWidth()) {
                        Text("从 JSON 恢复（完整替换，先预览再确认）")
                    }
                    Text(
                        "备份不含设备权限、通知投递标记、小组件绑定与运行中的计时器；导出文件为明文，请自行妥善保管，不会自动上传。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                "日序 v1.5 · 离线个人时间助手 · 数据保存在应用私有目录",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
