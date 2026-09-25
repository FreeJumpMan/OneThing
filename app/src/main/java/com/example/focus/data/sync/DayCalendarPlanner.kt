package com.example.focus.data.sync

import android.content.Context
import com.example.focus.data.db.AppDatabase
import com.example.focus.data.usage.IntervalMerger
import com.example.focus.data.usage.TimeInterval
import com.example.focus.data.usage.TimelineBuilder
import com.example.focus.data.usage.UsageStatsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/** 计划写入日历的一条事件 */
data class PlannedCalendarEvent(
    val title: String,
    val description: String,
    val startMs: Long,
    val endMs: Long,
)

/**
 * 「一天的时间」→ 系统日历的规划器。
 *
 * 把两类内容统一规划并互相去重：
 *  1. **专注 App 时段**（自动同步开关①）→ 标题为 App 名，如「不背单词 · 20m」；
 *     与当天计时记录重叠的部分会被剪掉（计时记录有自己的同步通道：结束时同步）。
 *  2. **App 使用时间块**（自动同步开关②）→ 标题为分类名，如「学习 · 45m」；
 *     会减去前面两类已覆盖的时段，保证同一段时间在日历上只出现一次。
 *
 * 手动同步（App 页按钮）用全量参数调用：两个 include 都传 true。
 */
class DayCalendarPlanner(private val context: Context) {

    private val usageRepo = UsageStatsRepository(context)
    private val db = AppDatabase.get(context)

    suspend fun plan(
        date: LocalDate,
        includeFocusApp: Boolean,
        includeAppUsage: Boolean,
        excludedCategories: Set<String>,
        switchToleranceMs: Long,
    ): List<PlannedCalendarEvent> = withContext(Dispatchers.IO) {
        if (!includeFocusApp && !includeAppUsage) return@withContext emptyList()

        val zone = ZoneId.systemDefault()
        val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        // 当天计时记录：只作为「已占用区间」参与去重（它们由专注结束时的通道写入日历）
        val sessions = db.focusDao().getByDateOnce(date.toString())
        val occupied = sessions
            .filter { it.startTimeMs < dayEnd && it.endTimeMs > dayStart }
            .map { TimeInterval(maxOf(it.startTimeMs, dayStart), minOf(it.endTimeMs, dayEnd)) }
            .toMutableList()

        val events = mutableListOf<PlannedCalendarEvent>()

        // ① 专注 App 的时段
        if (includeFocusApp && usageRepo.hasUsageAccess()) {
            val focusPackages = db.focusAppDao().getEnabled().map { it.packageName }.toSet()
            if (focusPackages.isNotEmpty()) {
                val segments = usageRepo.loadFocusUsageSegments(
                    focusPackages, dayStart, dayEnd, switchToleranceMs,
                )
                segments.forEach { segment ->
                    IntervalMerger.subtract(
                        listOf(TimeInterval(segment.startMs, segment.endMs)),
                        occupied,
                    ).forEach { remains ->
                        if (remains.durationMs >= MIN_EVENT_MS) {
                            events += PlannedCalendarEvent(
                                title = "${segment.appNames.joinToString("、")} · " +
                                    formatCompactDuration(remains.durationMs),
                                description = "专注 App 自动记录",
                                startMs = remains.startMs,
                                endMs = remains.endMs,
                            )
                            occupied += remains
                        }
                    }
                }
            }
        }

        // ② App 使用时间块（减去已占用，保证同一段时间只出现一次）
        if (includeAppUsage) {
            val categories = db.timeCategoryDao().getAll().associateBy { it.id }
            val rules = db.appCategoryRuleDao().getAll().associateBy { it.packageName }
            val blocks = TimelineBuilder.build(
                usageRepo.loadDayIntervals(date),
                rules,
                categories,
            )
            blocks.forEach { block ->
                if (block.categoryId.toString() in excludedCategories) return@forEach
                IntervalMerger.subtract(
                    listOf(TimeInterval(block.startMs, block.endMs)),
                    occupied,
                ).forEach { remains ->
                    if (remains.durationMs >= MIN_EVENT_MS) {
                        events += PlannedCalendarEvent(
                            title = "${block.categoryName} · " +
                                formatCompactDuration(remains.durationMs),
                            description = block.apps.joinToString("、"),
                            startMs = remains.startMs,
                            endMs = remains.endMs,
                        )
                    }
                }
            }
        }

        events.sortedBy { it.startMs }
    }

    private companion object {
        /** 不足 1 分钟的事件不写入日历 */
        const val MIN_EVENT_MS = 60_000L
    }
}

/** 紧凑时长：45m / 12h30m（与界面上的 formatDurationCompact 保持一致） */
private fun formatCompactDuration(ms: Long): String {
    val totalMin = ms / 60_000
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h > 0 && m > 0 -> "${h}h${m}m"
        h > 0 -> "${h}h"
        else -> "${m}m"
    }
}

/**
 * 执行一天的同步：生成计划 → 重建当天由本功能管理的日历事件。返回写入条数。
 * 没有日历权限时返回 0（调用方自行决定是否提示）。
 */
suspend fun syncDayToCalendar(
    context: Context,
    date: LocalDate,
    includeFocusApp: Boolean,
    includeAppUsage: Boolean,
    excludedCategories: Set<String>,
    switchToleranceMinutes: Int,
): Int {
    val syncManager = CalendarSyncManager(
        context,
        AppDatabase.get(context).calendarSyncDao(),
        AppDatabase.get(context).timelineEventDao(),
    )
    if (!syncManager.hasCalendarPermission()) return 0

    val events = DayCalendarPlanner(context).plan(
        date = date,
        includeFocusApp = includeFocusApp,
        includeAppUsage = includeAppUsage,
        excludedCategories = excludedCategories,
        switchToleranceMs = switchToleranceMinutes * 60_000L,
    )
    if (events.isEmpty()) return 0

    val dateStr = date.toString()
    syncManager.clearTimelineEvents(dateStr)
    var count = 0
    events.forEachIndexed { index, event ->
        val eventId = syncManager.insertTimelineBlock(
            date = dateStr,
            index = index,
            title = event.title,
            description = event.description,
            startMs = event.startMs,
            endMs = event.endMs,
        )
        if (eventId != null) count++
    }
    return count
}
