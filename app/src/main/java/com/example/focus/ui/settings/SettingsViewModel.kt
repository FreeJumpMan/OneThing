package com.example.focus.ui.settings

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.ContextCompat
import com.example.focus.data.backup.BackupManager
import com.example.focus.data.db.AppDatabase
import com.example.focus.data.db.FocusApp
import com.example.focus.data.db.TimeCategory
import com.example.focus.data.prefs.AppSettings
import com.example.focus.data.prefs.SettingsStore
import com.example.focus.data.prefs.ThemeMode
import com.example.focus.data.usage.UsageStatsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/** 各权限的当前状态 */
data class PermissionState(
    val notification: Boolean = false,
    val calendar: Boolean = false,
    val usageAccess: Boolean = false,
    val ignoringBatteryOptimization: Boolean = false,
)

/** 专注 App 管理页的一项 */
data class FocusAppCandidate(
    val packageName: String,
    val appName: String,
    val icon: ImageBitmap?,
    val recentMs: Long,
    val enabled: Boolean,
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val store = SettingsStore(application)
    private val focusAppDao = AppDatabase.get(application).focusAppDao()
    private val usageRepo = UsageStatsRepository(application)
    private val backupManager = BackupManager(AppDatabase.get(application))
    private val categoryDao = AppDatabase.get(application).timeCategoryDao()

    val settings: StateFlow<AppSettings> = store.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    /** 时间分类列表（用于“同步的分类”选择） */
    val categories: StateFlow<List<TimeCategory>> = categoryDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _permissions = MutableStateFlow(PermissionState())
    val permissions: StateFlow<PermissionState> = _permissions.asStateFlow()

    private val _candidates = MutableStateFlow<List<FocusAppCandidate>>(emptyList())
    val candidates: StateFlow<List<FocusAppCandidate>> = _candidates.asStateFlow()

    private val _candidatesLoading = MutableStateFlow(false)
    val candidatesLoading: StateFlow<Boolean> = _candidatesLoading.asStateFlow()

    init {
        refreshPermissions()
    }

    fun refreshPermissions() {
        val ctx = getApplication<Application>()
        _permissions.value = PermissionState(
            notification = ctx.hasNotificationPermission(),
            calendar = ctx.hasCalendarPermission(),
            usageAccess = usageRepo.hasUsageAccess(),
            ignoringBatteryOptimization = ctx.isIgnoringBatteryOptimizations(),
        )
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { store.setThemeMode(mode) }
    }

    fun setSwitchTolerance(minutes: Int) {
        viewModelScope.launch { store.setSwitchTolerance(minutes) }
    }

    /** 开关某个分类是否写入系统日历 */
    fun setCategoryCalendarSync(categoryId: Long, enabled: Boolean) {
        viewModelScope.launch {
            val current = store.settings.first().excludedCalendarCategories
            val next = if (enabled) {
                current - categoryId.toString()
            } else {
                current + categoryId.toString()
            }
            store.setExcludedCalendarCategories(next)
        }
    }

    /** 结束专注后自动同步到日历的开关 */
    fun setAutoSyncCalendar(enabled: Boolean) {
        viewModelScope.launch { store.setAutoSyncCalendar(enabled) }
    }

    /** App 使用自动同步到日历的开关 */
    fun setAutoSyncAppUsage(enabled: Boolean) {
        viewModelScope.launch { store.setAutoSyncAppUsage(enabled) }
    }

    /** 加载最近 7 天用过的 App 作为专注 App 候选（含已标记的） */
    fun loadCandidates() {
        viewModelScope.launch {
            _candidatesLoading.value = true
            val today = LocalDate.now()
            val used = if (usageRepo.hasUsageAccess()) {
                usageRepo.loadRange(today.minusDays(6), today)
            } else {
                emptyList()
            }
            val enabledPackages = focusAppDao.getEnabled().map { it.packageName }.toSet()
            _candidates.value = used.map {
                FocusAppCandidate(
                    packageName = it.packageName,
                    appName = it.appName,
                    icon = it.icon,
                    recentMs = it.durationMs,
                    enabled = it.packageName in enabledPackages,
                )
            }
            _candidatesLoading.value = false
        }
    }

    fun toggleFocusApp(candidate: FocusAppCandidate, enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) {
                focusAppDao.upsert(
                    FocusApp(candidate.packageName, candidate.appName, true)
                )
            } else {
                focusAppDao.delete(candidate.packageName)
            }
            _candidates.update { list ->
                list.map {
                    if (it.packageName == candidate.packageName) it.copy(enabled = enabled) else it
                }
            }
        }
    }

    suspend fun buildBackupJson(): String = backupManager.exportData()

    suspend fun restoreFromJson(json: String): String? = backupManager.importData(json)
}

// ===== 权限状态检查 =====

internal fun Context.hasNotificationPermission(): Boolean =
    if (Build.VERSION.SDK_INT >= 33) {
        ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }

internal fun Context.hasCalendarPermission(): Boolean =
    ContextCompat.checkSelfPermission(
        this, Manifest.permission.READ_CALENDAR
    ) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(
            this, Manifest.permission.WRITE_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED

internal fun Context.isIgnoringBatteryOptimizations(): Boolean {
    val powerManager = getSystemService(PowerManager::class.java) ?: return true
    return runCatching {
        powerManager.isIgnoringBatteryOptimizations(packageName)
    }.getOrDefault(true)
}
