package com.example.focus.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
}
