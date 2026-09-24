package com.example.focus.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一条专注会话记录。
 * date 字段按开始时间计算（"yyyy-MM-dd"），统计按它分组。
 * durationMs 用 SystemClock.elapsedRealtime() 差值，不受系统时间调整影响。
 */
@Entity(tableName = "focus_sessions")
data class FocusSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val durationMs: Long,
    val date: String,
)
