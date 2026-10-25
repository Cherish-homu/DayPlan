package com.jsy.dayplan.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jsy.dayplan.core.EntryDefaults
import com.jsy.dayplan.core.PlanRules
import com.jsy.dayplan.data.TodoEntity
import com.jsy.dayplan.vm.PlanViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTodoScreen(vm: PlanViewModel, id: Long, presetDate: String? = null) {
    val editing = id > 0
    val todos by vm.todos.collectAsState()
    val loaded by vm.todosLoaded.collectAsState()
    val existing = todos.firstOrNull { it.id == id }
    val saving by vm.saving.collectAsState()
    val saveError by vm.saveError.collectAsState()
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current

    var initialized by remember(id) { mutableStateOf(!editing) }
    LaunchedEffect(id, existing?.id) {
        if (editing && existing != null && !initialized) initialized = true
    }

    if (editing && !initialized) {
        if (!loaded) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            NotFoundScreen("待办不存在或已删除") { vm.pop() }
        }
        return
    }

    val base = existing
    val preset = remember(presetDate) {
        presetDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    }
    var title by remember(id) { mutableStateOf(base?.title ?: "") }
    var titleTouched by remember(id) { mutableStateOf(false) }
    val initialDue = base?.dueDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?: if (!editing) EntryDefaults.todoPresetDueDate(preset) else null
    var hasDue by remember(id) { mutableStateOf(initialDue != null) }
    var dueDate by remember(id) { mutableStateOf(initialDue ?: LocalDate.now()) }
    var hasTime by remember(id) { mutableStateOf(base?.dueTime != null) }
    var dueTime by remember(id) { mutableStateOf(base?.dueTime ?: "09:00") }
    var priority by remember(id) { mutableStateOf(base?.priority ?: 0) }
    var note by remember(id) { mutableStateOf(base?.note ?: "") }
    var showMore by remember(id) { mutableStateOf(false) }
    var dirty by remember(id) { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.saveError.value = null }

    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var confirmClearDue by remember { mutableStateOf(false) }

    val titleError = when {
        title.trim().length > PlanRules.TITLE_MAX -> "标题最多 ${PlanRules.TITLE_MAX} 字"
        titleTouched && title.isBlank() -> "请填写要完成的事，例如：周五前交报告"
        else -> null
    }
    val canSave = titleError == null && title.isNotBlank() &&
        (!editing || base != null) && !saving

    fun requestBack() {
        if (dirty) confirmDiscard = true else vm.pop()
    }
    BackHandler { requestBack() }

    fun buildEntity(): TodoEntity? = if (editing) {
        base?.copy(
            title = title.trim(),
            dueDate = if (hasDue) dueDate.toString() else null,
            dueTime = if (hasDue && hasTime) dueTime else null,
            priority = priority, note = note,
        )
    } else {
        TodoEntity(
            title = title.trim(),
            dueDate = if (hasDue) dueDate.toString() else null,
            dueTime = if (hasDue && hasTime) dueTime else null,
            priority = priority, note = note,
        )
    }

    fun doSave() {
        val entity = buildEntity() ?: return
        val clearing = base?.dueDate != null && !hasDue
        vm.saveTodo(entity) {
            if (clearing || (!editing && preset != null && !hasDue)) {
                Toast.makeText(context, "已保存到未定日期", Toast.LENGTH_LONG).show()
            }
            keyboard?.hide()
            vm.pop()
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(if (editing) "编辑待办" else "新增待办") },
                navigationIcon = {
                    IconButton(onClick = { requestBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    if (editing && base != null) {
                        TextButton(onClick = { confirmDelete = true }) { Text("删除") }
                    }
                    TextButton(
                        enabled = canSave,
                        onClick = {
                            if ((base?.dueDate != null && !hasDue) ||
                                (!editing && preset != null && !hasDue)
                            ) {
                                confirmClearDue = true
                            } else {
                                doSave()
                            }
                        },
                    ) { Text(if (saving) "保存中…" else "保存") }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = pageHorizontalPadding(), vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TypeBadge(PlanItemType.TODO)
                Text(
                    "完成一件事",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!editing && preset != null) {
                Text(
                    "来自月历：${preset.monthValue}月${preset.dayOfMonth}日，截止日期已预填，可清除。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            OutlinedTextField(
                value = title,
                onValueChange = { title = it; titleTouched = true; dirty = true },
                label = { Text("要完成什么？") },
                placeholder = { Text("例如：周五前交报告") },
                supportingText = {
                    Text(titleError ?: if (title.isBlank()) "填写后即可保存" else "最多 ${PlanRules.TITLE_MAX} 字")
                },
                isError = titleError != null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text("截止", style = MaterialTheme.typography.titleSmall)
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = !hasDue,
                    onClick = {
                        hasDue = false; hasTime = false; dirty = true
                    },
                    label = { Text("不设截止", maxLines = 1, softWrap = false) },
                )
                FilterChip(
                    selected = hasDue && dueDate == LocalDate.now(),
                    onClick = {
                        hasDue = true
                        dueDate = LocalDate.now()
                        dirty = true
                    },
                    label = { Text("今天", maxLines = 1, softWrap = false) },
                )
                FilterChip(
                    selected = hasDue && dueDate == LocalDate.now().plusDays(1),
                    onClick = {
                        hasDue = true
                        dueDate = LocalDate.now().plusDays(1)
                        dirty = true
                    },
                    label = { Text("明天", maxLines = 1, softWrap = false) },
                )
                FilterChip(
                    selected = hasDue && dueDate != LocalDate.now() &&
                        dueDate != LocalDate.now().plusDays(1),
                    onClick = { showDatePicker = true },
                    label = {
                        Text(
                            if (hasDue && dueDate != LocalDate.now() &&
                                dueDate != LocalDate.now().plusDays(1)
                            ) {
                                "${dueDate.monthValue}月${dueDate.dayOfMonth}日"
                            } else "选日期",
                            maxLines = 1,
                            softWrap = false,
                        )
                    },
                )
            }
            if (hasDue) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = hasTime, onCheckedChange = {
                        hasTime = it; dirty = true
                    })
                    Column(Modifier.padding(start = 8.dp)) {
                        Text("具体截止时间", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "设置后会在该时间提醒；通知是否可达见「提醒」状态。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (hasTime) {
                    OutlinedButton(
                        onClick = { showTimePicker = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("截止时间：$dueTime（本地时间）")
                    }
                }
            } else {
                Text(
                    "未定日期会归入「未定日期」分组，今天页默认折叠，不算作今日必须完成。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            TextButton(
                onClick = { showMore = !showMore },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp),
            ) {
                Icon(if (showMore) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
                Text(if (showMore) "收起更多设置" else "更多：优先级、备注", fontSize = 13.sp)
            }
            if (showMore) {
                Text("优先级", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = priority == 0,
                        onClick = { priority = 0; dirty = true },
                        label = { Text("普通") },
                    )
                    FilterChip(
                        selected = priority == 1,
                        onClick = { priority = 1; dirty = true },
                        label = { Text("重要") },
                    )
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it; dirty = true },
                    label = { Text("备注（可选，最多 ${PlanRules.NOTE_MAX} 字）") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                enabled = canSave,
                onClick = {
                    if ((base?.dueDate != null && !hasDue) ||
                        (!editing && preset != null && !hasDue)
                    ) {
                        confirmClearDue = true
                    } else {
                        doSave()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (saving) "保存中…" else "保存") }
        }
    }

    if (confirmClearDue) {
        AlertDialog(
            onDismissRequest = { confirmClearDue = false },
            title = { Text("清除截止日期？") },
            text = {
                Text(
                    "清除后这条待办会保存到「未定日期」，今天页默认折叠，不算作今日必须完成。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearDue = false
                    doSave()
                }) { Text("继续保存") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearDue = false }) { Text("返回填写") }
            },
        )
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("放弃未保存的录入？") },
            text = { Text("当前输入不会保存。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false; vm.pop()
                }) { Text("放弃") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("继续编辑") }
            },
        )
    }
    if (confirmDelete && base != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这条待办？") },
            text = { Text("将同时取消它的提醒，删除后无法在应用内撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteTodo(base.id, base.version) { vm.pop() }
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
    if (showDatePicker) {
        DatePickerRow(
            current = dueDate,
            onDismiss = { showDatePicker = false },
            onSelect = {
                showDatePicker = false
                it?.let { d ->
                    dueDate = d
                    hasDue = true
                    dirty = true
                }
            },
        )
    }
    if (showTimePicker) {
        val init = dueTime.split(":")
        val st = rememberTimePickerState(
            init.getOrNull(0)?.toIntOrNull() ?: 9,
            init.getOrNull(1)?.toIntOrNull() ?: 0,
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("选择截止时间") },
            text = { TimePicker(state = st) },
            confirmButton = {
                TextButton(onClick = {
                    dueTime = "%02d:%02d".format(st.hour, st.minute)
                    dirty = true
                    showTimePicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("取消") }
            },
        )
    }
}
