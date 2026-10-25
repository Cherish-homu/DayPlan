package com.jsy.dayplan.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.jsy.dayplan.vm.AiViewModel
import com.jsy.dayplan.vm.PlanViewModel
import com.jsy.dayplan.vm.RestoreOutcome
import com.jsy.dayplan.vm.Screen
import com.jsy.dayplan.vm.ThemeMode
import java.time.LocalDate

@Composable
fun AppRoot(vm: PlanViewModel, aiVm: AiViewModel, themeMode: ThemeMode) {
    val stack by vm.stack.collectAsState()
    val current = stack.last()
    val context = LocalContext.current
    val previewSummary by vm.restorePreviewSummary.collectAsState()
    val snapshotCount by vm.snapshotCount.collectAsState()
    val reminderStatus by vm.reminderStatusText.collectAsState()

    // AI 入口状态：配置变化（启用/入口显示）与发送中状态驱动重绘
    val aiConfigVersion by aiVm.configVersion.collectAsState()
    val aiConfig = remember(aiConfigVersion) { aiVm.config() }
    val sendState by aiVm.sendState.collectAsState()
    val batches by aiVm.batches.collectAsState()
    val savedNotice by aiVm.lastSavedNotice.collectAsState()
    var chanExpanded by remember { mutableStateOf(false) }
    var hasAppeared by remember { mutableStateOf(false) }
    var wasVisible by remember { mutableStateOf(aiConfig.showWhale) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val animationMode = remember(aiConfigVersion) {
        ChanAnimationMode.fromKey(aiConfig.animationMode)
    }
    val reduceMotion = rememberReduceMotion(animationMode)

    LaunchedEffect(savedNotice) {
        if (savedNotice != null) {
            delay(3500)
            aiVm.lastSavedNotice.value = null
        }
    }
    LaunchedEffect(aiConfig.showWhale) {
        if (aiConfig.showWhale && !wasVisible) hasAppeared = false
        wasVisible = aiConfig.showWhale
    }
    // 展开只是临时查看；切页后回到紧凑态，避免每次刷新留下大块立绘。
    LaunchedEffect(current) {
        if (chanExpanded) chanExpanded = false
    }
    LaunchedEffect(chanExpanded) {
        if (chanExpanded) {
            delay(12_000)
            chanExpanded = false
        }
    }

    fun hideAssistant() {
        aiVm.saveConfig(aiVm.config().copy(showWhale = false))
        hasAppeared = false
        scope.launch {
            val result = snackbar.showSnackbar(
                message = "已隐藏助手",
                actionLabel = "撤销",
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                aiVm.saveConfig(aiVm.config().copy(showWhale = true))
                hasAppeared = false
            }
        }
    }

    fun showRestoreOutcome(outcome: RestoreOutcome) {
        vm.refreshSnapshots()
        vm.refreshReminderStatus()
        val text = when (outcome) {
            is RestoreOutcome.Success -> "恢复完成，提醒已按新数据重建"
            is RestoreOutcome.RolledBack -> "恢复失败（原数据未变）：${outcome.message}"
            is RestoreOutcome.RemindersPending -> outcome.message
        }
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    }

    // ---------- SAF ----------
    var restorePending by remember { mutableStateOf(false) }
    var rollbackPending by remember { mutableStateOf(false) }
    val createJson = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            vm.launchExportJson(uri) { r ->
                Toast.makeText(
                    context,
                    if (r.ok) "已导出：${r.name}" else "导出失败：${r.message}",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
    val createSnapshot = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            vm.launchExportSnapshot(uri) { r ->
                Toast.makeText(
                    context,
                    if (r.ok) "恢复前快照已导出：${r.name}" else "导出失败：${r.message}",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
    var restoreErrors by remember { mutableStateOf<List<String>?>(null) }
    val openBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            vm.launchParse(uri) { preview, errors ->
                if (preview != null) restorePending = true
                else restoreErrors = errors
            }
        }
    }

    // 恢复预览确认（用独立组合维护，避免对话框生命周期问题）
    if (restorePending) {
        AlertDialog(
            onDismissRequest = { restorePending = false },
            title = { Text("确认恢复备份？") },
            text = { Text(previewSummary) },
            confirmButton = {
                TextButton(onClick = {
                    restorePending = false
                    vm.launchApplyRestore { outcome -> showRestoreOutcome(outcome) }
                }) { Text("替换本地全部数据") }
            },
            dismissButton = {
                TextButton(onClick = { restorePending = false }) { Text("取消") }
            },
        )
    }

    if (rollbackPending) {
        AlertDialog(
            onDismissRequest = { rollbackPending = false },
            title = { Text("用最近一次恢复前快照回退？") },
            text = {
                Text(
                    "将再次完整替换（先为当前数据生成新快照）。本机现有 $snapshotCount 份快照。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    rollbackPending = false
                    vm.launchRestoreLatestSnapshot { outcome -> showRestoreOutcome(outcome) }
                }) { Text("确认回退") }
            },
            dismissButton = {
                TextButton(onClick = { rollbackPending = false }) { Text("取消") }
            },
        )
    }

    restoreErrors?.let { errors ->
        AlertDialog(
            onDismissRequest = { restoreErrors = null },
            title = { Text("备份文件校验未通过") },
            text = { Text(errors.joinToString("; ") { "· $it" }) },
            confirmButton = { TextButton(onClick = { restoreErrors = null }) { Text("知道了") } },
        )
    }

    // 小组件“+ 新增”：应用内新增选择，与首页/月历使用同一类型面板
    val showAddChooser by vm.showAddChooser.collectAsState()
    if (showAddChooser) {
        AddItemSheet(
            contextLabel = "为今天新建",
            onDismiss = { vm.showAddChooser.value = false },
            onSelect = { type ->
                vm.showAddChooser.value = false
                when (type) {
                    PlanItemType.SCHEDULE -> vm.push(Screen.EditSchedule(-1, LocalDate.now().toString()))
                    PlanItemType.TODO -> vm.push(Screen.EditTodo(-1))
                    PlanItemType.COUNTDOWN -> vm.push(Screen.EditCountdown(-1))
                }
            },
        )
    }

    // 通知权限（Android 13+），首次主动启用提醒时请求
    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        vm.refreshReminderStatus()
        Toast.makeText(
            context,
            if (granted) "通知已开启" else "通知未开启：提醒将只在应用内可见",
            Toast.LENGTH_LONG,
        ).show()
    }

    BackHandler(enabled = stack.size > 1) { vm.pop() }

    val mainTab = current in setOf(
        Screen.Today, Screen.Calendar, Screen.Countdowns, Screen.Settings,
    )
    val chanStatus = ChanStatusLogic.from(
        sendState,
        pendingCount = batches.sumOf { it.record.actions.size },
        lastSaved = savedNotice,
    )

    Scaffold(
        snackbarHost = {
            SnackbarHost(snackbar) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    actionColor = MaterialTheme.colorScheme.primary,
                )
            }
        },
        bottomBar = {
            if (mainTab) {
                NavigationBar {
                    NavigationBarItem(
                        selected = current == Screen.Today,
                        onClick = { vm.switchTab(Screen.Today) },
                        icon = { Icon(Icons.Filled.Today, null) },
                        label = { Text("今天") },
                    )
                    NavigationBarItem(
                        selected = current == Screen.Calendar,
                        onClick = { vm.switchTab(Screen.Calendar) },
                        icon = { Icon(Icons.Filled.CalendarMonth, null) },
                        label = { Text("月历") },
                    )
                    NavigationBarItem(
                        selected = current == Screen.Countdowns,
                        onClick = { vm.switchTab(Screen.Countdowns) },
                        icon = { Icon(Icons.Filled.Event, null) },
                        label = { Text("倒数日") },
                    )
                    NavigationBarItem(
                        selected = current == Screen.Settings,
                        onClick = { vm.switchTab(Screen.Settings) },
                        icon = { Icon(Icons.Filled.Settings, null) },
                        label = { Text("我的") },
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (current) {
                Screen.Today -> TodayScreen(vm)
                Screen.Calendar -> CalendarScreen(vm)
                Screen.Countdowns -> CountdownsScreen(vm)
                Screen.TodoList -> TodoListScreen(vm)
                Screen.Settings -> SettingsScreen(
                    vm,
                    themeMode = themeMode,
                    onThemeChange = { vm.setThemeMode(it) },
                    onOpenAiSettings = { vm.push(Screen.AiSettings) },
                    animationMode = animationMode,
                    onAnimationModeChange = {
                        aiVm.saveConfig(aiVm.config().copy(animationMode = it.key))
                    },
                    assistantVisible = aiConfig.showWhale,
                    onAssistantVisibilityChange = {
                        aiVm.saveConfig(aiVm.config().copy(showWhale = it))
                    },
                    snapshotCount = snapshotCount,
                    reminderStatus = reminderStatus,
                    onExportJson = { createJson.launch("日序备份_${LocalDate.now()}.json") },
                    onExportSnapshot = { createSnapshot.launch("日序_恢复前快照_${LocalDate.now()}.json") },
                    onRestore = { openBackup.launch(arrayOf("application/json")) },
                    onRestoreLatestSnapshot = { rollbackPending = true },
                    onRefreshReminders = { vm.onForeground() },
                    onRequestNotification = {
                        if (Build.VERSION.SDK_INT >= 33) {
                            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            Toast.makeText(context, "系统无需通知权限，可直接使用", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onOpenNotificationSettings = {
                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        })
                    },
                    onOpenExactAlarmSettings = {
                        if (Build.VERSION.SDK_INT >= 31) {
                            context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                data = Uri.parse("package:${context.packageName}")
                            })
                        }
                    },
                    onSendTestNotification = {
                        vm.sendTestNotification { ok, msg ->
                            Toast.makeText(
                                context,
                                if (ok) "测试通知已发送" else "通知未开启：$msg",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    },
                )
                is Screen.EditSchedule -> EditScheduleScreen(vm, current.id, current.presetDate)
                is Screen.EditTodo -> EditTodoScreen(vm, current.id, current.presetDate)
                is Screen.EditCountdown -> EditCountdownScreen(vm, current.id, current.presetDate)
                Screen.Focus -> FocusScreen(vm)
                Screen.AiChat -> AiChatScreen(
                    aiVm,
                    reduceMotion = reduceMotion,
                    onBack = { vm.pop() },
                    onOpenSettings = { vm.push(Screen.AiSettings) },
                    onOpenEntity = { kind, id ->
                        when (kind) {
                            "schedule" -> vm.push(Screen.EditSchedule(id, null))
                            "todo" -> vm.push(Screen.EditTodo(id))
                            "countdown" -> vm.push(Screen.EditCountdown(id))
                        }
                    },
                )

                Screen.AiSettings -> AiSettingsScreen(aiVm, onBack = { vm.pop() })
            }

            // 只保留右侧小模块悬浮；不占整行底栏，也不影响左侧内容。
            if (mainTab && aiConfig.showWhale) {
                ChanEntryBar(
                    expanded = chanExpanded,
                    status = chanStatus,
                    reduceMotion = reduceMotion,
                    playAppear = !hasAppeared,
                    onAppearFinished = { hasAppeared = true },
                    onToggleExpanded = { chanExpanded = !chanExpanded },
                    onClick = { vm.push(Screen.AiChat) },
                    onHide = { hideAssistant() },
                    onRetry = { aiVm.send() },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(
                            end = 12.dp,
                            bottom = if (current == Screen.Countdowns) 84.dp else 8.dp,
                        ),
                )
            }

        }
    }
}


/** 系统动画关闭（0）或用户选择“减少”时不跑循环动效；恢复时重新读取系统值。 */
@Composable
private fun rememberReduceMotion(mode: ChanAnimationMode): Boolean {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var systemScale by remember {
        mutableFloatStateOf(
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ),
        )
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                systemScale = Settings.Global.getFloat(
                    context.contentResolver,
                    Settings.Global.ANIMATOR_DURATION_SCALE,
                    1f,
                )
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return ChanMotion.shouldReduce(mode, systemScale)
}
