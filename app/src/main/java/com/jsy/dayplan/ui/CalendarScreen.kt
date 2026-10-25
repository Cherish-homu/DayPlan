package com.jsy.dayplan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jsy.dayplan.core.CalendarLogic
import com.jsy.dayplan.core.CalendarTypeFilter
import com.jsy.dayplan.core.CountdownCategory
import com.jsy.dayplan.core.CountdownLogic
import com.jsy.dayplan.data.CountdownEntity
import com.jsy.dayplan.vm.PlanViewModel
import com.jsy.dayplan.vm.Screen
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

/**
 * 月历：类型筛选同时作用于网格标记、所选日列表和计数；
 * 从所选日期新增日程/待办/倒数日时都继承该日期。
 */
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
    var typeFilter by remember { mutableStateOf(CalendarTypeFilter.ALL) }
    var showAddSheet by remember { mutableStateOf(false) }

    val now by produceState(LocalTime.now(), selected) {
        while (true) {
            value = LocalTime.now()
            delay(30_000)
        }
    }
    val content = CalendarLogic.contentOn(
        schedules, todos, countdowns, selected, typeFilter, searchTitle,
    )
    val unfiltered = CalendarLogic.unfilteredContentOn(
        schedules, todos, countdowns, selected, searchTitle,
    )
    val count = CalendarLogic.countFor(content, typeFilter)
    val pagePadding = pageHorizontalPadding()
    val weekday = listOf("一", "二", "三", "四", "五", "六", "日")[
        (selected.dayOfWeek.value + 6) % 7
    ]
    val dateLabel = "${selected.monthValue}月${selected.dayOfMonth}日"
    val filterLabel = typeFilter.label

    fun openEditor(type: String) {
        when (type) {
            "schedule" -> vm.push(Screen.EditSchedule(-1, selected.toString()))
            "todo" -> vm.push(Screen.EditTodo(-1, selected.toString()))
            "countdown" -> vm.push(Screen.EditCountdown(-1, selected.toString()))
        }
    }

    Scaffold(
        topBar = {
            PageTopBar(
                title = "月历",
                subtitle = month.format(DateTimeFormatter.ofPattern("yyyy年M月")),
                subtitleStartPadding = 72.dp,
                navigationIcon = {
                    IconButton(onClick = { month = month.minusMonths(1) }) {
                        Icon(Icons.Filled.ChevronLeft, "上一月")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        month = YearMonth.from(today)
                        vm.selectedCalendarDate.value = today
                    }) { Text("今天") }
                    IconButton(onClick = { month = month.plusMonths(1) }) {
                        Icon(Icons.Filled.ChevronRight, "下一月")
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = pagePadding),
        ) {
            OutlinedTextField(
                value = searchTitle,
                onValueChange = { searchTitle = it },
                placeholder = { Text("搜索标题", fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Filled.Search, null, Modifier.size(18.dp)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CalendarTypeFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = typeFilter == filter,
                        onClick = { typeFilter = filter },
                        label = {
                            Text(
                                filter.label,
                                maxLines = 1,
                                softWrap = false,
                                fontSize = 13.sp,
                            )
                        },
                    )
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                listOf("一", "二", "三", "四", "五", "六", "日").forEach {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            val firstDow = (month.atDay(1).dayOfWeek.value + 6) % 7
            val daysInMonth = month.lengthOfMonth()
            val rows = (firstDow + daysInMonth + 6) / 7
            for (r in 0 until rows) {
                Row(Modifier.fillMaxWidth().height(48.dp)) {
                    for (c in 0 until 7) {
                        val idx = r * 7 + c
                        val dayNum = idx - firstDow + 1
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            if (dayNum in 1..daysInMonth) {
                                val d = month.atDay(dayNum)
                                val markers = CalendarLogic.markers(
                                    schedules, todos, countdowns, d, typeFilter, searchTitle,
                                )
                                val markerContent = CalendarLogic.contentOn(
                                    schedules, todos, countdowns, d, CalendarTypeFilter.TODO, searchTitle,
                                )
                                val todoDone = markerContent.todos.isNotEmpty() &&
                                    markerContent.todos.all { it.done }
                                val isSel = d == selected
                                val isToday = d == today
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .clip(MaterialTheme.shapes.small)
                                        .clickable { vm.selectedCalendarDate.value = d }
                                        .padding(vertical = 2.dp),
                                ) {
                                    Box(
                                        Modifier
                                            .size(30.dp)
                                            .clip(CircleShape)
                                            .background(if (isSel) MaterialTheme.colorScheme.primary else Color.Transparent)
                                            .then(
                                                if (isToday && !isSel) {
                                                    Modifier.border(
                                                        1.2.dp,
                                                        MaterialTheme.colorScheme.primary,
                                                        CircleShape,
                                                    )
                                                } else Modifier,
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            dayNum.toString(),
                                            fontSize = 14.sp,
                                            fontWeight = if (isToday || isSel) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSel) MaterialTheme.colorScheme.onPrimary
                                            else MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                    Row(
                                        Modifier.height(6.dp).padding(top = 1.dp),
                                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        if (CalendarTypeFilter.SCHEDULE in markers) {
                                            CalendarTypeMark(
                                                PlanItemType.SCHEDULE,
                                                if (isSel) MaterialTheme.colorScheme.onPrimary
                                                else MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                        if (CalendarTypeFilter.TODO in markers) {
                                            CalendarTypeMark(
                                                PlanItemType.TODO,
                                                if (isSel) MaterialTheme.colorScheme.onPrimary
                                                else todoMarkColor(),
                                                filled = todoDone,
                                            )
                                        }
                                        if (CalendarTypeFilter.COUNTDOWN in markers) {
                                            CalendarTypeMark(
                                                PlanItemType.COUNTDOWN,
                                                if (isSel) MaterialTheme.colorScheme.onPrimary
                                                else countdownMarkColor(),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LegendItem(PlanItemType.SCHEDULE, "短线=日程")
                LegendItem(PlanItemType.TODO, "方框=待办")
                LegendItem(PlanItemType.COUNTDOWN, "菱形=倒数日")
            }

            Card(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "$dateLabel 周$weekday",
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text(
                                "$count 项 · " + when {
                                    searchTitle.isNotBlank() -> "搜索“${searchTitle.trim()}”"
                                    typeFilter == CalendarTypeFilter.ALL -> "全部条目"
                                    else -> "只看${typeFilter.label}"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(
                            onClick = {
                                when (typeFilter) {
                                    CalendarTypeFilter.ALL -> showAddSheet = true
                                    CalendarTypeFilter.SCHEDULE -> openEditor("schedule")
                                    CalendarTypeFilter.TODO -> openEditor("todo")
                                    CalendarTypeFilter.COUNTDOWN -> openEditor("countdown")
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) {
                            Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                            Text(
                                if (typeFilter == CalendarTypeFilter.ALL) "新增"
                                else "新增$filterLabel",
                                fontSize = 13.sp,
                            )
                        }
                    }

                    if (content.isEmpty) {
                        val message = when {
                            searchTitle.isNotBlank() ->
                                "$dateLabel 没有匹配“${searchTitle.trim()}”的条目"

                            typeFilter != CalendarTypeFilter.ALL ->
                                "$dateLabel 没有${typeFilter.label}"

                            else -> "$dateLabel 没有安排，点右上角 + 新增"
                        }
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (searchTitle.isNotBlank()) {
                                TextButton(onClick = { searchTitle = "" }) { Text("清除搜索") }
                            }
                            if (typeFilter != CalendarTypeFilter.ALL) {
                                TextButton(onClick = { typeFilter = CalendarTypeFilter.ALL }) {
                                    Text("查看全部类型")
                                }
                            }
                            if (typeFilter == CalendarTypeFilter.ALL && searchTitle.isBlank()) {
                                TextButton(onClick = { openEditor("schedule") }) {
                                    Text("新建日程")
                                }
                                TextButton(onClick = { openEditor("todo") }) {
                                    Text("新建待办")
                                }
                            }
                        }
                    } else {
                        if (content.schedules.isNotEmpty()) {
                            TypeGroupLabel(PlanItemType.SCHEDULE, content.schedules.size)
                            content.schedules.forEach { e ->
                                ScheduleTimelineRow(
                                    schedule = e,
                                    today = selected,
                                    now = if (selected == today) now else null,
                                    onClick = { vm.push(Screen.EditSchedule(e.id, null)) },
                                )
                            }
                        }
                        if (content.todos.isNotEmpty()) {
                            TypeGroupLabel(PlanItemType.TODO, content.todos.size)
                            content.todos.forEach { e ->
                                TodoRow(
                                    todo = e,
                                    today = today,
                                    onToggle = { vm.toggleTodoDone(e) },
                                    onEdit = { vm.push(Screen.EditTodo(e.id)) },
                                )
                            }
                        }
                        if (content.countdowns.isNotEmpty()) {
                            TypeGroupLabel(PlanItemType.COUNTDOWN, content.countdowns.size)
                            content.countdowns.forEach { e ->
                                CalendarCountdownRow(e, today) {
                                    vm.push(Screen.EditCountdown(e.id))
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(80.dp))
        }
    }

    if (showAddSheet) {
        AddItemSheet(
            contextLabel = "为${dateLabel}新建",
            onDismiss = { showAddSheet = false },
            onSelect = { type ->
                showAddSheet = false
                when (type) {
                    PlanItemType.SCHEDULE -> openEditor("schedule")
                    PlanItemType.TODO -> openEditor("todo")
                    PlanItemType.COUNTDOWN -> openEditor("countdown")
                }
            },
        )
    }
}

@Composable
private fun TypeGroupLabel(type: PlanItemType, count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TypeBadge(type)
        Text(
            "$count 项",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LegendItem(type: PlanItemType, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CalendarTypeMark(
            type,
            when (type) {
                PlanItemType.SCHEDULE -> MaterialTheme.colorScheme.primary
                PlanItemType.TODO -> todoMarkColor()
                PlanItemType.COUNTDOWN -> countdownMarkColor()
            },
        )
        Text(
            text,
            Modifier.padding(start = 4.dp),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun CalendarCountdownRow(
    c: CountdownEntity,
    today: LocalDate,
    onClick: () -> Unit,
) {
    val occ = CountdownLogic.nextOccurrence(
        LocalDate.parse(c.targetDate), today, c.yearlyRepeat,
    )
    val days = CountdownLogic.daysBetween(today, occ)
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CalendarTypeMark(PlanItemType.COUNTDOWN, countdownMarkColor())
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(c.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${CountdownCategory.NAMES.getOrElse(c.category) { "其他" }} · " +
                    "${occ.monthValue}月${occ.dayOfMonth}日" + if (c.yearlyRepeat) " · 每年" else "",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            CountdownLogic.displayText(days),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = countdownMarkColor(),
        )
    }
}
