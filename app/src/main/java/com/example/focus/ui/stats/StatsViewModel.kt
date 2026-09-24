package com.example.focus.ui.stats

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.focus.data.db.AppDatabase
import com.example.focus.data.db.CumulativeStats
import com.example.focus.data.db.DayStats
import com.example.focus.data.db.SliceStat
import com.example.focus.data.repo.SessionRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * 统计板块 ViewModel，覆盖七项指标：
 * 1. 累计专注（次数/时长/日均） 2. 当日专注
 * 4. 时长分布饼图（日/周/月） 5. 时段分布（跟随查看月份） 6. 月度每日曲线（可切换月份）
 * 7. 年度按月曲线（可切换年份）
 *
 * 关键设计：日期边界不缓存，用 currentDate 流每 30 秒检查一次，
 * 跨天/跨周/跨月/跨年后统计自动切换到新周期。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = SessionRepository(AppDatabase.get(application).focusDao())

    enum class PieMode { DAY, WEEK, MONTH }

    /** 当前日期流：每 30 秒检查，仅当日期变化（跨天）时发射新值 */
    private val currentDate: Flow<LocalDate> = flow {
        while (true) {
            emit(LocalDate.now())
            delay(30_000)
        }
    }.distinctUntilChanged()

    /** 1. 累计专注（全时段，无需日期参数） */
    val cumulative: StateFlow<CumulativeStats?> =
        repo.observeCumulative().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** 2. 当日专注，跨天自动切换 */
    val todayStats: StateFlow<DayStats?> =
        currentDate.flatMapLatest { date ->
            repo.observeDayStats(date.toString())
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // ===== 顶部核心指标：本月专注与环比 =====

    private val thisMonth: YearMonth = YearMonth.now()
    private val lastMonth: YearMonth = thisMonth.minusMonths(1)

    /** 本月总专注时长 */
    val monthTotal: StateFlow<Long> =
        repo.observeTotalInRange(
            thisMonth.atDay(1).toString(),
            thisMonth.atEndOfMonth().toString(),
        ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    /** 上月总专注时长（算环比） */
    val lastMonthTotal: StateFlow<Long> =
        repo.observeTotalInRange(
            lastMonth.atDay(1).toString(),
            lastMonth.atEndOfMonth().toString(),
        ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    /** 4. 饼图模式切换 */
    val pieMode = MutableStateFlow(PieMode.DAY)

    /** 4. 饼图数据：按事项类目分组，日/周/月切换时间范围 */
    val pieData: StateFlow<List<SliceStat>> =
        currentDate.flatMapLatest { today ->
            pieMode.flatMapLatest { mode ->
                val range = when (mode) {
                    PieMode.DAY -> today.toString() to today.toString()
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

    // ===== 时段分布与月度/年度曲线：支持切换查看周期 =====

    /** 时段分布与月度曲线当前查看的月份 */
    val viewedMonth = MutableStateFlow(YearMonth.now())

    /** 年度曲线当前查看的年份 */
    val viewedYear = MutableStateFlow(LocalDate.now().year)

    /** 5. 专注时段分布（按小时，跟随查看月份） */
    val hourBars: StateFlow<List<SliceStat>> =
        viewedMonth.flatMapLatest { month ->
            repo.observeHourBars(
                month.atDay(1).toString(),
                month.atEndOfMonth().toString(),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 6. 月度每日专注时长（跟随查看月份） */
    val dailyLine: StateFlow<List<SliceStat>> =
        viewedMonth.flatMapLatest { month ->
            repo.observeDailyLine(
                month.atDay(1).toString(),
                month.atEndOfMonth().toString(),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 7. 年度专注统计（按月，跟随查看年份） */
    val monthly: StateFlow<List<SliceStat>> =
        viewedYear.flatMapLatest { year ->
            repo.observeMonthly("$year-01-01", "$year-12-31")
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun prevMonth() {
        viewedMonth.value = viewedMonth.value.minusMonths(1)
    }

    fun nextMonth() {
        val next = viewedMonth.value.plusMonths(1)
        // 不允许查看未来月份
        if (!next.isAfter(YearMonth.now())) viewedMonth.value = next
    }

    fun prevYear() {
        viewedYear.value = viewedYear.value - 1
    }

    fun nextYear() {
        val next = viewedYear.value + 1
        // 不允许查看未来年份
        if (next <= LocalDate.now().year) viewedYear.value = next
    }
}
