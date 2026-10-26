package com.jsy.dayplan.reminder

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.WindowCompat
import com.jsy.dayplan.R
import com.jsy.dayplan.ui.DayPlanTheme
import com.jsy.dayplan.ui.GlassCard
import com.jsy.dayplan.ui.MacaronBackdrop
import com.jsy.dayplan.ui.MainActivity
import com.jsy.dayplan.ui.isDayPlanDark
import com.jsy.dayplan.vm.ThemeMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private data class AlarmInfo(
    val title: String,
    val body: String,
    val entityType: String,
    val entityId: Long,
    val notificationId: Int,
    val plannedAt: Long,
)

/**
 * 到点全屏提醒：锁屏/未打开应用时由通知的 fullScreenIntent 拉起，
 * 显示大肥鱼、提醒内容、闹钟音乐与“关闭闹钟”。
 */
class ReminderAlarmActivity : ComponentActivity() {
    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var info by mutableStateOf(AlarmInfo("", "", "", 0, 0, 0))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onBackPressedDispatcher.addCallback(this) { finishAlarm() }
        info = fromIntent(intent)
        acquireWakeLock()
        startAlarmSound()
        val themeMode = runCatching {
            ThemeMode.valueOf(
                getSharedPreferences("prefs", MODE_PRIVATE).getString("theme", "SYSTEM") ?: "SYSTEM",
            )
        }.getOrDefault(ThemeMode.SYSTEM)
        setContent {
            DayPlanTheme(mode = themeMode) {
                val dark = isDayPlanDark()
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
                AlarmPopup(
                    info = info,
                    onClose = { finishAlarm() },
                    onOpen = { openDetail() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        info = fromIntent(intent)
    }

    override fun onDestroy() {
        stopAlarmSound()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun fromIntent(intent: Intent?): AlarmInfo {
        if (intent == null) {
            return AlarmInfo("该做下一件事了", "", "", 0, 0, System.currentTimeMillis())
        }
        return AlarmInfo(
            title = intent.getStringExtra(EXTRA_TITLE) ?: "该做下一件事了",
            body = intent.getStringExtra(EXTRA_BODY) ?: "",
            entityType = intent.getStringExtra(EXTRA_ENTITY_TYPE) ?: "",
            entityId = intent.getLongExtra(EXTRA_ENTITY_ID, 0),
            notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0),
            plannedAt = intent.getLongExtra(EXTRA_PLANNED_AT, System.currentTimeMillis()),
        )
    }

    private fun finishAlarm() {
        NotificationManagerCompat.from(this).cancel(info.notificationId)
        finish()
    }

    private fun openDetail() {
        NotificationManagerCompat.from(this).cancel(info.notificationId)
        if (info.entityType.isNotBlank() && info.entityId > 0) {
            startActivity(MainActivity.openEntityIntent(this, info.entityType, info.entityId))
        }
        finish()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DayPlan:reminder-alarm").apply {
            setReferenceCounted(false)
            acquire(10 * 60 * 1000L)
        }
    }

    private fun startAlarmSound() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val am = getSystemService(AudioManager::class.java)
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attrs)
            .build()
            .also { am.requestAudioFocus(it) }
        val prefs = ReminderAlarmPrefs(this)
        val candidates = buildList {
            prefs.soundUri?.let { raw -> runCatching { Uri.parse(raw) }.getOrNull()?.let(::add) }
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)?.let(::add)
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)?.let(::add)
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)?.let(::add)
        }.distinct()
        for (uri in candidates) {
            val p = MediaPlayer()
            try {
                p.setAudioAttributes(attrs)
                p.setDataSource(this, uri)
                p.isLooping = true
                p.prepare()
                p.start()
                player = p
                return
            } catch (e: Exception) {
                p.release()
            }
        }
    }

    private fun stopAlarmSound() {
        try { player?.stop() } catch (_: Exception) { }
        player?.release()
        player = null
        val am = getSystemService(AudioManager::class.java)
        focusRequest?.let { am.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    companion object {
        private const val EXTRA_TITLE = "alarm_title"
        private const val EXTRA_BODY = "alarm_body"
        private const val EXTRA_ENTITY_TYPE = "alarm_entity_type"
        private const val EXTRA_ENTITY_ID = "alarm_entity_id"
        private const val EXTRA_NOTIFICATION_ID = "alarm_notification_id"
        private const val EXTRA_PLANNED_AT = "alarm_planned_at"

        /** 设置页“预览”用：不依赖真实提醒，直接展示弹窗与当前闹钟音乐。 */
        fun previewIntent(context: Context): Intent =
            Intent(context, ReminderAlarmActivity::class.java).apply {
                putExtra(EXTRA_TITLE, "大肥鱼提醒测试")
                putExtra(EXTRA_BODY, "这是全屏提醒预览；点“关闭闹钟”结束。")
                putExtra(EXTRA_ENTITY_TYPE, "")
                putExtra(EXTRA_ENTITY_ID, 0L)
                putExtra(EXTRA_NOTIFICATION_ID, 0)
                putExtra(EXTRA_PLANNED_AT, System.currentTimeMillis())
            }

        fun intent(context: Context, fired: ReminderCoordinator.FiredInfo): Intent =
            Intent(context, ReminderAlarmActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_TITLE, fired.title)
                putExtra(EXTRA_BODY, fired.body)
                putExtra(EXTRA_ENTITY_TYPE, fired.entityType)
                putExtra(EXTRA_ENTITY_ID, fired.entityId)
                putExtra(EXTRA_NOTIFICATION_ID, fired.key.hashCode())
                putExtra(EXTRA_PLANNED_AT, fired.plannedAtEpochMs)
            }
    }
}

@Composable
private fun AlarmPopup(info: AlarmInfo, onClose: () -> Unit, onOpen: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        MacaronBackdrop()
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.chan_portrait),
                    contentDescription = "大肥鱼",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth(0.62f).fillMaxHeight(0.92f),
                )
            }
            Text(
                "大肥鱼提醒你",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
            )
            Spacer(Modifier.height(6.dp))
            GlassCard(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        info.title.ifBlank { "该做下一件事了" },
                        fontSize = 22.sp,
                        lineHeight = 28.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (info.body.isNotBlank()) {
                        Text(
                            info.body,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        formatAlarmTime(info.plannedAt),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onClose,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(18.dp),
            ) {
                Text("关闭闹钟", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
            TextButton(onClick = onOpen) { Text("查看详情") }
            Spacer(Modifier.height(8.dp))
        }
    }
}

private fun formatAlarmTime(epochMs: Long): String =
    Instant.ofEpochMilli(if (epochMs > 0) epochMs else System.currentTimeMillis())
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))
