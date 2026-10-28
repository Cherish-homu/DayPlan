package com.jsy.dayplan.ui.ledger

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.jsy.dayplan.ledger.core.Calculator
import com.jsy.dayplan.ledger.core.Money
import com.jsy.dayplan.ledger.vm.LedgerViewModel
import com.jsy.dayplan.ui.GlassCard
import com.jsy.dayplan.ui.pageHorizontalPadding

/** 账户子页内容：账户合计（受检求和）、账户列表、已归档折叠区。 */
@Composable
fun LedgerAccountsContent(vm: LedgerViewModel, onManageAccounts: () -> Unit) {
    val accounts by vm.accounts.collectAsState()
    val balances by vm.balances.collectAsState()
    val balMap = balances.associate { it.accountId to it.balanceFen }
    val active = accounts.filter { !it.archived }
    val archived = accounts.filter { it.archived }
    var archivedExpanded by remember { mutableStateOf(false) }
    val pagePad = pageHorizontalPadding()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = pagePad),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 合计卡：受检求和，极端数据不静默溢出
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    "账户合计（期初 + 收入 − 支出 ± 转账）",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    Money.formatYuan(
                        Calculator.totalOf(balances.filter { b -> active.any { it.id == b.accountId } }),
                    ),
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "以上为「记账余额」，不等同于实际银行/支付平台余额",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        active.forEach { a ->
            GlassCard(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(14.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(a.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Text(
                            "期初 ${Money.formatYuan(a.openingFen)}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        Money.formatYuan(balMap[a.id] ?: 0L),
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        if (archived.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().clickable { archivedExpanded = !archivedExpanded }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (archivedExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "已归档账户（${archived.size}）",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (archivedExpanded) {
                archived.forEach { a ->
                    GlassCard(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(12.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("${a.name}（已归档）", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(Money.formatYuan(balMap[a.id] ?: 0L))
                        }
                    }
                }
            }
        }

        TextButton(onClick = onManageAccounts, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("管理账户（新增 / 改名 / 期初 / 归档）")
        }
        Spacer(Modifier.height(com.jsy.dayplan.ui.ledger.LIST_BOTTOM_CLEARANCE))
    }
}
