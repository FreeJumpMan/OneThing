package com.example.focus

import android.app.Application
import com.example.focus.data.prefs.TimerStateStore
import com.example.focus.service.TimerService
import com.example.focus.service.TimerStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 应用入口：只在**进程创建时**恢复一次未结束的计时。
 *
 * 为什么放在 Application 而不是 Activity：
 * Activity 从后台返回时可能被销毁重建，若在重建时再次读取存档恢复，
 * 会用较旧的快照覆盖内存里正在走的计时，表现为"时间倒退/计时停止"。
 * Application.onCreate 每个进程只执行一次，天然避免这个问题。
 */
class FocusApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            val persisted = TimerStateStore(this@FocusApplication).load() ?: return@launch
            if (persisted.name.isEmpty()) return@launch
            TimerStateHolder.restore(
                name = persisted.name,
                accumulatedMs = persisted.accumulatedMs,
                segmentStartElapsed = persisted.segmentStartElapsed,
                startEpochMs = persisted.startEpochMs,
                wasRunning = persisted.running,
            )
            // 确保前台服务在运行（服务被系统重建时也会自行从存档恢复）
            TimerService.ensureRunning(this@FocusApplication)
        }
    }
}
