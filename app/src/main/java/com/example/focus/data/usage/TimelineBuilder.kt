package com.example.focus.data.usage

import com.example.focus.data.db.AppCategoryRule
import com.example.focus.data.db.ProductivityLevel
import com.example.focus.data.db.TimeCategory

/** 合并后的时间块（相邻同类区间合并而来） */
data class TimelineBlock(
    val categoryId: Long,
    val categoryName: String,
    val colorHex: String,
    val startMs: Long,
    val endMs: Long,
    val apps: List<String>,
    /** 该块的生产力属性，用于决定能否被相邻块吸收 */
    val productivity: ProductivityLevel = ProductivityLevel.NEUTRAL,
) {
    val durationMs: Long get() = endMs - startMs
}

/**
 * App 时间轴的时间块生成器（App 页展示与日历同步共用）。
 *
 * 从 AppUsageViewModel 抽出来共享：展示与同步两条路径必须基于同一套合并逻辑，
 * 否则同一天在界面上和日历里会呈现不同形状的时间块。
 */
object TimelineBuilder {

    /** 用户规则 > 内置默认规则 > null（未分类） */
    fun resolveCategory(
        packageName: String,
        appName: String,
        rules: Map<String, AppCategoryRule>,
    ): Pair<Long, ProductivityLevel>? {
        rules[packageName]?.let { rule ->
            val level = runCatching { ProductivityLevel.valueOf(rule.productivity) }
                .getOrDefault(ProductivityLevel.NEUTRAL)
            return rule.categoryId to level
        }
        return DefaultCategoryRules.match(appName, packageName)
    }

    /**
     * 把事件级 App 区间合并成时间块。
     *
     * 三级处理，兼顾紧凑与诚实：
     * 1. 同一分类、间隔不超阈值的相邻区间拼成一块；
     * 2. **仅「中性」小段**（如工具类的短暂切换）被相邻较长的块吸收——
     *    专注/分心/个人的小段一律保留，否则会把刷手机的几分钟也算进学习时间；
     * 3. 最终仍不足 minBlockMs 的不返回。
     */
    fun build(
        intervals: List<AppUsageInterval>,
        rules: Map<String, AppCategoryRule>,
        categories: Map<Long, TimeCategory>,
    ): List<TimelineBlock> {
        val minSegmentMs = 30_000L        // 过滤 30 秒以下的误触
        val minBlockMs = 60_000L          // 最终不足 1 分钟的不返回
        val mergeGapMs = 2 * 60_000L      // 同类相邻间隔 2 分钟内则拼合
        val absorbMs = 5 * 60_000L        // 不足 5 分钟的「中性」块才会被吸收

        val classified = intervals
            .filter { it.durationMs >= minSegmentMs }
            .map { interval ->
                val resolved = resolveCategory(interval.packageName, interval.appName, rules)
                val categoryId = resolved?.first ?: DefaultCategoryRules.CAT_OTHER
                val level = resolved?.second ?: ProductivityLevel.NEUTRAL
                Triple(interval, categoryId, level)
            }

        // 第一级：合并同类相邻区间
        val merged = mutableListOf<TimelineBlock>()
        var index = 0
        while (index < classified.size) {
            val firstInterval = classified[index].first
            val categoryId = classified[index].second
            val level = classified[index].third
            var end = firstInterval.endMs
            val apps = mutableListOf(firstInterval.appName)
            var next = index + 1
            while (
                next < classified.size &&
                classified[next].second == categoryId &&
                classified[next].first.startMs - end <= mergeGapMs
            ) {
                end = maxOf(end, classified[next].first.endMs)
                apps += classified[next].first.appName
                next++
            }
            val category = categories[categoryId]
            merged += TimelineBlock(
                categoryId = categoryId,
                categoryName = category?.name ?: "其他",
                colorHex = category?.colorHex ?: "#B8AFA6",
                startMs = firstInterval.startMs,
                endMs = end,
                apps = apps.distinct(),
                productivity = level,
            )
            index = next
        }

        // 第二级：仅中性的小段被相邻较长的块吸收
        val absorbed = mutableListOf<TimelineBlock>()
        var i = 0
        while (i < merged.size) {
            val block = merged[i]
            val absorbable = block.productivity == ProductivityLevel.NEUTRAL &&
                block.durationMs < absorbMs
            if (!absorbable) {
                absorbed += block
                i++
                continue
            }
            val prev = absorbed.lastOrNull()
            val following = merged.getOrNull(i + 1)
            when {
                prev != null && (following == null || prev.durationMs >= following.durationMs) -> {
                    absorbed[absorbed.size - 1] = prev.copy(
                        endMs = block.endMs,
                        apps = (prev.apps + block.apps).distinct(),
                    )
                    i++
                }

                following != null -> {
                    absorbed += following.copy(
                        startMs = block.startMs,
                        apps = (block.apps + following.apps).distinct(),
                    )
                    i += 2
                }

                else -> {
                    absorbed += block
                    i++
                }
            }
        }

        // 第三级：过滤仍过短的块
        return absorbed.filter { it.durationMs >= minBlockMs }
    }
}
