package com.jsy.dayplan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jsy.dayplan.core.TodayLogic
import com.jsy.dayplan.core.UpcomingLogic
import com.jsy.dayplan.core.UpcomingKind
import com.jsy.dayplan.core.UpcomingEntry
import com.jsy.dayplan.core.UpcomingDay
import com.jsy.dayplan.data.CountdownEntity
import com.jsy.dayplan.data.ScheduleEntity
import com.jsy.dayplan.vm.PlanViewModel
import com.jsy.dayplan.vm.Screen
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

/**
 * 今天：一个“接下来”重点 → 日程时间轴 → 待办清单 → 重要日子横卡。
 * 待办入口始终存在，未定日期与已完成默认折叠。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(vm: PlanViewModel) {
    val today by vm.today.collectAsState()
    val schedules by vm.schedules.collectAsState()
    val todos by vm.todos.collectAsState()
    val countdowns by vm.countdowns.collectAsState()

    var showAddSheet by remember { mutableStateOf(false) }
    var showNoDate by remember { mutableStateOf(false) }
    var showCompleted by remember { mutableStateOf(false) }

    val now by produceState(LocalTime.now(), today) {
        while (true) {
            value = LocalTime.now()
            delay(30_000)
        }
    }
    val groups = TodayLogic.todoGroups(todos, today)
    val daySchedules = TodayLogic.schedulesForToday(schedules, today)
    val focus = TodayLogic.focusSchedule(schedules, today, now)
    val topCountdowns = TodayLogic.topCountdowns(countdowns, today, limit = 12)
    val upcoming = remember(today, schedules, todos) {
        UpcomingLogic.build(today, schedules, todos)
    }
    val pagePadding = pageHorizontalPadding()
    val weekday = listOf("一", "二", "三", "四", "五", "六", "日")[
        (today.dayOfWeek.value + 6) % 7
    ]

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            PageTopBar(
                title = "今天",
                subtitle = "${today.monthValue}月${today.dayOfMonth}日 周$weekday",
                actions = {
                    TextButton(onClick = { showAddSheet = true }) {
                        Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(2.dp))
                        Text("新增")
                    }
                    TextButton(onClick = { vm.push(Screen.Focus) }) {
                        Icon(Icons.Filled.Timer, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("专注")
                    }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(
                start = pagePadding,
                end = pagePadding,
                bottom = 80.dp,
            ),
        ) {
            item {
                FocusCard(
                    focus = focus,
                    today = today,
                    now = now,
                    scheduleCount = daySchedules.size,
                    dueTodayCount = groups.dueToday.size,
                    overdueCount = groups.overdue.size,
                    onOpen = { entity -> vm.push(Screen.EditSchedule(entity.id, null)) },
                    onAddSchedule = { vm.push(Screen.EditSchedule(-1, today.toString())) },
                    onOpenTodos = { vm.push(Screen.TodoList) },
                )
            }

            item {
                SectionHeader(
                    title = "日程",
                    subtitle = if (daySchedules.isEmpty()) null else "今天 ${daySchedules.size} 段安排",
                    addLabel = "+日程",
                    onAdd = { vm.push(Screen.EditSchedule(-1, today.toString())) },
                )
            }
            if (daySchedules.isNotEmpty()) {
                items(daySchedules, key = { "s${it.id}" }) { entity ->
                    ScheduleTimelineRow(
                        schedule = entity,
                        today = today,
                        now = now,
                        onClick = { vm.push(Screen.EditSchedule(entity.id, null)) },
                    )
                }
            } else {
                item {
                    EmptyHintCard(
                        "今天还没有日程。点右上角“+日程”安排一段时间，给今天一个清晰的开始。",
                    )
                }
            }

            if (upcoming.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "未来几天",
                        subtitle = "明天起 7 天 · ${upcoming.sumOf { it.totalCount }} 项",
                        actionLabel = "月历",
                        onAction = { vm.switchTab(Screen.Calendar) },
                    )
                }
                item {
                    UpcomingCard(
                        days = upcoming,
                        today = today,
                        onClick = { entry ->
                            when (entry.kind) {
                                UpcomingKind.SCHEDULE -> vm.push(Screen.EditSchedule(entry.id, null))
                                UpcomingKind.TODO -> vm.push(Screen.EditTodo(entry.id))
                            }
                        },
                    )
                }
            }

            item {
                SectionHeader(
                    title = "待办",
                    subtitle = todoSubtitle(groups.dueToday.size, groups.overdue.size, groups.future.size),
                    addLabel = "+待办",
                    onAdd = { vm.push(Screen.EditTodo(-1)) },
                    actionLabel = "全部 ${todos.size} 项",
                    onAction = { vm.push(Screen.TodoList) },
                )
            }
            if (groups.overdue.isNotEmpty()) {
                item { GroupHeading("已逾期", groups.overdue.size, MaterialTheme.colorScheme.error) }
                items(groups.overdue, key = { "o${it.id}" }) { t ->
                    TodoRow(
                        t, today,
                        onToggle = { vm.toggleTodoDone(t) },
                        onEdit = { vm.push(Screen.EditTodo(t.id)) },
                    )
                }
            }
            if (groups.dueToday.isNotEmpty()) {
                item { GroupHeading("今日截止", groups.dueToday.size, MaterialTheme.colorScheme.primary) }
                items(groups.dueToday, key = { "d${it.id}" }) { t ->
                    TodoRow(
                        t, today,
                        onToggle = { vm.toggleTodoDone(t) },
                        onEdit = { vm.push(Screen.EditTodo(t.id)) },
                    )
                }
            }
            if (groups.overdue.isEmpty() && groups.dueToday.isEmpty()) {
                item {
                    EmptyHintCard(
                        if (todos.isEmpty()) {
                            "还没有待办。待办是完成一件事，做完就勾掉。"
                        } else {
                            "今天没有到期待办。未来事项可以在全部待办里查看。"
                        },
                        actionLabel = if (todos.isEmpty()) "新增待办" else "查看全部",
                        onAction = {
                            if (todos.isEmpty()) vm.push(Screen.EditTodo(-1))
                            else vm.push(Screen.TodoList)
                        },
                    )
                }
            }
            if (groups.noDate.isNotEmpty()) {
                item {
                    CollapsibleGroupHeader(
                        "未定日期", groups.noDate.size, showNoDate,
                    ) { showNoDate = !showNoDate }
                }
                if (showNoDate) {
                    items(groups.noDate, key = { "n${it.id}" }) { t ->
                        TodoRow(
                            t, today,
                            onToggle = { vm.toggleTodoDone(t) },
                            onEdit = { vm.push(Screen.EditTodo(t.id)) },
                        )
                    }
                }
            }
            if (groups.future.isNotEmpty()) {
                item {
                    TextButton(onClick = { vm.push(Screen.TodoList) }) {
                        Text(
                            "未来 ${groups.future.size} 项 · 在全部待办查看",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (groups.completed.isNotEmpty()) {
                item {
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

            item {
                SectionHeader(
                    title = "重要日子",
                    subtitle = if (topCountdowns.isEmpty()) null else "期待或纪念的日子",
                    addLabel = "+倒数日",
                    onAdd = { vm.push(Screen.EditCountdown(-1)) },
                    actionLabel = if (topCountdowns.isEmpty()) null else "全部",
                    onAction = if (topCountdowns.isEmpty()) null else {
                        { vm.switchTab(Screen.Countdowns) }
                    },
                )
            }
            if (topCountdowns.isEmpty()) {
                item {
                    EmptyHintCard(
                        "还没有重要日子。倒数日用来期待或纪念某一天。",
                        actionLabel = "新增倒数日",
                        onAction = { vm.push(Screen.EditCountdown(-1)) },
                    )
                }
            } else {
                item {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(end = pagePadding),
                    ) {
                        items(topCountdowns, key = { "cd${it.id}" }) { c ->
                            CountdownMiniCard(c, today) {
                                vm.push(Screen.EditCountdown(c.id))
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    if (showAddSheet) {
        AddItemSheet(
            contextLabel = "为今天新建",
            onDismiss = { showAddSheet = false },
            onSelect = { type ->
                showAddSheet = false
                when (type) {
                    PlanItemType.SCHEDULE -> vm.push(Screen.EditSchedule(-1, today.toString()))
                    PlanItemType.TODO -> vm.push(Screen.EditTodo(-1))
                    PlanItemType.COUNTDOWN -> vm.push(Screen.EditCountdown(-1))
                }
            },
        )
    }
}

@Composable
private fun FocusCard(
    focus: TodayLogic.FocusSchedule?,
    today: LocalDate,
    now: LocalTime,
    scheduleCount: Int,
    dueTodayCount: Int,
    overdueCount: Int,
    onOpen: (ScheduleEntity) -> Unit,
    onAddSchedule: () -> Unit,
    onOpenTodos: () -> Unit,
) {
    val hasPendingTodos = overdueCount > 0 || dueTodayCount > 0
    val summary = if (scheduleCount == 0) {
        when {
            overdueCount > 0 -> "逾期 $overdueCount 项待办"
            dueTodayCount > 0 -> "$dueTodayCount 项待办今日截止"
            else -> "今天还没有安排"
        }
    } else {
        buildString {
            append("$scheduleCount 段安排")
            append(" · ")
            if (dueTodayCount == 0) append("没有今日截止待办")
            else append("$dueTodayCount 项待办今日截止")
            if (overdueCount > 0) append(" · 逾期 $overdueCount 项")
        }
    }
    val shape = RoundedCornerShape(28.dp)
    val primary = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(shape)
            .background(Brush.linearGradient(focusGradient()))
            .border(1.dp, Color.White.copy(alpha = 0.6f), shape),
    ) {
        // 与专注页同款的装饰圆环，半透明不抢正文
        Canvas(Modifier.matchParentSize()) {
            val center = Offset(size.width * 0.93f, size.height * 0.48f)
            val radius = size.width * 0.4f
            drawCircle(
                color = primary.copy(alpha = 0.15f),
                radius = radius,
                center = center,
                style = Stroke(width = 1.5.dp.toPx()),
            )
            drawArc(
                color = primary.copy(alpha = 0.22f),
                startAngle = -110f,
                sweepAngle = 250f,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round),
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.22f),
                radius = radius * 0.62f,
                center = Offset(size.width * 0.04f, size.height * 0.98f),
            )
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "接下来",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = primary,
                )
                Spacer(Modifier.weight(1f))
                Surface(
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.5f),
                ) {
                    Text(
                        "$scheduleCount 段安排 · ${dueTodayCount + overdueCount} 待办",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            if (focus == null) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "今天",
                        fontSize = 34.sp,
                        lineHeight = 38.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "时间由你安排",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            } else {
                val s = focus.schedule
                val nowMin = now.hour * 60 + now.minute
                val remaining = (focus.endMinute - nowMin).coerceAtLeast(0)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        if (s.allDay) "全天" else s.startTime ?: "--:--",
                        fontSize = 32.sp,
                        lineHeight = 36.sp,
                        fontWeight = FontWeight.Bold,
                        color = primary,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            s.title,
                            fontSize = 18.sp,
                            lineHeight = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { onOpen(s) },
                        )
                        Text(
                            buildString {
                                if (s.allDay) append("今天全天")
                                else append("${s.startTime ?: ""}–${s.endTime ?: ""}")
                                if (focus.ongoing) append(" · 进行中")
                                else if (!s.allDay) append(" · 还剩 ${durationText(remaining)}")
                            },
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    summary,
                    fontSize = 12.sp,
                    color = if (overdueCount > 0) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(
                    Modifier
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.62f))
                        .clickable { if (hasPendingTodos) onOpenTodos() else onAddSchedule() }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(
                        if (hasPendingTodos) "查看待办" else "安排一段时间",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

private fun durationText(minutes: Int): String = when {
    minutes <= 0 -> "不到 1 分钟"
    minutes < 60 -> "$minutes 分钟"
    minutes % 60 == 0 -> "${minutes / 60} 小时"
    else -> "${minutes / 60} 小时 ${minutes % 60} 分"
}

private fun todoSubtitle(dueToday: Int, overdue: Int, future: Int): String = when {
    dueToday == 0 && overdue == 0 && future > 0 -> "未来 $future 项"
    dueToday == 0 && overdue == 0 -> "今天没有到期待办"
    overdue > 0 && dueToday > 0 -> "今日 $dueToday 项 · 逾期 $overdue 项"
    overdue > 0 -> "逾期 $overdue 项"
    else -> "今日截止 $dueToday 项"
}

@Composable
private fun GroupHeading(text: String, count: Int, color: androidx.compose.ui.graphics.Color) {
    Text(
        "$text · $count",
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = color,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp, start = 4.dp),
    )
}

@Composable
private fun UpcomingCard(
    days: List<UpcomingDay>,
    today: LocalDate,
    onClick: (UpcomingEntry) -> Unit,
) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            days.forEachIndexed { index, day ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = if (index == 0) 0.dp else 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        upcomingDayLabel(day.date, today),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${day.totalCount} 项",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                day.entries.forEach { entry ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onClick(entry) }
                            .padding(horizontal = 6.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(
                                    if (entry.kind == UpcomingKind.SCHEDULE) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        todoMarkColor()
                                    },
                                ),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            entry.timeLabel,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            modifier = Modifier.width(96.dp),
                        )
                        Text(
                            entry.title,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (entry.kind == UpcomingKind.TODO && entry.priority == 1) {
                            Text("重要", fontSize = 10.sp, color = countdownMarkColor())
                        }
                    }
                }
                if (day.hiddenCount > 0) {
                    Text(
                        "另有 ${day.hiddenCount} 项，点“月历”查看",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 6.dp, bottom = 2.dp),
                    )
                }
                if (index != days.lastIndex) {
                    HorizontalDivider(
                        Modifier.padding(vertical = 6.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                }
            }
        }
    }
}

private fun upcomingDayLabel(date: LocalDate, today: LocalDate): String {
    val offset = ChronoUnit.DAYS.between(today, date)
    val prefix = when (offset) {
        1L -> "明天"
        2L -> "后天"
        else -> "${date.monthValue}月${date.dayOfMonth}日"
    }
    val weekday = listOf("一", "二", "三", "四", "五", "六", "日")[
        (date.dayOfWeek.value + 6) % 7
    ]
    return "$prefix · 周$weekday"
}
