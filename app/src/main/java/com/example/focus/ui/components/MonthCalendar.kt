package com.example.focus.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.YearMonth

/**
 * 日历组件，支持两种形态：
 * - 展开：显示整月（6 行）
 * - 折叠：只显示选中日期所在的那一周（1 行）
 *
 * 样式参考主流系统日历：大号日期数字、有记录的日期下方显示标记点，
 * 记录越多点越多（形成专注热力感），选中日期用淡橙色圆形背景。
 */
@Composable
fun MonthCalendar(
    month: YearMonth,
    selected: LocalDate,
    markCounts: Map<Int, Int>,
    collapsed: Boolean,
    onDayClick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val weekDayNames = listOf("一", "二", "三", "四", "五", "六", "日")
    val today = LocalDate.now()

    val weeks: List<List<LocalDate?>> = if (collapsed) {
        val monday = selected.minusDays((selected.dayOfWeek.value - 1).toLong())
        listOf((0..6).map { monday.plusDays(it.toLong()) })
    } else {
        val firstOffset = LocalDate.of(month.year, month.month, 1).dayOfWeek.value - 1
        val daysInMonth = month.lengthOfMonth()
        (0 until 6).map { row ->
            (0..6).map { col ->
                val dayNum = row * 7 + col - firstOffset + 1
                if (dayNum in 1..daysInMonth) {
                    LocalDate.of(month.year, month.month, dayNum)
                } else {
                    null
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            weekDayNames.forEach { name ->
                Text(
                    text = name,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        weeks.forEach { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 3.dp, vertical = 3.dp),
                    ) {
                        if (date != null) {
                            DayCell(
                                date = date,
                                isSelected = date == selected,
                                isToday = date == today,
                                recordCount = if (date.monthValue == month.monthValue) {
                                    markCounts[date.dayOfMonth] ?: 0
                                } else {
                                    0
                                },
                                onClick = { onDayClick(date) },
                            )
                        } else {
                            Box(modifier = Modifier.size(width = 1.dp, height = 52.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    isSelected: Boolean,
    isToday: Boolean,
    recordCount: Int,
    onClick: () -> Unit,
) {
    val shape = CircleShape
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    androidx.compose.ui.graphics.Color.Transparent
                }
            )
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = date.dayOfMonth.toString(),
            fontSize = if (isSelected) 17.sp else 16.sp,
            fontWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.Normal,
            color = when {
                isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
                isToday -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
        // 记录标记点：1～2 条一个点，3～4 条两个点，5 条以上三个点
        val dots = when {
            recordCount <= 0 -> 0
            recordCount <= 2 -> 1
            recordCount <= 4 -> 2
            else -> 3
        }
        Row(
            modifier = Modifier
                .padding(top = 3.dp)
                .height(4.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp),
        ) {
            repeat(dots) {
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
    }
}
