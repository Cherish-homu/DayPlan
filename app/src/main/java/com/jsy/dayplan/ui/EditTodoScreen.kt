package com.jsy.dayplan.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
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
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.unit.dp
import com.jsy.dayplan.core.PlanRules
import com.jsy.dayplan.data.TodoEntity
import com.jsy.dayplan.vm.PlanViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTodoScreen(vm: PlanViewModel, id: Long) {
    val editing = id > 0
    val todos by vm.todos.collectAsState()
    val loaded by vm.todosLoaded.collectAsState()
    val existing = todos.firstOrNull { it.id == id }
    val saving by vm.saving.collectAsState()
    val saveError by vm.saveError.collectAsState()

    // 真实已加载实体状态：冷启动 Flow 为空时先显示加载，不把 null 固化进表单。
    var initialized by remember(id) { mutableStateOf(!editing) }
    LaunchedEffect(id, existing?.id) {
        if (editing && existing != null && !initialized) {
            initialized = true
        }
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
    var title by remember(id) { mutableStateOf(base?.title ?: "") }
    var hasDue by remember(id) { mutableStateOf(base?.dueDate != null) }
    var dueDate by remember(id) {
        mutableStateOf(base?.dueDate?.let(LocalDate::parse) ?: LocalDate.now())
    }
    var hasTime by remember(id) { mutableStateOf(base?.dueTime != null) }
    var dueTime by remember(id) { mutableStateOf(base?.dueTime ?: "09:00") }
    var priority by remember(id) { mutableStateOf(base?.priority ?: 0) }
    var note by remember(id) { mutableStateOf(base?.note ?: "") }
    var dirty by remember(id) { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.saveError.value = null }

    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    val titleError = if (title.trim().length > PlanRules.TITLE_MAX) "标题最多 ${PlanRules.TITLE_MAX} 字" else null
    val canSave = titleError == null && title.isNotBlank() && (!hasDue || dueDate != null) &&
        (!editing || base != null) && !saving

    fun requestBack() {
        if (dirty) confirmDiscard = true else vm.pop()
    }
    BackHandler { requestBack() }

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
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it; dirty = true },
                label = { Text("标题（1–80 字）") },
                supportingText = { titleError?.let { Text(it, color = MaterialTheme.colorScheme.error) } },
                isError = titleError != null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = hasDue, onCheckedChange = {
                    hasDue = it; dirty = true
                    if (!it) hasTime = false
                })
                Text("设置截止日期", Modifier.padding(start = 8.dp))
            }
            if (hasDue) {
                OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("截止：${dueDate.format(DateTimeFormatter.ISO_DATE)}")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = hasTime, onCheckedChange = { hasTime = it; dirty = true })
                    Text(
                        "到点提醒（需先有日期）",
                        Modifier.padding(start = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (hasTime) {
                    OutlinedButton(onClick = { showTimePicker = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("截止时间：$dueTime")
                    }
                }
            }
            Text("优先级", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = priority == 0, onClick = { priority = 0; dirty = true },
                    label = { Text("普通") })
                FilterChip(selected = priority == 1, onClick = { priority = 1; dirty = true },
                    label = { Text("重要") })
            }
            OutlinedTextField(
                value = note,
                onValueChange = { note = it; dirty = true },
                label = { Text("备注（可选，最多 1000 字）") },
                modifier = Modifier.fillMaxWidth(),
            )
            saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                enabled = canSave,
                onClick = {
                    val entity = if (editing) {
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
                    } ?: return@Button
                    vm.saveTodo(entity, onDone = { vm.pop() })
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (saving) "保存中…" else "保存") }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("放弃未保存的录入？") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; vm.pop() }) { Text("放弃") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("继续编辑") } },
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
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
    if (showDatePicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = dueDate.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        dueDate = Instant.ofEpochMilli(it).atZone(ZoneId.of("UTC")).toLocalDate()
                        dirty = true
                    }
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("取消") } },
        ) { DatePicker(state = state) }
    }
    if (showTimePicker) {
        val init = dueTime.split(":")
        val st = rememberTimePickerState(init[0].toInt(), init[1].toInt())
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
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("取消") } },
        )
    }
}

@Composable
internal fun NotFoundScreen(message: String, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onBack) { Text("返回") }
        }
    }
}
