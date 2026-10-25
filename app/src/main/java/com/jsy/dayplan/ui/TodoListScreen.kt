package com.jsy.dayplan.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jsy.dayplan.core.TodayLogic
import com.jsy.dayplan.vm.PlanViewModel
import com.jsy.dayplan.vm.Screen

/** 全部待办：逾期/今日/未来/未定日期/已完成，各组给数量；已完成默认折叠。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoListScreen(vm: PlanViewModel) {
    val todos by vm.todos.collectAsState()
    val today by vm.today.collectAsState()
    var showCompleted by remember { mutableStateOf(false) }
    val groups = TodayLogic.todoGroups(todos, today)
    val activeCount = groups.overdue.size + groups.dueToday.size + groups.future.size + groups.noDate.size
    val pagePadding = pageHorizontalPadding()

    Scaffold(
        topBar = {
            PageTopBar(
                title = "全部待办",
                subtitle = "未完成 $activeCount 项 · 已完成 ${groups.completed.size} 项",
                titleSize = 20.sp,
                titleLineHeight = 26.sp,
                subtitleStartPadding = 72.dp,
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
                text = { Text("新增待办") },
                icon = { Icon(Icons.Filled.Add, null) },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(
                start = pagePadding,
                end = pagePadding,
                bottom = 96.dp,
            ),
        ) {
            if (todos.isEmpty()) {
                item {
                    EmptyHintCard(
                        "还没有待办。待办是完成一件事，做完就勾掉。",
                        actionLabel = "新增待办",
                        onAction = { vm.push(Screen.EditTodo(-1)) },
                    )
                }
            }
            todoGroup("已逾期", groups.overdue, "o", vm, today)
            todoGroup("今日截止", groups.dueToday, "d", vm, today)
            todoGroup("以后", groups.future, "f", vm, today)
            todoGroup("未定日期", groups.noDate, "n", vm, today)
            if (groups.completed.isNotEmpty()) {
                item(key = "ch") {
                    CollapsibleGroupHeader(
                        "已完成", groups.completed.size, showCompleted,
                    ) { showCompleted = !showCompleted }
                }
                if (showCompleted) {
                    items(groups.completed, key = { "c${it.id}" }) { t ->
                        TodoRow(
                            t, today,
                            onToggle = { vm.toggleTodoDone(t) },
                            onEdit = { vm.push(Screen.EditTodo(t.id)) },
                        )
                    }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.todoGroup(
    label: String,
    entries: List<com.jsy.dayplan.data.TodoEntity>,
    prefix: String,
    vm: PlanViewModel,
    today: java.time.LocalDate,
) {
    if (entries.isEmpty()) return
    item(key = "h$prefix") {
        Text(
            "$label · ${entries.size}",
            Modifier.padding(top = 18.dp, bottom = 4.dp, start = 4.dp),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    items(entries, key = { "$prefix${it.id}" }) { t ->
        TodoRow(
            t, today,
            onToggle = { vm.toggleTodoDone(t) },
            onEdit = { vm.push(Screen.EditTodo(t.id)) },
        )
    }
}
