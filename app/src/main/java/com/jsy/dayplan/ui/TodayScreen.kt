package com.jsy.dayplan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jsy.dayplan.core.CountdownLogic
import com.jsy.dayplan.core.ScheduleCategory
import com.jsy.dayplan.core.TodayLogic
import com.jsy.dayplan.data.CountdownEntity
import com.jsy.dayplan.data.ScheduleEntity
import com.jsy.dayplan.data.TodoEntity
import com.jsy.dayplan.vm.PlanViewModel
import com.jsy.dayplan.vm.Screen
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(vm: PlanViewModel) {
    val today by vm.today.collectAsState()
    val overview by vm.todayOverview.collectAsState()
    val schedules by vm.schedules.collectAsState()
    val todos by vm.todos.collectAsState()

    var fabMenu by remember { mutableStateOf(false) }
    var showCompleted by remember { mutableStateOf(false) }
    val dateText = today.format(DateTimeFormatter.ofPattern("yyyy年M月d日"))
    val weekday = listOf("一", "二", "三", "四", "五", "六", "日")[
        (today.dayOfWeek.value + 6) % 7]
    val groups = TodayLogic.todoGroups(todos, today)
    val daySchedules = TodayLogic.schedulesForToday(schedules, today)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("$dateText 周$weekday") },
                actions = {
                    TextButton(onClick = { vm.push(Screen.Focus) }) {
                        Icon(Icons.Filled.Timer, null)
                        Spacer(Modifier.width(4.dp))
                        Text("专注")
                    }
                },
            )
        },
        floatingActionButton = {
            Box {
                ExtendedFloatingActionButton(
                    onClick = { fabMenu = true },
                    text = { Text("新增") },
                    icon = { Icon(Icons.Filled.Add, null) },
                )
                DropdownMenu(fabMenu, onDismissRequest = { fabMenu = false }) {
                    DropdownMenuItem(text = { Text("待办") }, onClick = {
                        fabMenu = false; vm.push(Screen.EditTodo(-1))
                    })
                    DropdownMenuItem(text = { Text("日程") }, onClick = {
                        fabMenu = false; vm.push(Screen.EditSchedule(-1, today.toString()))
                    })
                    DropdownMenuItem(text = { Text("倒数日") }, onClick = {
                        fabMenu = false; vm.push(Screen.EditCountdown(-1))
                    })
                }
            }
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            item {
                Text(
                    "今天 ${overview.first} 项日程，${overview.second} 项待办",
                    Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
            }

            // 今日安排
            item {
                SectionTitle("今日安排")
                if (daySchedules.isEmpty()) {
                    EmptyHint("今天没有日程") { vm.push(Screen.EditSchedule(-1, today.toString())) }
                } else {
                    daySchedules.forEach { entity ->
                        ScheduleLine(entity, today) {
                            vm.push(Screen.EditSchedule(entity.id, null))
                        }
                    }
                }
            }

            // 待办事项
            item { SectionTitle("待办事项") }
            val activeEmpty = groups.overdue.isEmpty() && groups.dueToday.isEmpty() &&
                groups.noDate.isEmpty()
            if (activeEmpty && groups.completed.isEmpty()) {
                item { EmptyHint("今天没有待办") { vm.push(Screen.EditTodo(-1)) } }
            } else {
                if (groups.overdue.isNotEmpty()) {
                    item { GroupLabel("逾期未完成") }
                    groups.overdue.forEach { t ->
                        item(key = "od${t.id}") {
                            TodoLine(t, onToggle = { vm.toggleTodoDone(t) }, onEdit = {
                                vm.push(Screen.EditTodo(t.id))
                            })
                        }
                    }
                }
                if (groups.dueToday.isNotEmpty()) {
                    item { GroupLabel("今日到期") }
                    groups.dueToday.forEach { t ->
                        item(key = "dt${t.id}") {
                            TodoLine(t, onToggle = { vm.toggleTodoDone(t) }, onEdit = {
                                vm.push(Screen.EditTodo(t.id))
                            })
                        }
                    }
                }
                if (groups.noDate.isNotEmpty()) {
                    item { GroupLabel("无日期") }
                    groups.noDate.forEach { t ->
                        item(key = "nd${t.id}") {
                            TodoLine(t, onToggle = { vm.toggleTodoDone(t) }, onEdit = {
                                vm.push(Screen.EditTodo(t.id))
                            })
                        }
                    }
                }
                if (groups.completed.isNotEmpty()) {
                    item {
                        TextButton(onClick = { showCompleted = !showCompleted }) {
                            Icon(
                                if (showCompleted) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                null,
                            )
                            Text("已完成 ${groups.completed.size} 项")
                        }
                    }
                    if (showCompleted) {
                        groups.completed.forEach { t ->
                            item(key = "cp${t.id}") {
                                TodoLine(t, onToggle = { vm.toggleTodoDone(t) }, onEdit = {
                                    vm.push(Screen.EditTodo(t.id))
                                })
                            }
                        }
                    }
                }
                item {
                    TextButton(onClick = { vm.push(Screen.TodoList) }) { Text("全部待办 →") }
                }
            }

            // 重要日子（置顶 3 张）
            item { SectionTitle("重要日子") }
            val top = overview.third
            if (top.isEmpty()) {
                item { EmptyHint("还没有倒数日") { vm.push(Screen.EditCountdown(-1)) } }
            } else {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        top.forEach { c ->
                            CountdownCard(c, today, Modifier.weight(1f)) {
                                vm.push(Screen.EditCountdown(c.id))
                            }
                        }
                        if (top.size < 3) {
                            Spacer(Modifier.weight((3 - top.size).toFloat()))
                        }
                    }
                }
                item {
                    TextButton(onClick = { vm.switchTab(Screen.Countdowns) }) {
                        Text("全部倒数日 →")
                    }
                }
            }
            item { Spacer(Modifier.height(90.dp)) }
        }
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text,
        Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        style = MaterialTheme.typography.titleMedium,
    )
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text,
        Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
        color = MaterialTheme.colorScheme.tertiary,
        fontSize = 12.sp,
    )
}

@Composable
private fun EmptyHint(text: String, onAdd: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onAdd) { Text("新增一条") }
        }
    }
}

@Composable
private fun ScheduleLine(s: ScheduleEntity, today: LocalDate, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.background(
                MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp),
            ).padding(horizontal = 8.dp, vertical = 4.dp),
        ) { Text(ScheduleCategory.NAMES[s.category], fontSize = 12.sp) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(s.title, fontSize = 15.sp)
            val timeText = if (s.allDay) "全天" else "${s.startTime}–${s.endTime}"
            Text(
                (if (s.startDate == s.endDate) timeText else "${s.startDate} ~ ${s.endDate}") +
                    (if (s.location.isBlank()) "" else " · ${s.location}"),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun TodoLine(t: TodoEntity, onToggle: () -> Unit, onEdit: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = t.done, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f).clickable(onClick = onEdit)) {
            Text(
                t.title + if (t.priority == 1) " ★" else "",
                fontSize = 15.sp,
                textDecoration = if (t.done) TextDecoration.LineThrough else null,
            )
            if (t.dueDate != null) {
                Text(
                    "截止 ${t.dueDate} ${t.dueTime ?: ""}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CountdownCard(
    c: CountdownEntity,
    today: LocalDate,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val occ = CountdownLogic.nextOccurrence(LocalDate.parse(c.targetDate), today, c.yearlyRepeat)
    val days = CountdownLogic.daysBetween(today, occ)
    Card(modifier.clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp)) {
            Text(c.title, fontWeight = FontWeight.SemiBold, maxLines = 2)
            Text(
                CountdownLogic.displayText(days),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                occ.toString(),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
