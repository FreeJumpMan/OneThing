package com.example.focus.ui.settings

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.ContextCompat
import com.example.focus.data.backup.AutoBackupManager
import com.example.focus.data.backup.BackupCheck
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
    private val backupManager = BackupManager(AppDatabase.get(application), store)
    private val autoBackupManager = AutoBackupManager(application, AppDatabase.get(application), store)
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
        pruneStaleCategoryExclusions()
    }

    /**
     * 排除集合里可能残留已删除分类的旧 id（例如 v1.7 把 8 个分类收到 5 个时留下），
     * 它们已经不影响同步，但会让设置页的数字和对话框里的开关对不上。启动时清一次。
     */
    private fun pruneStaleCategoryExclusions() {
        viewModelScope.launch {
            val validIds = categoryDao.getAll().map { it.id.toString() }.toSet()
            if (validIds.isEmpty()) return@launch // 分类还没落库（首次启动），不碰
            val excluded = store.settings.first().excludedCalendarCategories
            val cleaned = excluded.filterTo(mutableSetOf()) { it in validIds }
            if (cleaned != excluded) store.setExcludedCalendarCategories(cleaned)
        }
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

    /** 「一事 · 时间记录」专属日历的颜色 */
    fun setTimelineCalendarColor(color: Int) {
        viewModelScope.launch { store.setTimelineCalendarColor(color) }
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

    /** 导入前体检：校验文件归属与版本，并返回摘要供确认弹窗展示 */
    suspend fun checkBackup(json: String): BackupCheck = backupManager.checkBackup(json)

    /** 从 JSON 恢复数据，返回 null 表示成功否则为错误信息 */
    suspend fun restoreFromJson(json: String, keepCalendarMappings: Boolean): String? =
        backupManager.importData(json, keepCalendarMappings)

    // ===== 自动备份 =====

    /**
     * 选定备份目录：持久化授权 → 记下目录 → 立刻备一份验证目录真能写。
     * 返回 false 表示目录不可写（例如选到了只读位置），需要换一个。
     */
    suspend fun enableAutoBackup(uri: Uri): Boolean {
        val ctx = getApplication<Application>()
        // 授权在部分 ROM 上可能拒绝（如文件管理器返回不可持久化的树），失败不阻塞后续尝试
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        val name = autoBackupManager.directoryName(uri)
        store.setAutoBackupDir(uri.toString(), name)
        val ok = autoBackupManager.backupNow()
        store.setLastAutoBackup(System.currentTimeMillis(), ok)
        return ok
    }

    /** 关闭自动备份（只清除记录，不动目录里的文件） */
    suspend fun disableAutoBackup() {
        store.setAutoBackupDir(null, null)
    }

    /** 手动立即备份一次 */
    suspend fun backupNow(): Boolean {
        val ok = autoBackupManager.backupNow()
        store.setLastAutoBackup(System.currentTimeMillis(), ok)
        return ok
    }
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
