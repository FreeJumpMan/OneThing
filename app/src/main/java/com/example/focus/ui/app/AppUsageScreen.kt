package com.example.focus.ui.app

import android.Manifest
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.focus.data.usage.AppUsageItem
import com.example.focus.data.usage.DayUsage
import com.example.focus.ui.formatDurationCompact
import com.example.focus.ui.formatTimeOfDay
import com.example.focus.ui.parseHexColor
import java.time.LocalDate

/**
 * App 时间页面：回答"我今天把手机时间花在哪里了"。
 * 数据来自系统使用统计，与「统计」页的专注数据严格分离。
 * 用户可把某些 App 标记为「专注 App」，用于区分有效专注与普通使用。
 */
@Composable
fun AppUsageScreen(viewModel: AppUsageViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    // 从系统设置（使用情况访问）返回时刷新
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val detail = state.detail
    val syncEvent by viewModel.syncEvent.collectAsState()
    val appContext = LocalContext.current

    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.READ_CALENDAR] == true &&
            result[Manifest.permission.WRITE_CALENDAR] == true
        if (granted) {
            viewModel.onCalendarPermissionGranted()
        } else {
            viewModel.onCalendarPermissionDenied()
            Toast.makeText(appContext, "需要日历权限才能同步", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(syncEvent) {
        when (val event = syncEvent) {
            is TimelineSyncEvent.NeedPermission -> calendarPermissionLauncher.launch(
                arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            )

            is TimelineSyncEvent.Done -> {
                Toast.makeText(
                    appContext,
                    "已同步 ${event.count} 个时间块到系统日历",
                    Toast.LENGTH_SHORT,
                ).show()
                viewModel.consumeSyncEvent()
            }

            is TimelineSyncEvent.Failed -> {
                Toast.makeText(appContext, event.message, Toast.LENGTH_LONG).show()
                viewModel.consumeSyncEvent()
            }

            null -> Unit
        }
    }
    if (detail != null) {
        AppDetailContent(
            detail = detail,
            onBack = { viewModel.closeDetail() },
            onToggleFocus = { viewModel.setFocusApp(it) },
        )
        return
    }

    val focusMs = state.apps.filter { it.packageName in state.focusPackages }.sumOf { it.durationMs }
    val otherMs = (state.totalMs - focusMs).coerceAtLeast(0L)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                text = "App 时间",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(top = 20.dp),
            )
            Text(
                text = "看看手机时间花在了哪里",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        if (!state.hasPermission) {
            item { PermissionGuideCard() }
        } else {
            item {
                DateRow(
                    date = state.selectedDate,
                    canGoNext = state.selectedDate.isBefore(LocalDate.now()),
                    onPrev = { viewModel.moveDay(-1) },
                    onNext = { viewModel.moveDay(1) },
                )
            }
            item {
                SummaryCard(
                    totalMs = state.totalMs,
                    previousDayTotalMs = state.previousDayTotalMs,
                    focusMs = focusMs,
                    otherMs = otherMs,
                    hasFocusApps = state.focusPackages.isNotEmpty(),
                )
            }
            item { TimeDistributionSection(usage = state.categoryUsage) }
            item {
                TimelineSection(
                    blocks = state.timeline,
                    onSync = { viewModel.syncTimelineToCalendar() },
                )
            }
            item { WeekTrendSection(trend = state.weekTrend) }
            item {
                RankingSection(
                    apps = state.apps,
                    appMeta = state.appMeta,
                    onAppClick = { viewModel.openDetail(it) },
                )
            }
        }

        item { Spacer(modifier = Modifier.height(8.dp)) }
    }
}

/** 未授权时的引导卡 */
@Composable
private fun PermissionGuideCard() {
    val context = LocalContext.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(22.dp)) {
            Text(
                text = "开启 App 时间记录",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "开启后，一事才能统计各个 App 的使用时间。数据只在本机读取与展示，不会上传。",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    }
                },
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("去开启", fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** 日期选择行 */
@Composable
private fun DateRow(
    date: LocalDate,
    canGoNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val isToday = date == LocalDate.now()
        Text(
            text = if (isToday) {
                "今天 · ${date.monthValue}月${date.dayOfMonth}日"
            } else {
                "${date.monthValue}月${date.dayOfMonth}日"
            },
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.weight(1f))
        IconButton(onClick = onPrev, modifier = Modifier.size(34.dp)) {
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                contentDescription = "前一天",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(
            onClick = onNext,
            enabled = canGoNext,
            modifier = Modifier.size(34.dp),
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
}

/** 当日总览：总时长 + 较前一日 + 专注/其他分解 */
@Composable
private fun SummaryCard(
    totalMs: Long,
    previousDayTotalMs: Long,
    focusMs: Long,
    otherMs: Long,
    hasFocusApps: Boolean,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp, horizontal = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = formatDurationCompact(totalMs),
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                softWrap = false,
            )
            Text(
                text = "手机使用时间",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )

            if (previousDayTotalMs > 0L) {
                val diff = totalMs - previousDayTotalMs
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = when {
                        diff == 0L -> "与前一日持平"
                        diff > 0L -> "较前一日 +${formatDurationCompact(diff)}"
                        else -> "较前一日 -${formatDurationCompact(-diff)}"
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = when {
                        diff == 0L -> MaterialTheme.colorScheme.onSurfaceVariant
                        diff > 0L -> Color(0xFFE0912F)
                        else -> Color(0xFF48B59B)
                    },
                )
            }

            if (hasFocusApps) {
                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline,
                    thickness = 0.5.dp,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    SplitStat("专注 App", focusMs, MaterialTheme.colorScheme.primary)
                    SplitStat("其他", otherMs, MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SplitStat(label: String, valueMs: Long, valueColor: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = formatDurationCompact(valueMs),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = valueColor,
        )
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** 今日时间分布：按时间分类的横向条形图 */
@Composable
private fun TimeDistributionSection(usage: List<CategoryUsage>) {
    if (usage.isEmpty()) return
    val maxMs = usage.maxOf { it.totalMs }.coerceAtLeast(1L)
    val trackColor = MaterialTheme.colorScheme.surfaceVariant

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "时间分布",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(10.dp))
        usage.forEach { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.name,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(56.dp),
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(10.dp)
                        .background(trackColor, RoundedCornerShape(2.dp))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(item.totalMs.toFloat() / maxMs)
                            .background(parseHexColor(item.colorHex), RoundedCornerShape(2.dp))
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = formatDurationCompact(item.totalMs),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(56.dp),
                )
            }
        }
    }
}

/** 今日时间轴：紧凑单行布局（时间 · 分类色条 · 分类 · 涉及的 App · 时长） */
@Composable
private fun TimelineSection(blocks: List<TimelineBlock>, onSync: () -> Unit) {
    if (blocks.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "今日时间轴",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onSync, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Outlined.Sync,
                    contentDescription = "把今日时间轴同步到系统日历",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(19.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        blocks.forEach { block ->
            val color = parseHexColor(block.colorHex)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatTimeOfDay(block.startMs),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(42.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(16.dp)
                        .background(color, RoundedCornerShape(1.5.dp))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = block.categoryName,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = color,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(48.dp),
                )
                Text(
                    text = block.apps.joinToString(" + "),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = formatDurationCompact(block.durationMs),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** 最近 7 天使用趋势（轻量柱状图） */
@Composable
private fun WeekTrendSection(trend: List<DayUsage>) {
    if (trend.isEmpty()) return
    val primary = MaterialTheme.colorScheme.primary
    val maxMs = trend.maxOf { it.totalMs }.coerceAtLeast(1L)
    val weekLabels = listOf("一", "二", "三", "四", "五", "六", "日")

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "使用趋势",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
        ) {
            val slot = size.width / trend.size
            val barWidth = slot * 0.4f
            trend.forEachIndexed { index, day ->
                val ratio = day.totalMs.toFloat() / maxMs
                val barHeight = (ratio * (size.height - 6f)).coerceAtLeast(2f)
                val left = index * slot + (slot - barWidth) / 2f
                val isToday = day.date == LocalDate.now()
                drawRoundRect(
                    color = if (isToday) primary else primary.copy(alpha = 0.3f),
                    topLeft = Offset(left, size.height - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            trend.forEach { day ->
                Text(
                    text = weekLabels[day.date.dayOfWeek.value - 1],
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    color = if (day.date == LocalDate.now()) {
                        primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** App 使用排行（点击进入详情，名称下方显示所属分类与属性） */
@Composable
private fun RankingSection(
    apps: List<AppUsageItem>,
    appMeta: Map<String, AppMeta>,
    onAppClick: (AppUsageItem) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "使用排行",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(4.dp))

        if (apps.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 28.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "这一天还没有使用记录",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            apps.forEach { app ->
                val meta = appMeta[app.packageName]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onAppClick(app) }
                        .padding(horizontal = 4.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(icon = app.icon, fallback = app.appName.take(1))
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = app.appName,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (meta != null) {
                            Text(
                                text = "${meta.categoryName} · ${meta.productivityLabel}",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                color = parseHexColor(meta.colorHex),
                                modifier = Modifier.padding(top = 1.dp),
                            )
                        }
                    }
                    Text(
                        text = formatDurationCompact(app.durationMs),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 单个 App 详情：今日 / 本周 / 本月 + 计入专注时间开关 */
@Composable
private fun AppDetailContent(
    detail: AppDetail,
    onBack: () -> Unit,
    onToggleFocus: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
    ) {
        Row(
            modifier = Modifier.padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "返回",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(icon = detail.icon, fallback = detail.appName.take(1), size = 46)
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                text = detail.appName,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(modifier = Modifier.height(22.dp))

        // 今日 / 本周 / 本月
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 20.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                DetailStat("今日", detail.todayMs)
                DetailStat("本周", detail.weekMs)
                DetailStat("本月", detail.monthMs)
            }
        }

        Spacer(modifier = Modifier.height(26.dp))

        Text(
            text = "专注设置",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(10.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "计入专注时间",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "开启后，这个 App 的使用时间会进入专注统计",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Switch(
                    checked = detail.isFocusApp,
                    onCheckedChange = onToggleFocus,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                    ),
                )
            }
        }
    }
}

@Composable
private fun DetailStat(label: String, valueMs: Long) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = formatDurationCompact(valueMs),
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            softWrap = false,
        )
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

@Composable
private fun AppIcon(icon: ImageBitmap?, fallback: String, size: Int = 34) {
    if (icon != null) {
        Image(
            bitmap = icon,
            contentDescription = null,
            modifier = Modifier
                .size(size.dp)
                .clip(RoundedCornerShape((size / 4).dp)),
        )
    } else {
        Box(
            modifier = Modifier
                .size(size.dp)
                .clip(RoundedCornerShape((size / 4).dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = fallback,
                fontSize = (size / 2.4).sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
