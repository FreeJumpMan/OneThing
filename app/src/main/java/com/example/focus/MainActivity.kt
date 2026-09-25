package com.example.focus

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
        super.onCreate(savedInstanceState)
        setContent {
            val settingsStore = remember { SettingsStore(this) }
            val settings by settingsStore.settings.collectAsState(initial = AppSettings())
            val darkTheme = when (settings.themeMode) {
                ThemeMode.FOLLOW_SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            FocusTheme(darkTheme = darkTheme) {
                FocusApp()
            }
        }
    }
}
