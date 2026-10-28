package com.jsy.dayplan.ui.ledger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jsy.dayplan.ledger.core.Money
import com.jsy.dayplan.ledger.vm.LedgerViewModel
import com.jsy.dayplan.ui.GlassCard
import com.jsy.dayplan.ui.pageHorizontalPadding

/** 分类管理（完整页面）：新增支出/收入分类、改名、归档；归档不删除历史账目。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerManageCategoriesScreen(vm: LedgerViewModel, onBack: () -> Unit) {
    val categories by vm.categories.collectAsState()
    var newName by remember { mutableStateOf("") }
    var newType by remember { mutableStateOf(0) }
    var renameTarget by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var archiveTarget by remember { mutableStateOf<Long?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("管理分类", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { pad ->
        LedgerOnSurface {
            Column(Modifier.padding(pad).fillMaxSize().padding(horizontal = pageHorizontalPadding())) {
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            label = { Text("新分类名称") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Button(onClick = {
                                try {
                                    vm.addCategoryBlocking(newName, newType)
                                    newName = ""
                                    error = null
                                } catch (e: Exception) {
                                    error = e.message
                                }
                            }) { Text("新增${if (newType == 0) "支出" else "收入"}分类") }
                            TextButton(onClick = { newType = if (newType == 0) 1 else 0 }) {
                                Text("当前：${if (newType == 0) "支出" else "收入"}（点击切换）", fontSize = 13.sp)
                            }
                        }
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                    }
                }
                LazyColumn(
                    Modifier.weight(1f, fill = true),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(categories, key = { it.id }) { c ->
                        GlassCard(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Row(
                                Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        (if (c.type == 1) "收入 · " else "支出 · ") + c.name +
                                            if (c.archived) "（已归档）" else "",
                                        fontSize = 14.sp,
                                    )
                                }
                                Row {
                                    TextButton(onClick = { renameTarget = c.id to c.name }) { Text("改名") }
                                    if (!c.archived) {
                                        TextButton(onClick = { archiveTarget = c.id }) { Text("归档") }
                                    }
                                }
                            }
                        }
                    }
                }
            Text(
                "归档后不再出现在新增记账的分类里；已有账目与历史记录仍会完整保留和显示。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = com.jsy.dayplan.ui.ledger.LIST_BOTTOM_CLEARANCE),
            )
            }
        }
    }

    renameTarget?.let { (id, old) ->
        var text by remember(id) { mutableStateOf(old) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("分类改名") },
            text = {
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        vm.renameCategoryBlocking(id, text)
                        renameTarget = null
                    } catch (e: Exception) {
                        error = e.message
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } },
        )
    }
    archiveTarget?.let { id ->
        AlertDialog(
            onDismissRequest = { archiveTarget = null },
            title = { Text("归档该分类？") },
            text = { Text("归档后不再出现在新增记账的分类里；已有账目与历史记录仍会完整保留和显示。") },
            confirmButton = {
                TextButton(onClick = { vm.archiveCategoryBlocking(id); archiveTarget = null }) {
                    Text("归档", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { archiveTarget = null }) { Text("取消") } },
        )
    }
}

/** 账户管理（完整页面）：新增（含期初，允许负数）、改名、改期初、归档。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerManageAccountsScreen(vm: LedgerViewModel, onBack: () -> Unit) {
    val accounts by vm.accounts.collectAsState()
    var newName by remember { mutableStateOf("") }
    var opening by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var openingTarget by remember { mutableStateOf<Long?>(null) }
    var archiveTarget by remember { mutableStateOf<Long?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("管理账户", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { pad ->
        LedgerOnSurface {
            Column(Modifier.padding(pad).fillMaxSize().padding(horizontal = pageHorizontalPadding())) {
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            label = { Text("新账户名称") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = opening,
                            onValueChange = { opening = it.filter { c -> c.isDigit() || c == '.' || c == '-' } },
                            label = { Text("期初余额（元，可选，允许负数）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(onClick = {
                            try {
                                val fen = if (opening.isBlank()) 0L else {
                                    when (val r = Money.parseOpeningYuanToFen(opening)) {
                                        is Money.ParseResult.Bad -> throw Exception(r.reason)
                                        is Money.ParseResult.Ok -> r.fen
                                    }
                                }
                                vm.addAccountBlocking(newName, fen)
                                newName = ""
                                opening = ""
                                error = null
                            } catch (e: Exception) {
                                error = e.message
                            }
                        }) { Text("新增账户") }
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                        Text(
                            "期初余额不计入收入、支出和月度结余",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                LazyColumn(
                    Modifier.weight(1f, fill = true),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(accounts, key = { it.id }) { a ->
                        GlassCard(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Row(
                                Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    a.name + if (a.archived) "（已归档）" else "",
                                    fontSize = 14.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                Row {
                                    TextButton(onClick = { renameTarget = a.id to a.name }) { Text("改名") }
                                    TextButton(onClick = { openingTarget = a.id }) { Text("期初") }
                                    if (!a.archived) {
                                        TextButton(onClick = { archiveTarget = a.id }) { Text("归档") }
                                    }
                                }
                            }
                        }
                    }
                }
            Text(
                "已有账目的账户只允许归档，不能删除，以保留完整历史。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = com.jsy.dayplan.ui.ledger.LIST_BOTTOM_CLEARANCE),
            )
            }
        }
    }

    renameTarget?.let { (id, old) ->
        var text by remember(id) { mutableStateOf(old) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("账户改名") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        vm.renameAccountBlocking(id, text)
                        renameTarget = null
                    } catch (e: Exception) {
                        error = e.message
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } },
        )
    }
    openingTarget?.let { id ->
        var text by remember(id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { openingTarget = null },
            title = { Text("设置期初余额（元）") },
            text = {
                Column {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.filter { c -> c.isDigit() || c == '.' || c == '-' } },
                        singleLine = true,
                    )
                    Text("允许负数，例如 -50.00", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        val fen = if (text.isBlank()) 0L else {
                            when (val r = Money.parseOpeningYuanToFen(text)) {
                                is Money.ParseResult.Bad -> throw Exception(r.reason)
                                is Money.ParseResult.Ok -> r.fen
                            }
                        }
                        vm.setOpeningBlocking(id, fen)
                        openingTarget = null
                    } catch (e: Exception) {
                        error = e.message
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { openingTarget = null }) { Text("取消") } },
        )
    }
    archiveTarget?.let { id ->
        AlertDialog(
            onDismissRequest = { archiveTarget = null },
            title = { Text("归档该账户？") },
            text = { Text("归档后不再出现在新增记账与转账的账户列表；历史账目、转账与余额仍会完整保留和显示。") },
            confirmButton = {
                TextButton(onClick = { vm.archiveAccountBlocking(id); archiveTarget = null }) {
                    Text("归档", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { archiveTarget = null }) { Text("取消") } },
        )
    }
}
