package com.jsy.dayplan.ui

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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jsy.dayplan.core.EntryDefaults
import com.jsy.dayplan.core.PlanRules
import com.jsy.dayplan.core.ScheduleCategory
import com.jsy.dayplan.core.ScheduleLogic
import com.jsy.dayplan.data.ScheduleEntity
import com.jsy.dayplan.vm.PlanViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScheduleScreen(vm: PlanViewModel, id: Long, presetDate: String?) {
    val editing = id > 0
    val schedules by vm.schedules.collectAsState()
    val loaded by vm.schedulesLoaded.collectAsState()
    val existing = schedules.firstOrNull { it.id == id }
    val saving by vm.saving.collectAsState()
    val saveError by vm.saveError.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current

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
            NotFoundScreen("日程不存在或已删除") { vm.pop() }
        }
        return
    }

    val base = existing
    val presetLocal = remember(presetDate) {
        presetDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    }
    val suggestion = remember(presetLocal, editing) {
        if (editing) null
        else EntryDefaults.scheduleSuggestion(presetLocal, LocalDate.now(), LocalTime.now())
    }
    var title by remember(id) { mutableStateOf(base?.title ?: "") }
    var titleTouched by remember(id) { mutableStateOf(false) }
    var startDate by remember(id) {
        mutableStateOf(
            base?.startDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?: suggestion?.startDate ?: LocalDate.now(),
        )
    }
    var endDate by remember(id) {
        mutableStateOf(
            base?.endDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?: suggestion?.endDate ?: LocalDate.now(),
        )
    }
    var allDay by remember(id) { mutableStateOf(base?.allDay ?: false) }
    var startTime by remember(id) { mutableStateOf(base?.startTime ?: suggestion?.startTime ?: "09:00") }
    var endTime by remember(id) { mutableStateOf(base?.endTime ?: suggestion?.endTime ?: "10:00") }
    var location by remember(id) { mutableStateOf(base?.location ?: "") }
    var note by remember(id) { mutableStateOf(base?.note ?: "") }
    var category by remember(id) { mutableStateOf(base?.category ?: 0) }
    var remind by remember(id) { mutableStateOf(base?.remindMinutesBefore ?: 0) }
    var showMore by remember(id) { mutableStateOf(false) }
    var dirty by remember(id) { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.saveError.value = null }

    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val titleError = when {
        title.trim().length > PlanRules.TITLE_MAX -> "标题最多 ${PlanRules.TITLE_MAX} 字"
        titleTouched && title.isBlank() -> "请填写日程标题，例如：阅读文献"
        else -> null
    }
    val noteError = if (note.length > PlanRules.NOTE_MAX) "备注最多 ${PlanRules.NOTE_MAX} 字" else null
    val span = ScheduleLogic.ScheduleSpan(
        if (editing) id else 0, title, startDate, endDate, allDay,
        if (allDay) null else startTime, if (allDay) null else endTime,
    )
    val spanError = if (title.isBlank()) null else ScheduleLogic.validateSpan(span)
    val conflicts = if (spanError == null && title.isNotBlank()) {
        ScheduleLogic.conflictingWith(
            span,
            schedules.map {
                ScheduleLogic.ScheduleSpan(
                    it.id, it.title, LocalDate.parse(it.startDate), LocalDate.parse(it.endDate),
                    it.allDay, it.startTime, it.endTime,
                )
            },
        )
    } else emptyList()
    val canSave = titleError == null && noteError == null && title.isNotBlank() &&
        spanError == null && (!editing || base != null) && !saving

    fun requestBack() { if (dirty) confirmDiscard = true else vm.pop() }
    BackHandler { requestBack() }

    fun save() {
        val entity = if (editing) {
            base?.copy(
                title = title.trim(), startDate = startDate.toString(),
                endDate = endDate.toString(), allDay = allDay,
                startTime = if (allDay) null else startTime,
                endTime = if (allDay) null else endTime,
                location = location.trim(), note = note,
                category = category, remindMinutesBefore = remind,
            )
        } else {
            ScheduleEntity(
                title = title.trim(), startDate = startDate.toString(),
                endDate = endDate.toString(), allDay = allDay,
                startTime = if (allDay) null else startTime,
                endTime = if (allDay) null else endTime,
                location = location.trim(), note = note,
                category = category, remindMinutesBefore = remind,
            )
        } ?: return
        vm.saveSchedule(entity, onDone = {
            keyboard?.hide()
            vm.pop()
        })
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(if (editing) "编辑日程" else "新增日程") },
                navigationIcon = {
                    IconButton(onClick = { requestBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    if (editing && base != null) {
                        TextButton(onClick = { confirmDelete = true }) { Text("删除") }
                    }
                    TextButton(enabled = canSave, onClick = { save() }) {
                        Text(if (saving) "保存中…" else "保存")
                    }
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
                TypeBadge(PlanItemType.SCHEDULE)
                Text(
                    "占一段时间",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!editing && suggestion != null && !dirty) {
                Text(
                    suggestion.label + "（可修改）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            OutlinedTextField(
                value = title,
                onValueChange = { title = it; titleTouched = true; dirty = true },
                label = { Text("准备做什么？") },
                placeholder = { Text("例如：阅读文献") },
                supportingText = {
                    Text(titleError ?: if (title.isBlank()) "填写后即可保存" else "最多 ${PlanRules.TITLE_MAX} 字")
                },
                isError = titleError != null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text("日期与时段", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { showStartPicker = true },
                    modifier = Modifier.weight(1f),
                ) {
                    DateButtonLabel(
                        "开始",
                        "${startDate.monthValue}月${startDate.dayOfMonth}日",
                    )
                }
                OutlinedButton(
                    onClick = { showEndPicker = true },
                    modifier = Modifier.weight(1f),
                ) {
                    DateButtonLabel(
                        "结束",
                        "${endDate.monthValue}月${endDate.dayOfMonth}日",
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = allDay, onCheckedChange = {
                    allDay = it; dirty = true
                })
                Text("全天", Modifier.padding(start = 8.dp))
            }
            if (!allDay) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { showTimePicker = "start" },
                        modifier = Modifier.weight(1f),
                    ) { Text("$startTime 开始", maxLines = 1, softWrap = false) }
                    OutlinedButton(
                        onClick = { showTimePicker = "end" },
                        modifier = Modifier.weight(1f),
                    ) { Text("$endTime 结束", maxLines = 1, softWrap = false) }
                }
            }
            spanError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Text("提前提醒", style = MaterialTheme.typography.titleSmall)
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(0 to "不提醒", 15 to "15 分", 30 to "30 分", 60 to "1 小时", 120 to "2 小时")
                    .forEach { (v, label) ->
                        FilterChip(
                            selected = remind == v,
                            onClick = { remind = v; dirty = true },
                            label = { Text(label, maxLines = 1, softWrap = false) },
                        )
                    }
            }

            TextButton(
                onClick = { showMore = !showMore },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp),
            ) {
                Icon(if (showMore) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
                Text(if (showMore) "收起更多设置" else "更多：分类、地点、备注", fontSize = 13.sp)
            }
            if (showMore) {
                Text("分类", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ScheduleCategory.NAMES.forEachIndexed { i, n ->
                        FilterChip(
                            selected = category == i,
                            onClick = { category = i; dirty = true },
                            label = { Text(n) },
                        )
                    }
                }
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it; dirty = true },
                    label = { Text("地点（可选，最多 ${PlanRules.LOCATION_MAX} 字）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it; dirty = true },
                    label = { Text("备注（可选，最多 ${PlanRules.NOTE_MAX} 字）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                noteError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }

            if (conflicts.isNotEmpty()) {
                Text(
                    "与 ${conflicts.size} 条日程时间重叠（仍可保存）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = { save() },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (saving) "保存中…" else "保存") }
        }
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
            title = { Text("删除这条日程？") },
            text = { Text("将同时取消它的提醒，删除后无法在应用内撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteSchedule(base.id, base.version) { vm.pop() }
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
    if (showStartPicker) DatePickerRow(
        current = startDate,
        onDismiss = { showStartPicker = false },
        onSelect = {
            showStartPicker = false
            it?.let { d ->
                startDate = d
                if (endDate.isBefore(d)) endDate = d
                dirty = true
            }
        },
    )
    if (showEndPicker) DatePickerRow(
        current = endDate,
        onDismiss = { showEndPicker = false },
        onSelect = {
            showEndPicker = false
            it?.let { d -> endDate = d; dirty = true }
        },
    )
    showTimePicker?.let { which ->
        val init = (if (which == "start") startTime else endTime).split(":")
        val st = rememberTimePickerState(
            init.getOrNull(0)?.toIntOrNull() ?: 9,
            init.getOrNull(1)?.toIntOrNull() ?: 0,
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = null },
            title = { Text("选择时间") },
            text = { TimePicker(state = st) },
            confirmButton = {
                TextButton(onClick = {
                    val t = "%02d:%02d".format(st.hour, st.minute)
                    if (which == "start") startTime = t else endTime = t
                    dirty = true
                    showTimePicker = null
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = null }) { Text("取消") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerRow(current: LocalDate, onDismiss: () -> Unit, onSelect: (LocalDate?) -> Unit) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = current.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onSelect(state.selectedDateMillis?.let {
                    Instant.ofEpochMilli(it).atZone(ZoneId.of("UTC")).toLocalDate()
                })
            }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    ) { DatePicker(state = state) }
}


@Composable
private fun DateButtonLabel(prefix: String, date: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(prefix, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(date, fontSize = 14.sp, maxLines = 1, softWrap = false)
    }
}
