package com.jsy.dayplan.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jsy.dayplan.core.TodayLogic
import com.jsy.dayplan.vm.PlanViewModel
import com.jsy.dayplan.vm.Screen

/** 全部待办：逾期/今日/未来/无日期/已完成，均支持编辑与完成撤销。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoListScreen(vm: PlanViewModel) {
    val todos by vm.todos.collectAsState()
    val today by vm.today.collectAsState()
    var showCompleted by remember { mutableStateOf(true) }
    val groups = TodayLogic.todoGroups(todos, today)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("全部待办") },
                navigationIcon = {
                    IconButton(onClick = { vm.pop() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { vm.push(Screen.EditTodo(-1)) },
                text = { Text("新增") },
                icon = { Icon(Icons.Filled.Add, null) },
            )
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            if (todos.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth().padding(12.dp)) {
                        Column(Modifier.padding(14.dp)) {
                            Text("还没有待办", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { vm.push(Screen.EditTodo(-1)) }) { Text("新增一条") }
                        }
                    }
                }
            }
            fun group(label: String, items: List<com.jsy.dayplan.data.TodoEntity>, prefix: String) {
                if (items.isEmpty()) return
                item(key = "h$prefix") {
                    Text(
                        label,
                        Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items.forEach { t ->
                    item(key = "$prefix${t.id}") {
                        TodoLine(
                            t,
                            onToggle = { vm.toggleTodoDone(t) },
                            onEdit = { vm.push(Screen.EditTodo(t.id)) },
                        )
                    }
                }
            }
            group("逾期未完成", groups.overdue, "o")
            group("今日到期", groups.dueToday, "d")
            group("未来", groups.future, "f")
            group("无日期", groups.noDate, "n")
            if (groups.completed.isNotEmpty()) {
                item(key = "ch") {
                    TextButton(onClick = { showCompleted = !showCompleted }) {
                        Text(if (showCompleted) "收起已完成（${groups.completed.size}）"
                        else "展开已完成（${groups.completed.size}）")
                    }
                }
                if (showCompleted) {
                    groups.completed.forEach { t ->
                        item(key = "c${t.id}") {
                            TodoLine(
                                t,
                                onToggle = { vm.toggleTodoDone(t) },
                                onEdit = { vm.push(Screen.EditTodo(t.id)) },
                            )
                        }
                    }
                }
            }
            item { androidx.compose.foundation.layout.Spacer(Modifier.padding(80.dp)) }
        }
    }
}
