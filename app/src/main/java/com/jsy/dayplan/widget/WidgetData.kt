package com.jsy.dayplan.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import com.jsy.dayplan.core.CountdownLogic
import com.jsy.dayplan.core.TodayLogic
import com.jsy.dayplan.data.PlanDb
import java.time.LocalDate

/** 小组件绑定配置（appWidgetId 属于当前设备，不写入业务备份）。 */
object WidgetPrefs {
    private const val PREFS = "widget_bindings"

    fun bindCountdown(context: Context, appWidgetId: Int, countdownId: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong("cd_$appWidgetId", countdownId).apply()
    }

    fun getCountdownBinding(context: Context, appWidgetId: Int): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong("cd_$appWidgetId", -1L)

    fun removeCountdown(context: Context, appWidgetId: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove("cd_$appWidgetId").apply()
    }
}

/**
 * 小组件数据快照：在后台线程从 Room 读取并计算，
 * 供 RemoteViews 填充。日期与天数口径与 App 页面一致（复用 CountdownLogic / TodayLogic）。
 */
data class CountdownWidgetData(
    val countdownId: Long,
    val title: String,
    val targetDate: LocalDate,
    val days: Long,
    val displayText: String,
    val deleted: Boolean = false,
)

data class TodayWidgetData(
    val dateText: String,
    val scheduleCount: Int,
    val dueTodayCount: Int,
    val overdueCount: Int,
    val topLines: List<TopLine>,
) {
    data class TopLine(
        val time: String,
        val title: String,
        val kind: String,
        val entityType: String,
        val entityId: Long,
    )
}

object WidgetDataLoader {

    suspend fun loadCountdown(
        db: PlanDb,
        countdownId: Long,
        today: LocalDate,
    ): CountdownWidgetData {
        if (countdownId <= 0) {
            return CountdownWidgetData(countdownId, "", today, 0, "", deleted = true)
        }
        val c = db.dao().countdownById(countdownId)
            ?: return CountdownWidgetData(countdownId, "", today, 0, "", deleted = true)
        val target = LocalDate.parse(c.targetDate)
        val occ = CountdownLogic.nextOccurrence(target, today, c.yearlyRepeat)
        val days = CountdownLogic.daysBetween(today, occ)
        return CountdownWidgetData(
            countdownId = c.id,
            title = c.title,
            targetDate = occ,
            days = days,
            displayText = CountdownLogic.displayText(days),
        )
    }

    /** 今日数据：总数与展示行分开计算；待办分组/排序复用 TodayLogic。 */
    suspend fun loadToday(db: PlanDb, today: LocalDate): TodayWidgetData {
        val schedules = db.dao().allSchedules()
        val todos = db.dao().allTodos()

        val todaySchedules = TodayLogic.schedulesForToday(schedules, today)
        val groups = TodayLogic.todoGroups(todos, today)

        val scheduleLines = todaySchedules.take(3).map { e ->
            TodayWidgetData.TopLine(
                time = if (e.allDay) "全天" else (e.startTime ?: ""),
                title = e.title,
                kind = "日程",
                entityType = "schedule",
                entityId = e.id,
            )
        }

        val top = scheduleLines.toMutableList()
        groups.dueToday.take((3 - top.size).coerceAtLeast(0)).forEach { t ->
            top.add(
                TodayWidgetData.TopLine(
                    time = t.dueTime ?: "全天",
                    title = t.title,
                    kind = "今日待办",
                    entityType = "todo",
                    entityId = t.id,
                ),
            )
        }
        groups.overdue.take((3 - top.size).coerceAtLeast(0)).forEach { t ->
            top.add(
                TodayWidgetData.TopLine(
                    time = t.dueTime ?: "逾期",
                    title = t.title,
                    kind = "逾期",
                    entityType = "todo",
                    entityId = t.id,
                ),
            )
        }

        return TodayWidgetData(
            dateText = "%d月%d日".format(today.monthValue, today.dayOfMonth),
            scheduleCount = todaySchedules.size,
            dueTodayCount = groups.dueToday.size,
            overdueCount = groups.overdue.size,
            topLines = top,
        )
    }
}

/** 统一刷新入口：保存/删除/完成/日期变化/回前台时调用（挂起，不阻塞主线程）。 */
object PlanWidgetUpdater {
    suspend fun updateAll(context: Context, db: PlanDb, today: LocalDate) {
        val mgr = AppWidgetManager.getInstance(context)
        val cdProvider = ComponentName(context, CountdownWidgetProvider::class.java)
        mgr.getAppWidgetIds(cdProvider).forEach { id ->
            CountdownWidgetProvider.pushUpdate(context, mgr, id, db, today)
        }
        val todayProvider = ComponentName(context, TodayWidgetProvider::class.java)
        mgr.getAppWidgetIds(todayProvider).forEach { id ->
            TodayWidgetProvider.pushUpdate(context, mgr, id, db, today)
        }
    }
}
