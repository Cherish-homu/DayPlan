package com.jsy.dayplan.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.jsy.dayplan.vm.AiViewModel
import com.jsy.dayplan.vm.PlanViewModel

class MainActivity : ComponentActivity() {

    private val vm: PlanViewModel by viewModels()
    private val aiVm: AiViewModel by viewModels()

    companion object {
        const val EXTRA_OPEN_TODAY = "open_today"
        const val EXTRA_OPEN_ADD = "open_add"
        const val EXTRA_OPEN_ENTITY_TYPE = "open_entity_type"
        const val EXTRA_OPEN_ENTITY_ID = "open_entity_id"
        const val EXTRA_OPEN_FOCUS = "open_focus"

        /** 通知/小组件点击：打开具体条目（冷启动与已有 Activity 都走同一入口）。 */
        fun openEntityIntent(context: Context, type: String, id: Long): Intent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_OPEN_ENTITY_TYPE, type)
                putExtra(EXTRA_OPEN_ENTITY_ID, id)
            }

        fun openTodayIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_OPEN_TODAY, true)
            }

        fun openAddIntent(context: Context, what: String): Intent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_OPEN_ADD, what)
            }

        fun openFocusIntent(context: Context, token: String): Intent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_OPEN_FOCUS, true)
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm.handleIntent(intent)
        setContent {
            val themeMode by vm.themeMode.collectAsState()
            DayPlanTheme(mode = themeMode) {
                AppRoot(vm, aiVm, themeMode)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        vm.handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        // 回前台：刷新日期、刷新组件、可重试地重新协调提醒
        vm.onForeground()
    }
}
