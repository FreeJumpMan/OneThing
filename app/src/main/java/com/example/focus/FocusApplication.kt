package com.example.focus

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.focus.data.backup.AutoBackupManager
import com.example.focus.data.db.AppDatabase
import com.example.focus.data.db.FocusSession
import com.example.focus.data.prefs.SettingsStore
import com.example.focus.data.prefs.TimerStateStore
import com.example.focus.data.sync.CalendarSyncManager
import com.example.focus.data.sync.syncDayToCalendar
import com.example.focus.data.usage.DefaultCategoryRules
import com.example.focus.service.TimerService
import com.example.focus.service.TimerStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 应用入口：只在**进程创建时**恢复一次未结束的计时，并写入内置时间分类。
 *
 * 为什么恢复逻辑放在 Application 而不是 Activity：
 * Activity 从后台返回时可能被销毁重建，若在重建时再次读取存档恢复，
 * 会用较旧的快照覆盖内存里正在走的计时，表现为"时间倒退/计时停止"。
 * Application.onCreate 每个进程只执行一次，天然避免这个问题。
 */
/** 自动同步通知的渠道与 id（渠道属性创建后不可改，需要调整时换新 id） */
private const val SYNC_CHANNEL_ID = "focus_sync_v1"
private const val SYNC_NOTIFICATION_ID = 2

/** 日历自动同步的最小间隔：避免频繁重建当天的日历事件 */
private const val AUTO_SYNC_MIN_INTERVAL_MS = 15 * 60_000L

class FocusApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastAutoSyncAtMs = 0L

    /** 自动备份的互斥锁：启动时的每日备份与专注结束时的备份不可能同时写 */
    private val backupMutex = Mutex()

    override fun onCreate() {
        super.onCreate()
        seedDefaultCategories()
        restoreTimerIfNeeded()
        syncTodayToCalendarIfEnabled()
        autoBackupDailyIfNeeded()
    }

    // ===== 自动备份（目录由用户在设置里挑选，SAF 树 URI） =====

    /** 每天首次打开 App 时补一次；上一次失败的话当天会重试 */
    fun autoBackupDailyIfNeeded() {
        appScope.launch {
            runCatching {
                val settings = SettingsStore(this@FocusApplication).settings.first()
                if (settings.autoBackupDir == null) return@launch
                val lastDay = if (settings.lastAutoBackupAt > 0L) {
                    Instant.ofEpochMilli(settings.lastAutoBackupAt)
                        .atZone(ZoneId.systemDefault()).toLocalDate().toString()
                } else {
                    null
                }
                if (settings.lastAutoBackupOk && lastDay == LocalDate.now().toString()) return@launch
                runBackup()
            }
        }
    }

    /**
     * 一次专注刚结束，是数据最该落盘的时刻，所以这里不做节流。
     * 同一天多次结束只是覆盖同一个当天文件。
     */
    fun autoBackupOnSessionEnd() {
        appScope.launch { runCatching { runBackup() } }
    }

    private suspend fun runBackup() {
        if (!backupMutex.tryLock()) return // 已有一份备份在写，跳过这一次
        try {
            val store = SettingsStore(this)
            if (store.settings.first().autoBackupDir == null) return
            val ok = AutoBackupManager(this, AppDatabase.get(this), store).backupNow()
            store.setLastAutoBackup(System.currentTimeMillis(), ok)
        } finally {
            backupMutex.unlock()
        }
    }

    /**
     * 把当天的时间写进日历（自动同步开关开启时）。幂等：先清当天旧事件再重建。
     * 触发点：进程创建 + App 回到前台（MainActivity.onStart）；
     * 15 分钟内不重复执行，避免频繁重建日历事件。
     */
    fun syncTodayToCalendarIfEnabled() {
        appScope.launch {
            runCatching {
                val settings = SettingsStore(this@FocusApplication).settings.first()
                if (!settings.autoSyncCalendar && !settings.autoSyncAppUsage) return@launch
                val now = System.currentTimeMillis()
                if (now - lastAutoSyncAtMs < AUTO_SYNC_MIN_INTERVAL_MS) return@launch
                lastAutoSyncAtMs = now
                syncDayToCalendar(
                    context = this@FocusApplication,
                    date = LocalDate.now(),
                    includeFocusApp = settings.autoSyncCalendar,
                    includeAppUsage = settings.autoSyncAppUsage,
                    excludedCategories = settings.excludedCalendarCategories,
                    switchToleranceMinutes = settings.switchToleranceMinutes,
                )
            }
        }
    }

    /** 首次运行时写入内置时间分类（用户之后可自行增删改） */
    private fun seedDefaultCategories() {
        appScope.launch {
            runCatching {
                val dao = AppDatabase.get(this@FocusApplication).timeCategoryDao()
                if (dao.count() == 0) {
                    dao.upsertAll(DefaultCategoryRules.CATEGORIES)
                }
            }
        }
    }

    private fun restoreTimerIfNeeded() {
        appScope.launch {
            val persisted = TimerStateStore(this@FocusApplication).load() ?: return@launch
            if (persisted.name.isEmpty()) return@launch
            TimerStateHolder.restore(
                name = persisted.name,
                accumulatedMs = persisted.accumulatedMs,
                segmentStartElapsed = persisted.segmentStartElapsed,
                startEpochMs = persisted.startEpochMs,
                wasRunning = persisted.running,
            )
            // 确保前台服务在运行（服务被系统重建时也会自行从存档恢复）
            TimerService.ensureRunning(this@FocusApplication)
        }
    }

    /**
     * 结束专注后自动同步到系统日历（设置开关开启时）。
     *
     * 跑在 appScope 而不是 TimerService 的 scope 里——服务结束时会 stopSelf，
     * 它自己的 scope 随即被取消，挂在那里的同步会被中断。
     * 成功后发一条轻量通知；未开开关 / 未授权 / 失败都静默。
     */
    fun autoSyncToCalendar(session: FocusSession, sessionId: Long) {
        if (sessionId <= 0L) return
        appScope.launch {
            runCatching {
                val settings = SettingsStore(this@FocusApplication).settings.first()
                if (!settings.autoSyncCalendar) return@launch

                val db = AppDatabase.get(this@FocusApplication)
                val syncManager = CalendarSyncManager(
                    this@FocusApplication,
                    db.calendarSyncDao(),
                    db.timelineEventDao(),
                )
                if (!syncManager.hasCalendarPermission()) return@launch

                syncManager.syncSession(
                    sessionId = sessionId,
                    title = session.name,
                    startTimeMs = session.startTimeMs,
                    endTimeMs = session.endTimeMs,
                    durationMs = session.durationMs,
                )
                notifySynced(session)
            }
        }
    }

    /** 「已同步到日历」通知：低优先级、不打扰，点击回到 App */
    private fun notifySynced(session: FocusSession) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            nm.getNotificationChannel(SYNC_CHANNEL_ID) == null
        ) {
            nm.createNotificationChannel(
                NotificationChannel(
                    SYNC_CHANNEL_ID,
                    "日历同步",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "专注结束后自动同步到日历的提醒"
                    setShowBadge(false)
                }
            )
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, SYNC_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_focus)
            .setContentTitle("已同步到日历")
            .setContentText("${session.name} · ${formatCompactDuration(session.durationMs)}")
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .build()
        runCatching {
            NotificationManagerCompat.from(this).notify(SYNC_NOTIFICATION_ID, notification)
        }
    }

    /** 通知文案里的时长：45 分钟 / 1 小时 20 分 */
    private fun formatCompactDuration(ms: Long): String {
        val minutes = (ms / 60_000).coerceAtLeast(1)
        return if (minutes >= 60) {
            val h = minutes / 60
            val m = (minutes % 60).toInt()
            if (m == 0) "$h 小时" else "$h 小时 $m 分"
        } else {
            "$minutes 分钟"
        }
    }
}
