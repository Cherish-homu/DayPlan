package com.jsy.dayplan.ui.ledger

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
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
import com.jsy.dayplan.ledger.data.TransferEntity
import com.jsy.dayplan.ledger.vm.LedgerViewModel
import com.jsy.dayplan.ui.pageHorizontalPadding
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 转账（完整页面）：新增（transferId=-1）或编辑。转账不计入收支。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerEditTransferScreen(
    vm: LedgerViewModel,
    transferId: Long,
    onBack: () -> Unit,
) {
    val accounts by vm.accounts.collectAsState()
    val editing = transferId > 0
    val active = accounts.filter { !it.archived }

    var amountText by remember { mutableStateOf("") }
    var fromId by remember { mutableStateOf<Long?>(null) }
    var toId by remember { mutableStateOf<Long?>(null) }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var note by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(transferId) {
        if (editing) {
            vm.repo.transferById(transferId)?.let { t ->
                amountText = Money.formatFen(t.amountFen)
                fromId = t.fromAccountId
                toId = t.toAccountId
                date = LocalDate.parse(t.bizDate)
                note = t.note
                loaded = true
            }
        }
    }
    LaunchedEffect(active) {
        if (fromId == null) fromId = active.firstOrNull()?.id
        if (toId == null) toId = active.getOrNull(1)?.id ?: active.firstOrNull()?.id
    }

    var confirmDiscard by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    val amountError = remember(amountText) {
        when (val r = Money.parseYuanToFen(amountText)) {
            is Money.ParseResult.Ok -> null
            is Money.ParseResult.Bad -> if (amountText.isBlank()) null else r.reason
        }
    }
    val sameAccount = fromId != null && fromId == toId
    val canSave = amountError == null && amountText.isNotBlank() &&
        fromId != null && toId != null && !sameAccount
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
                title = { Text(if (editing) "编辑转账" else "账户转账", fontWeight = FontWeight.Bold) },
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
            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text("转账金额（元）") },
                supportingText = {
                    amountError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (sameAccount) {
                        Text("转出与转入账户不能相同", color = MaterialTheme.colorScheme.error)
                    }
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.fillMaxWidth().testTag("ledger_t_amount"),
                isError = amountError != null || sameAccount,
            )
            LedgerAccountPicker(active, fromId) { fromId = it }
            LedgerAccountPicker(active.filter { it.id != fromId }, toId) { toId = it }
            OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
                Text("业务日期：${date.format(DateTimeFormatter.ISO_DATE)}", maxLines = 1)
            }
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("备注（可选）") },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "转账在两个账户之间原子生效，不计入收入、支出或预算；双方余额同步变化。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            saveError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            Button(
                enabled = canSave && !saving,
                onClick = {
                    val fen = (Money.parseYuanToFen(amountText) as Money.ParseResult.Ok).fen
                    val now = System.currentTimeMillis()
                    val t = if (editing) {
                        TransferEntity(
                            id = transferId, amountFen = fen,
                            fromAccountId = fromId!!, toAccountId = toId!!,
                            bizDate = date.toString(),
                            bizTime = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")),
                            createdAtEpochMs = now, note = note,
                        )
                    } else {
                        TransferEntity(
                            amountFen = fen, fromAccountId = fromId!!, toAccountId = toId!!,
                            bizDate = date.toString(),
                            bizTime = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")),
                            createdAtEpochMs = now, note = note,
                        )
                    }
                    vm.saveTransfer(t) { onBack() }
                },
                modifier = Modifier.fillMaxWidth().testTag("ledger_t_save").padding(bottom = 24.dp),
            ) { Text(if (saving) "保存中…" else "保存转账") }
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("放弃未保存的转账？") },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; onBack() }) {
                    Text("放弃", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("继续编辑") } },
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
                    state.selectedDateMillis?.let {
                        date = Instant.ofEpochMilli(it).atZone(ZoneId.of("UTC")).toLocalDate()
                    }
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("取消") } },
        ) { DatePicker(state = state) }
    }
}
