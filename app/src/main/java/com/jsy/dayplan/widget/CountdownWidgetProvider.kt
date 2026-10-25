package com.jsy.dayplan.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.jsy.dayplan.R
import com.jsy.dayplan.data.PlanDb
import com.jsy.dayplan.ui.MainActivity
import java.time.LocalDate
import kotlinx.coroutines.runBlocking

/** 「重要日子」小组件：2x2，可绑定倒数日；多实例互不影响。 */
class CountdownWidgetProvider : AppWidgetProvider() {

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

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { WidgetPrefs.removeCountdown(context, it) }
    }

    companion object {
        suspend fun pushUpdate(
            context: Context,
            mgr: AppWidgetManager,
            appWidgetId: Int,
            db: PlanDb,
            today: LocalDate,
        ) {
            val binding = WidgetPrefs.getCountdownBinding(context, appWidgetId)
            val data = WidgetDataLoader.loadCountdown(db, binding, today)
            val views = RemoteViews(context.packageName, R.layout.widget_countdown)
            if (data.deleted) {
                views.setTextViewText(R.id.wcd_title, "事件已删除，点击重新选择")
                views.setTextViewText(R.id.wcd_target, "")
                views.setTextViewText(R.id.wcd_days, "—")
            } else {
                views.setTextViewText(R.id.wcd_title, data.title)
                views.setTextViewText(R.id.wcd_target, data.targetDate.toString())
                views.setTextViewText(R.id.wcd_days, data.displayText)
            }

            val configure = PendingIntent.getActivity(
                context,
                20_000 + appWidgetId,
                Intent(context, CountdownWidgetConfigure::class.java).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            // 已绑定且事件存在 → 打开该倒数日；未绑定/已删除 → 重新选择
            val rootPi = if (data.deleted) {
                configure
            } else {
                PendingIntent.getActivity(
                    context,
                    10_000 + appWidgetId,
                    MainActivity.openEntityIntent(context, "countdown", data.countdownId),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }
            views.setOnClickPendingIntent(R.id.wcd_root, rootPi)
            views.setOnClickPendingIntent(R.id.wcd_reshoot, configure)
            mgr.updateAppWidget(appWidgetId, views)
        }
    }
}
