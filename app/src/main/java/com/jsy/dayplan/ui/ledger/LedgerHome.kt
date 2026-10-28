package com.jsy.dayplan.ui.ledger

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.jsy.dayplan.ledger.vm.LedgerTab
import com.jsy.dayplan.ledger.vm.LedgerViewModel
import com.jsy.dayplan.ui.PageTopBar
import com.jsy.dayplan.ui.pageHorizontalPadding

/**
 * 账本主页：日序底栏第 2 项「账本」的落地页。
 * 顶栏 + 四个子页胶囊（明细/统计/账户/设置）由这里统一提供；
 * 四个子页只输出内容，不再各自套 Scaffold/TopAppBar。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerHome(
    vm: LedgerViewModel,
    onNewTxn: () -> Unit,
    onEditTxn: (Long) -> Unit,
    onNewTransfer: () -> Unit,
    onEditTransfer: (Long) -> Unit,
    onManageCategories: () -> Unit,
    onManageAccounts: () -> Unit,
) {
    val tab by vm.tab.collectAsState()

    Scaffold(
        containerColor = Color.Transparent,
        floatingActionButton = {
            if (tab == LedgerTab.OVERVIEW) {
                ExtendedFloatingActionButton(
                    onClick = onNewTxn,
                    text = { Text("记一笔") },
                    icon = { Icon(Icons.Filled.Add, null) },
                    modifier = Modifier.testTag("ledger_fab_add"),
                )
            }
        },
    ) { pad ->
        LedgerOnSurface {
            Column(Modifier.padding(pad).fillMaxSize()) {
                PageTopBar(
                    title = "小账本",
                    subtitle = "记账 · 预算 · 账户 · 数据在本机",
                )
                LedgerTabPills(
                    tabs = listOf(
                        "明细" to (tab == LedgerTab.OVERVIEW),
                        "统计" to (tab == LedgerTab.STATS),
                        "账户" to (tab == LedgerTab.ACCOUNTS),
                        "设置" to (tab == LedgerTab.SETTINGS),
                    ),
                    onSelect = { i ->
                        vm.selectTab(
                            when (i) {
                                1 -> LedgerTab.STATS
                                2 -> LedgerTab.ACCOUNTS
                                3 -> LedgerTab.SETTINGS
                                else -> LedgerTab.OVERVIEW
                            },
                        )
                    },
                    modifier = Modifier.padding(horizontal = pageHorizontalPadding()),
                )
                Box(Modifier.fillMaxSize()) {
                    when (tab) {
                        LedgerTab.OVERVIEW -> LedgerOverviewContent(
                            vm = vm,
                            onNewTransfer = onNewTransfer,
                            onEditTxn = onEditTxn,
                            onEditTransfer = onEditTransfer,
                            onGoImport = { vm.selectTab(LedgerTab.SETTINGS) },
                        )
                        LedgerTab.STATS -> LedgerStatsContent(vm)
                        LedgerTab.ACCOUNTS -> LedgerAccountsContent(vm, onManageAccounts)
                        LedgerTab.SETTINGS -> LedgerSettingsContent(
                            vm = vm,
                            onManageCategories = onManageCategories,
                            onManageAccounts = onManageAccounts,
                        )
                    }
                }
            }
        }
    }
}
