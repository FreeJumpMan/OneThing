package com.example.focus.service

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 计时展示状态，进程内单例，由 TimerService 更新、UI 收集。
 * 不持久化，进程死了就没了，持久化恢复走 TimerStateStore。
 */
data class TimerUiState(
    val sessionName: String = "",
    /** 当前累计总时长，展示用 */
    val totalMs: Long = 0L,
    val running: Boolean = false,
    /** 会话是否已开始（区分"未开始"和"已暂停"） */
    val started: Boolean = false,
    /** 会话真实开始时间（epoch 毫秒），结束落库用 */
    val startEpochMs: Long = 0L,
    /** 已结算的时长（暂停前的累计），内部用 */
    val accumulatedMs: Long = 0L,
    /** 当前运行段的 elapsedRealtime 起点，内部用 */
    val segmentStartElapsed: Long = 0L,
)

object TimerStateHolder {

    private val _state = MutableStateFlow(TimerUiState())
    val state: StateFlow<TimerUiState> = _state.asStateFlow()

    fun update(transform: (TimerUiState) -> TimerUiState) {
        _state.value = transform(_state.value)
    }

    fun reset() {
        _state.value = TimerUiState()
    }

    /**
     * 从持久化精确恢复。
     * elapsedRealtime 是系统级单调时钟，进程重启后依然连续，因此运行段可以精确补回；
     * 若设备重启过（存的起点比当前 elapsedRealtime 还大）则退化为暂停状态。
     */
    fun restore(
        name: String,
        accumulatedMs: Long,
        segmentStartElapsed: Long,
        startEpochMs: Long,
        wasRunning: Boolean,
    ) {
        val now = SystemClock.elapsedRealtime()
        val canResume = wasRunning && segmentStartElapsed in 1..now
        val total = if (canResume) {
            accumulatedMs + (now - segmentStartElapsed)
        } else {
            accumulatedMs
        }
        _state.value = TimerUiState(
            sessionName = name,
            totalMs = total,
            running = canResume,
            started = true,
            startEpochMs = startEpochMs,
            accumulatedMs = accumulatedMs,
            segmentStartElapsed = if (canResume) segmentStartElapsed else now,
        )
    }
}
