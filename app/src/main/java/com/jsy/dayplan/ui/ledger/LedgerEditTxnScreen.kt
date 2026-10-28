package com.jsy.dayplan.ui.ledger

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jsy.dayplan.ledger.core.Money
import com.jsy.dayplan.ledger.data.AccountEntity
import com.jsy.dayplan.ledger.data.TransactionEntity
import com.jsy.dayplan.ledger.vm.LedgerViewModel
import com.jsy.dayplan.ui.GlassCard
import com.jsy.dayplan.ui.pageHorizontalPadding
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 记一笔 / 编辑账目（完整页面，有自己的顶栏与返回）。txnId = -1 表示新增。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LedgerEditTxnScreen(
    vm: LedgerViewModel,
    txnId: Long,
    onBack: () -> Unit,
    onManageCategories: () -> Unit,
) {
    val accounts by vm.accounts.collectAsState()
    val categories by vm.categories.collectAsState()
    val editing = txnId > 0
    val existing = remember(txnId) { mutableStateOf<TransactionEntity?>(null) }
    LaunchedEffect(txnId) {
        if (editing) existing.value = vm.repo.txnById(txnId)
    }

    // 表单状态
    var type by remember { mutableStateOf(0) }
    var amountText by remember { mutableStateOf("") }
    var catId by remember { mutableStateOf<Long?>(null) }
    var accId by remember { mutableStateOf<Long?>(null) }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var time by remember { mutableStateOf(LocalTime.now().withSecond(0)) }
    var note by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(existing.value) {
        val e = existing.value
        if (e != null && !loaded) {
            type = e.type
            amountText = Money.formatFen(e.amountFen)
            catId = e.categoryId
            accId = e.accountId
            date = LocalDate.parse(e.bizDate)
            time = LocalTime.parse(e.bizTime)
            note = e.note
            loaded = true
        }
    }
    if (!editing || loaded) {
        LaunchedEffect(type, categories, accounts) {
            if (catId == null || categories.none { it.id == catId && it.type == type }) {
                catId = categories.filter { it.type == type && !it.archived }
                    .minByOrNull { it.sort }?.id
            }
            if (accId == null) accId = vm.defaultAccountId()
        }
    }

    var confirmDiscard by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    val amountError = remember(amountText) {
        when (val r = Money.parseYuanToFen(amountText)) {
            is Money.ParseResult.Ok -> null
            is Money.ParseResult.Bad -> if (amountText.isBlank()) null else r.reason
        }
    }
    val canSave = catId != null && accId != null && amountError == null &&
        Money.parseYuanToFen(amountText) is Money.ParseResult.Ok
    val saving by vm.saving.collectAsState()
    val saveError by vm.saveError.collectAsState()

    BackHandler {
        if (amountText.isNotBlank() || note.isNotBlank()) confirmDiscard = true
        else onBack()
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(if (editing) "编辑账目" else "记一笔", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (amountText.isNotBlank() || note.isNotBlank()) confirmDiscard = true
                        else onBack()
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { pad ->
        LedgerOnSurface {
            Column(
                Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = pageHorizontalPadding()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = type == 0,
                    onClick = { type = 0 },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text("支出") }
                SegmentedButton(
                    selected = type == 1,
                    onClick = { type = 1 },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text("收入") }
            }

            OutlinedTextField(
                value = amountText,
                onValueChange = { s ->
                    // 只允许数字与一个小数点
                    amountText = s.filter { it.isDigit() || it == '.' }
                },
                label = { Text("金额（元，最多两位小数）") },
                supportingText = {
                    amountError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.fillMaxWidth().testTag("ledger_amount_input"),
                isError = amountError != null,
            )

            Text("分类", style = MaterialTheme.typography.titleSmall)
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        categories.filter { it.type == type && !it.archived }.forEach { c ->
                            FilterChip(
                                selected = catId == c.id,
                                onClick = { catId = c.id },
                                label = { Text(c.name) },
                            )
                        }
                    }
                    TextButton(onClick = onManageCategories) {
                        Text("管理分类（新增 / 改名 / 归档）", fontSize = 13.sp)
                    }
                }
            }

            LedgerAccountPicker(accounts.filter { !it.archived }, accId) { accId = it }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.weight(1f)) {
                    Text("日期：${date.format(DateTimeFormatter.ISO_DATE)}", maxLines = 1)
                }
                OutlinedButton(onClick = { showTimePicker = true }, modifier = Modifier.weight(1f)) {
                    Text("时间：${time.format(DateTimeFormatter.ofPattern("HH:mm"))}", maxLines = 1)
                }
            }

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("备注（可选）") },
                modifier = Modifier.fillMaxWidth(),
            )

            saveError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }

            Button(
                enabled = canSave && !saving,
                onClick = {
                    val fen = (Money.parseYuanToFen(amountText) as Money.ParseResult.Ok).fen
                    val now = System.currentTimeMillis()
                    val t = if (editing) {
                        existing.value!!.copy(
                            type = type, amountFen = fen, categoryId = catId!!,
                            accountId = accId!!, bizDate = date.toString(),
                            bizTime = time.format(DateTimeFormatter.ofPattern("HH:mm")),
                            note = note,
                        )
                    } else {
                        TransactionEntity(
                            type = type, amountFen = fen, categoryId = catId!!,
                            accountId = accId!!, bizDate = date.toString(),
                            bizTime = time.format(DateTimeFormatter.ofPattern("HH:mm")),
                            createdAtEpochMs = now, note = note,
                        )
                    }
                    vm.saveTxn(t) { onBack() }
                },
                modifier = Modifier.fillMaxWidth().testTag("ledger_save_btn").padding(bottom = 24.dp),
            ) {
                Text(if (saving) "保存中…" else "保存")
            }
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("放弃未保存的录入？") },
            text = { Text("返回将丢失尚未保存的内容。") },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; onBack() }) {
                    Text("放弃", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("继续编辑") }
            },
        )
    }

    if (showDatePicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        date = Instant.ofEpochMilli(ms).atZone(ZoneId.of("UTC")).toLocalDate()
                    }
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("取消") } },
        ) { DatePicker(state = state) }
    }
    if (showTimePicker) {
        val tpState = rememberTimePickerState(initialHour = time.hour, initialMinute = time.minute)
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("选择时间") },
            text = { TimePicker(state = tpState) },
            confirmButton = {
                TextButton(onClick = {
                    time = LocalTime.of(tpState.hour, tpState.minute)
                    showTimePicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("取消") } },
        )
    }
}

/** 账户下拉选择（排除归档账户）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerAccountPicker(
    accounts: List<AccountEntity>,
    selected: Long?,
    onSelect: (Long) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val current = accounts.firstOrNull { it.id == selected }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = current?.name ?: "选择账户",
            onValueChange = {},
            readOnly = true,
            label = { Text("账户") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            accounts.forEach { a ->
                DropdownMenuItem(
                    text = { Text(a.name) },
                    onClick = { onSelect(a.id); expanded = false },
                )
            }
        }
    }
}
