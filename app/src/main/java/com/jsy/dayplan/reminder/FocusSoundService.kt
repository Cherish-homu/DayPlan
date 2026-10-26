package com.jsy.dayplan.reminder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import com.jsy.dayplan.DayPlanApp
import com.jsy.dayplan.core.FocusNoise
import com.jsy.dayplan.core.FocusPreferences
import com.jsy.dayplan.core.FocusSound
import com.jsy.dayplan.ui.MainActivity
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 用户选择后才启动的离线音效；前台媒体服务允许专注期间锁屏播放。
 * 合成在独立线程进行，代次（generation）隔离旧播放线程，停止时释放 AudioTrack 与音频焦点。
 */
class FocusSoundService : Service() {
    private val generation = AtomicInteger()
    @Volatile private var track: AudioTrack? = null
    @Volatile private var worker: Thread? = null
    private var focusRequest: AudioFocusRequest? = null
    private val manager by lazy { getSystemService(AudioManager::class.java) }
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            // 通知栏“关闭声音”是用户明确的关闭动作：同时关闭偏好，避免暂停/继续后自动恢复。
            runCatching {
                val prefs = FocusPreferences(this)
                prefs.save(prefs.load().copy(sound = FocusSound.OFF))
            }
            clearError()
            stopSelf()
            return START_NOT_STICKY
        }
        val app = application as DayPlanApp
        val state = app.loadTimerState()
        val config = FocusPreferences(this).load()
        if (!state.running || state.paused || config.sound == FocusSound.OFF) {
            stopSelf()
            return START_NOT_STICKY
        }

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "专注背景音", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
        val open = PendingIntent.getActivity(
            this, 1741, MainActivity.openFocusIntent(this, ""),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1742,
            Intent(this, FocusSoundService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("专注中 · ${config.sound.title}")
            .setContentText("点击回到专注，或关闭背景音")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "关闭声音", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        stopPlayback()
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        error.value = "背景音被其他音频打断，可回到专注页重新开启"
                        mainHandler.post { stopSelf() }
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                        track?.setVolume(config.volume * 0.2f)
                    AudioManager.AUDIOFOCUS_GAIN ->
                        track?.setVolume(config.volume)
                }
            }
            .build()
        focusRequest = request
        if (manager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            error.value = "其他应用正在使用音频，背景音暂未播放"
            stopSelf()
            return START_NOT_STICKY
        }
        clearError()
        val current = generation.get()
        worker = Thread({
            var audio: AudioTrack? = null
            try {
                val min = AudioTrack.getMinBufferSize(
                    RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                )
                require(min > 0) { "设备不支持该音频格式" }
                val output = AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build(),
                    )
                    .setBufferSizeInBytes(maxOf(min * 2, 8192))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                audio = output
                check(output.state == AudioTrack.STATE_INITIALIZED) { "音频输出初始化失败" }
                if (generation.get() != current) return@Thread
                track = output
                output.setVolume(config.volume)
                output.play()
                playing.value = config.sound
                val noise = FocusNoise(config.sound, System.nanoTime().toInt())
                val buffer = ShortArray(1024)
                while (generation.get() == current && !Thread.currentThread().isInterrupted) {
                    noise.fill(buffer, RATE)
                    var written = 0
                    while (written < buffer.size && generation.get() == current) {
                        val n = output.write(buffer, written, buffer.size - written, AudioTrack.WRITE_BLOCKING)
                        check(n > 0) { "音频输出不可用" }
                        written += n
                    }
                }
            } catch (e: Exception) {
                if (generation.get() == current) {
                    error.value = "背景音播放失败，可重新选择音效"
                    mainHandler.post { stopSelf() }
                }
            } finally {
                try { audio?.pause() } catch (_: Exception) { }
                try { audio?.flush() } catch (_: Exception) { }
                try { audio?.release() } catch (_: Exception) { }
                if (generation.get() == current) {
                    track = null
                    playing.value = FocusSound.OFF
                }
            }
        }, "DayPlan-focus-audio").apply { isDaemon = true; start() }
        return START_NOT_STICKY
    }

    private fun stopPlayback() {
        generation.incrementAndGet()
        worker?.interrupt()
        worker = null
        try { track?.pause() } catch (_: Exception) { }
        track = null
        focusRequest?.let { manager.abandonAudioFocusRequest(it) }
        focusRequest = null
        playing.value = FocusSound.OFF
    }

    override fun onDestroy() {
        stopPlayback()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) { stopSelf() }

    companion object {
        private const val CHANNEL = "dayplan_focus_sound"
        private const val NOTIFICATION_ID = 1740
        private const val STOP = "com.jsy.dayplan.STOP_FOCUS_SOUND"
        private const val RATE = 22050
        val playing = MutableStateFlow(FocusSound.OFF)
        val error = MutableStateFlow<String?>(null)
        fun clearError() { error.value = null }
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, FocusSoundService::class.java))
            true
        } catch (_: Exception) {
            error.value = "系统暂不允许播放背景音，请回到专注页重试"
            false
        }
        fun stop(context: Context) {
            try { context.stopService(Intent(context, FocusSoundService::class.java)) } catch (_: Exception) { }
        }
    }
}
