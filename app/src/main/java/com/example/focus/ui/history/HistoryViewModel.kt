package com.example.focus.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.focus.data.backup.BackupManager
import com.example.focus.data.db.AppDatabase
import com.example.focus.data.db.FocusSession
import com.example.focus.data.repo.SessionRepository
import com.example.focus.data.sync.CalendarSyncManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = SessionRepository(AppDatabase.get(application).focusDao())
    private val syncManager = CalendarSyncManager(
        application,
        AppDatabase.get(application).calendarSyncDao(),
    )
    private val backupManager = BackupManager(AppDatabase.get(application))

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

    /** 已同步到日历的会话 id 集合 */
    private val _syncedIds = MutableStateFlow<Set<Long>>(emptySet())
    val syncedIds: StateFlow<Set<Long>> = _syncedIds.asStateFlow()

    /** 等待授权的待同步会话（授权后自动重试） */
    private val _pendingSync = MutableStateFlow<FocusSession?>(null)
    val pendingSync: StateFlow<FocusSession?> = _pendingSync.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

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
        val session = _pendingSync.value ?: return
        _pendingSync.value = null
        // 防御：重试前再确认两个权限都在，防止 ROM 只授予了其中一个
        if (!syncManager.hasCalendarPermission()) {
            _error.value = "日历读写权限不完整，请在系统设置中检查日历权限"
            return
        }
        doSync(session)
    }

    fun dismissPendingSync() {
        _pendingSync.value = null
    }

    /** 权限被永久拒绝时调用，弹出引导去设置页 */
    fun onPermissionDeniedPermanently() {
        _pendingSync.value = null
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
            }.onSuccess {
                _syncedIds.update { ids -> ids + session.id }
            }.onFailure { e ->
                _error.value = if (e is SecurityException) {
                    "日历权限不足，请在系统设置中检查日历权限"
                } else {
                    e.message ?: "同步到日历失败"
                }
            }
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
            _syncedIds.update { ids -> ids - session.id }
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

    fun clearError() {
        _error.value = null
    }

    /** 导出全部业务数据为 JSON 字符串 */
    suspend fun buildBackupJson(): String = backupManager.exportData()

    /** 从 JSON 恢复数据，返回 null 表示成功否则为错误信息 */
    suspend fun restoreFromJson(json: String): String? = backupManager.importData(json)

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
