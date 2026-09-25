package com.example.focus

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.example.focus.data.prefs.AppSettings
import com.example.focus.data.prefs.SettingsStore
import com.example.focus.data.prefs.ThemeMode
import com.example.focus.ui.FocusApp
import com.example.focus.ui.theme.FocusTheme

/**
 * 唯一 Activity。
 * 计时状态的恢复在 FocusApplication 里完成（进程级一次），这里只负责主题与内容。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val settingsStore = remember { SettingsStore(this) }
            val settings by settingsStore.settings.collectAsState(initial = AppSettings())
            val darkTheme = when (settings.themeMode) {
                ThemeMode.FOLLOW_SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // 状态栏 / 导航栏图标颜色跟随应用主题（浅色底用深色图标）
            val view = LocalView.current
            SideEffect {
                val window = (view.context as Activity).window
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }

            FocusTheme(darkTheme = darkTheme) {
                FocusApp()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // 回到前台时补一次日历自动同步（内部有 15 分钟节流）
        (application as? FocusApplication)?.syncTodayToCalendarIfEnabled()
    }
}
