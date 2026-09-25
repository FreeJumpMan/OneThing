package com.example.focus.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.focus.data.db.FocusSession
import com.example.focus.data.db.SliceStat
import com.example.focus.ui.components.HourBarChart
import com.example.focus.ui.components.PieChart
import com.example.focus.ui.components.SmoothLineChart
import com.example.focus.ui.components.chartColors
import com.example.focus.ui.formatDurationCompact
import com.example.focus.ui.parseHexColor
import com.example.focus.ui.stats.StatsViewModel.PieMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
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
    val todayEffective by viewModel.todayEffective.collectAsState()
    val blockDate by viewModel.blockDate.collectAsState()
    val dayBlockSessions by viewModel.dayBlockSessions.collectAsState()
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

        // 辅助数据：一行紧凑信息（今日为「有效专注」，含专注 App 使用时间）
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CompactStat(
                        label = "今日",
                        value = "${formatDurationCompact(todayEffective?.effectiveMs ?: 0L)}" +
                            " · ${todayStats?.sessionCount ?: 0} 次",
                    )
                    CompactStat(
                        label = "累计",
                        value = "${formatDurationCompact(cumulative?.totalMs ?: 0)}" +
                            " · ${cumulative?.sessionCount ?: 0} 次",
                    )
                }
                val appMs = todayEffective?.appMs ?: 0L
                if (appMs > 0L) {
                    Text(
                        text = "今日含 App 专注 ${formatDurationCompact(appMs)}（已与计时去重）",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }

        // 项目分布（环形图 + 百分比）。日视图跟随「一天的时间块」的日期
        item {
            SectionBlock(
                title = "项目分布",
                periodText = if (pieMode == PieMode.DAY) {
                    blockDate.format(dayBlockShortFormatter)
                } else {
                    null
                },
            ) {
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

        // 一天的时间块
        item {
            SectionBlock(title = "一天的时间块") {
                DayTimeBlocksContent(
                    date = blockDate,
                    sessions = dayBlockSessions,
                    canGoNext = blockDate.isBefore(LocalDate.now()),
                    onPrev = { viewModel.prevBlockDate() },
                    onNext = { viewModel.nextBlockDate() },
                )
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

private val dayBlockDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("M月d日 E", Locale.CHINA)

private val dayBlockShortFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)

/** 时间块日期行的大字：今天 / 昨天 / 前天，更早则显示日期 */
private fun dayBlockTitle(date: LocalDate): String {
    val today = LocalDate.now()
    return when (date) {
        today -> "今天"
        today.minusDays(1) -> "昨天"
        today.minusDays(2) -> "前天"
        else -> date.format(dayBlockShortFormatter)
    }
}

private const val DAY_CELL_MINUTES = 10
private const val DAY_CELL_COUNT = 24 * 60 / DAY_CELL_MINUTES
private const val DAY_CELL_MS = DAY_CELL_MINUTES * 60_000L

/** 时间块网格：格子尺寸、格间空隙、中央标签列宽（整体居中，两侧留白） */
private val DAY_CELL_SIZE = 13.dp
private val DAY_CELL_GAP = 6.dp
private val DAY_LABEL_WIDTH = 28.dp
private val DAY_HALF_WIDTH = DAY_CELL_SIZE * 6 + DAY_CELL_GAP * 5

/** 「一天的时间块」图例项 */
private data class DayBlockLegend(val name: String, val totalMs: Long, val color: Color)

/**
 * 把当天专注记录投影到 144 个 10 分钟格上。
 * 每格取与它重叠时间最长的一条记录所属事项，无记录为 null。
 * 颜色按事项当天总时长降序分配，复用统计图表调色板。
 */
private fun buildDayCells(
    date: LocalDate,
    sessions: List<FocusSession>,
): Pair<List<Color?>, List<DayBlockLegend>> {
    if (sessions.isEmpty()) return emptyList<Color?>() to emptyList()

    val legend = sessions.groupBy { it.name }
        .map { (name, list) -> name to list.sumOf { it.durationMs } }
        .sortedByDescending { it.second }
        .mapIndexed { index, (name, total) ->
            DayBlockLegend(name, total, chartColors[index % chartColors.size])
        }
    val colorByName = legend.associate { it.name to it.color }

    val zone = ZoneId.systemDefault()
    val dayStartMs = date.atStartOfDay(zone).toInstant().toEpochMilli()
    val dayEndMs = dayStartMs + DAY_CELL_COUNT * DAY_CELL_MS

    val cells = MutableList<Color?>(DAY_CELL_COUNT) { null }
    val occupied = LongArray(DAY_CELL_COUNT)

    sessions.forEach { session ->
        val start = maxOf(session.startTimeMs, dayStartMs)
        val end = minOf(session.endTimeMs, dayEndMs)
        if (end <= start) return@forEach
        val first = ((start - dayStartMs) / DAY_CELL_MS).toInt().coerceIn(0, DAY_CELL_COUNT - 1)
        val last = ((end - 1 - dayStartMs) / DAY_CELL_MS).toInt().coerceIn(0, DAY_CELL_COUNT - 1)
        for (i in first..last) {
            val cellStart = dayStartMs + i * DAY_CELL_MS
            val overlap = minOf(end, cellStart + DAY_CELL_MS) - maxOf(start, cellStart)
            if (overlap > occupied[i]) {
                occupied[i] = overlap
                cells[i] = colorByName[session.name]
            }
        }
    }
    return cells to legend
}

/**
 * 「一天的时间块」：把当天专注记录铺到 12 行 × 12 格（每格 10 分钟）的网格上。
 * 左列为 0–11 时、右列为 12–23 时，每行一小时。
 */
@Composable
private fun DayTimeBlocksContent(
    date: LocalDate,
    sessions: List<FocusSession>,
    canGoNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    val (cells, legend) = remember(date, sessions) { buildDayCells(date, sessions) }
    val emptyCellColor = if (isSystemInDarkTheme()) Color(0xFF2A2A2A) else Color(0xFFE4DED7)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPrev, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                    contentDescription = "前一天",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(132.dp),
            ) {
                Text(
                    text = date.format(dayBlockDateFormatter),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = dayBlockTitle(date),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
            IconButton(
                onClick = onNext,
                enabled = canGoNext,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = "后一天",
                    tint = if (canGoNext) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        if (legend.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 26.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "这一天没有专注记录",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            DayBlockGrid(cells = cells, legend = legend, emptyCellColor = emptyCellColor)
        }
    }
}

/**
 * 「一天的时间块」的网格部分：副标题 + 上午/下午列头 + 144 格 + 图例。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayBlockGrid(
    cells: List<Color?>,
    legend: List<DayBlockLegend>,
    emptyCellColor: Color,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "每个时间块代表 10 分钟",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(
                DAY_CELL_GAP,
                Alignment.CenterHorizontally,
            ),
        ) {
            Box(
                modifier = Modifier.width(DAY_HALF_WIDTH),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "上午",
                    fontSize = 11.sp,
                    lineHeight = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(DAY_LABEL_WIDTH))
            Box(
                modifier = Modifier.width(DAY_HALF_WIDTH),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "下午",
                    fontSize = 11.sp,
                    lineHeight = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        Column(verticalArrangement = Arrangement.spacedBy(DAY_CELL_GAP)) {
            repeat(12) { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(
                        DAY_CELL_GAP,
                        Alignment.CenterHorizontally,
                    ),
                ) {
                    repeat(6) { col ->
                        DayCell(cells[row * 6 + col], emptyCellColor)
                    }
                    Text(
                        text = if (row == 0) "0/12" else row.toString(),
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(DAY_LABEL_WIDTH),
                    )
                    repeat(6) { col ->
                        DayCell(cells[(row + 12) * 6 + col], emptyCellColor)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            legend.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .background(item.color, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = item.name,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = formatDurationCompact(item.totalMs),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun DayCell(color: Color?, emptyColor: Color) {
    Box(
        modifier = Modifier
            .size(DAY_CELL_SIZE)
            .clip(RoundedCornerShape(2.dp))
            .background(color ?: emptyColor)
    )
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
