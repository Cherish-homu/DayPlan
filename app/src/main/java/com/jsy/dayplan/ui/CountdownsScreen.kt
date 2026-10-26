package com.jsy.dayplan.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jsy.dayplan.core.CountdownCategory
import com.jsy.dayplan.core.CountdownLogic
import com.jsy.dayplan.data.CountdownEntity
import com.jsy.dayplan.vm.PlanViewModel
import java.time.LocalDate

/** 倒数日完整列表：置顶优先、临近优先；筛选改为单行横向滚动，避免“其他”拆成两行。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CountdownsScreen(vm: PlanViewModel) {
    val countdowns by vm.countdowns.collectAsState()
    val today by vm.today.collectAsState()
    var filter by remember { mutableStateOf<Int?>(null) }
    val pagePadding = pageHorizontalPadding()

    val enriched = countdowns.map { c ->
        val occ = CountdownLogic.nextOccurrence(LocalDate.parse(c.targetDate), today, c.yearlyRepeat)
        val days = CountdownLogic.daysBetween(today, occ)
        Triple(c, occ, days)
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            PageTopBar(
                title = "倒数日",
                subtitle = "期待或纪念的日子",
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { vm.push(com.jsy.dayplan.vm.Screen.EditCountdown(-1)) },
                text = { Text("新增倒数日") },
                icon = { Icon(Icons.Filled.Add, null) },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = pagePadding, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = filter == null, onClick = { filter = null },
                    label = { Text("全部", maxLines = 1, softWrap = false) },
                )
                CountdownCategory.NAMES.forEachIndexed { i, n ->
                    FilterChip(
                        selected = filter == i, onClick = { filter = i },
                        label = { Text(n, maxLines = 1, softWrap = false) },
                    )
                }
            }

            val visible = enriched.filter { filter == null || it.first.category == filter }
            val upcoming = visible
                .filter {
                    !CountdownLogic.isPast(
                        LocalDate.parse(it.first.targetDate), today, it.first.yearlyRepeat,
                    )
                }
                .sortedWith(
                    compareByDescending<Triple<CountdownEntity, LocalDate, Long>> { it.first.pinned }
                        .thenBy { it.second },
                )
            val past = visible
                .filter {
                    CountdownLogic.isPast(
                        LocalDate.parse(it.first.targetDate), today, it.first.yearlyRepeat,
                    )
                }
                .sortedByDescending { it.second }

            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = pagePadding,
                    end = pagePadding,
                    bottom = 96.dp,
                ),
            ) {
                if (upcoming.isEmpty() && past.isEmpty()) {
                    item {
                        EmptyHintCard(
                            if (countdowns.isEmpty()) {
                                "还没有倒数日。倒数日用来期待或纪念某一天。"
                            } else {
                                "这个类别下还没有倒数日。"
                            },
                            actionLabel = "新增倒数日",
                            onAction = { vm.push(com.jsy.dayplan.vm.Screen.EditCountdown(-1)) },
                        )
                    }
                }
                if (upcoming.isNotEmpty()) {
                    item {
                        SectionHeader(title = "即将到来")
                    }
                    items(upcoming, key = { "u${it.first.id}" }) { (c, occ, days) ->
                        CountdownRow(c, occ, days, today) {
                            vm.push(com.jsy.dayplan.vm.Screen.EditCountdown(c.id))
                        }
                    }
                }
                if (past.isNotEmpty()) {
                    item {
                        Text(
                            "已过去 · ${past.size}",
                            Modifier.padding(top = 24.dp, bottom = 6.dp),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(past, key = { "p${it.first.id}" }) { (c, occ, days) ->
                        CountdownRow(c, occ, days, today, past = true) {
                            vm.push(com.jsy.dayplan.vm.Screen.EditCountdown(c.id))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CountdownRow(
    c: CountdownEntity,
    occ: LocalDate,
    days: Long,
    today: LocalDate,
    past: Boolean = false,
    onClick: () -> Unit,
) {
    GlassCard(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CalendarTypeMark(PlanItemType.COUNTDOWN, countdownMarkColor())
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        c.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (c.pinned) {
                        Icon(
                            Icons.Filled.Star,
                            "置顶",
                            Modifier.padding(start = 4.dp),
                            tint = countdownMarkColor(),
                        )
                    }
                }
                Text(
                    "${CountdownCategory.NAMES.getOrElse(c.category) { "其他" }} · " +
                        "${occ.monthValue}月${occ.dayOfMonth}日" +
                        (if (c.yearlyRepeat) " · 每年" else ""),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (past) {
                    Text(
                        "原定 ${c.targetDate}" +
                            (if (c.yearlyRepeat) "（每年，显示下一周年）" else ""),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    if (days == 0L) "今天" else if (days > 0) "$days" else "${-days}",
                    fontSize = if (days == 0L) 18.sp else 26.sp,
                    lineHeight = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (past) MaterialTheme.colorScheme.onSurfaceVariant
                    else countdownMarkColor(),
                )
                if (days != 0L) {
                    Text(
                        if (days > 0) "天后" else "天前",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
