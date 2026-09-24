package com.example.focus

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.focus.ui.FocusApp
import com.example.focus.ui.theme.FocusTheme

/**
 * 唯一 Activity。计时状态的恢复在 FocusApplication 里完成（进程级一次），
 * 这里不做恢复，避免 Activity 重建时覆盖正在运行的计时。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FocusTheme {
                FocusApp()
            }
        }
    }
}
