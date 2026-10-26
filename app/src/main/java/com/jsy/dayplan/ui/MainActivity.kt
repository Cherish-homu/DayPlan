package com.jsy.dayplan.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import com.jsy.dayplan.vm.AiViewModel
import com.jsy.dayplan.vm.PlanViewModel
import com.jsy.dayplan.vm.Screen
import com.jsy.dayplan.vm.ThemeMode

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
            val stack by vm.stack.collectAsState()
            val dark = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // 专注页是深色照片背景，系统栏也要跟着变深，避免上下出现亮色条
            val focusScreen = stack.lastOrNull() == Screen.Focus
            val darkBars = dark || focusScreen
            // 状态栏/导航栏跟随马卡龙背景首尾色；专注页使用黑色边框
            SideEffect {
                @Suppress("DEPRECATION")
                window.statusBarColor = when {
                    focusScreen -> 0xFF000000.toInt()
                    dark -> 0xFF171324.toInt()
                    else -> 0xFFFFF4F9.toInt()
                }
                @Suppress("DEPRECATION")
                window.navigationBarColor = when {
                    focusScreen -> 0xFF000000.toInt()
                    dark -> 0xFF221C33.toInt()
                    else -> 0xFFEAF7F3.toInt()
                }
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !darkBars
                    isAppearanceLightNavigationBars = !darkBars
                }
            }
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
