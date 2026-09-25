package com.example.focus.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.focus.data.db.AppDatabase
import com.example.focus.data.db.FocusSession
import com.example.focus.data.db.HiddenUsageSegment
import com.example.focus.data.prefs.SettingsStore
import com.example.focus.data.repo.SessionRepository
import com.example.focus.data.sync.CalendarSyncManager
import com.example.focus.data.usage.IntervalMerger
import com.example.focus.data.usage.TimeInterval
import com.example.focus.data.usage.UsageStatsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 历史板块 ViewModel。
 * 日历视图数据：当前月份 + 有记录的日期集合 + 选中日期当天的会话。
 * 日程视图数据：全部会话。
 * 日历同步：权限不足时挂起待同步会话，授权后重试。
 */
/** 专注 App 的自动使用记录（由系统使用记录派生，仅供展示，不入库） */
data class AutoRecord(
    val appNames: List<String>,
    val startMs: Long,
    val endMs: Long,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
    val displayName: String get() = appNames.joinToString("、")
}

/** 「一键同步」的撤回凭据：本次写入的条数与会话 id */
data class SyncUndoState(
    val count: Int,
    val sessionIds: List<Long>,
)

/** 隐藏自动记录的撤销凭据 */
data class HiddenUndo(
    val id: Long,
    val label: String,
)

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = SessionRepository(AppDatabase.get(application).focusDao())
    private val syncManager = CalendarSyncManager(
        application,
        AppDatabase.get(application).calendarSyncDao(),
        AppDatabase.get(application).timelineEventDao(),
    )
    private val usageRepo = UsageStatsRepository(application)
    private val settingsStore = SettingsStore(application)
    private val focusAppDao = AppDatabase.get(application).focusAppDao()
    private val hiddenSegmentDao = AppDatabase.get(application).hiddenUsageSegmentDao()

    val currentMonth = MutableStateFlow(YearMonth.now())
    val selectedDate = MutableStateFlow(LocalDate.now())

    /** 日程视图：全部会话 */
    val allSessions: StateFlow<List<FocusSession>> =
        repo.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 当前月份中有记录的日期及记录数（日历热力点） */
    val markCounts: StateFlow<Map<Int, Int>> =
        currentMonth.flatMapLatest { month ->
            repo.observeCountsByDay(month.atDay(1).toString(), month.atEndOfMonth().toString())
                .map { list ->
                    list.mapNotNull { stat ->
                        stat.label.toIntOrNull()?.let { it to stat.totalMs.toInt() }
                    }.toMap()
                }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** 选中日期当天的会话 */
    val selectedDaySessions: StateFlow<List<FocusSession>> =
        selectedDate.flatMapLatest { date ->
            repo.observeByDate(date.toString())
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 用户标记为「专注 App」的包名集合 */
    private val focusPackages: Flow<Set<String>> =
        focusAppDao.observeAll().map { list ->
            list.filter { it.enabled }.map { it.packageName }.toSet()
        }

    /** 自动记录刷新信号：从后台切回时变化，触发重新读取系统使用记录 */
    private val autoRefreshTick = MutableStateFlow(0L)

    /**
     * 选中日期当天的「专注 App 自动记录」：来自系统使用记录，与计时记录去重后的剩余部分。
     * 展示层实时派生、不落库；仅覆盖系统使用记录保留期内的日期。
     */
    val autoRecords: StateFlow<List<AutoRecord>> =
        combine(
            selectedDate,
            focusPackages,
            selectedDaySessions,
            autoRefreshTick,
            hiddenSegmentDao.observeAll(),
        ) { date, packages, sessions, _, hidden ->
            loadAutoRecords(date, packages, sessions, hidden)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 界面回到前台时调用：自动记录不落库，需要主动刷新系统数据 */
    fun refreshAutoRecords() {
        autoRefreshTick.value = System.currentTimeMillis()
    }

    /** 已同步到日历的会话 id 集合（从映射表实时派生，手动与自动同步都覆盖） */
    val syncedIds: StateFlow<Set<Long>> =
        AppDatabase.get(application).calendarSyncDao().observeAll()
            .map { list -> list.map { it.sessionId }.toSet() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /** 等待授权的待同步会话（授权后自动重试） */
    private val _pendingSync = MutableStateFlow<FocusSession?>(null)
    val pendingSync: StateFlow<FocusSession?> = _pendingSync.asStateFlow()

    /** 批量同步（当天全部）等待授权标记 */
    private val _pendingSyncAll = MutableStateFlow(false)
    val pendingSyncAll: StateFlow<Boolean> = _pendingSyncAll.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** 最近一次「一键同步」的撤回状态（同步成功后出现，用于底部浮条） */
    private val _syncUndo = MutableStateFlow<SyncUndoState?>(null)
    val syncUndo: StateFlow<SyncUndoState?> = _syncUndo.asStateFlow()

    /** 撤回最近一次「一键同步」：删掉本次创建的事件与映射 */
    fun undoLastSync() {
        val state = _syncUndo.value ?: return
        _syncUndo.value = null
        viewModelScope.launch {
            state.sessionIds.forEach { id ->
                runCatching { syncManager.removeSessionEvents(id) }
            }
        }
    }

    fun dismissSyncUndo() {
        _syncUndo.value = null
    }

    /** 权限被永久拒绝（不再询问）时，引导用户去系统设置开启 */
    private val _showSettingsGuide = MutableStateFlow(false)
    val showSettingsGuide: StateFlow<Boolean> = _showSettingsGuide.asStateFlow()

    fun prevMonth() {
        currentMonth.value = currentMonth.value.minusMonths(1)
        // 选中日期跟随月份首日，避免停留在不存在的日期
        selectedDate.value = currentMonth.value.atDay(1)
    }

    fun nextMonth() {
        currentMonth.value = currentMonth.value.plusMonths(1)
        selectedDate.value = currentMonth.value.atDay(1)
    }

    fun select(date: LocalDate) {
        selectedDate.value = date
    }

    fun syncToCalendar(session: FocusSession) {
        if (!syncManager.hasCalendarPermission()) {
            _pendingSync.value = session
            return
        }
        doSync(session)
    }

    /** 权限授权后由 UI 调用，重试挂起的同步 */
    fun retryPendingSync() {
        // 批量同步（当天全部）
        if (_pendingSyncAll.value) {
            _pendingSyncAll.value = false
            if (syncManager.hasCalendarPermission()) {
                syncSessionsToCalendar(selectedDaySessions.value)
            }
            return
        }
        val session = _pendingSync.value ?: return
        _pendingSync.value = null
        // 防御：重试前再确认两个权限都在，防止 ROM 只授予了其中一个
        if (!syncManager.hasCalendarPermission()) {
            _error.value = "日历读写权限不完整，请在系统设置中检查日历权限"
            return
        }
        doSync(session)
    }

    /** 一键同步选中日期的全部记录（已同步的自动跳过） */
    fun syncSelectedDay() {
        val sessions = selectedDaySessions.value
        if (sessions.isEmpty()) return
        if (!syncManager.hasCalendarPermission()) {
            _pendingSyncAll.value = true
            return
        }
        syncSessionsToCalendar(sessions)
    }

    private fun syncSessionsToCalendar(sessions: List<FocusSession>) {
        viewModelScope.launch {
            var failure: String? = null
            val syncedNow = mutableListOf<Long>()
            sessions.forEach { session ->
                if (session.id in syncedIds.value) return@forEach
                runCatching {
                    syncManager.syncSession(
                        sessionId = session.id,
                        title = session.name,
                        startTimeMs = session.startTimeMs,
                        endTimeMs = session.endTimeMs,
                        durationMs = session.durationMs,
                    )
                }.onSuccess {
                    syncedNow += session.id
                }.onFailure { e ->
                    failure = if (e is SecurityException) {
                        "日历权限不足，请在系统设置中检查日历权限"
                    } else {
                        e.message ?: "同步到日历失败"
                    }
                }
            }
            if (syncedNow.isNotEmpty()) {
                _syncUndo.value = SyncUndoState(count = syncedNow.size, sessionIds = syncedNow)
            }
            failure?.let { _error.value = it }
        }
    }

    fun dismissPendingSync() {
        _pendingSync.value = null
        _pendingSyncAll.value = false
    }

    /** 权限被永久拒绝时调用，弹出引导去设置页 */
    fun onPermissionDeniedPermanently() {
        _pendingSync.value = null
        _pendingSyncAll.value = false
        _showSettingsGuide.value = true
    }

    fun dismissSettingsGuide() {
        _showSettingsGuide.value = false
    }

    private fun doSync(session: FocusSession) {
        viewModelScope.launch {
            runCatching {
                syncManager.syncSession(
                    sessionId = session.id,
                    title = session.name,
                    startTimeMs = session.startTimeMs,
                    endTimeMs = session.endTimeMs,
                    durationMs = session.durationMs,
                )
            }.onFailure { e ->
                _error.value = if (e is SecurityException) {
                    "日历权限不足，请在系统设置中检查日历权限"
                } else {
                    e.message ?: "同步到日历失败"
                }
            }
        }
    }

    /** 最近一次「隐藏自动记录」的撤销状态 */
    private val _hiddenUndo = MutableStateFlow<HiddenUndo?>(null)
    val hiddenUndo: StateFlow<HiddenUndo?> = _hiddenUndo.asStateFlow()

    /**
     * 隐藏一段自动记录。
     * 自动记录是从系统使用记录实时派生的，改不了源数据，所以「删除」= 记下这个区间不再展示。
     */
    fun hideAutoRecord(record: AutoRecord) {
        viewModelScope.launch {
            val id = hiddenSegmentDao.insert(
                HiddenUsageSegment(
                    startMs = record.startMs,
                    endMs = record.endMs,
                    appName = record.displayName,
                )
            )
            _hiddenUndo.value = HiddenUndo(id = id, label = record.displayName)
        }
    }

    /** 撤销删除：把隐藏记录移除，那段自动记录会重新出现 */
    fun undoHideAutoRecord() {
        val undo = _hiddenUndo.value ?: return
        _hiddenUndo.value = null
        viewModelScope.launch { hiddenSegmentDao.deleteById(undo.id) }
    }

    fun dismissHiddenUndo() {
        _hiddenUndo.value = null
    }

    /**
     * 把一段自动记录改成一条真实记录（可改名称与起止时间）。
     * 原自动段一并隐藏，避免转完还在时间线上出现两条。
     * 新记录与手动添加的一样，可以继续编辑、删除、同步到日历。
     */
    fun convertAutoRecord(record: AutoRecord, name: String, startMs: Long, endMs: Long) {
        viewModelScope.launch {
            hiddenSegmentDao.insert(
                HiddenUsageSegment(
                    startMs = record.startMs,
                    endMs = record.endMs,
                    appName = record.displayName,
                )
            )
            repo.insert(
                FocusSession(
                    name = name,
                    startTimeMs = startMs,
                    endTimeMs = endMs,
                    durationMs = endMs - startMs,
                    date = formatDate(startMs),
                )
            )
        }
    }

    fun delete(session: FocusSession, deleteCalendarEvent: Boolean = true) {
        viewModelScope.launch {
            repo.delete(session)
            if (deleteCalendarEvent) {
                syncManager.removeSessionEvents(session.id)
            } else {
                // 保留系统日历事件，只清掉本地映射
                syncManager.clearSessionMapping(session.id)
            }
        }
    }

    /** 手动添加一条专注记录（不经计时直接录入） */
    fun addSession(name: String, startMs: Long, endMs: Long) {
        viewModelScope.launch {
            repo.insert(
                FocusSession(
                    name = name,
                    startTimeMs = startMs,
                    endTimeMs = endMs,
                    durationMs = endMs - startMs,
                    date = formatDate(startMs),
                )
            )
        }
    }

    /** 编辑会话：名称、起止时间。若已同步到日历则联动更新日历事件 */
    fun updateSession(session: FocusSession, name: String, startMs: Long, endMs: Long) {
        viewModelScope.launch {
            val updated = session.copy(
                name = name,
                startTimeMs = startMs,
                endTimeMs = endMs,
                durationMs = endMs - startMs,
                date = formatDate(startMs),
            )
            repo.update(updated)
            if (syncManager.isSessionSynced(session.id)) {
                runCatching {
                    syncManager.syncSession(
                        sessionId = updated.id,
                        title = updated.name,
                        startTimeMs = updated.startTimeMs,
                        endTimeMs = updated.endTimeMs,
                        durationMs = updated.durationMs,
                    )
                }
            }
        }
    }

    private fun formatDate(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
            .toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)

    /**
     * 读取某天的「专注 App 自动记录」：系统使用片段剪掉计时记录覆盖的时段。
     * 剩余不足 1 分钟的碎片不展示（避免视觉噪音）。
     */
    private suspend fun loadAutoRecords(
        date: LocalDate,
        packages: Set<String>,
        sessions: List<FocusSession>,
        hidden: List<HiddenUsageSegment>,
    ): List<AutoRecord> {
        if (packages.isEmpty() || !usageRepo.hasUsageAccess()) return emptyList()

        val zone = ZoneId.systemDefault()
        val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val toleranceMs = settingsStore.settings.first().switchToleranceMinutes * 60_000L

        val segments = usageRepo.loadFocusUsageSegments(packages, dayStart, dayEnd, toleranceMs)
        if (segments.isEmpty()) return emptyList()

        // 与当天计时记录重叠的部分剪掉：同一段时间不重复展示
        val sessionIntervals = sessions
            .filter { it.startTimeMs < dayEnd && it.endTimeMs > dayStart }
            .map {
                TimeInterval(
                    maxOf(it.startTimeMs, dayStart),
                    minOf(it.endTimeMs, dayEnd),
                )
            }

        val minDisplayMs = 60_000L
        return segments.flatMap { segment ->
            IntervalMerger.subtract(
                listOf(TimeInterval(segment.startMs, segment.endMs)),
                sessionIntervals,
            )
                .filter { it.durationMs >= minDisplayMs }
                // 被删掉的自动记录：与隐藏区间重叠过半就整段不展示
                .filter { remaining -> hidden.none { overlapsMostly(remaining, it) } }
                .map { remaining ->
                    AutoRecord(
                        appNames = segment.appNames,
                        startMs = remaining.startMs,
                        endMs = remaining.endMs,
                    )
                }
        }
    }

    fun clearError() {
        _error.value = null
    }

    /** 片段与隐藏区间的重叠是否超过自身一半（允许系统数据轻微偏移，不必精确相等） */
    private fun overlapsMostly(piece: TimeInterval, hidden: HiddenUsageSegment): Boolean {
        val overlap = minOf(piece.endMs, hidden.endMs) - maxOf(piece.startMs, hidden.startMs)
        return overlap * 2 >= piece.durationMs
    }

    // ===== 诊断：探查国产 ROM 日历自定义字段 =====

    private val _debugColumns = MutableStateFlow<Pair<List<String>, List<String>>?>(null)
    val debugColumns: StateFlow<Pair<List<String>, List<String>>?> = _debugColumns.asStateFlow()

    private val _debugRow = MutableStateFlow<Map<String, String>?>(null)
    val debugRow: StateFlow<Map<String, String>?> = _debugRow.asStateFlow()

    private val _debugCalendars = MutableStateFlow<List<String>?>(null)
    val debugCalendars: StateFlow<List<String>?> = _debugCalendars.asStateFlow()

    fun loadDebugColumns() {
        viewModelScope.launch {
            _debugColumns.value = syncManager.inspectEventColumns() to syncManager.inspectCalendarColumns()
            _debugCalendars.value = syncManager.inspectCalendars()
        }
    }

    fun loadDebugRow(title: String) {
        viewModelScope.launch {
            _debugRow.value = syncManager.inspectEventRow(title)
        }
    }

    fun clearDebug() {
        _debugColumns.value = null
        _debugRow.value = null
        _debugCalendars.value = null
    }

    /** 诊断入口是否有日历权限（没有则需先走权限申请） */
    fun hasCalendarPermission(): Boolean = syncManager.hasCalendarPermission()

    /** 诊断等待授权标记：授权成功后由 UI 打开诊断对话框 */
    private val _debugPending = MutableStateFlow(false)
    val debugPending: StateFlow<Boolean> = _debugPending.asStateFlow()

    fun requestDebugPermission() {
        _debugPending.value = true
    }

    fun debugPermissionGranted() {
        _debugPending.value = false
    }

    fun debugPermissionDenied() {
        _debugPending.value = false
    }
}
