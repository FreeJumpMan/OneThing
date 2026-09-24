package com.example.focus.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.timerDataStore by preferencesDataStore(name = "timer_state")

/**
 * 计时状态的持久化存储。
 *
 * 关键设计：保存的是"运行段的 elapsedRealtime 起点"而不是总时长快照。
 * SystemClock.elapsedRealtime() 是系统级单调时钟（开机以来毫秒数），
 * 进程被杀重建后依然连续，所以恢复时能精确补上丢失的运行段（0 损失）。
 * 只有设备重启才会重置它，此时退化为暂停状态由用户手动继续。
 *
 * 因此运行过程中无需周期性写盘，只在状态变化（开始/暂停/继续/结束）时保存。
 */
class TimerStateStore(private val context: Context) {

    private object Keys {
        val NAME = stringPreferencesKey("name")
        val ACCUMULATED_MS = longPreferencesKey("accumulated_ms")
        val SEGMENT_START_ELAPSED = longPreferencesKey("segment_start_elapsed")
        val START_EPOCH_MS = longPreferencesKey("start_epoch_ms")
        val RUNNING = booleanPreferencesKey("running")
    }

    data class Persisted(
        val name: String,
        /** 已结算的累计时长（不含当前运行段） */
        val accumulatedMs: Long,
        /** 当前运行段的 elapsedRealtime 起点，暂停时为 0 */
        val segmentStartElapsed: Long,
        /** 会话真实开始时间（epoch 毫秒），结束落库用 */
        val startEpochMs: Long,
        val running: Boolean,
    )

    suspend fun save(
        name: String,
        accumulatedMs: Long,
        segmentStartElapsed: Long,
        startEpochMs: Long,
        running: Boolean,
    ) {
        try {
            context.timerDataStore.edit { prefs ->
                prefs[Keys.NAME] = name
                prefs[Keys.ACCUMULATED_MS] = accumulatedMs
                prefs[Keys.SEGMENT_START_ELAPSED] = segmentStartElapsed
                prefs[Keys.START_EPOCH_MS] = startEpochMs
                prefs[Keys.RUNNING] = running
            }
        } catch (e: Exception) {
            // 写入失败（磁盘/损坏）不致命，计时状态丢失可接受，绝不崩溃
        }
    }

    /**
     * 读取持久化的计时状态。
     * 数据损坏时返回 null（宁可丢状态也不能让 app 启动崩溃）。
     */
    suspend fun load(): Persisted? {
        return try {
            val prefs = context.timerDataStore.data.first()
            val name = prefs[Keys.NAME] ?: return null
            Persisted(
                name = name,
                accumulatedMs = prefs[Keys.ACCUMULATED_MS] ?: 0L,
                segmentStartElapsed = prefs[Keys.SEGMENT_START_ELAPSED] ?: 0L,
                startEpochMs = prefs[Keys.START_EPOCH_MS] ?: 0L,
                running = prefs[Keys.RUNNING] ?: false,
            )
        } catch (e: Exception) {
            null
        }
    }

    suspend fun clear() {
        try {
            context.timerDataStore.edit { it.clear() }
        } catch (e: Exception) {
            // 忽略，同上
        }
    }
}
