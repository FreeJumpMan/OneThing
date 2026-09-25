package com.example.focus.ui.app

import android.app.Application
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.focus.data.db.AppCategoryRule
import com.example.focus.data.db.AppDatabase
import com.example.focus.data.db.FocusApp
import com.example.focus.data.db.ProductivityLevel
import com.example.focus.data.db.TimeCategory
import com.example.focus.data.prefs.SettingsStore
import com.example.focus.data.sync.CalendarSyncManager
import com.example.focus.data.sync.syncDayToCalendar
import com.example.focus.data.usage.AppUsageInterval
import com.example.focus.data.usage.AppUsageItem
import com.example.focus.data.usage.DayUsage
import com.example.focus.data.usage.DefaultCategoryRules
import com.example.focus.data.usage.TimelineBlock
import com.example.focus.data.usage.TimelineBuilder
import com.example.focus.data.usage.UsageStatsRepository
import com.example.focus.ui.formatDurationCompact
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/** 某个 App 的分类信息（展示用） */
data class AppMeta(
    val categoryName: String,
    val colorHex: String,
    val productivityLabel: String,
)

/** 某个时间分类在某天的累计时长（时间分布用） */
data class CategoryUsage(
    val categoryId: Long,
    val name: String,
    val colorHex: String,
    val totalMs: Long,
)

/** 时间轴同步到日历的结果（供 UI 提示） */
sealed interface TimelineSyncEvent {
    /** 缺日历权限，需要 UI 发起申请 */
    data object NeedPermission : TimelineSyncEvent

    data class Done(val count: Int) : TimelineSyncEvent

    data class Failed(val message: String) : TimelineSyncEvent
}

/** 某个 App 的详情（今日 / 本周 / 本月 + 专注标记） */
data class AppDetail(
    val packageName: String,
    val appName: String,
    val icon: ImageBitmap?,
    val todayMs: Long = 0L,
    val weekMs: Long = 0L,
    val monthMs: Long = 0L,
    val isFocusApp: Boolean = false,
    val loading: Boolean = true,
)

data class AppUsageUiState(
    val hasPermission: Boolean = false,
    val selectedDate: LocalDate = LocalDate.now(),
    /** 当天所有 App 的使用总时长 */
    val totalMs: Long = 0L,
    /** 前一天的总时长（算环比） */
    val previousDayTotalMs: Long = 0L,
    /** 各 App 使用排行（含图标） */
    val apps: List<AppUsageItem> = emptyList(),
    /** 包名 → 分类信息（分类名 / 颜色 / 生产力属性） */
    val appMeta: Map<String, AppMeta> = emptyMap(),
    /** 当天按时间分类聚合的分布 */
    val categoryUsage: List<CategoryUsage> = emptyList(),
    /** 当天的时间轴（合并后的时间块） */
    val timeline: List<TimelineBlock> = emptyList(),
    /** 最近 7 天趋势 */
    val weekTrend: List<DayUsage> = emptyList(),
    /** 已标记为专注 App 的包名 */
    val focusPackages: Set<String> = emptySet(),
    /** 非 null 时展示详情页 */
    val detail: AppDetail? = null,
    val loading: Boolean = true,
)

/**
 * App 时间页面 ViewModel。
 * 直接向系统查询使用时长（不做秒级轮询、不落库），分钟级精度足够。
 * 分类规则优先级：用户规则 > 内置默认规则 > 其他。
 */
class AppUsageViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = UsageStatsRepository(application)
    private val db = AppDatabase.get(application)
    private val focusAppDao = db.focusAppDao()
    private val categoryDao = db.timeCategoryDao()
    private val ruleDao = db.appCategoryRuleDao()
    private val calendarSyncManager = CalendarSyncManager(
        application,
        db.calendarSyncDao(),
        db.timelineEventDao(),
    )
    private val settingsStore = SettingsStore(application)

    private val _state = MutableStateFlow(AppUsageUiState())
    val state: StateFlow<AppUsageUiState> = _state.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            focusAppDao.observeAll().collect { list ->
                _state.update { state ->
                    state.copy(
                        focusPackages = list.filter { it.enabled }.map { it.packageName }.toSet()
                    )
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val hasPermission = repo.hasUsageAccess()
            val date = _state.value.selectedDate
            if (!hasPermission) {
                _state.update { it.copy(hasPermission = false, loading = false) }
                return@launch
            }
            _state.update { it.copy(hasPermission = true, loading = true) }

            val total = repo.loadDayTotal(date)
            val previousTotal = repo.loadDayTotal(date.minusDays(1))
            val apps = repo.loadDay(date)
            val trend = repo.loadDailyTotals(7, date)

            // 分类解析：用户规则优先，其次内置默认规则
            val categories = categoryDao.getAll().associateBy { it.id }
            val rules = ruleDao.getAll().associateBy { it.packageName }

            val meta = apps.associate { item ->
                val resolved = TimelineBuilder.resolveCategory(item.packageName, item.appName, rules)
                val categoryId = resolved?.first ?: DefaultCategoryRules.CAT_OTHER
                val level = resolved?.second ?: ProductivityLevel.NEUTRAL
                val category = categories[categoryId]
                    ?: DefaultCategoryRules.CATEGORIES.first { it.id == DefaultCategoryRules.CAT_OTHER }
                item.packageName to AppMeta(
                    categoryName = category.name,
                    colorHex = category.colorHex,
                    productivityLabel = level.label,
                )
            }

            val usage = apps
                .groupBy { item ->
                    TimelineBuilder.resolveCategory(item.packageName, item.appName, rules)?.first
                        ?: DefaultCategoryRules.CAT_OTHER
                }
                .map { (categoryId, list) ->
                    val category = categories[categoryId]
                        ?: DefaultCategoryRules.CATEGORIES.first { it.id == DefaultCategoryRules.CAT_OTHER }
                    CategoryUsage(
                        categoryId = categoryId,
                        name = category.name,
                        colorHex = category.colorHex,
                        totalMs = list.sumOf { it.durationMs },
                    )
                }
                .sortedByDescending { it.totalMs }

            // 时间轴：事件级区间 → 分类 → 合并成时间块
            val timeline = TimelineBuilder.build(repo.loadDayIntervals(date), rules, categories)

            _state.update {
                it.copy(
                    totalMs = total,
                    previousDayTotalMs = previousTotal,
                    apps = apps,
                    appMeta = meta,
                    categoryUsage = usage,
                    timeline = timeline,
                    weekTrend = trend,
                    loading = false,
                )
            }
        }
    }

    fun moveDay(offsetDays: Long) {        val target = _state.value.selectedDate.plusDays(offsetDays)
        // 不允许查看未来
        if (target.isAfter(LocalDate.now())) return
        _state.update { it.copy(selectedDate = target) }
        refresh()
    }

    // ===== 详情页 =====

    fun openDetail(item: AppUsageItem) {
        _state.update {
            it.copy(
                detail = AppDetail(
                    packageName = item.packageName,
                    appName = item.appName,
                    icon = item.icon,
                    todayMs = item.durationMs,
                )
            )
        }
        viewModelScope.launch {
            val today = LocalDate.now()
            val weekStart = today.minusDays((today.dayOfWeek.value - 1).toLong())
            val weekMs = repo.loadAppRange(item.packageName, weekStart, today)
            val monthMs = repo.loadAppRange(item.packageName, today.withDayOfMonth(1), today)
            val enabled = focusAppDao.getByPackageName(item.packageName)?.enabled ?: false
            _state.update { state ->
                state.copy(
                    detail = state.detail?.copy(
                        weekMs = weekMs,
                        monthMs = monthMs,
                        isFocusApp = enabled,
                        loading = false,
                    )
                )
            }
        }
    }

    fun closeDetail() {
        _state.update { it.copy(detail = null) }
    }

    /** 切换「计入专注时间」 */
    fun setFocusApp(enabled: Boolean) {
        val detail = _state.value.detail ?: return
        viewModelScope.launch {
            if (enabled) {
                focusAppDao.upsert(
                    FocusApp(
                        packageName = detail.packageName,
                        appName = detail.appName,
                        enabled = true,
                    )
                )
            } else {
                focusAppDao.delete(detail.packageName)
            }
            _state.update { state ->
                state.copy(detail = state.detail?.copy(isFocusApp = enabled))
            }
        }
    }

    // ===== 时间轴同步到系统日历 =====

    private val _syncEvent = MutableStateFlow<TimelineSyncEvent?>(null)
    val syncEvent: StateFlow<TimelineSyncEvent?> = _syncEvent.asStateFlow()

    private var pendingTimelineSync = false

    /** 把当天的内容（专注 App 时段 + 分类时间块）写入系统日历 */
    fun syncTimelineToCalendar() {
        val snapshot = _state.value
        if (!calendarSyncManager.hasCalendarPermission()) {
            pendingTimelineSync = true
            _syncEvent.value = TimelineSyncEvent.NeedPermission
            return
        }
        doSyncTimeline(snapshot.selectedDate)
    }

    /** UI 获得日历权限后回调，继续未完成的同步 */
    fun onCalendarPermissionGranted() {
        if (!pendingTimelineSync) return
        pendingTimelineSync = false
        doSyncTimeline(_state.value.selectedDate)
    }

    fun onCalendarPermissionDenied() {
        pendingTimelineSync = false
    }

    fun consumeSyncEvent() {
        _syncEvent.value = null
    }

    /**
     * 手动同步：与自动同步走同一个规划器（全量内容：专注 App 时段 + 分类时间块），
     * 先清掉当天旧事件再重建，保证两条路径产出一致。
     */
    private fun doSyncTimeline(date: LocalDate) {
        viewModelScope.launch {
            val settings = settingsStore.settings.first()
            val count = syncDayToCalendar(
                context = getApplication(),
                date = date,
                includeFocusApp = true,
                includeAppUsage = true,
                excludedCategories = settings.excludedCalendarCategories,
                switchToleranceMinutes = settings.switchToleranceMinutes,
            )
            _syncEvent.value = if (count > 0) {
                TimelineSyncEvent.Done(count)
            } else {
                TimelineSyncEvent.Failed("没有可写入的内容，或系统日历不可用")
            }
        }
    }
}
