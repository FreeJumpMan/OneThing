package com.example.focus.data.usage

/** 一段连续时间区间（毫秒时间戳） */
data class TimeInterval(val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

/**
 * 时间区间合并工具。
 *
 * 用于把「专注计时」与「专注 App 使用」两套时间数据合并去重，
 * 避免同一段时间被重复计算（例如背单词 App 用了 45 分钟，
 * 同时用户也开了 45 分钟计时，不能算成 90 分钟）。
 */
object IntervalMerger {

    /**
     * 合并重叠或相邻的区间。
     *
     * @param gapToleranceMs 间隔不超过该值时也拼接为一段，
     *        用于实现「短暂切换阈值」：从专注 App 短暂切走再切回来，
     *        中间的小段空隙不应打断连续专注。
     */
    fun merge(intervals: List<TimeInterval>, gapToleranceMs: Long = 0L): List<TimeInterval> {
        val sorted = intervals
            .filter { it.endMs > it.startMs }
            .sortedBy { it.startMs }
        if (sorted.isEmpty()) return emptyList()

        val result = mutableListOf<TimeInterval>()
        var currentStart = sorted.first().startMs
        var currentEnd = sorted.first().endMs

        for (index in 1 until sorted.size) {
            val next = sorted[index]
            if (next.startMs - currentEnd <= gapToleranceMs) {
                if (next.endMs > currentEnd) currentEnd = next.endMs
            } else {
                result += TimeInterval(currentStart, currentEnd)
                currentStart = next.startMs
                currentEnd = next.endMs
            }
        }
        result += TimeInterval(currentStart, currentEnd)
        return result
    }

    /** 合并后的总时长 */
    fun totalDurationMs(intervals: List<TimeInterval>, gapToleranceMs: Long = 0L): Long =
        merge(intervals, gapToleranceMs).sumOf { it.durationMs }

    /** 把区间裁剪到 [fromMs, toMs) 窗口内（跨窗口的区间会被切开） */
    fun clip(intervals: List<TimeInterval>, fromMs: Long, toMs: Long): List<TimeInterval> =
        intervals.mapNotNull { interval ->
            val start = maxOf(interval.startMs, fromMs)
            val end = minOf(interval.endMs, toMs)
            if (end > start) TimeInterval(start, end) else null
        }

    /**
     * 从 intervals 中减去 cut 覆盖的部分（差集），返回剩余区间。
     * 用于展示层去重：自动记录中与手动记录重叠的时段不再重复展示。
     */
    fun subtract(intervals: List<TimeInterval>, cut: List<TimeInterval>): List<TimeInterval> {
        if (cut.isEmpty()) return intervals.filter { it.endMs > it.startMs }
        val cuts = cut.filter { it.endMs > it.startMs }.sortedBy { it.startMs }
        val result = mutableListOf<TimeInterval>()
        for (interval in intervals) {
            if (interval.endMs <= interval.startMs) continue
            var cursor = interval.startMs
            for (c in cuts) {
                if (c.endMs <= cursor) continue
                if (c.startMs >= interval.endMs) break
                if (c.startMs > cursor) {
                    result += TimeInterval(cursor, minOf(c.startMs, interval.endMs))
                }
                cursor = maxOf(cursor, c.endMs)
                if (cursor >= interval.endMs) break
            }
            if (cursor < interval.endMs) result += TimeInterval(cursor, interval.endMs)
        }
        return result
    }
}
