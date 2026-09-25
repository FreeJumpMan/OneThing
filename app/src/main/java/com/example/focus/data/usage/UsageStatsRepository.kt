package com.example.focus.data.usage

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Process
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.example.focus.data.db.FocusSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/** 单个 App 在某天的使用情况 */
data class AppUsageItem(
    val packageName: String,
    val appName: String,
    val durationMs: Long,
    val icon: ImageBitmap?,
)

/** 某天的使用总时长（趋势用） */
data class DayUsage(
    val date: LocalDate,
    val totalMs: Long,
)

/** 某天的专注构成 */
data class EffectiveFocus(
    /** 主动计时（FocusSession） */
    val sessionMs: Long,
    /** 专注 App 使用时长（已合并短暂切换） */
    val appMs: Long,
    /** 去重后的有效专注 */
    val effectiveMs: Long,
)

/** 某个 App 的一段前台使用区间（时间轴用） */
data class AppUsageInterval(
    val packageName: String,
    val appName: String,
    val startMs: Long,
    val endMs: Long,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

/**
 * 读取系统层面的 App 使用时长（UsageStatsManager）。
 *
 * 需要用户在系统设置中授予「使用情况访问」权限（特殊权限，无法用运行时权限弹窗申请）。
 * 数据只在本机读取与展示，不经过网络。
 *
 * 注意：不做秒级轮询，也不落库——直接按天向系统查询，分钟级精度足够。
 * 历史数据依赖系统保留时长（通常数天），如需长期留存可后续接入落库。
 */
class UsageStatsRepository(private val context: Context) {

    companion object {
        /** 短暂切换阈值：离开专注 App 不超过 2 分钟视为未中断 */
        const val DEFAULT_SWITCH_TOLERANCE_MS = 2 * 60_000L

        /** 排行最小展示时长：不足 1 分钟不展示 */
        private const val MIN_DISPLAY_MS = 60_000L
    }

    /** 是否已获得「使用情况访问」权限 */
    fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** 某天各 App 的使用时长，按由多到少排序（含图标） */
    suspend fun loadDay(date: LocalDate): List<AppUsageItem> = withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val pm = context.packageManager
        val homePackages = homePackages(pm)

        queryStats(start, end)
            .groupBy { it.packageName }
            .mapNotNull { (pkg, list) ->
                val total = list.sumOf { it.totalTimeInForeground }
                // 不足 1 分钟不展示，避免排行被秒级碎片刷屏
                if (total < MIN_DISPLAY_MS) return@mapNotNull null
                // 桌面不算「使用」（与系统数字健康口径一致）
                if (pkg in homePackages) return@mapNotNull null
                val label = appLabel(pm, pkg) ?: return@mapNotNull null
                AppUsageItem(pkg, label, total, loadIcon(pkg))
            }
            .sortedByDescending { it.durationMs }
    }

    /** 指定区间内各 App 的使用时长合计（专注 App 候选列表用） */
    suspend fun loadRange(
        startDate: LocalDate,
        endDateInclusive: LocalDate,
    ): List<AppUsageItem> = withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        val start = startDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = endDateInclusive.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val pm = context.packageManager
        val homePackages = homePackages(pm)
        queryStats(start, end)
            .groupBy { it.packageName }
            .mapNotNull { (pkg, list) ->
                val total = list.sumOf { it.totalTimeInForeground }
                if (total < MIN_DISPLAY_MS) return@mapNotNull null
                if (pkg in homePackages) return@mapNotNull null
                val label = appLabel(pm, pkg) ?: return@mapNotNull null
                AppUsageItem(pkg, label, total, loadIcon(pkg))
            }
            .sortedByDescending { it.durationMs }
    }

    /** 某天的 App 使用总时长（不含桌面，与排行口径一致） */
    suspend fun loadDayTotal(date: LocalDate): Long = withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val homePackages = homePackages(context.packageManager)
        queryStats(start, end)
            .filter { it.packageName !in homePackages }
            .sumOf { it.totalTimeInForeground }
    }

    /** 最近 N 天的每日总时长（最后一项为 endDate） */
    suspend fun loadDailyTotals(days: Int, endDate: LocalDate): List<DayUsage> =
        withContext(Dispatchers.IO) {
            (days - 1 downTo 0).map { offset ->
                val date = endDate.minusDays(offset.toLong())
                val zone = ZoneId.systemDefault()
                val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
                val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                DayUsage(date, queryStats(start, end).sumOf { it.totalTimeInForeground })
            }
        }

    // ===== 专注 App 时间轴与去重合并 =====

    /**
     * 读取指定区间内「专注 App」的使用区间（事件级，已按短暂切换阈值合并）。
     */
    suspend fun loadFocusAppIntervals(
        packages: Set<String>,
        startMs: Long,
        endMs: Long,
        gapToleranceMs: Long = DEFAULT_SWITCH_TOLERANCE_MS,
    ): List<TimeInterval> = withContext(Dispatchers.IO) {
        if (packages.isEmpty()) return@withContext emptyList()
        val manager = context.getSystemService(UsageStatsManager::class.java)
            ?: return@withContext emptyList()
        val events = manager.queryEvents(startMs, endMs) ?: return@withContext emptyList()

        val resumedType = if (Build.VERSION.SDK_INT >= 29) {
            UsageEvents.Event.ACTIVITY_RESUMED
        } else {
            @Suppress("DEPRECATION")
            UsageEvents.Event.MOVE_TO_FOREGROUND
        }
        val pausedType = if (Build.VERSION.SDK_INT >= 29) {
            UsageEvents.Event.ACTIVITY_PAUSED
        } else {
            @Suppress("DEPRECATION")
            UsageEvents.Event.MOVE_TO_BACKGROUND
        }

        val openAt = mutableMapOf<String, Long>()
        val raw = mutableListOf<TimeInterval>()
        val event = UsageEvents.Event()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName ?: continue
            if (pkg !in packages) continue
            when (event.eventType) {
                resumedType -> {
                    // 同一 App 连续 RESUMED 时先关闭前一段，避免丢失区间
                    openAt[pkg]?.let { previous ->
                        if (event.timeStamp > previous) {
                            raw += TimeInterval(previous, event.timeStamp)
                        }
                    }
                    openAt[pkg] = event.timeStamp
                }

                pausedType, UsageEvents.Event.ACTIVITY_STOPPED -> {
                    val start = openAt.remove(pkg)
                    if (start != null && event.timeStamp > start) {
                        raw += TimeInterval(start, event.timeStamp)
                    }
                }
            }
        }
        // 查询窗口结束时仍在前台的 App
        openAt.values.forEach { start ->
            if (endMs > start) raw += TimeInterval(start, endMs)
        }

        IntervalMerger.merge(raw, gapToleranceMs)
    }

    /**
     * 某天的有效专注 = 专注计时 ∪ 专注 App 使用（重叠部分只算一次）。
     */
    suspend fun loadDayEffectiveFocus(
        date: LocalDate,
        sessions: List<FocusSession>,
        packages: Set<String>,
        gapToleranceMs: Long = DEFAULT_SWITCH_TOLERANCE_MS,
    ): EffectiveFocus = withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val sessionIntervals = sessions
            .filter { it.startTimeMs < dayEnd && it.endTimeMs > dayStart }
            .map {
                TimeInterval(
                    maxOf(it.startTimeMs, dayStart),
                    minOf(it.endTimeMs, dayEnd),
                )
            }

        val appIntervals = loadFocusAppIntervals(packages, dayStart, dayEnd, gapToleranceMs)

        EffectiveFocus(
            sessionMs = sessionIntervals.sumOf { it.durationMs },
            appMs = appIntervals.sumOf { it.durationMs },
            effectiveMs = IntervalMerger.totalDurationMs(sessionIntervals + appIntervals),
        )
    }

    /**
     * 区间内每天的有效专注（一次查事件、按天切分去重），用于趋势曲线。
     */
    suspend fun loadDailyEffectiveFocus(
        sessions: List<FocusSession>,
        packages: Set<String>,
        startDate: LocalDate,
        endDate: LocalDate,
        gapToleranceMs: Long = DEFAULT_SWITCH_TOLERANCE_MS,
    ): Map<LocalDate, Long> = withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        val rangeStart = startDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val rangeEnd = endDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val appIntervals = loadFocusAppIntervals(packages, rangeStart, rangeEnd, gapToleranceMs)

        val result = mutableMapOf<LocalDate, Long>()
        var day = startDate
        while (!day.isAfter(endDate)) {
            val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

            val sessionSlice = sessions
                .filter { it.startTimeMs < dayEnd && it.endTimeMs > dayStart }
                .map {
                    TimeInterval(
                        maxOf(it.startTimeMs, dayStart),
                        minOf(it.endTimeMs, dayEnd),
                    )
                }
            val appSlice = IntervalMerger.clip(appIntervals, dayStart, dayEnd)

            val total = IntervalMerger.totalDurationMs(sessionSlice + appSlice)
            if (total > 0L) result[day] = total
            day = day.plusDays(1)
        }
        result
    }

    /**
     * 某天所有 App 的使用区间（事件级，未合并），供时间轴与时间块使用。
     * 已排除桌面（Launcher）。
     */
    suspend fun loadDayIntervals(date: LocalDate): List<AppUsageInterval> = withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val manager = context.getSystemService(UsageStatsManager::class.java)
            ?: return@withContext emptyList()
        val events = manager.queryEvents(start, end) ?: return@withContext emptyList()
        val pm = context.packageManager
        val homePackages = homePackages(pm)

        val resumedType = if (Build.VERSION.SDK_INT >= 29) {
            UsageEvents.Event.ACTIVITY_RESUMED
        } else {
            @Suppress("DEPRECATION")
            UsageEvents.Event.MOVE_TO_FOREGROUND
        }
        val pausedType = if (Build.VERSION.SDK_INT >= 29) {
            UsageEvents.Event.ACTIVITY_PAUSED
        } else {
            @Suppress("DEPRECATION")
            UsageEvents.Event.MOVE_TO_BACKGROUND
        }

        val openAt = mutableMapOf<String, Long>()
        val raw = mutableListOf<AppUsageInterval>()
        val event = UsageEvents.Event()

        fun closeInterval(pkg: String, endMs: Long) {
            val begin = openAt.remove(pkg) ?: return
            if (endMs <= begin) return
            if (pkg in homePackages) return
            val label = appLabel(pm, pkg) ?: return
            raw += AppUsageInterval(pkg, label, begin, endMs)
        }

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName ?: continue
            when (event.eventType) {
                resumedType -> {
                    // 连续 RESUMED 时先收尾前一段
                    if (openAt.containsKey(pkg)) closeInterval(pkg, event.timeStamp)
                    openAt[pkg] = event.timeStamp
                }

                pausedType, UsageEvents.Event.ACTIVITY_STOPPED -> closeInterval(pkg, event.timeStamp)
            }
        }
        // 查询窗口结束时仍在前台的
        openAt.keys.toList().forEach { pkg -> closeInterval(pkg, end) }

        raw.sortedBy { it.startMs }
    }

    /** 某个 App 在指定区间内的使用总时长（详情页的今日/本周/本月） */
    suspend fun loadAppRange(
        packageName: String,
        startDate: LocalDate,
        endDateInclusive: LocalDate,
    ): Long = withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        val start = startDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = endDateInclusive.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        queryStats(start, end)
            .filter { it.packageName == packageName }
            .sumOf { it.totalTimeInForeground }
    }

    private fun queryStats(startMs: Long, endMs: Long) =
        // 结束时间不能超过当前时刻：部分 ROM 在区间包含未来时间时会返回不完整结果
        run {
            val safeEnd = minOf(endMs, System.currentTimeMillis())
            if (safeEnd <= startMs) {
                emptyList()
            } else {
                context.getSystemService(UsageStatsManager::class.java)
                    ?.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startMs, safeEnd)
                    ?: emptyList()
            }
        }

    private fun appLabel(pm: PackageManager, pkg: String): String? = runCatching {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()

    /** 桌面（Launcher）包名集合，这部分时间不计入「使用」 */
    private fun homePackages(pm: PackageManager): Set<String> = runCatching {
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_HOME)
        pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName }
            .toSet()
    }.getOrDefault(emptySet())

    private fun loadIcon(pkg: String): ImageBitmap? = runCatching {
        val drawable = context.packageManager.getApplicationIcon(pkg)
        val size = (context.resources.displayMetrics.density * 36).toInt().coerceAtLeast(48)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        bitmap.asImageBitmap()
    }.getOrNull()
}
