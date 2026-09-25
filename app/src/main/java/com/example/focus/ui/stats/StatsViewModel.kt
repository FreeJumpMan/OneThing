package com.example.focus.ui.stats

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.focus.data.db.AppCategoryRule
import com.example.focus.data.db.AppDatabase
import com.example.focus.data.db.CumulativeStats
import com.example.focus.data.db.DayStats
import com.example.focus.data.db.FocusSession
import com.example.focus.data.db.ProductivityLevel
import com.example.focus.data.db.SliceStat
import com.example.focus.data.prefs.SettingsStore
import com.example.focus.data.repo.SessionRepository
import com.example.focus.data.usage.DefaultCategoryRules
import com.example.focus.data.usage.EffectiveFocus
import com.example.focus.data.usage.UsageStatsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * 统计板块 ViewModel。
 *
 * 「有效专注」的口径：专注计时 ∪ 专注 App 使用时间（区间去重）。
 * 这样没有手动开计时的碎片时间（例如背单词）也能被系统使用记录补足，
 * 但同一段时间不会被重复计算两次。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = SessionRepository(AppDatabase.get(application).focusDao())
    private val usageRepo = UsageStatsRepository(application)
    private val focusAppDao = AppDatabase.get(application).focusAppDao()
    private val settingsStore = SettingsStore(application)

    enum class PieMode { DAY, WEEK, MONTH }

    /** 当前日期流：每 30 秒检查，仅当日期变化（跨天）时发射新值 */
    private val currentDate: Flow<LocalDate> = flow {
        while (true) {
            emit(LocalDate.now())
            delay(30_000)
        }
    }.distinctUntilChanged()

    /** 用户标记为「专注 App」的包名集合 */
    private val focusPackages: Flow<Set<String>> =
        focusAppDao.observeAll().map { list ->
            list.filter { it.enabled }.map { it.packageName }.toSet()
        }

    /** 短暂切换阈值（来自设置，分钟 → 毫秒） */
    private val switchToleranceMs: Flow<Long> =
        settingsStore.settings.map { it.switchToleranceMinutes * 60_000L }

    /** 1. 累计专注（全时段，纯计时数据） */
    val cumulative: StateFlow<CumulativeStats?> =
        repo.observeCumulative().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** 2. 当日计时聚合（次数 / 计时时长），用于次数与对照 */
    val todayStats: StateFlow<DayStats?> =
        currentDate.flatMapLatest { date ->
            repo.observeDayStats(date.toString())
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** 2c. 「一天的时间块」当前查看的日期 */
    val blockDate = MutableStateFlow(LocalDate.now())

    /** 该日期当天的全部专注记录（「一天的时间块」网格用） */
    val dayBlockSessions: StateFlow<List<FocusSession>> =
        blockDate.flatMapLatest { date ->
            repo.observeInRange(date.toString(), date.toString())
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun prevBlockDate() {
        blockDate.value = blockDate.value.minusDays(1)
    }

    fun nextBlockDate() {
        val next = blockDate.value.plusDays(1)
        if (!next.isAfter(LocalDate.now())) blockDate.value = next
    }

    /** 2b. 当日有效专注：计时 ∪ 专注 App 使用（去重） */
    val todayEffective: StateFlow<EffectiveFocus?> =
        combine(
            currentDate.flatMapLatest { date ->
                repo.observeInRange(date.toString(), date.toString()).map { date to it }
            },
            focusPackages,
            switchToleranceMs,
        ) { (date, sessions), packages, toleranceMs ->
            TodayContext(date, sessions, packages, toleranceMs)
        }
            .flatMapLatest { ctx ->
                flow {
                    emit(
                        usageRepo.loadDayEffectiveFocus(
                            date = ctx.date,
                            sessions = ctx.sessions,
                            packages = ctx.packages,
                            gapToleranceMs = ctx.toleranceMs,
                        )
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // ===== 顶部核心指标：本月专注与环比（纯计时口径，App 数据仅系统保留期内可用） =====

    private val thisMonth: YearMonth = YearMonth.now()
    private val lastMonth: YearMonth = thisMonth.minusMonths(1)

    val monthTotal: StateFlow<Long> =
        repo.observeTotalInRange(
            thisMonth.atDay(1).toString(),
            thisMonth.atEndOfMonth().toString(),
        ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val lastMonthTotal: StateFlow<Long> =
        repo.observeTotalInRange(
            lastMonth.atDay(1).toString(),
            lastMonth.atEndOfMonth().toString(),
        ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    // ===== 项目分布（按事项类目，数据只能来自计时记录） =====

    val pieMode = MutableStateFlow(PieMode.DAY)

    val pieData: StateFlow<List<SliceStat>> =
        combine(currentDate, blockDate) { today, anchor -> today to anchor }
            .flatMapLatest { (today, anchorDate) ->
                pieMode.flatMapLatest { mode ->
                    val range = when (mode) {
                        PieMode.DAY -> anchorDate.toString() to anchorDate.toString()
                        PieMode.WEEK -> {
                            val weekStart = today.with(DayOfWeek.MONDAY)
                            weekStart.toString() to weekStart.plusDays(6).toString()
                        }

                        PieMode.MONTH -> {
                            val month = YearMonth.from(today)
                            month.atDay(1).toString() to month.atEndOfMonth().toString()
                        }
                    }
                    repo.observePieByName(range.first, range.second)
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ===== 周期可切换的图表 =====

    val viewedMonth = MutableStateFlow(YearMonth.now())
    val viewedYear = MutableStateFlow(LocalDate.now().year)

    /** 5. 时段分布（按小时，纯计时口径） */
    val hourBars: StateFlow<List<SliceStat>> =
        viewedMonth.flatMapLatest { month ->
            repo.observeHourBars(
                month.atDay(1).toString(),
                month.atEndOfMonth().toString(),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 6. 每日趋势：每天的有效专注（计时 ∪ 专注 App，去重） */
    val dailyLine: StateFlow<List<SliceStat>> =
        combine(viewedMonth, focusPackages, switchToleranceMs) { month, packages, toleranceMs ->
            Triple(month, packages, toleranceMs)
        }
            .flatMapLatest { (month, packages, toleranceMs) ->
                repo.observeInRange(
                    month.atDay(1).toString(),
                    month.atEndOfMonth().toString(),
                ).map { sessions -> MonthContext(month, sessions, packages, toleranceMs) }
            }
            .flatMapLatest { ctx ->
                flow {
                    val daily = usageRepo.loadDailyEffectiveFocus(
                        sessions = ctx.sessions,
                        packages = ctx.packages,
                        startDate = ctx.month.atDay(1),
                        endDate = ctx.month.atEndOfMonth(),
                        gapToleranceMs = ctx.toleranceMs,
                    )
                    emit(
                        daily.entries
                            .sortedBy { it.key }
                            .map { SliceStat(it.key.toString(), it.value) }
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 7. 年度趋势（按月，纯计时口径） */
    val monthly: StateFlow<List<SliceStat>> =
        viewedYear.flatMapLatest { year ->
            repo.observeMonthly("$year-01-01", "$year-12-31")
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun prevMonth() {
        viewedMonth.value = viewedMonth.value.minusMonths(1)
    }

    fun nextMonth() {
        val next = viewedMonth.value.plusMonths(1)
        if (!next.isAfter(YearMonth.now())) viewedMonth.value = next
    }

    fun prevYear() {
        viewedYear.value = viewedYear.value - 1
    }

    fun nextYear() {
        val next = viewedYear.value + 1
        if (next <= LocalDate.now().year) viewedYear.value = next
    }
}

/** 当日有效专注计算所需的上下文 */
private data class TodayContext(
    val date: LocalDate,
    val sessions: List<FocusSession>,
    val packages: Set<String>,
    val toleranceMs: Long,
)

/** 月度每日有效专注计算所需的上下文 */
private data class MonthContext(
    val month: YearMonth,
    val sessions: List<FocusSession>,
    val packages: Set<String>,
    val toleranceMs: Long,
)
