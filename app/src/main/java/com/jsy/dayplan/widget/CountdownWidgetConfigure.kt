package com.jsy.dayplan.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.jsy.dayplan.data.CountdownEntity
import com.jsy.dayplan.ui.DayPlanTheme
import com.jsy.dayplan.ui.MainActivity
import com.jsy.dayplan.vm.ThemeMode
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 绑定配置页：后台读取倒数日；选择后写入绑定并刷新；取消不留绑定。 */
@OptIn(ExperimentalMaterial3Api::class)
class CountdownWidgetConfigure : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            setResult(RESULT_CANCELED); finish(); return
        }

        setResult(RESULT_CANCELED)
        val db = com.jsy.dayplan.data.PlanDb.get(this)

        setContent {
            DayPlanTheme(mode = ThemeMode.SYSTEM) {
                var loading by remember { mutableStateOf(true) }
                var countdowns by remember { mutableStateOf<List<CountdownEntity>>(emptyList()) }
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    withContext(Dispatchers.IO) { db.dao().allCountdowns() }
                        .let {
                            countdowns = it
                            loading = false
                        }
                }

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("选择要显示的倒数日") },
                            actions = {
                                TextButton(onClick = { finish() }) { Text("取消") }
                            },
                        )
                    },
                ) { pad ->
                    when {
                        loading -> Column(
                            Modifier.padding(pad).fillMaxSize().padding(20.dp),
                        ) {
                            CircularProgressIndicator()
                        }
                        countdowns.isEmpty() -> EmptyState(
                            Modifier.padding(pad),
                            onAdd = {
                                startActivity(MainActivity.openAddIntent(this, "countdown"))
                                finish()
                            },
                        )
                        else -> LazyColumn(Modifier.padding(pad)) {
                            items(countdowns.size) { i ->
                                val c = countdowns[i]
                                Column(
                                    Modifier.fillMaxWidth().clickable {
                                        WidgetPrefs.bindCountdown(this@CountdownWidgetConfigure, appWidgetId, c.id)
                                        lifecycleScope.launch {
                                            withContext(Dispatchers.IO) {
                                                CountdownWidgetProvider.pushUpdate(
                                                    this@CountdownWidgetConfigure,
                                                    AppWidgetManager.getInstance(this@CountdownWidgetConfigure),
                                                    appWidgetId, db, LocalDate.now(),
                                                )
                                            }
                                            val out = Intent().apply {
                                                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                                            }
                                            setResult(RESULT_OK, out)
                                            finish()
                                        }
                                    }.padding(16.dp),
                                ) {
                                    Text(c.title, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        c.targetDate,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun EmptyState(modifier: Modifier, onAdd: () -> Unit) {
        Column(modifier.fillMaxSize().padding(20.dp)) {
            Text("还没有倒数日", style = MaterialTheme.typography.titleMedium)
            Text(
                "先添加一条重要日子，再回到桌面选择它。",
                Modifier.padding(top = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onAdd, modifier = Modifier.padding(top = 16.dp)) {
                Text("去新增倒数日")
            }
        }
    }
}
