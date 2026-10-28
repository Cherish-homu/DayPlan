package com.jsy.dayplan.ui.ledger

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jsy.dayplan.ledger.core.Money
import com.jsy.dayplan.ledger.data.TransactionEntity
import com.jsy.dayplan.ledger.data.TransferEntity
import com.jsy.dayplan.ledger.vm.LedgerViewModel
import com.jsy.dayplan.ui.EmptyHintCard
import com.jsy.dayplan.ui.GlassCard
import com.jsy.dayplan.ui.pageHorizontalPadding
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 明细子页内容：固定头部（月份/概览/筛选）+ LazyColumn 懒加载列表。
 * 账目行只在可见时组合，几千笔以上的真实账本不会一次性建满 Composable。
 * 底部 contentPadding 预留 160dp：最后一行可以滚到「记一笔」FAB 与右侧助手上方。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LedgerOverviewContent(
    vm: LedgerViewModel,
    onNewTransfer: () -> Unit,
    onEditTxn: (Long) -> Unit,
    onEditTransfer: (Long) -> Unit,
    onGoImport: () -> Unit,
) {
    val txns by vm.visibleTxns.collectAsState()
    val transfers by vm.visibleTransfers.collectAsState()
    val allTxns by vm.txns.collectAsState()
    val allTransfers by vm.transfers.collectAsState()
    val accounts by vm.accounts.collectAsState()
    val categories by vm.categories.collectAsState()
    val stats by vm.monthStats.collectAsState()
    val month by vm.selectedMonth.collectAsState()
    val fCat by vm.filterCategoryId.collectAsState()
    val fAcc by vm.filterAccountId.collectAsState()
    val fKw by vm.filterKeyword.collectAsState()

    var showFilter by remember { mutableStateOf(false) }
    var deleteTxnTarget by remember { mutableStateOf<Long?>(null) }
    var deleteTransferTarget by remember { mutableStateOf<Long?>(null) }
    val accName = accounts.associate { it.id to it.name }
    val catName = categories.associate { it.id to it.name }
    val pagePad = pageHorizontalPadding()

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = pagePad),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LedgerMonthSwitch(
                month = month,
                onPrev = vm::prevMonth,
                onNext = vm::nextMonth,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onNewTransfer,
                modifier = Modifier.testTag("ledger_btn_transfer"),
            ) {
                Icon(Icons.Filled.SwapHoriz, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("转账", fontSize = 13.sp)
            }
            IconButton(onClick = { showFilter = !showFilter }) {
                Icon(
                    Icons.Filled.FilterList,
                    "筛选",
                    tint = if (showFilter || fCat != null || fAcc != null || fKw.isNotBlank()) {
                        MaterialTheme.colorScheme.primary
                    } else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 本月收支概览（口径与统计一致）
        Row(
            Modifier.fillMaxWidth().padding(horizontal = pagePad),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LedgerStatCard("本月收入", stats.incomeFen, ledgerIncomeColor(), Modifier.weight(1f))
            LedgerStatCard("本月支出", stats.expenseFen, ledgerExpenseColor(), Modifier.weight(1f))
            LedgerStatCard("本月结余", stats.balanceFen, MaterialTheme.colorScheme.primary, Modifier.weight(1f))
        }

        if (showFilter) {
            GlassCard(Modifier.fillMaxWidth().padding(horizontal = pagePad, vertical = 6.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            var menu by remember { mutableStateOf(false) }
                            FilterChip(
                                selected = fCat != null,
                                onClick = { menu = true },
                                label = { Text(fCat?.let { catName[it] ?: "分类" } ?: "全部分类", fontSize = 12.sp) },
                            )
                            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                categories.forEach { c ->
                                    DropdownMenuItem(
                                        text = { Text((if (c.type == 1) "收·" else "支·") + c.name) },
                                        onClick = { vm.filterCategoryId.value = c.id; menu = false },
                                    )
                                }
                            }
                        }
                        Box {
                            var menu by remember { mutableStateOf(false) }
                            FilterChip(
                                selected = fAcc != null,
                                onClick = { menu = true },
                                label = { Text(fAcc?.let { accName[it] ?: "账户" } ?: "全部账户", fontSize = 12.sp) },
                            )
                            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                accounts.forEach { a ->
                                    DropdownMenuItem(
                                        text = { Text(a.name) },
                                        onClick = { vm.filterAccountId.value = a.id; menu = false },
                                    )
                                }
                            }
                        }
                        TextButton(onClick = {
                            vm.filterCategoryId.value = null
                            vm.filterAccountId.value = null
                            vm.filterKeyword.value = ""
                        }) { Text("清除", fontSize = 13.sp) }
                    }
                    OutlinedTextField(
                        value = fKw,
                        onValueChange = { vm.filterKeyword.value = it },
                        label = { Text("备注关键词") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        // 空库导入提示：账目与转账都为空（首次种子后账户不为空，但仍应提示）
        if (allTxns.isEmpty() && allTransfers.isEmpty()) {
            Box(Modifier.padding(horizontal = pagePad, vertical = 8.dp)) {
                EmptyHintCard(
                    text = "以前用小账本？把旧备份 JSON 导入进来，账目一分钟搬完。",
                    actionLabel = "去导入",
                    onAction = onGoImport,
                )
            }
        }

        // 明细：当月账目 + 当月转账合并，按日分组（LazyColumn 懒加载）
        val merged = remember(txns, transfers) {
            (txns.map { TRow.Txn(it) } + transfers.map { TRow.Transfer(it) })
                .sortedWith(compareByDescending<TRow> { it.date() }.thenByDescending { it.time() })
        }
        val grouped = remember(merged) {
            var lastDate = ""
            merged.map { row ->
                val newDay = row.date() != lastDate
                if (newDay) lastDate = row.date()
                GroupedRow(row, newDay)
            }
        }

        if (merged.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                Text(
                    "本月暂无记录\n点右下角「记一笔」开始",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = LIST_BOTTOM_CLEARANCE),
            ) {
                items(grouped, key = { it.row.key() }) { g ->
                    Column(Modifier.fillMaxWidth()) {
                        if (g.newDay) {
                            Row(
                                Modifier.fillMaxWidth()
                                    .padding(horizontal = pagePad, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    prettyLedgerDate(g.row.date()),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(8.dp))
                                Box(
                                    Modifier.weight(1f).height(1.dp)
                                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                                )
                            }
                        }
                        when (val row = g.row) {
                            is TRow.Txn -> LedgerTxnRow(
                                t = row.t, accName = accName, catName = catName,
                                onClick = { onEditTxn(row.t.id) },
                                onLongClick = { deleteTxnTarget = row.t.id },
                                modifier = Modifier.padding(horizontal = pagePad),
                            )
                            is TRow.Transfer -> LedgerTransferRow(
                                t = row.t, accName = accName,
                                onClick = { onEditTransfer(row.t.id) },
                                onLongClick = { deleteTransferTarget = row.t.id },
                                modifier = Modifier.padding(horizontal = pagePad),
                            )
                        }
                    }
                }
            }
        }
    }

    deleteTxnTarget?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteTxnTarget = null },
            title = { Text("删除这笔账目？") },
            text = { Text("删除后相关汇总将同步刷新，且无法恢复。") },
            confirmButton = {
                TextButton(onClick = { vm.deleteTxn(id); deleteTxnTarget = null }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteTxnTarget = null }) { Text("取消") } },
        )
    }
    deleteTransferTarget?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteTransferTarget = null },
            title = { Text("删除这笔转账？") },
            text = { Text("删除后两个账户的余额都会同步调整。") },
            confirmButton = {
                TextButton(onClick = { vm.deleteTransfer(id); deleteTransferTarget = null }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteTransferTarget = null }) { Text("取消") } },
        )
    }
}

/** 列表底部余量：需同时让最后一行避开「记一笔」FAB（约 52dp）与右侧助手（84dp 偏移 + 约 56dp 高）。 */
internal val LIST_BOTTOM_CLEARANCE = 160.dp

private data class GroupedRow(val row: TRow, val newDay: Boolean)

private sealed class TRow {
    abstract fun date(): String
    abstract fun time(): String
    abstract fun key(): String

    data class Txn(val t: TransactionEntity) : TRow() {
        override fun date() = t.bizDate
        override fun time() = t.bizTime
        override fun key() = "t${t.id}"
    }

    data class Transfer(val t: TransferEntity) : TRow() {
        override fun date() = t.bizDate
        override fun time() = t.bizTime
        override fun key() = "f${t.id}"
    }
}

private fun prettyLedgerDate(d: String): String {
    val today = LocalDate.now().format(DateTimeFormatter.ISO_DATE)
    return when (d) {
        today -> "今天 · $d"
        LocalDate.now().minusDays(1).format(DateTimeFormatter.ISO_DATE) -> "昨天 · $d"
        else -> d
    }
}

/** 账目行：左侧分类标签，中间备注/时间/账户，右侧带符号金额（行高 ≥ 56dp）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LedgerTxnRow(
    t: TransactionEntity,
    accName: Map<Long, String>,
    catName: Map<Long, String>,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .rowMinHeight()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LedgerCategoryChip(catName[t.categoryId] ?: "?", t.type == 1)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                t.note.ifBlank { catName[t.categoryId] ?: "账目" },
                fontSize = 15.sp,
                maxLines = 1,
            )
            Text(
                "${t.bizTime} · ${accName[t.accountId] ?: "?"}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            signedAmount(t.type, t.amountFen),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (t.type == 1) ledgerIncomeColor() else ledgerExpenseColor(),
        )
    }
}

/** 转账行：单独一行，不计入收支。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LedgerTransferRow(
    t: TransferEntity,
    accName: Map<Long, String>,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .rowMinHeight()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.SwapHoriz, null,
            Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "${accName[t.fromAccountId] ?: "?"} → ${accName[t.toAccountId] ?: "?"}",
                fontSize = 15.sp,
                maxLines = 1,
            )
            Text(
                "转账 · ${t.bizTime} · 不计入收支",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            Money.formatYuan(t.amountFen),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun Modifier.rowMinHeight(): Modifier =
    this.then(Modifier.padding(vertical = 8.dp))
