package com.jsy.dayplan.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jsy.dayplan.vm.PlanViewModel
import com.jsy.dayplan.vm.ThemeMode

/**
 * 我的：外观与助手 / 提醒 / 备份与恢复 / 关于 四行入口，
 * 每行显示一行状态；展开后才显示技术细节，保留全部既有能力。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: PlanViewModel,
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    onOpenAiSettings: () -> Unit,
    animationMode: ChanAnimationMode,
    onAnimationModeChange: (ChanAnimationMode) -> Unit,
    assistantVisible: Boolean,
    onAssistantVisibilityChange: (Boolean) -> Unit,
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
    var expanded by remember { mutableStateOf<String?>(null) }
    var showLicense by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.refreshReminderStatus()
        vm.refreshSnapshots()
    }

    val themeLabel = when (themeMode) {
        ThemeMode.SYSTEM -> "跟随系统"
        ThemeMode.LIGHT -> "浅色"
        ThemeMode.DARK -> "深色"
    }
    val assistantLabel = if (assistantVisible) "助手显示" else "助手已隐藏"
    val reminderSummary = when {
        !notifEnabled -> "通知未开启，提醒只在应用内可见"
        !exactAllowed -> "精确提醒未允许，可能延迟几分钟"
        missed > 0 -> "有 $missed 条提醒错过"
        else -> "通知与精确提醒正常"
    }

    Scaffold(
        topBar = {
            PageTopBar(
                title = "我的",
                subtitle = "外观、提醒与数据",
            )
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = pageHorizontalPadding(), vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsGroup(
                icon = Icons.Filled.Palette,
                iconTint = MaterialTheme.colorScheme.primary,
                title = "外观与助手",
                summary = "$themeLabel · $assistantLabel · ${animationMode.label}动画",
                expanded = expanded == "appearance",
                onToggle = { expanded = if (expanded == "appearance") null else "appearance" },
            ) {
                Text("主题", style = MaterialTheme.typography.titleSmall)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(
                        "跟随系统" to ThemeMode.SYSTEM,
                        "浅色" to ThemeMode.LIGHT,
                        "深色" to ThemeMode.DARK,
                    ).forEachIndexed { i, (label, mode) ->
                        SegmentedButton(
                            selected = themeMode == mode,
                            onClick = { onThemeChange(mode) },
                            shape = SegmentedButtonDefaults.itemShape(i, 3),
                        ) { Text(label, maxLines = 1, softWrap = false) }
                    }
                }
                Text(
                    "助手动画",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ChanAnimationMode.entries.forEachIndexed { i, mode ->
                        SegmentedButton(
                            selected = animationMode == mode,
                            onClick = { onAnimationModeChange(mode) },
                            shape = SegmentedButtonDefaults.itemShape(i, ChanAnimationMode.entries.size),
                        ) { Text(mode.label, maxLines = 1, softWrap = false) }
                    }
                }
                Text(
                    animationMode.description,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("显示助手", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "默认 48dp 头像，点开可展开全身；长按隐藏，可在此恢复。",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = assistantVisible,
                        onCheckedChange = onAssistantVisibilityChange,
                    )
                }
                OutlinedButton(
                    onClick = onOpenAiSettings,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("AI 助手设置（自备 Key，离线不受影响）") }
            }

            SettingsGroup(
                icon = Icons.Filled.NotificationsActive,
                iconTint = peachMarkColor(),
                title = "提醒",
                summary = reminderSummary,
                expanded = expanded == "reminder",
                onToggle = { expanded = if (expanded == "reminder") null else "reminder" },
            ) {
                StatusLine(
                    "通知",
                    if (notifEnabled) "已开启" else "未开启，提醒只在应用内可见",
                    notifEnabled,
                )
                StatusLine(
                    "精确提醒",
                    if (exactAllowed) "已允许" else "未允许，可能延迟几分钟",
                    exactAllowed,
                )
                if (reminderStatus.isNotBlank()) {
                    Text(
                        reminderStatus,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onRefreshReminders,
                        modifier = Modifier.weight(1f),
                    ) { Text("修复提醒") }
                    OutlinedButton(
                        onClick = {
                            if (notifEnabled) onOpenNotificationSettings()
                            else onRequestNotification()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(if (notifEnabled) "通知设置" else "开启通知") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onOpenExactAlarmSettings,
                        modifier = Modifier.weight(1f),
                    ) { Text("精确提醒授权") }
                    OutlinedButton(
                        onClick = onSendTestNotification,
                        modifier = Modifier.weight(1f),
                    ) { Text("测试通知") }
                }
                Text(
                    "权限变化后点「修复提醒」；拒绝授权不影响保存日程。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SettingsGroup(
                icon = Icons.Filled.Backup,
                iconTint = todoMarkColor(),
                title = "备份与恢复",
                summary = "最近导出：$lastExport · 本机 $snapshotCount 份快照",
                expanded = expanded == "backup",
                onToggle = { expanded = if (expanded == "backup") null else "backup" },
            ) {
                Text(
                    "完整 JSON（明文），导出后自行保管，不会自动上传。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onExportJson, modifier = Modifier.fillMaxWidth()) {
                    Text("导出备份")
                }
                OutlinedButton(onClick = onRestore, modifier = Modifier.fillMaxWidth()) {
                    Text("从 JSON 恢复（先预览再确认）")
                }
                if (snapshotCount > 0) {
                    Text(
                        "本机保留 $snapshotCount 份恢复前自动快照，可用于回退。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onExportSnapshot,
                            modifier = Modifier.weight(1f),
                        ) { Text("导出快照") }
                        OutlinedButton(
                            onClick = onRestoreLatestSnapshot,
                            modifier = Modifier.weight(1f),
                        ) { Text("用快照回退") }
                    }
                } else {
                    Text(
                        "尚无恢复前快照；完成一次恢复后会自动保留。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "备份不含设备权限、通知投递标记、小组件绑定与运行中的计时器。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SettingsGroup(
                icon = Icons.Filled.Info,
                iconTint = countdownMarkColor(),
                title = "关于",
                summary = "日序 v1.7.1 · 本地离线 · 无账号",
                expanded = expanded == "about",
                onToggle = { expanded = if (expanded == "about") null else "about" },
            ) {
                Text(
                    "日序是个人自用的离线时间助手。日程、待办、倒数日、专注计时、提醒与桌面小组件都保存在本机应用私有目录。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "AI 助手为可选功能，使用你自备的 DeepSeek API Key；不启用时离线功能完全可用。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { showLicense = true }) {
                    Text("素材许可、署名与修改说明")
                }
            }

            Text(
                "日序 v1.7.1 · 数据保存在应用私有目录",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 80.dp),
            )
        }
    }

    if (showLicense) {
        ChanAssetLicenseDialog { showLicense = false }
    }
}

@Composable
private fun SettingsGroup(
    icon: ImageVector,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    title: String,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    icon,
                    null,
                    tint = iconTint,
                )
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        summary,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(tween(180)) + fadeIn(tween(160)),
                exit = shrinkVertically(tween(160)) + fadeOut(tween(120)),
            ) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                    Column(
                        Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        content = content,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$label：", style = MaterialTheme.typography.bodySmall)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = if (ok) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.tertiary,
        )
    }
}
