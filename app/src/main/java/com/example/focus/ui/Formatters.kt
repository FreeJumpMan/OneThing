package com.example.focus.ui

import androidx.compose.ui.graphics.Color
import java.time.format.DateTimeFormatter

/** 计时大数字：mm:ss 或 h:mm:ss */
fun formatClock(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
    else String.format("%02d:%02d", m, s)
}

/** 时长展示：45分钟 / 1小时30分 */
fun formatDuration(ms: Long): String {
    val totalMin = ms / 60_000
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h > 0 && m > 0 -> "${h}小时${m}分"
        h > 0 -> "${h}小时"
        else -> "${m}分钟"
    }
}

/** 紧凑时长：45m / 12h30m（用于空间受限的统计卡片，避免文字换行） */
fun formatDurationCompact(ms: Long): String {
    val totalMin = ms / 60_000
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h > 0 && m > 0 -> "${h}h${m}m"
        h > 0 -> "${h}h"
        else -> "${m}m"
    }
}

/** 图表短标签：小时 "08" → "08时"；日期 "2026-08-27" → "08/27" */
fun shortLabel(label: String): String =
    if (label.length == 2) "${label}时"
    else label.substring(5).replace("-", "/")

private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** epoch 毫秒 → "HH:mm" */
fun formatTimeOfDay(epochMs: Long): String =
    java.time.Instant.ofEpochMilli(epochMs)
        .atZone(java.time.ZoneId.systemDefault())
        .format(timeFormatter)

/** 十六进制颜色字符串 → Compose 颜色；非法值回退暖灰 */
fun parseHexColor(hex: String): Color = runCatching {
    Color(android.graphics.Color.parseColor(hex))
}.getOrDefault(Color(0xFFB8AFA6))
