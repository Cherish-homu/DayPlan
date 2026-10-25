package com.jsy.dayplan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jsy.dayplan.core.ScheduleCategory
import com.jsy.dayplan.core.ScheduleLogic
import com.jsy.dayplan.data.CountdownEntity
import com.jsy.dayplan.data.ScheduleEntity
import com.jsy.dayplan.data.TodoEntity
import com.jsy.dayplan.vm.PlanViewModel
import com.jsy.dayplan.vm.Screen
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/** 月历：周一为每周第一天；小圆点区分类型并提供文字图例；点选日期显示条目。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(vm: PlanViewModel) {
    val today by vm.today.collectAsState()
    val selected by vm.selectedCalendarDate.collectAsState()
    val schedules by vm.schedules.collectAsState()
    val todos by vm.todos.collectAsState()
    val countdowns by vm.countdowns.collectAsState()

    var month: YearMonth by remember { mutableStateOf(YearMonth.from(today)) }
    var searchTitle: String by remember { mutableStateOf("") }
    var typeFilter: String? by remember { mutableStateOf(null) }

    val scheduleSpans = schedules.map {
        ScheduleLogic.ScheduleSpan(
            it.id, it.title, LocalDate.parse(it.startDate), LocalDate.parse(it.endDate),
            it.allDay, it.startTime, it.endTime,
        )
    }

    fun coveredSchedules(d: LocalDate): List<ScheduleEntity> =
        scheduleSpans.filter { sp ->
            ScheduleLogic.coveredDates(sp).any { it == d } &&
                (searchTitle.isBlank() || sp.title.contains(searchTitle, ignoreCase = true))
        }.map { sp -> schedules.first { it.id == sp.id } }

    fun todosOf(d: LocalDate): List<TodoEntity> = todos.filter {
        it.dueDate == d.toString() &&
            (searchTitle.isBlank() || it.title.contains(searchTitle, ignoreCase = true))
    }

    fun countdownsOf(d: LocalDate): List<CountdownEntity> = countdowns.filter {
        (it.targetDate == d.toString() || (it.yearlyRepeat && it.targetDate.substring(5) == d.toString().substring(5))) &&
            (searchTitle.isBlank() || it.title.contains(searchTitle, ignoreCase = true))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("月历 · ${month.format(DateTimeFormatter.ofPattern("yyyy年M月"))}") },
                navigationIcon = {
                    IconButton(onClick = { month = month.minusMonths(1) }) {
                        Icon(Icons.Filled.ChevronLeft, "上一月")
                    }
                },
                actions = {
                    TextButton(onClick = { month = YearMonth.from(today); vm.selectedCalendarDate.value = today }) {
                        Text("今天")
                    }
                    IconButton(onClick = { month = month.plusMonths(1) }) {
                        Icon(Icons.Filled.ChevronRight, "下一月")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                vm.push(
                    when (typeFilter) {
                        "待办" -> Screen.EditTodo(-1)
                        "倒数日" -> Screen.EditCountdown(-1)
                        else -> Screen.EditSchedule(-1, selected.toString())
                    },
                )
            }) { Icon(Icons.Filled.Add, "为所选日期新增") }
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp),
        ) {
            // 搜索与类型筛选
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = searchTitle,
                    onValueChange = { searchTitle = it },
                    label = { Text("搜索标题") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                listOf("日程", "待办", "倒数日").forEach { t ->
                    FilterChip(
                        selected = typeFilter == t,
                        onClick = { typeFilter = if (typeFilter == t) null else t },
                        label = { Text(t) },
                    )
                }
            }

            // 星期表头（周一起始）
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                listOf("一", "二", "三", "四", "五", "六", "日").forEach {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // 月历网格
            val firstDow = (month.atDay(1).dayOfWeek.value + 6) % 7   // 周一=0
            val daysInMonth = month.lengthOfMonth()
            val cells = firstDow + daysInMonth
            val rows = (cells + 6) / 7
            for (r in 0 until rows) {
                Row(Modifier.fillMaxWidth().height(44.dp)) {
                    for (c in 0 until 7) {
                        val idx = r * 7 + c
                        val dayNum = idx - firstDow + 1
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            if (dayNum in 1..daysInMonth) {
                                val d = month.atDay(dayNum)
                                val hasS = coveredSchedules(d).isNotEmpty()
                                val hasT = todosOf(d).isNotEmpty()
                                val hasC = countdownsOf(d).isNotEmpty()
                                val isSel = d == selected
                                val isToday = d == today
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(
                                            if (isSel) MaterialTheme.colorScheme.primary
                                            else androidx.compose.ui.graphics.Color.Transparent,
                                        )
                                        .clickable { vm.selectedCalendarDate.value = d }
                                        .padding(4.dp),
                                ) {
                                    Text(
                                        dayNum.toString(),
                                        fontSize = 13.sp,
                                        fontWeight = if (isToday || isSel) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSel) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurface,
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                        if (hasS) Dot(MaterialTheme.colorScheme.primary)
                                        if (hasT) Dot(MaterialTheme.colorScheme.tertiary)
                                        if (hasC) Dot(MaterialTheme.colorScheme.secondary)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 文字图例（不只依赖颜色）
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Legend("● 日程", MaterialTheme.colorScheme.primary)
                Legend("● 待办", MaterialTheme.colorScheme.tertiary)
                Legend("● 倒数日", MaterialTheme.colorScheme.secondary)
            }

            // 所选日期条目
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        selected.format(DateTimeFormatter.ofPattern("M月d日")) + " 的安排",
                        fontWeight = FontWeight.SemiBold,
                    )
                    val s = coveredSchedules(selected)
                    val t = todosOf(selected)
                    val c = countdownsOf(selected)
                    if (s.isEmpty() && t.isEmpty() && c.isEmpty()) {
                        Text(
                            "该日暂无条目，点右下角 + 新增",
                            Modifier.padding(top = 6.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        s.forEach { e ->
                            Text(
                                "📅 ${e.title}（${if (e.allDay) "全天" else "${e.startTime}–${e.endTime}"}）",
                                Modifier.padding(top = 6.dp).clickable {
                                    vm.push(com.jsy.dayplan.vm.Screen.EditSchedule(e.id, null))
                                },
                            )
                        }
                        t.forEach { e ->
                            Text(
                                "☑ ${e.title}" + if (e.done) "（已完成）" else "",
                                Modifier.padding(top = 4.dp).clickable {
                                    vm.push(com.jsy.dayplan.vm.Screen.EditTodo(e.id))
                                },
                            )
                        }
                        c.forEach { e ->
                            Text(
                                "🎯 ${e.title}",
                                Modifier.padding(top = 4.dp).clickable {
                                    vm.push(com.jsy.dayplan.vm.Screen.EditCountdown(e.id))
                                },
                            )
                        }
                    }
                }
            }
            androidx.compose.foundation.layout.Spacer(Modifier.height(90.dp))
        }
    }
}

@Composable
private fun Dot(color: androidx.compose.ui.graphics.Color) {
    Box(Modifier.size(5.dp).clip(CircleShape).background(color))
}

@Composable
private fun Legend(text: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(
            text.removePrefix("● "),
            Modifier.padding(start = 4.dp),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
