package com.example.focus.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.focus.data.db.SliceStat
import com.example.focus.ui.components.HourBarChart
import com.example.focus.ui.components.PieChart
import com.example.focus.ui.components.SmoothLineChart
import com.example.focus.ui.formatDurationCompact
import com.example.focus.ui.stats.StatsViewModel.PieMode
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 统计板块：一个主指标 + 图表 + 少量辅助数据。
 * 主指标与辅助数据不使用卡片，图表区块用「标题 + 细分隔线 + 图表」的轻量形式，
 * 以减少视觉噪音、让信息层级清楚。
 */
@Composable
fun StatsScreen(viewModel: StatsViewModel = viewModel()) {
    val cumulative by viewModel.cumulative.collectAsState()
    val todayStats by viewModel.todayStats.collectAsState()
    val pieMode by viewModel.pieMode.collectAsState()
    val pieData by viewModel.pieData.collectAsState()
    val hourBars by viewModel.hourBars.collectAsState()
    val dailyLine by viewModel.dailyLine.collectAsState()
    val monthly by viewModel.monthly.collectAsState()
    val viewedMonth by viewModel.viewedMonth.collectAsState()
    val viewedYear by viewModel.viewedYear.collectAsState()
    val monthTotal by viewModel.monthTotal.collectAsState()
    val lastMonthTotal by viewModel.lastMonthTotal.collectAsState()
    val currentMonth = remember { YearMonth.now() }

    val hourSlots: List<SliceStat> = remember(hourBars) {
        val pairs = hourBars.mapNotNull { stat -> stat.label.toIntOrNull()?.let { it to stat.totalMs } }
        if (pairs.isEmpty()) {
            emptyList()
        } else {
            val map = pairs.toMap()
            (pairs.minOf { it.first }..pairs.maxOf { it.first }).map { hour ->
                SliceStat(hour.toString().padStart(2, '0'), map[hour] ?: 0L)
            }
        }
    }

    val yearSlots: List<SliceStat> = remember(monthly, viewedYear) {
        val map = monthly.associateBy { it.label }
        (1..12).map { month ->
            val label = "$viewedYear-${month.toString().padStart(2, '0')}"
            SliceStat(label, map[label]?.totalMs ?: 0L)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
    ) {
        item {
            Text(
                text = "统计",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(top = 20.dp),
            )
            Text(
                text = "你的专注数据",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        // 主指标：本月专注（无卡片，视觉中心）
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 26.dp, bottom = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "${currentMonth.monthValue} 月",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = formatDurationCompact(monthTotal),
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    softWrap = false,
                )
                Text(
                    text = "本月专注",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val days = LocalDate.now().dayOfMonth.coerceAtLeast(1)
                    Text(
                        text = "日均 ${formatDurationCompact(monthTotal / days)}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (lastMonthTotal > 0) {
                        Spacer(modifier = Modifier.width(14.dp))
                        val diff = ((monthTotal - lastMonthTotal) * 100f / lastMonthTotal).roundToInt()
                        Text(
                            text = "较上月 ${if (diff >= 0) "↑" else "↓"}${abs(diff)}%",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (diff >= 0) {
                                Color(0xFF48B59B)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }

        // 辅助数据：一行紧凑信息，降低信息密度
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompactStat(
                    label = "今日",
                    value = "${formatDurationCompact(todayStats?.totalMs ?: 0)} · ${todayStats?.sessionCount ?: 0} 次",
                )
                CompactStat(
                    label = "累计",
                    value = "${formatDurationCompact(cumulative?.totalMs ?: 0)} · ${cumulative?.sessionCount ?: 0} 次",
                )
            }
        }

        // 项目分布（环形图 + 百分比）
        item {
            SectionBlock(title = "项目分布") {
                SingleChoiceSegmentedButtonRow {
                    PieMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = pieMode == mode,
                            onClick = { viewModel.pieMode.value = mode },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = PieMode.entries.size,
                            ),
                        ) {
                            Text(
                                when (mode) {
                                    PieMode.DAY -> "日"
                                    PieMode.WEEK -> "周"
                                    PieMode.MONTH -> "月"
                                }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                PieChart(data = pieData)
            }
        }

        // 专注时段
        item {
            SectionBlock(
                title = "专注时段",
                periodText = "${viewedMonth.year}年${viewedMonth.monthValue.toString().padStart(2, '0')}月",
                canGoNext = viewedMonth.isBefore(YearMonth.now()),
                onPrev = { viewModel.prevMonth() },
                onNext = { viewModel.nextMonth() },
            ) {
                HourBarChart(data = hourSlots)
            }
        }

        // 每日趋势
        item {
            SectionBlock(
                title = "每日趋势",
                periodText = "${viewedMonth.year}年${viewedMonth.monthValue.toString().padStart(2, '0')}月",
                canGoNext = viewedMonth.isBefore(YearMonth.now()),
                onPrev = { viewModel.prevMonth() },
                onNext = { viewModel.nextMonth() },
            ) {
                SmoothLineChart(data = dailyLine)
            }
        }

        // 年度趋势
        item {
            SectionBlock(
                title = "年度趋势",
                periodText = "${viewedYear}年",
                canGoNext = viewedYear < LocalDate.now().year,
                onPrev = { viewModel.prevYear() },
                onNext = { viewModel.nextYear() },
            ) {
                SmoothLineChart(
                    data = yearSlots,
                    xAxisLabel = { label ->
                        val month = label.substringAfter("-").toIntOrNull() ?: 0
                        "${month}月"
                    },
                )
            }
        }

        item { Spacer(modifier = Modifier.height(28.dp)) }
    }
}

/** 紧凑信息：小标签 + 数值在同一行，降低视觉密度 */
@Composable
private fun CompactStat(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 图表区块：标题（可带周期与前后切换）+ 细分隔线 + 图表内容，不使用卡片。
 */
@Composable
private fun SectionBlock(
    title: String,
    periodText: String? = null,
    canGoNext: Boolean = true,
    onPrev: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 26.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (periodText != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = periodText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            if (onPrev != null && onNext != null) {
                IconButton(onClick = onPrev, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                        contentDescription = "上一个周期",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(
                    onClick = onNext,
                    enabled = canGoNext,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = "下一个周期",
                        tint = if (canGoNext) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 细分隔线
        androidx.compose.material3.HorizontalDivider(
            color = MaterialTheme.colorScheme.outline,
            thickness = 0.5.dp,
        )

        Spacer(modifier = Modifier.height(14.dp))

        content()
    }
}
