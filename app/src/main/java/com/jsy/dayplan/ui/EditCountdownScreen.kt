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
import com.jsy.dayplan.core.CountdownCategory
import com.jsy.dayplan.core.CountdownLogic
import com.jsy.dayplan.core.PlanRules
import com.jsy.dayplan.data.CountdownEntity
import com.jsy.dayplan.vm.PlanViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditCountdownScreen(vm: PlanViewModel, id: Long) {
    val editing = id > 0
    val countdowns by vm.countdowns.collectAsState()
    val loaded by vm.countdownsLoaded.collectAsState()
    val existing = countdowns.firstOrNull { it.id == id }
    val saving by vm.saving.collectAsState()
    val saveError by vm.saveError.collectAsState()

    var initialized by remember(id) { mutableStateOf(!editing) }
    LaunchedEffect(id, existing?.id) {
        if (editing && existing != null) initialized = true
    }

    if (editing && !initialized) {
        if (!loaded) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            NotFoundScreen("倒数日不存在或已删除") { vm.pop() }
        }
        return
    }

    val base = existing
    var title by remember(id) { mutableStateOf(base?.title ?: "") }
    var target by remember(id) {
        mutableStateOf(base?.targetDate?.let(LocalDate::parse) ?: LocalDate.now().plusMonths(1))
    }
    var category by remember(id) { mutableStateOf(base?.category ?: 0) }
    var note by remember(id) { mutableStateOf(base?.note ?: "") }
    var pinned by remember(id) { mutableStateOf(base?.pinned ?: false) }
    var yearly by remember(id) { mutableStateOf(base?.yearlyRepeat ?: false) }
    var remindEnabled by remember(id) { mutableStateOf(base?.remindEnabled ?: false) }
    var remindDays by remember(id) { mutableStateOf(base?.remindDaysBefore ?: 0) }
    var remindTime by remember(id) { mutableStateOf(base?.remindTime ?: "09:00") }
    var dirty by remember(id) { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.saveError.value = null }

    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    val today = LocalDate.now()

    val titleError = if (title.trim().length > PlanRules.TITLE_MAX) "标题最多 ${PlanRules.TITLE_MAX} 字" else null
    val canSave = titleError == null && title.isNotBlank() && (!editing || base != null) && !saving

    fun requestBack() { if (dirty) confirmDiscard = true else vm.pop() }
    BackHandler { requestBack() }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(if (editing) "编辑倒数日" else "新增倒数日") },
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
            OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
                Text("目标日期：${target.format(DateTimeFormatter.ISO_DATE)}")
            }
            val occ = CountdownLogic.nextOccurrence(target, today, yearly)
            val days = CountdownLogic.daysBetween(today, occ)
            Text(
                "预览：${CountdownLogic.displayText(days)}（$occ）",
                color = MaterialTheme.colorScheme.primary,
            )

            Text("类别", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CountdownCategory.NAMES.forEachIndexed { i, n ->
                    FilterChip(selected = category == i, onClick = { category = i; dirty = true },
                        label = { Text(n) })
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = pinned, onCheckedChange = { pinned = it; dirty = true })
                Text("置顶显示", Modifier.padding(start = 8.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = yearly, onCheckedChange = { yearly = it; dirty = true })
                Column(Modifier.padding(start = 8.dp)) {
                    Text("每年重复")
                    Text(
                        "2 月 29 日的纪念日在非闰年按 2 月 28 日计算",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = remindEnabled, onCheckedChange = {
                    remindEnabled = it; dirty = true
                })
                Text("启用提醒", Modifier.padding(start = 8.dp))
            }
            if (remindEnabled) {
                Text("提醒时机（最多一种）", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0 to "当天", 1 to "提前1天", 7 to "提前7天").forEach { (v, label) ->
                        FilterChip(
                            selected = remindDays == v,
                            onClick = { remindDays = v; dirty = true },
                            label = { Text(label) },
                        )
                    }
                }
                OutlinedButton(onClick = { showTimePicker = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("提醒时间：$remindTime（本地时间）")
                }
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
                    val originalDate = if (yearly) {
                        base?.originalDate?.takeIf { it.isNotBlank() } ?: target.toString()
                    } else target.toString()
                    val entity = if (editing) {
                        base?.copy(
                            title = title.trim(), targetDate = target.toString(),
                            category = category, note = note, pinned = pinned,
                            yearlyRepeat = yearly, remindEnabled = remindEnabled,
                            remindDaysBefore = remindDays,
                            remindTime = remindTime, originalDate = originalDate,
                        )
                    } else {
                        CountdownEntity(
                            title = title.trim(), targetDate = target.toString(),
                            category = category, note = note, pinned = pinned,
                            yearlyRepeat = yearly, remindEnabled = remindEnabled,
                            remindDaysBefore = remindDays,
                            remindTime = remindTime, originalDate = originalDate,
                        )
                    } ?: return@Button
                    vm.saveCountdown(entity, onDone = { vm.pop() })
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
            title = { Text("删除这条倒数日？") },
            text = { Text("将同时取消它的提醒与桌面组件绑定显示。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteCountdown(base.id, base.version) { vm.pop() }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
    if (showDatePicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = target.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        target = Instant.ofEpochMilli(it).atZone(ZoneId.of("UTC")).toLocalDate()
                        dirty = true
                    }
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("取消") } },
        ) { DatePicker(state = state) }
    }
    if (showTimePicker) {
        val init = remindTime.split(":")
        val st = rememberTimePickerState(init[0].toInt(), init[1].toInt())
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("选择提醒时间") },
            text = { TimePicker(state = st) },
            confirmButton = {
                TextButton(onClick = {
                    remindTime = "%02d:%02d".format(st.hour, st.minute)
                    dirty = true
                    showTimePicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("取消") } },
        )
    }
}
