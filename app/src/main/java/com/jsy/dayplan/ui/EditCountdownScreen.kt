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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jsy.dayplan.core.CountdownCategory
import com.jsy.dayplan.core.CountdownLogic
import com.jsy.dayplan.core.EntryDefaults
import com.jsy.dayplan.core.PlanRules
import com.jsy.dayplan.data.CountdownEntity
import com.jsy.dayplan.vm.PlanViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditCountdownScreen(vm: PlanViewModel, id: Long, presetDate: String? = null) {
    val editing = id > 0
    val countdowns by vm.countdowns.collectAsState()
    val loaded by vm.countdownsLoaded.collectAsState()
    val existing = countdowns.firstOrNull { it.id == id }
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
            NotFoundScreen("倒数日不存在或已删除") { vm.pop() }
        }
        return
    }

    val base = existing
    val preset = remember(presetDate) {
        presetDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    }
    val today = LocalDate.now()
    var title by remember(id) { mutableStateOf(base?.title ?: "") }
    var titleTouched by remember(id) { mutableStateOf(false) }
    var target by remember(id) {
        mutableStateOf(
            base?.targetDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?: EntryDefaults.countdownTarget(preset, today),
        )
    }
    var category by remember(id) { mutableStateOf(base?.category ?: 0) }
    var note by remember(id) { mutableStateOf(base?.note ?: "") }
    var pinned by remember(id) { mutableStateOf(base?.pinned ?: false) }
    var yearly by remember(id) { mutableStateOf(base?.yearlyRepeat ?: false) }
    var remindEnabled by remember(id) { mutableStateOf(base?.remindEnabled ?: false) }
    var remindDays by remember(id) { mutableStateOf(base?.remindDaysBefore ?: 0) }
    var remindTime by remember(id) { mutableStateOf(base?.remindTime ?: "09:00") }
    var showMore by remember(id) { mutableStateOf(false) }
    var dirty by remember(id) { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.saveError.value = null }

    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    val titleError = when {
        title.trim().length > PlanRules.TITLE_MAX -> "标题最多 ${PlanRules.TITLE_MAX} 字"
        titleTouched && title.isBlank() -> "请填写要期待或纪念的事，例如：生日"
        else -> null
    }
    val canSave = titleError == null && title.isNotBlank() && (!editing || base != null) && !saving

    fun requestBack() { if (dirty) confirmDiscard = true else vm.pop() }
    BackHandler { requestBack() }

    fun save() {
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
        } ?: return
        vm.saveCountdown(entity, onDone = {
            keyboard?.hide()
            vm.pop()
        })
    }

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
                TypeBadge(PlanItemType.COUNTDOWN)
                Text(
                    "期待或纪念某一天",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!editing && preset != null) {
                Text(
                    "来自月历：${preset.monthValue}月${preset.dayOfMonth}日，目标日期已预填。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            OutlinedTextField(
                value = title,
                onValueChange = { title = it; titleTouched = true; dirty = true },
                label = { Text("在期待哪一天？") },
                placeholder = { Text("例如：生日") },
                supportingText = {
                    Text(titleError ?: if (title.isBlank()) "填写后即可保存" else "最多 ${PlanRules.TITLE_MAX} 字")
                },
                isError = titleError != null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = { showDatePicker = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "目标日期：${target.monthValue}月${target.dayOfMonth}日" +
                        "（${target.format(DateTimeFormatter.ISO_DATE)}）",
                )
            }
            val occ = CountdownLogic.nextOccurrence(target, today, yearly)
            val days = CountdownLogic.daysBetween(today, occ)
            Text(
                "预览：${CountdownLogic.displayText(days)}（${occ.monthValue}月${occ.dayOfMonth}日）",
                color = MaterialTheme.colorScheme.primary,
            )

            TextButton(
                onClick = { showMore = !showMore },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp),
            ) {
                Icon(if (showMore) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
                Text(if (showMore) "收起更多设置" else "更多：类别、置顶、每年、提醒、备注", fontSize = 13.sp)
            }
            if (showMore) {
                Text("类别", style = MaterialTheme.typography.titleSmall)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CountdownCategory.NAMES.forEachIndexed { i, n ->
                        FilterChip(
                            selected = category == i,
                            onClick = { category = i; dirty = true },
                            label = { Text(n, maxLines = 1, softWrap = false) },
                        )
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
                        listOf(0 to "当天", 1 to "提前 1 天", 7 to "提前 7 天").forEach { (v, label) ->
                            FilterChip(
                                selected = remindDays == v,
                                onClick = { remindDays = v; dirty = true },
                                label = { Text(label, maxLines = 1, softWrap = false) },
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = { showTimePicker = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("提醒时间：$remindTime（本地时间）")
                    }
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
                onClick = { save() },
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
            title = { Text("删除这个倒数日？") },
            text = { Text("将同时取消它的提醒，删除后无法在应用内撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteCountdown(base.id, base.version) { vm.pop() }
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
    if (showDatePicker) {
        DatePickerRow(
            current = target,
            onDismiss = { showDatePicker = false },
            onSelect = {
                showDatePicker = false
                it?.let { d -> target = d; dirty = true }
            },
        )
    }
    if (showTimePicker) {
        val init = remindTime.split(":")
        val st = rememberTimePickerState(
            init.getOrNull(0)?.toIntOrNull() ?: 9,
            init.getOrNull(1)?.toIntOrNull() ?: 0,
        )
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
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("取消") }
            },
        )
    }
}
