package com.jsy.dayplan.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
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
import com.jsy.dayplan.core.CountdownCategory
import com.jsy.dayplan.core.CountdownLogic
import com.jsy.dayplan.data.CountdownEntity
import com.jsy.dayplan.vm.PlanViewModel
import java.time.LocalDate

/** 倒数日完整列表：置顶优先、临近优先、已过去区按最近过去；可按类别过滤。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CountdownsScreen(vm: PlanViewModel) {
    val countdowns by vm.countdowns.collectAsState()
    val today by vm.today.collectAsState()
    var filter by remember { mutableStateOf<Int?>(null) }

    val enriched = countdowns.map { c ->
        val occ = CountdownLogic.nextOccurrence(LocalDate.parse(c.targetDate), today, c.yearlyRepeat)
        val days = CountdownLogic.daysBetween(today, occ)
        Triple(c, occ, days)
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("倒数日") })
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { vm.push(com.jsy.dayplan.vm.Screen.EditCountdown(-1)) },
                text = { Text("新增") },
                icon = { Icon(Icons.Filled.Add, null) },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(
                    selected = filter == null, onClick = { filter = null },
                    label = { Text("全部") },
                )
                CountdownCategory.NAMES.forEachIndexed { i, n ->
                    FilterChip(
                        selected = filter == i, onClick = { filter = i },
                        label = { Text(n) },
                    )
                }
            }

            val visible = enriched.filter { filter == null || it.first.category == filter }
            val upcoming = visible
                .filter { !CountdownLogic.isPast(LocalDate.parse(it.first.targetDate), today, it.first.yearlyRepeat) }
                .sortedWith(compareByDescending<Triple<CountdownEntity, LocalDate, Long>> { it.first.pinned }
                    .thenBy { it.second })
            val past = visible
                .filter { CountdownLogic.isPast(LocalDate.parse(it.first.targetDate), today, it.first.yearlyRepeat) }
                .sortedByDescending { it.second }

            LazyColumn(Modifier.fillMaxSize()) {
                if (upcoming.isEmpty() && past.isEmpty()) {
                    item {
                        Card(Modifier.fillMaxWidth().padding(12.dp)) {
                            Column(Modifier.padding(14.dp)) {
                                Text("还没有倒数日", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick = {
                                    vm.push(com.jsy.dayplan.vm.Screen.EditCountdown(-1))
                                }) { Text("新增一条") }
                            }
                        }
                    }
                }
                upcoming.forEach { (c, occ, days) ->
                    item(key = "u${c.id}") {
                        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp)
                            .clickable { vm.push(com.jsy.dayplan.vm.Screen.EditCountdown(c.id)) }) {
                            Column(Modifier.padding(12.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            (if (c.pinned) "📌 " else "") + c.title,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Text(
                                            "${CountdownCategory.NAMES[c.category]} · ${occ}" +
                                                (if (c.yearlyRepeat) " · 每年" else ""),
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Text(
                                        CountdownLogic.displayText(days),
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
                if (past.isNotEmpty()) {
                    item { Text("已过去", Modifier.padding(12.dp), fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    past.forEach { (c, occ, days) ->
                        item(key = "p${c.id}") {
                            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp)
                                .clickable { vm.push(com.jsy.dayplan.vm.Screen.EditCountdown(c.id)) }) {
                                Column(Modifier.padding(12.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Column {
                                            Text(c.title)
                                            Text(
                                                "原定 ${c.targetDate}" +
                                                    (if (c.yearlyRepeat) "（每年，显示下一周年）" else ""),
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Text(
                                            CountdownLogic.displayText(days),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                item { androidx.compose.foundation.layout.Spacer(Modifier.padding(80.dp)) }
            }
        }
    }
}
