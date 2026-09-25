package com.example.focus.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.example.focus.R
import com.example.focus.data.db.AppDatabase
import com.example.focus.data.db.FocusSession
import com.example.focus.data.prefs.TimerStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 前台计时服务，正计时的核心。
 *
 * 计时不依赖系统当前时间，用 SystemClock.elapsedRealtime()（开机以来的毫秒数）
 * 计算差值，用户修改系统时间不会影响计时准确性。
 *
 * 状态流转由 ACTION 驱动，总时长公式：
 *   总时长 = 已结算累计(accumulatedMs) + (当前时间 - 当前段起点)
 *
 * 通知中的计时由系统 chronometer 渲染（setUsesChronometer），进程被冻结时仍会继续走时，
 * 因此运行中无需每秒刷新通知，只做低频兜底。
 *
 * 持久化只在状态变化时写入（运行段的起点用 elapsedRealtime，进程被杀后可精确恢复）。
 */
class TimerService : Service() {

    companion object {
        private const val CHANNEL_ID = "focus_timer_v4"
        private const val NOTIFICATION_ID = 1

        const val ACTION_START = "com.example.focus.action.START"
        const val ACTION_PAUSE = "com.example.focus.action.PAUSE"
        const val ACTION_RESUME = "com.example.focus.action.RESUME"
        const val ACTION_STOP = "com.example.focus.action.STOP"
        const val ACTION_TICK_REFRESH = "com.example.focus.action.TICK_REFRESH"

        /** 后台唤醒刷新通知的间隔（进程被冻结时由系统闹钟叫醒） */
        private const val REFRESH_INTERVAL_MS = 30_000L

        /** 媒体会话对外声明的可控动作（手表/耳机据此显示按钮） */
        private const val PLAYBACK_ACTIONS = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_STOP
        const val EXTRA_NAME = "com.example.focus.extra.NAME"

        fun start(context: Context, name: String) {
            val intent = Intent(context, TimerService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_NAME, name)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun pause(context: Context) =
            context.startService(Intent(context, TimerService::class.java).setAction(ACTION_PAUSE))

        fun resume(context: Context) =
            context.startService(Intent(context, TimerService::class.java).setAction(ACTION_RESUME))

        fun stop(context: Context) =
            context.startService(Intent(context, TimerService::class.java).setAction(ACTION_STOP))

        /** 确保服务在运行（无动作，触发服务端从持久化恢复）。启动失败不致命 */
        fun ensureRunning(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, TimerService::class.java),
                )
            }
        }
    }

    private lateinit var store: TimerStateStore
    private var mediaSession: MediaSessionCompat? = null
    private var appIconBitmap: android.graphics.Bitmap? = null
    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var foregroundActive = false

    private val ticker = object : Runnable {
        override fun run() {
            val state = TimerStateHolder.state.value
            if (state.started && state.running) {
                val total = state.accumulatedMs +
                    (SystemClock.elapsedRealtime() - state.segmentStartElapsed)
                TimerStateHolder.update { it.copy(totalMs = total) }
                // 媒体样式通知里系统 chronometer 不生效，时间必须写在正文里并主动刷新
                updateNotification(total)
                handler.postDelayed(this, 1000)
            }
        }
    }

    private val refreshAlarmIntent: PendingIntent by lazy {
        PendingIntent.getService(
            this, 3,
            Intent(this, TimerService::class.java).setAction(ACTION_TICK_REFRESH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * 用系统闹钟定时唤醒刷新通知。
     * 进程被 ROM 冻结时 Handler 会停，但系统闹钟仍能唤醒进程，
     * 保证通知里的时间不会长时间停滞（粒度 30 秒）。
     */
    private fun scheduleRefresh() {
        runCatching {
            getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + REFRESH_INTERVAL_MS,
                refreshAlarmIntent,
            )
        }
    }

    private fun cancelRefresh() {
        runCatching {
            getSystemService(AlarmManager::class.java).cancel(refreshAlarmIntent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        store = TimerStateStore(this)
        appIconBitmap = loadAppIcon()

        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID, "专注计时", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "显示正在进行的专注计时"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
        // 渠道属性创建后无法修改（重要性/锁屏可见性），故换新渠道 ID 并清理旧渠道
        // 注意：绝不能删当前渠道 CHANNEL_ID，否则 startForeground 会因渠道缺失而抛异常崩溃
        runCatching {
            manager.deleteNotificationChannel("focus_timer")
            manager.deleteNotificationChannel("focus_timer_v2")
            manager.deleteNotificationChannel("focus_timer_v3")
        }

        // 媒体会话：国产 ROM 会把普通前台服务通知挡在锁屏外，
        // 而媒体样式通知走锁屏的媒体控制面板（系统特权区），可以正常显示
        mediaSession = MediaSessionCompat(this, "focus_timer").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = resumeTimer()

                override fun onPause() = pauseTimer()

                override fun onStop() = stopAndSave()
            })
            setActive(true)
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setState(
                        PlaybackStateCompat.STATE_PLAYING,
                        PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                        1f,
                    )
                    .setActions(PLAYBACK_ACTIONS)
                    .build()
            )
        }
    }

    /**
     * 同步媒体会话的播放状态。
     * 手表/耳机等蓝牙设备靠这个状态决定显示"播放"还是"暂停"按钮，
     * 暂停后不更新会导致设备一直显示暂停，从设备端无法恢复计时。
     */
    private fun updatePlaybackState(running: Boolean) {
        mediaSession?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(
                    if (running) {
                        PlaybackStateCompat.STATE_PLAYING
                    } else {
                        PlaybackStateCompat.STATE_PAUSED
                    },
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                    if (running) 1f else 0f,
                )
                .setActions(PLAYBACK_ACTIONS)
                .build()
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == null) {
            // 服务被系统重建，或由 Application 无动作拉起 → 从持久化恢复计时
            restoreFromStore()
            return START_STICKY
        }

        when (action) {
            ACTION_START -> {
                val name = intent.getStringExtra(EXTRA_NAME) ?: "专注"
                TimerStateHolder.update {
                    it.copy(
                        sessionName = name,
                        accumulatedMs = 0L,
                        segmentStartElapsed = SystemClock.elapsedRealtime(),
                        running = true,
                        started = true,
                        startEpochMs = System.currentTimeMillis(),
                        totalMs = 0L,
                    )
                }
                persist()
                enterForeground()
                updatePlaybackState(running = true)
            }

            ACTION_PAUSE -> pauseTimer()

            ACTION_RESUME -> resumeTimer()

            ACTION_STOP -> stopAndSave()

            ACTION_TICK_REFRESH -> {
                // 系统闹钟叫醒：刷新通知并续约下一次唤醒
                val state = TimerStateHolder.state.value
                if (state.started && state.running) {
                    val total = state.accumulatedMs +
                        (SystemClock.elapsedRealtime() - state.segmentStartElapsed)
                    TimerStateHolder.update { it.copy(totalMs = total) }
                    updateNotification(total)
                    scheduleRefresh()
                }
            }
        }

        val state = TimerStateHolder.state.value
        if (state.started && state.running) {
            handler.removeCallbacks(ticker)
            handler.post(ticker)
            scheduleRefresh()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        cancelRefresh()
        serviceScope.cancel()
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    /** 把应用图标转成位图，用作锁屏媒体卡片的封面 */
    private fun loadAppIcon(): android.graphics.Bitmap? = runCatching {
        val drawable = androidx.core.content.res.ResourcesCompat.getDrawable(
            resources, R.mipmap.ic_launcher_yishi, null,
        ) ?: return null
        val size = 256
        val bitmap = android.graphics.Bitmap.createBitmap(
            size, size, android.graphics.Bitmap.Config.ARGB_8888,
        )
        val canvas = android.graphics.Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        bitmap
    }.getOrNull()

    /**
     * 进程被杀后服务重建 / 冷启动恢复：
     * 先进入前台保证不被拦截（此时用占位通知），再从持久化取回状态并修正通知。
     */
    private fun restoreFromStore() {
        enterForeground()
        serviceScope.launch {
            val persisted = store.load()
            if (persisted == null || persisted.name.isEmpty()) {
                stopSelf()
                return@launch
            }
            TimerStateHolder.restore(
                name = persisted.name,
                accumulatedMs = persisted.accumulatedMs,
                segmentStartElapsed = persisted.segmentStartElapsed,
                startEpochMs = persisted.startEpochMs,
                wasRunning = persisted.running,
            )
            val state = TimerStateHolder.state.value
            updateNotification(state.totalMs)
            updatePlaybackState(running = state.running)
            if (state.running) {
                handler.removeCallbacks(ticker)
                handler.post(ticker)
            }
        }
    }

    /** 暂停计时（通知按钮、锁屏媒体控件、服务控制共用） */
    private fun pauseTimer() {
        val state = TimerStateHolder.state.value
        if (state.started && state.running) {
            val now = SystemClock.elapsedRealtime()
            TimerStateHolder.update {
                it.copy(
                    accumulatedMs = it.accumulatedMs + (now - it.segmentStartElapsed),
                    running = false,
                )
            }
            persist()
            handler.removeCallbacks(ticker)
            cancelRefresh()
            updateNotification(TimerStateHolder.state.value.totalMs)
            updatePlaybackState(running = false)
        }
    }

    /** 继续计时（通知按钮、锁屏媒体控件、服务控制共用） */
    private fun resumeTimer() {
        val state = TimerStateHolder.state.value
        if (state.started && !state.running) {
            TimerStateHolder.update {
                it.copy(segmentStartElapsed = SystemClock.elapsedRealtime(), running = true)
            }
            if (!foregroundActive) enterForeground()
            // 关键：外部触发（手表/耳机媒体控制、锁屏控件）不会经过 onStartCommand，
            // 必须在这里重新拉起刷新循环，否则状态是运行但时间不动
            handler.removeCallbacks(ticker)
            handler.post(ticker)
            scheduleRefresh()
            persist()
            updateNotification(TimerStateHolder.state.value.totalMs)
            updatePlaybackState(running = true)
        }
    }

    private fun enterForeground() {
        val state = TimerStateHolder.state.value
        val notification = buildNotification(
            name = state.sessionName.ifEmpty { "一事" },
            totalMs = state.totalMs,
            running = if (state.started) state.running else true,
        )
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundActive = true
    }

    /** 结束计时：结算总时长、写库、清理状态、退出前台 */
    private fun stopAndSave() {
        val state = TimerStateHolder.state.value
        if (state.started) {
            val now = SystemClock.elapsedRealtime()
            val total = state.accumulatedMs +
                if (state.running) (now - state.segmentStartElapsed) else 0L
            val endEpochMs = state.startEpochMs + total
            val session = FocusSession(
                name = state.sessionName,
                startTimeMs = state.startEpochMs,
                endTimeMs = endEpochMs,
                durationMs = total,
                date = formatDate(state.startEpochMs),
            )
            serviceScope.launch {
                AppDatabase.get(this@TimerService).focusDao().insert(session)
                store.clear()
            }
        }
        TimerStateHolder.reset()
        handler.removeCallbacks(ticker)
        cancelRefresh()
        if (foregroundActive) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundActive = false
        }
        stopSelf()
    }

    /** 持久化当前状态（运行段起点用 elapsedRealtime，可精确恢复） */
    private fun persist() {
        val state = TimerStateHolder.state.value
        if (!state.started) return
        val running = state.running
        val accumulated = state.accumulatedMs
        val segmentStart = if (running) state.segmentStartElapsed else 0L
        val name = state.sessionName
        val startEpochMs = state.startEpochMs
        serviceScope.launch {
            store.save(name, accumulated, segmentStart, startEpochMs, running)
        }
    }

    private fun updateNotification(totalMs: Long) {
        val state = TimerStateHolder.state.value
        if (!state.started) return
        runCatching {
            NotificationManagerCompat.from(this).notify(
                NOTIFICATION_ID,
                buildNotification(state.sessionName, totalMs, state.running),
            )
        }
    }

    private fun buildNotification(name: String, totalMs: Long, running: Boolean): Notification {
        val pauseIntent = PendingIntent.getService(
            this, 1,
            Intent(this, TimerService::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 2,
            Intent(this, TimerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val displayName = name.ifEmpty { "一事" }

        // 同步媒体元数据，让锁屏媒体控件显示项目名与时长
        mediaSession?.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, displayName)
                .putString(
                    MediaMetadataCompat.METADATA_KEY_ARTIST,
                    "${if (running) "专注中" else "已暂停"} · ${formatClock(totalMs)}",
                )
                .apply {
                    appIconBitmap?.let {
                        putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
                    }
                }
                .build()
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_focus)
            .setContentTitle(displayName)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1)
            )
            .addAction(0, if (running) "暂停" else "继续", pauseIntent)
            .addAction(0, "结束", stopIntent)

        if (running) {
            // 双保险：
            // 1) 系统 chronometer：进程被冻结时由系统继续走时（若 ROM 支持媒体样式下显示）
            // 2) 正文时间：由服务每秒刷新，作为可见性保底
            builder.setUsesChronometer(true)
            builder.setWhen(System.currentTimeMillis() - totalMs)
            builder.setContentText("专注中 · ${formatClock(totalMs)}")
        } else {
            builder.setUsesChronometer(false)
            builder.setContentText("已暂停 · ${formatClock(totalMs)}")
        }
        return builder.build()
    }

    private fun formatClock(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val sec = totalSec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%02d:%02d", m, sec)
    }

    private fun formatDate(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()
            .format(DateTimeFormatter.ISO_LOCAL_DATE)
}
