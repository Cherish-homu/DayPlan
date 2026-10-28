package com.jsy.dayplan.ui.ledger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jsy.dayplan.ledger.core.Money
import com.jsy.dayplan.ledger.vm.LedgerViewModel
import com.jsy.dayplan.ui.GlassCard
import com.jsy.dayplan.ui.pageHorizontalPadding

/** 统计子页内容：月份切换、收支结余三卡、总支出预算、分类支出排行。 */
@Composable
fun LedgerStatsContent(vm: LedgerViewModel) {
    val stats by vm.monthStats.collectAsState()
    val month by vm.selectedMonth.collectAsState()
    val categories by vm.categories.collectAsState()
    val budget by vm.monthBudget.collectAsState()
    val catName = categories.associate { it.id to it.name }

    var showBudgetDialog by remember { mutableStateOf(false) }
    var budgetInput by remember { mutableStateOf("") }
    var budgetError by remember { mutableStateOf<String?>(null) }
    val pagePad = pageHorizontalPadding()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = pagePad),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LedgerMonthSwitch(month, vm::prevMonth, vm::nextMonth)

        // 收支结余三卡
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LedgerStatCard("收入", stats.incomeFen, ledgerIncomeColor(), Modifier.weight(1f))
            LedgerStatCard("支出", stats.expenseFen, ledgerExpenseColor(), Modifier.weight(1f))
            LedgerStatCard("结余", stats.balanceFen, MaterialTheme.colorScheme.primary, Modifier.weight(1f))
        }

        // 预算卡
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("$month 总支出预算", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    TextButton(onClick = {
                        budgetInput = budget?.let { Money.formatFen(it.budgetFen) } ?: ""
                        budgetError = null
                        showBudgetDialog = true
                    }) { Text("设置", fontSize = 13.sp) }
                }
                val b = budget
                when {
                    b == null -> Text(
                        "本月未设置预算\n（设置后显示已用与剩余）",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                    else -> {
                        val limit = b.budgetFen
                        val used = stats.expenseFen
                        if (limit == 0L) {
                            Text(
                                "预算为 0：本月不计划任何支出。已用 ${Money.formatYuan(used)}" +
                                    (if (used > 0) "，已超额" else "，未超支"),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp,
                            )
                        } else {
                            val over = used > limit
                            val ratio = (used.toDouble() / limit).coerceIn(0.0, 1.0)
                            val remain = limit - used
                            LinearProgressIndicator(
                                progress = { ratio.toFloat() },
                                modifier = Modifier.fillMaxWidth().height(8.dp),
                                color = if (over) MaterialTheme.colorScheme.error
                                else peachMarkColorLedger(),
                                trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            )
                            Text(
                                if (remain >= 0) {
                                    "已用 ${Money.formatYuan(used)} / ${Money.formatYuan(limit)}，剩余 ${Money.formatYuan(remain)}"
                                } else {
                                    "已用 ${Money.formatYuan(used)}，超出预算 ${Money.formatYuan(-remain)}"
                                },
                                Modifier.padding(top = 6.dp),
                                fontSize = 13.sp,
                                color = if (over) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (over) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                        Text(
                            "预算不影响账目保存，也不限制转账",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // 分类支出排行
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("本月分类支出排行", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                if (stats.expenseByCategory.isEmpty()) {
                    Text(
                        "本月暂无支出",
                        Modifier.padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                } else {
                    val maxV = stats.expenseByCategory.maxOf { it.second }
                    stats.expenseByCategory.forEach { (catId, fen) ->
                        Column(Modifier.padding(top = 10.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(catName[catId] ?: "已删除分类", fontSize = 14.sp)
                                Text(
                                    Money.formatYuan(fen),
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp,
                                    color = ledgerExpenseColor(),
                                )
                            }
                            LinearProgressIndicator(
                                progress = { (fen.toDouble() / maxV).toFloat() },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(6.dp),
                                color = ledgerExpenseColor(),
                                trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(com.jsy.dayplan.ui.ledger.LIST_BOTTOM_CLEARANCE))
    }

    if (showBudgetDialog) {
        AlertDialog(
            onDismissRequest = { showBudgetDialog = false },
            title = { Text("设置 $month 总支出预算") },
            text = {
                Column {
                    OutlinedTextField(
                        value = budgetInput,
                        onValueChange = { budgetInput = it },
                        label = { Text("预算金额（元，0 表示本月不计划支出）") },
                        singleLine = true,
                    )
                    budgetError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    when (val r = Money.parseBudgetYuanToFen(budgetInput)) {
                        is Money.ParseResult.Bad -> budgetError = r.reason
                        is Money.ParseResult.Ok -> {
                            val err = vm.setBudgetBlocking(month, r.fen)
                            if (err == null) showBudgetDialog = false else budgetError = err
                        }
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showBudgetDialog = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun peachMarkColorLedger() = com.jsy.dayplan.ui.peachMarkColor()
