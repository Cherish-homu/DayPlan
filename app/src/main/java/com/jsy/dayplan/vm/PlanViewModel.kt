package com.jsy.dayplan.vm

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jsy.dayplan.DayPlanApp
import com.jsy.dayplan.backup.BackupCodec
import com.jsy.dayplan.backup.BackupIO
import com.jsy.dayplan.backup.BackupValidator
import com.jsy.dayplan.backup.RestorePreview
import com.jsy.dayplan.backup.ValidateResult
import com.jsy.dayplan.core.TodayLogic
import com.jsy.dayplan.data.CountdownEntity
import com.jsy.dayplan.data.PlanRepository
import com.jsy.dayplan.data.ScheduleEntity
import com.jsy.dayplan.data.TodoEntity
import com.jsy.dayplan.reminder.ReminderCoordinator
import com.jsy.dayplan.ui.MainActivity
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 屏幕栈（单 Activity 自管理导航）。 */
sealed class Screen(val route: String) {
    data object Today : Screen("today")
    data object Calendar : Screen("calendar")
    data object Countdowns : Screen("countdowns")
    data object TodoList : Screen("todos")
    data object Settings : Screen("settings")
    data object Focus : Screen("focus")
    data object AiChat : Screen("aiChat")
    data object AiSettings : Screen("aiSettings")
    data class EditSchedule(val id: Long, val presetDate: String?) : Screen("editSched")
    data class EditTodo(val id: Long, val presetDate: String? = null) : Screen("editTodo")
    data class EditCountdown(val id: Long, val presetDate: String? = null) : Screen("editCd")
}

/** 恢复结果分阶段：数据是否已替换、提醒是否已重建，界面不能混为一谈。 */
sealed class RestoreOutcome {
    data object Success : RestoreOutcome()

    /** 事务回滚，原数据未变。 */
    data class RolledBack(val message: String) : RestoreOutcome()

    /** 数据已恢复，但提醒重建失败（可重试）；原数据已替换。 */
    data class RemindersPending(val message: String) : RestoreOutcome()
}

class PlanViewModel @JvmOverloads constructor(
    app: Application,
    repoOverride: PlanRepository? = null,
    coordinatorOverride: ReminderCoordinator? = null,
) : AndroidViewModel(app) {
    val app0 = app as DayPlanApp
    val repo: PlanRepository = repoOverride ?: app0.repo
    val coordinator: ReminderCoordinator = coordinatorOverride ?: app0.coordinator

    // ---------- 主题 ----------
    val themeMode = MutableStateFlow(
        try {
            ThemeMode.valueOf(
                app0.getSharedPreferences("prefs", Context.MODE_PRIVATE)
                    .getString("theme", "SYSTEM") ?: "SYSTEM",
            )
        } catch (e: Exception) { ThemeMode.SYSTEM }
    )

    fun setThemeMode(m: ThemeMode) {
        themeMode.value = m
        app0.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            .edit().putString("theme", m.name).apply()
    }

    // ---------- 导航 ----------
    private val _stack = MutableStateFlow(listOf<Screen>(Screen.Today))
    val stack: StateFlow<List<Screen>> = _stack
    fun push(s: Screen) { _stack.value = _stack.value + s }
    fun pop() { if (_stack.value.size > 1) _stack.value = _stack.value.dropLast(1) }
    fun switchTab(s: Screen) { _stack.value = listOf(s) }

    // ---------- 数据 ----------
    val schedulesLoaded = MutableStateFlow(false)
    val todosLoaded = MutableStateFlow(false)
    val countdownsLoaded = MutableStateFlow(false)

    val schedules = repo.schedules.onEach { schedulesLoaded.value = true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val todos = repo.todos.onEach { todosLoaded.value = true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val countdowns = repo.countdowns.onEach { countdownsLoaded.value = true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _today = MutableStateFlow(LocalDate.now())
    val today: StateFlow<LocalDate> = _today

    /** 前台返回、跨午夜与周期检查时刷新“今天”。 */
    fun refreshToday() { _today.value = LocalDate.now() }

    val selectedCalendarDate = MutableStateFlow(LocalDate.now())
    val countdownFilter = MutableStateFlow<Int?>(null)

    init {
        // 应用在前台跨过午夜时自动切换日期（允许短暂延迟）
        viewModelScope.launch {
            while (isActive) {
                delay(30_000)
                val today = LocalDate.now()
                if (today != _today.value) {
                    _today.value = today
                    refreshWidgets()
                }
            }
        }
    }

    // ---------- 今日汇总（客观概况） ----------
    val todayOverview: StateFlow<Triple<Int, Int, List<CountdownEntity>>> =
        combine(schedules, todos, countdowns, _today) { s, t, c, today ->
            val groups = TodayLogic.todoGroups(t, today)
            Triple(
                TodayLogic.schedulesForToday(s, today).size,
                groups.overdue.size + groups.dueToday.size + groups.noDate.size,
                TodayLogic.topCountdowns(c, today, 3),
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, Triple(0, 0, emptyList()))

    // ---------- 保存（真实防连点） ----------
    private var savingJob: Job? = null
    val saving = MutableStateFlow(false)
    val saveError = MutableStateFlow<String?>(null)

    private fun startSave(block: suspend () -> Unit, onDone: () -> Unit) {
        if (savingJob?.isActive == true) return
        saveError.value = null
        saving.value = true
        savingJob = viewModelScope.launch {
            try {
                block()
                withContext(Dispatchers.Main) { onDone() }
            } catch (e: Exception) {
                saveError.value = e.message ?: "保存失败"
            } finally {
                saving.value = false
            }
        }
    }

    fun saveSchedule(s: ScheduleEntity, onDone: () -> Unit) = startSave({
        // 必须使用落库后的真实 ID/version，而不是原始输入或 0 号记录
        val saved = repo.saveSchedule(s)
        coordinator.onScheduleSaved(saved)
    }, onDone)

    fun saveTodo(t: TodoEntity, onDone: () -> Unit) = startSave({
        val saved = repo.saveTodo(t)
        coordinator.onTodoSaved(saved)
    }, onDone)

    fun saveCountdown(c: CountdownEntity, onDone: () -> Unit) = startSave({
        val saved = repo.saveCountdown(c)
        coordinator.onCountdownSaved(saved, LocalDateTime.now())
    }, onDone)

    fun deleteSchedule(id: Long, version: Int, onDone: () -> Unit = {}) = viewModelScope.launch {
        try {
            repo.deleteSchedule(id)
            coordinator.onScheduleDeleted(id, version)
            withContext(Dispatchers.Main) { onDone() }
        } catch (e: Exception) {
            saveError.value = e.message ?: "删除失败"
        }
    }

    fun deleteTodo(id: Long, version: Int, onDone: () -> Unit = {}) = viewModelScope.launch {
        try {
            repo.deleteTodo(id)
            coordinator.onTodoDeleted(id, version)
            withContext(Dispatchers.Main) { onDone() }
        } catch (e: Exception) {
            saveError.value = e.message ?: "删除失败"
        }
    }

    fun deleteCountdown(id: Long, version: Int, onDone: () -> Unit = {}) = viewModelScope.launch {
        try {
            repo.deleteCountdown(id)
            coordinator.onCountdownDeleted(id, version)
            withContext(Dispatchers.Main) { onDone() }
        } catch (e: Exception) {
            saveError.value = e.message ?: "删除失败"
        }
    }

    /** 完成/撤销完成：撤销完成时仅注册未来提醒。 */
    fun toggleTodoDone(t: TodoEntity) {
        viewModelScope.launch {
            try {
                val updated = t.copy(
                    done = !t.done,
                    doneAtEpochMs = if (!t.done) System.currentTimeMillis() else 0,
                )
                val saved = repo.saveTodo(updated)
                coordinator.onTodoSaved(saved)
            } catch (e: Exception) {
                saveError.value = e.message ?: "更新失败"
            }
        }
    }

    // ---------- 前台协调 / 状态 ----------
    private var syncJob: Job? = null
    val reminderStatusText = MutableStateFlow("")
    val missedCount = MutableStateFlow(0)
    val snapshotCount = MutableStateFlow(0)
    val reminderSyncFailed = MutableStateFlow(false)

    fun onForeground() {
        refreshToday()
        refreshSnapshots()
        if (syncJob?.isActive == true) return
        syncJob = viewModelScope.launch(Dispatchers.IO) {
            var ok = false
            var lastError: String? = null
            repeat(2) {
                if (!ok) {
                    try {
                        app0.reconcileAndNotify()
                        ok = true
                    } catch (e: Exception) {
                        lastError = e.message ?: e.javaClass.simpleName
                    }
                }
            }
            reminderSyncFailed.value = !ok
            if (!ok) {
                app0.reminderStatus.recordBlocked(
                    "提醒重新协调失败：$lastError", System.currentTimeMillis(),
                )
            }
            refreshStatusInternal()
            refreshSnapshotsInternal()
            app0.refreshWidgets()
        }
    }

    fun refreshSnapshots() {
        viewModelScope.launch(Dispatchers.IO) { refreshSnapshotsInternal() }
    }

    private suspend fun refreshSnapshotsInternal() {
        snapshotCount.value = repo.snapshots().size
    }

    fun refreshReminderStatus() {
        viewModelScope.launch(Dispatchers.IO) { refreshStatusInternal() }
    }

    private suspend fun refreshStatusInternal() {
        val missed = try { repo.dao.missedCount() } catch (e: Exception) { 0 }
        missedCount.value = missed
        val delivered = app0.reminderStatus.lastDeliveredTitle()
        val blocked = app0.reminderStatus.blockedReason()
        val notif = notificationsEnabled()
        reminderStatusText.value = buildString {
            if (!notif) append("系统通知未开启，提醒不会出现在通知栏。")
            if (blocked != null) {
                if (isNotEmpty()) append("\n")
                append("最近一次投递受阻：").append(blocked)
            }
            if (delivered != null) {
                if (isNotEmpty()) append("\n")
                append("最近一次成功投递：").append(delivered)
            }
            if (missed > 0) {
                if (isNotEmpty()) append("\n")
                append("已标记错过的提醒：").append(missed).append(" 条")
            }
        }
    }

    fun refreshWidgets() {
        app0.refreshWidgets()
    }

    // ---------- 外部意图（通知/小组件） ----------
    val showAddChooser = MutableStateFlow(false)

    fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val add = intent.getStringExtra(MainActivity.EXTRA_OPEN_ADD)
        if (add != null) {
            intent.removeExtra(MainActivity.EXTRA_OPEN_ADD)
            when (add) {
                "todo" -> push(Screen.EditTodo(-1))
                "schedule" -> push(Screen.EditSchedule(-1, LocalDate.now().toString()))
                "countdown" -> push(Screen.EditCountdown(-1))
                else -> {
                    switchTab(Screen.Today)
                    showAddChooser.value = true
                }
            }
            return
        }
        val type = intent.getStringExtra(MainActivity.EXTRA_OPEN_ENTITY_TYPE)
        val id = intent.getLongExtra(MainActivity.EXTRA_OPEN_ENTITY_ID, -1L)
        if (type != null) {
            intent.removeExtra(MainActivity.EXTRA_OPEN_ENTITY_TYPE)
            intent.removeExtra(MainActivity.EXTRA_OPEN_ENTITY_ID)
            if (id > 0) {
                when (type) {
                    "schedule" -> push(Screen.EditSchedule(id, null))
                    "todo" -> push(Screen.EditTodo(id))
                    "countdown" -> push(Screen.EditCountdown(id))
                }
            }
            return
        }
        if (intent.getBooleanExtra(MainActivity.EXTRA_OPEN_TODAY, false)) {
            intent.removeExtra(MainActivity.EXTRA_OPEN_TODAY)
            switchTab(Screen.Today)
        }
        if (intent.getBooleanExtra(MainActivity.EXTRA_OPEN_FOCUS, false)) {
            intent.removeExtra(MainActivity.EXTRA_OPEN_FOCUS)
            push(Screen.Focus)
        }
    }

    // ---------- SAF 导出/恢复 ----------
    data class FileOpResult(val name: String, val ok: Boolean, val message: String)

    private fun fileNameOf(uri: android.net.Uri): String {
        val c = getApplication<Application>().contentResolver
        c.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cur ->
                if (cur.moveToFirst()) {
                    val idx = cur.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return cur.getString(idx) ?: "未命名文件"
                }
            }
        return "未命名文件"
    }

    fun launchExportJson(uri: android.net.Uri, cb: (FileOpResult) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val name = fileNameOf(uri)
            try {
                val text = exportBackupJson().first
                BackupIO.requireEncodedWithinLimit(text)
                val bytes = text.toByteArray(Charsets.UTF_8)
                val cr = getApplication<Application>().contentResolver
                cr.openOutputStream(uri, "wt")?.use { os ->
                    os.write(bytes); os.flush()
                } ?: throw IllegalStateException("无法打开所选位置")
                val readBack = cr.openInputStream(uri)?.use {
                    BackupIO.readTextBounded(it)
                } ?: throw IllegalStateException("回读失败")
                if (readBack != text) throw IllegalStateException("写回校验不一致")
                markExportedNow()
                withContext(Dispatchers.Main) { cb(FileOpResult(name, true, "")) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { cb(FileOpResult(name, false, e.message ?: "未知错误")) }
            }
        }
    }

    fun launchParse(uri: android.net.Uri, cb: (RestorePreview?, List<String>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cr = getApplication<Application>().contentResolver
                val text = cr.openInputStream(uri)?.use {
                    // 边读边限：超过 10 MiB 立即中止，不先 readBytes
                    BackupIO.readTextBounded(it)
                } ?: throw IllegalStateException("无法读取所选文件")
                val (preview, errors) = parseBackup(text)
                withContext(Dispatchers.Main) {
                    if (preview != null) rememberPendingRestore(text, preview)
                    cb(preview, errors)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { cb(null, listOf(e.message ?: "读取失败")) }
            }
        }
    }

    fun launchApplyRestore(cb: (RestoreOutcome) -> Unit) {
        val text = pendingRestoreText
        if (text == null) { cb(RestoreOutcome.RolledBack("没有待恢复内容")); return }
        viewModelScope.launch(Dispatchers.IO) {
            val outcome = applyRestoreText(text)
            if (outcome !is RestoreOutcome.RolledBack) pendingRestoreText = null
            withContext(Dispatchers.Main) { cb(outcome) }
        }
    }

    fun launchRestoreLatestSnapshot(cb: (RestoreOutcome) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val file = repo.latestSnapshot()
            if (file == null) {
                withContext(Dispatchers.Main) { cb(RestoreOutcome.RolledBack("当前没有恢复前快照")) }
                return@launch
            }
            val text = try {
                BackupIO.readTextBounded(file.inputStream())
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    cb(RestoreOutcome.RolledBack("快照读取失败：${e.message}"))
                }
                return@launch
            }
            val outcome = applyRestoreText(text)
            withContext(Dispatchers.Main) { cb(outcome) }
        }
    }

    val lastExportAtState = MutableStateFlow(lastExportAt())

    fun markExportedNow() {
        val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        app0.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            .edit().putString("lastExportAt", now).apply()
        lastExportAtState.value = now
    }

    fun lastExportAt(): String = app0.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        .getString("lastExportAt", "从未导出") ?: "从未导出"

    // ---------- 通知 ----------
    fun notificationsEnabled(): Boolean =
        com.jsy.dayplan.reminder.DayPlanNotifications.deliveryProblem(
            app0, DayPlanApp.REMINDER_CHANNEL_ID,
        ) == null

    fun exactAlarmAllowed(): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            val am = app0.getSystemService(android.app.AlarmManager::class.java)
            am.canScheduleExactAlarms()
        } else true

    fun sendTestNotification(cb: (Boolean, String) -> Unit) {
        if (!notificationsEnabled()) {
            cb(false, "通知未开启，请先在系统设置中允许通知")
            return
        }
        val notif = androidx.core.app.NotificationCompat.Builder(
            app0, DayPlanApp.REMINDER_CHANNEL_ID,
        )
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("日序测试通知")
            .setContentText("通知通道工作正常。提醒类通知会以此样式出现。")
            .build()
        try {
            androidx.core.app.NotificationManagerCompat.from(app0).notify(999001, notif)
            cb(true, "")
        } catch (e: SecurityException) {
            cb(false, "通知权限被撤销：${e.message}")
        }
    }

    // ---------- 备份 ----------
    suspend fun exportBackupJson(): Pair<String, String> {
        val backup = repo.backupSnapshot(
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
            "1.2",
        )
        // 导出前先验证准备导出的快照
        val normalized = when (val v = BackupValidator.validate(backup)) {
            is ValidateResult.Bad ->
                throw IllegalStateException("待导出快照校验失败：" + v.errors.first())
            is ValidateResult.Ok -> v.backup
        }
        val text = BackupCodec.encode(normalized)
        BackupIO.requireEncodedWithinLimit(text)
        return text to "日序备份_${LocalDate.now()}.json"
    }

    suspend fun parseBackup(text: String): Pair<RestorePreview?, List<String>> =
        withContext(Dispatchers.Default) {
            try {
                val b = BackupCodec.decode(text)
                when (val v = BackupValidator.validate(b)) {
                    is ValidateResult.Ok -> v.preview to emptyList()
                    is ValidateResult.Bad -> null to v.errors
                }
            } catch (e: Exception) {
                null to listOf(e.message ?: "解析失败")
            }
        }

    var pendingRestoreText: String? = null
    val restorePreviewSummary = MutableStateFlow("")

    fun rememberPendingRestore(text: String, preview: RestorePreview) {
        pendingRestoreText = text
        restorePreviewSummary.value = buildString {
            append("备份内容：\n")
            append("· 日程 ${preview.schedules} 项、待办 ${preview.todos} 项、倒数日 ${preview.countdowns} 项\n\n")
            append("确认后将【替换】本机全部数据（恢复前自动生成可回退快照；任何失败都不会改动现有数据）。")
        }
    }

    /**
     * 分阶段恢复：事务失败 → 原数据未变；事务成功但提醒重建失败 → 数据已恢复，
     * 明确告知可稍后重试提醒，而不是谎称“原数据未变”。
     */
    private suspend fun applyRestoreText(text: String): RestoreOutcome {
        val validated = try {
            val b = BackupCodec.decode(text)
            when (val v = BackupValidator.validate(b)) {
                is ValidateResult.Bad -> return RestoreOutcome.RolledBack(v.errors.joinToString("\n"))
                is ValidateResult.Ok -> v.backup
            }
        } catch (e: Exception) {
            return RestoreOutcome.RolledBack(e.message ?: "备份解析失败")
        }

        val snapDir = repo.snapshotDir
            ?: File(getApplication<Application>().filesDir, "restore_snapshots")
        val (currentAiGen, _) = app0.ai.executor.currentBoundary()
        val restored = try {
            // 业务替换与 AI 权威边界在同一 Room 事务提交；旧草稿/撤销立即失效
            repo.applyRestoreWithBoundary(
                BackupCodec.toEntities(validated),
                snapDir,
                aiGeneration = currentAiGen + 1,
                aiSessionId = app0.ai.settings.chatSessionId,
            )
        } catch (e: Exception) {
            return RestoreOutcome.RolledBack(e.message ?: "恢复失败，原数据未变")
        }

        // 提交后只更新缓存/内存视图；即使此处中断，启动也会从 DB 边界恢复
        val epoch = restored.aiEpoch
        val session = epoch.sessionId.ifBlank { app0.ai.settings.chatSessionId }
        app0.ai.settings.markBoundaryPersisted(epoch.dataGeneration, session)
        com.jsy.dayplan.data.AiExecutionGate.set(
            epoch.dataGeneration, session, bumpToken = true,
        )
        app0.ai.controller.invalidateAfterRestore()

        // 数据已替换：刷新组件，并尽力重建提醒（可重试）
        refreshWidgets()
        var rebuilt = false
        var lastError: String? = null
        repeat(3) {
            if (!rebuilt) {
                try {
                    app0.reconcileAndNotify()
                    rebuilt = true
                } catch (e: Exception) {
                    lastError = e.message ?: e.javaClass.simpleName
                }
            }
        }
        refreshSnapshotsInternal()
        refreshStatusInternal()
        return if (rebuilt) {
            RestoreOutcome.Success
        } else {
            RestoreOutcome.RemindersPending(
                "数据已恢复，但提醒重建未完成：$lastError。可在“我的”页重新协调提醒。",
            )
        }
    }

    // ---------- 恢复快照（回退入口） ----------
    fun snapshotFiles(): List<File> = repo.snapshots()

    fun latestSnapshotText(): String? = repo.latestSnapshot()?.let {
        try { BackupIO.readTextBounded(it.inputStream()) } catch (e: Exception) { null }
    }

    fun launchExportSnapshot(uri: android.net.Uri, cb: (FileOpResult) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val name = fileNameOf(uri)
            try {
                val text = latestSnapshotText()
                    ?: throw IllegalStateException("当前没有恢复前快照")
                BackupIO.requireEncodedWithinLimit(text)
                val cr = getApplication<Application>().contentResolver
                cr.openOutputStream(uri, "wt")?.use { os ->
                    os.write(text.toByteArray(Charsets.UTF_8)); os.flush()
                } ?: throw IllegalStateException("无法打开所选位置")
                val readBack = cr.openInputStream(uri)?.use { BackupIO.readTextBounded(it) }
                    ?: throw IllegalStateException("回读失败")
                if (readBack != text) throw IllegalStateException("写回校验不一致")
                withContext(Dispatchers.Main) { cb(FileOpResult(name, true, "")) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { cb(FileOpResult(name, false, e.message ?: "未知错误")) }
            }
        }
    }

    // ---------- 预算 ----------
    fun setBudget(month: String, fen: Long): String? = kotlinx.coroutines.runBlocking {
        try {
            repo.setBudget(month, fen)
            null
        } catch (e: Exception) {
            e.message ?: "保存失败"
        }
    }
}
