package com.example.focus.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.focus.data.db.SliceStat
import com.example.focus.ui.formatDurationCompact
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 统计图表配色：以品牌珊瑚橙为主，其余低饱和度（浅橙 / 浅金 / 低饱和绿 / 暖灰 / 橙棕），
 * 避免变成彩色 Dashboard。
 */
private val chartColors = listOf(
    Color(0xFFFF684A),
    Color(0xFFFFA07A),
    Color(0xFFE8C89A),
    Color(0xFF6FBFA0),
    Color(0xFFB8AFA6),
    Color(0xFFC97B5F),
)

@Composable
private fun EmptyHint() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("暂无数据", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * 专注时长分布饼图：按事项类目分组，环形样式，中心显示占比最高项。
 * 饼图在上、图例在下（全宽），保证类目名称有足够空间显示。
 */
@Composable
fun PieChart(data: List<SliceStat>, modifier: Modifier = Modifier) {
    if (data.isEmpty()) {
        EmptyHint()
        return
    }
    val total = data.sumOf { it.totalMs }.coerceAtLeast(1L)
    val topSlice = data.first()
    val topPercent = (topSlice.totalMs * 100f / total).roundToInt()
    val dotColor = MaterialTheme.colorScheme.primary
    val textMuted = MaterialTheme.colorScheme.onSurfaceVariant
    val textMain = MaterialTheme.colorScheme.onSurface

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.size(176.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeWidth = 26.dp.toPx()
                val inset = strokeWidth / 2f
                val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
                var startAngle = -90f
                data.forEachIndexed { index, slice ->
                    val sweep = slice.totalMs.toFloat() / total * 360f
                    drawArc(
                        color = chartColors[index % chartColors.size],
                        startAngle = startAngle,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Butt),
                    )
                    startAngle += sweep
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "$topPercent%",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = dotColor,
                )
                Text(
                    text = topSlice.label,
                    fontSize = 11.sp,
                    color = textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(84.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            data.forEachIndexed { index, slice ->
                val percent = (slice.totalMs * 100f / total).roundToInt()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(chartColors[index % chartColors.size], CircleShape)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = slice.label,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                        color = textMain,
                    )
                    Text(
                        text = "$percent%",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = chartColors[index % chartColors.size],
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = formatDurationCompact(slice.totalMs),
                        fontSize = 12.sp,
                        modifier = Modifier.width(54.dp),
                        textAlign = TextAlign.End,
                        color = textMuted,
                    )
                }
            }
        }
    }
}

/**
 * 月度每日专注时长：平滑曲线 + 渐变填充 + 关键点数值标注 + Y 轴刻度 + 日期轴。
 * xAxisLabel 决定 X 轴刻度文字（日期或月份）。
 */
@Composable
fun SmoothLineChart(
    data: List<SliceStat>,
    modifier: Modifier = Modifier,
    xAxisLabel: (String) -> String = ::axisDateLabel,
) {
    if (data.isEmpty()) {
        EmptyHint()
        return
    }

    val textMeasurer = rememberTextMeasurer()
    val lineColor = chartColors[0]
    val labelStyle = TextStyle(
        fontSize = 10.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    val maxMinutes = data.maxOf { it.totalMs }.toFloat() / 60_000f
    val yMax = niceCeil(maxMinutes.coerceAtLeast(1f))

    // 数值标签：有值的点，太多则只标最大的 5 个，避免拥挤
    val nonZeroIndices = data.indices.filter { data[it].totalMs > 0 }
    val labeledIndices: Set<Int> = if (nonZeroIndices.size <= 6) {
        nonZeroIndices.toSet()
    } else {
        nonZeroIndices.sortedByDescending { data[it].totalMs }.take(5).toSet()
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(220.dp)
    ) {
        val leftPad = 52.dp.toPx()
        val bottomPad = 22.dp.toPx()
        val topPad = 26.dp.toPx()
        val chartW = size.width - leftPad
        val chartH = size.height - topPad - bottomPad
        val n = data.size
        val stepX = if (n > 1) chartW / (n - 1) else 0f

        fun yOf(minutes: Float): Float = topPad + chartH * (1f - (minutes / yMax))

        listOf(yMax, yMax * 2f / 3f, yMax / 3f, 0f).forEach { value ->
            val y = yOf(value)
            drawLine(
                color = gridColor,
                start = Offset(leftPad, y),
                end = Offset(size.width, y),
                strokeWidth = 1f,
            )
            val layout = textMeasurer.measure(AnnotatedString(yAxisLabel(value)), labelStyle)
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(
                    x = (leftPad - 8.dp.toPx() - layout.size.width).coerceAtLeast(0f),
                    y = y - layout.size.height / 2f,
                ),
            )
        }

        val points = data.mapIndexed { index, slice ->
            Offset(leftPad + index * stepX, yOf(slice.totalMs / 60_000f))
        }

        if (points.size >= 2) {
            val linePath = buildSmoothPath(points)
            val fillPath = Path().apply {
                addPath(linePath)
                lineTo(points.last().x, topPad + chartH)
                lineTo(points.first().x, topPad + chartH)
                close()
            }
            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(lineColor.copy(alpha = 0.30f), lineColor.copy(alpha = 0.02f)),
                    startY = topPad,
                    endY = topPad + chartH,
                ),
            )
            drawPath(
                path = linePath,
                color = lineColor,
                style = Stroke(
                    width = 2.5.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
        }

        points.forEachIndexed { index, point ->
            if (data[index].totalMs > 0) {
                drawCircle(Color.White, radius = 5.dp.toPx(), center = point)
                drawCircle(lineColor, radius = 3.5.dp.toPx(), center = point)
                if (index in labeledIndices) {
                    val layout = textMeasurer.measure(
                        AnnotatedString(formatDurationCompact(data[index].totalMs)),
                        labelStyle.copy(color = lineColor),
                    )
                    drawText(
                        textLayoutResult = layout,
                        topLeft = Offset(
                            x = (point.x - layout.size.width / 2f)
                                .coerceIn(0f, size.width - layout.size.width),
                            y = (point.y - layout.size.height - 8.dp.toPx()).coerceAtLeast(0f),
                        ),
                    )
                }
            }
        }

        val xTicks = if (n <= 5) data.indices.toList()
        else listOf(0, n / 4, n / 2, n * 3 / 4, n - 1).distinct()
        xTicks.forEach { index ->
            val layout = textMeasurer.measure(
                AnnotatedString(xAxisLabel(data[index].label)),
                labelStyle,
            )
            val x = (leftPad + index * stepX - layout.size.width / 2f)
                .coerceIn(0f, size.width - layout.size.width)
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(x, size.height - bottomPad + 5.dp.toPx()),
            )
        }
    }
}

/**
 * 时段分布柱状图：顶部圆角柱 + 柱顶数值 + Y 轴刻度（分钟）+ X 轴小时标签。
 * data 需要是连续槽位（缺失小时补 0），label 为小时数字字符串。
 */
@Composable
fun HourBarChart(data: List<SliceStat>, modifier: Modifier = Modifier) {
    if (data.isEmpty() || data.all { it.totalMs == 0L }) {
        EmptyHint()
        return
    }

    val textMeasurer = rememberTextMeasurer()
    val barColor = chartColors[0]
    val labelStyle = TextStyle(
        fontSize = 10.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    val maxMinutes = data.maxOf { it.totalMs }.toFloat() / 60_000f
    val (yMax, step) = niceAxis(maxMinutes.coerceAtLeast(1f))

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(210.dp)
    ) {
        val leftPad = 56.dp.toPx()
        val bottomPad = 22.dp.toPx()
        val topPad = 24.dp.toPx()
        val chartW = size.width - leftPad
        val chartH = size.height - topPad - bottomPad
        val n = data.size
        val slot = chartW / n
        val barWidth = slot * 0.44f

        fun yOf(minutes: Float): Float = topPad + chartH * (1f - (minutes / yMax))

        // Y 轴刻度线 + 标签
        var tick = step
        while (tick <= yMax + 0.5f) {
            val y = yOf(tick)
            drawLine(
                color = gridColor,
                start = Offset(leftPad, y),
                end = Offset(size.width, y),
                strokeWidth = 1f,
            )
            val layout = textMeasurer.measure(
                AnnotatedString("${tick.roundToInt()}分钟"),
                labelStyle,
            )
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(
                    x = (leftPad - 6.dp.toPx() - layout.size.width).coerceAtLeast(0f),
                    y = y - layout.size.height / 2f,
                ),
            )
            tick += step
        }

        // 柱体（顶部圆角）+ 柱顶数值
        data.forEachIndexed { index, slice ->
            val minutes = slice.totalMs / 60_000f
            if (minutes > 0f) {
                val barHeight = (minutes / yMax) * chartH
                val left = leftPad + index * slot + (slot - barWidth) / 2f
                val top = topPad + chartH - barHeight
                val radius = minOf(barWidth / 2f, barHeight)

                val barPath = Path().apply {
                    moveTo(left, topPad + chartH)
                    lineTo(left, top + radius)
                    quadraticBezierTo(left, top, left + radius, top)
                    lineTo(left + barWidth - radius, top)
                    quadraticBezierTo(left + barWidth, top, left + barWidth, top + radius)
                    lineTo(left + barWidth, topPad + chartH)
                    close()
                }
                drawPath(path = barPath, color = barColor)

                val layout = textMeasurer.measure(
                    AnnotatedString("${minutes.roundToInt()}"),
                    labelStyle.copy(color = barColor),
                )
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(
                        x = (left + barWidth / 2f - layout.size.width / 2f)
                            .coerceIn(0f, size.width - layout.size.width),
                        y = (top - layout.size.height - 4.dp.toPx()).coerceAtLeast(0f),
                    ),
                )
            }
        }

        // X 轴小时标签（密集时隔一个标）
        data.forEachIndexed { index, slice ->
            val show = n <= 8 || index % 2 == 0
            if (show) {
                val hour = slice.label.toIntOrNull() ?: return@forEachIndexed
                val layout = textMeasurer.measure(AnnotatedString("${hour}点"), labelStyle)
                val x = (leftPad + index * slot + slot / 2f - layout.size.width / 2f)
                    .coerceIn(0f, size.width - layout.size.width)
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(x, size.height - bottomPad + 5.dp.toPx()),
                )
            }
        }
    }
}

/** Catmull-Rom 转三次贝塞尔，得到平滑曲线 */
private fun buildSmoothPath(points: List<Offset>): Path {
    val path = Path()
    if (points.isEmpty()) return path
    path.moveTo(points[0].x, points[0].y)
    for (i in 0 until points.size - 1) {
        val p0 = points.getOrElse(i - 1) { points[i] }
        val p1 = points[i]
        val p2 = points[i + 1]
        val p3 = points.getOrElse(i + 2) { points[i + 1] }
        val c1x = p1.x + (p2.x - p0.x) / 6f
        val c1y = p1.y + (p2.y - p0.y) / 6f
        val c2x = p2.x - (p3.x - p1.x) / 6f
        val c2y = p2.y - (p3.y - p1.y) / 6f
        path.cubicTo(c1x, c1y, c2x, c2y, p2.x, p2.y)
    }
    return path
}

/** Y 轴最大值向上取整到 1/2/3/5 × 10^n */
private fun niceCeil(value: Float): Float {
    if (value <= 0f) return 1f
    val exp = kotlin.math.floor(kotlin.math.log10(value.toDouble())).toInt()
    val base = 10.0.pow(exp).toFloat()
    val normalized = value / base
    val nice = when {
        normalized <= 1f -> 1f
        normalized <= 2f -> 2f
        normalized <= 3f -> 3f
        normalized <= 5f -> 5f
        else -> 10f
    }
    return nice * base
}

/** 柱状图 Y 轴：返回（最大值, 刻度步长），步长取自常用时间档位 */
private fun niceAxis(maxValue: Float): Pair<Float, Float> {
    val candidates = listOf(5f, 10f, 15f, 20f, 30f, 60f, 90f, 120f, 180f, 240f, 300f, 600f)
    val target = (maxValue / 5f).coerceAtLeast(1f)
    val step = candidates.minByOrNull { abs(it - target) } ?: target
    val ticks = ceil(maxValue / step).toInt().coerceAtLeast(2)
    return step * ticks to step
}

/** Y 轴刻度文字：300分 / 12h */
private fun yAxisLabel(minutes: Float): String = when {
    minutes <= 0f -> "0"
    minutes >= 600f -> "${(minutes / 60f).roundToInt()}h"
    else -> "${minutes.roundToInt()}分"
}

/** X 轴日期："2026-08-27" → "8-27" */
private fun axisDateLabel(label: String): String {
    val parts = label.split("-")
    return if (parts.size == 3) {
        "${parts[1].trimStart('0')}-${parts[2].trimStart('0')}"
    } else {
        label
    }
}
