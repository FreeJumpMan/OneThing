package com.example.focus.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "app_settings")

/** 深色模式选项 */
enum class ThemeMode {
    FOLLOW_SYSTEM,
    LIGHT,
    DARK,
}

/** 用户可配置项 */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.FOLLOW_SYSTEM,
    /** 短暂切换阈值（分钟）：离开专注 App 不超过该时长视为未中断 */
    val switchToleranceMinutes: Int = 2,
    /**
     * 不写入系统日历的时间分类 id（存字符串）。
     * 默认空集合 = 全部分类都同步；新分类默认允许，无需额外配置。
     */
    val excludedCalendarCategories: Set<String> = emptySet(),
    /** 结束专注后自动把这条记录同步到系统日历 */
    val autoSyncCalendar: Boolean = false,
    /** 把当天的 App 使用时间块自动同步到系统日历（遵循 excludedCalendarCategories） */
    val autoSyncAppUsage: Boolean = false,
    /** 「一事 · 时间记录」专属日历的颜色（ARGB） */
    val timelineCalendarColor: Int = 0xFF48B59B.toInt(),
    /** 自动备份目录（SAF 树 URI 字符串）；null = 未开启 */
    val autoBackupDir: String? = null,
    /** 自动备份目录的显示名（仅用于设置页展示） */
    val autoBackupDirName: String? = null,
    /** 上一次自动备份的时间戳，0 = 从未备份过 */
    val lastAutoBackupAt: Long = 0L,
    /** 上一次自动备份是否成功（目录被删/权限失效时为 false） */
    val lastAutoBackupOk: Boolean = true,
)

/**
 * 应用设置存储（DataStore）。
 * 存的是「配置」，业务数据仍在 Room。
 */
class SettingsStore(private val context: Context) {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val SWITCH_TOLERANCE_MINUTES = intPreferencesKey("switch_tolerance_minutes")
        val EXCLUDED_CALENDAR_CATEGORIES = stringSetPreferencesKey("excluded_calendar_categories")
        val AUTO_SYNC_CALENDAR = booleanPreferencesKey("auto_sync_calendar")
        val AUTO_SYNC_APP_USAGE = booleanPreferencesKey("auto_sync_app_usage")
        val TIMELINE_CALENDAR_COLOR = intPreferencesKey("timeline_calendar_color")
        val AUTO_BACKUP_DIR = stringPreferencesKey("auto_backup_dir")
        val AUTO_BACKUP_DIR_NAME = stringPreferencesKey("auto_backup_dir_name")
        val LAST_AUTO_BACKUP_AT = longPreferencesKey("last_auto_backup_at")
        val LAST_AUTO_BACKUP_OK = booleanPreferencesKey("last_auto_backup_ok")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        AppSettings(
            themeMode = prefs[Keys.THEME_MODE]
                ?.let { name -> runCatching { ThemeMode.valueOf(name) }.getOrNull() }
                ?: ThemeMode.FOLLOW_SYSTEM,
            switchToleranceMinutes = prefs[Keys.SWITCH_TOLERANCE_MINUTES] ?: 2,
            excludedCalendarCategories = prefs[Keys.EXCLUDED_CALENDAR_CATEGORIES] ?: emptySet(),
            autoSyncCalendar = prefs[Keys.AUTO_SYNC_CALENDAR] ?: false,
            autoSyncAppUsage = prefs[Keys.AUTO_SYNC_APP_USAGE] ?: false,
            timelineCalendarColor = prefs[Keys.TIMELINE_CALENDAR_COLOR] ?: 0xFF48B59B.toInt(),
            autoBackupDir = prefs[Keys.AUTO_BACKUP_DIR],
            autoBackupDirName = prefs[Keys.AUTO_BACKUP_DIR_NAME],
            lastAutoBackupAt = prefs[Keys.LAST_AUTO_BACKUP_AT] ?: 0L,
            lastAutoBackupOk = prefs[Keys.LAST_AUTO_BACKUP_OK] ?: true,
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setSwitchTolerance(minutes: Int) {
        context.settingsDataStore.edit { it[Keys.SWITCH_TOLERANCE_MINUTES] = minutes }
    }

    suspend fun setExcludedCalendarCategories(categoryIds: Set<String>) {
        context.settingsDataStore.edit { it[Keys.EXCLUDED_CALENDAR_CATEGORIES] = categoryIds }
    }

    suspend fun setAutoSyncCalendar(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.AUTO_SYNC_CALENDAR] = enabled }
    }

    suspend fun setAutoSyncAppUsage(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.AUTO_SYNC_APP_USAGE] = enabled }
    }

    suspend fun setTimelineCalendarColor(color: Int) {
        context.settingsDataStore.edit { it[Keys.TIMELINE_CALENDAR_COLOR] = color }
    }

    /**
     * 设置自动备份目录（null = 关闭自动备份）。
     * 目录 URI 与显示名一起存，避免设置页展示时再去查一次 DocumentsProvider。
     */
    suspend fun setAutoBackupDir(dir: String?, dirName: String?) {
        context.settingsDataStore.edit { prefs ->
            if (dir == null) {
                prefs.remove(Keys.AUTO_BACKUP_DIR)
                prefs.remove(Keys.AUTO_BACKUP_DIR_NAME)
            } else {
                prefs[Keys.AUTO_BACKUP_DIR] = dir
                if (dirName != null) prefs[Keys.AUTO_BACKUP_DIR_NAME] = dirName
            }
        }
    }

    /** 记录一次备份结果（成功与否都记，好在设置页如实显示） */
    suspend fun setLastAutoBackup(atMs: Long, ok: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.LAST_AUTO_BACKUP_AT] = atMs
            prefs[Keys.LAST_AUTO_BACKUP_OK] = ok
        }
    }

    /**
     * 用一份设置整体覆盖当前设置（备份恢复用）。
     * 逐项写入而不是替换整个 DataStore，避免抹掉将来可能新增的其他键。
     * 注意：自动备份目录属于「本机环境」，不随备份迁移，所以不在这里恢复。
     */
    suspend fun replaceAll(settings: AppSettings) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.THEME_MODE] = settings.themeMode.name
            prefs[Keys.SWITCH_TOLERANCE_MINUTES] = settings.switchToleranceMinutes
            prefs[Keys.EXCLUDED_CALENDAR_CATEGORIES] = settings.excludedCalendarCategories
            prefs[Keys.AUTO_SYNC_CALENDAR] = settings.autoSyncCalendar
            prefs[Keys.AUTO_SYNC_APP_USAGE] = settings.autoSyncAppUsage
            prefs[Keys.TIMELINE_CALENDAR_COLOR] = settings.timelineCalendarColor
        }
    }
}
