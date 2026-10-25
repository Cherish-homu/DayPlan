package com.jsy.dayplan.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import com.jsy.dayplan.R
import com.jsy.dayplan.data.PlanDb
import com.jsy.dayplan.ui.MainActivity
import java.time.LocalDate
import kotlinx.coroutines.runBlocking

/** 「今日安排」小组件：4x2，日期 + 计数 + 最多 3 条安排；每条可点进对应编辑页。 */
class TodayWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        val appContext = context.applicationContext
        Thread {
            try {
                runBlocking {
                    val db = PlanDb.get(appContext)
                    val today = LocalDate.now()
                    appWidgetIds.forEach { id ->
                        pushUpdate(appContext, mgr, id, db, today)
                    }
                }
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        suspend fun pushUpdate(
            context: Context,
            mgr: AppWidgetManager,
            appWidgetId: Int,
            db: PlanDb,
            today: LocalDate,
        ) {
            val data = WidgetDataLoader.loadToday(db, today)
            val views = RemoteViews(context.packageName, R.layout.widget_today)
            views.setTextViewText(R.id.wt_date, data.dateText)
            views.setTextViewText(
                R.id.wt_summary,
                "今天 ${data.scheduleCount} 项日程，${data.dueTodayCount} 项待办" +
                    (if (data.overdueCount > 0) "（逾期 ${data.overdueCount}）" else ""),
            )

            val slots = listOf(
                Triple(R.id.wt_line1, R.id.wt_line1_time, 0),
                Triple(R.id.wt_line2, R.id.wt_line2_time, 1),
                Triple(R.id.wt_line3, R.id.wt_line3_time, 2),
            )
            slots.forEach { (titleId, timeId, _) ->
                views.setViewVisibility(titleId, android.view.View.GONE)
                views.setViewVisibility(timeId, android.view.View.GONE)
            }
            data.topLines.take(3).forEachIndexed { i, line ->
                val (titleId, timeId, idx) = slots[i]
                views.setViewVisibility(titleId, android.view.View.VISIBLE)
                views.setViewVisibility(timeId, android.view.View.VISIBLE)
                views.setTextViewText(titleId, "${line.kind} · ${line.title}")
                views.setTextViewText(timeId, line.time)

                // 每条记录分别设置点击动作，打开对应条目
                val lineIntent = MainActivity.openEntityIntent(
                    context, line.entityType, line.entityId,
                )
                val linePi = PendingIntent.getActivity(
                    context,
                    50_000 + appWidgetId * 10 + idx,
                    lineIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                views.setOnClickPendingIntent(titleId, linePi)
                views.setOnClickPendingIntent(timeId, linePi)
            }
            views.setViewVisibility(
                R.id.wt_empty,
                if (data.topLines.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE,
            )

            // 根区域 → 今日页；+ 新增 → 应用内新增选择（与普通打开不同）
            val openToday = PendingIntent.getActivity(
                context,
                30_000 + appWidgetId,
                MainActivity.openTodayIntent(context),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val addChooser = PendingIntent.getActivity(
                context,
                31_000 + appWidgetId,
                MainActivity.openAddIntent(context, "chooser"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.wt_root, openToday)
            views.setOnClickPendingIntent(R.id.wt_add, addChooser)
            mgr.updateAppWidget(appWidgetId, views)
        }
    }
}
