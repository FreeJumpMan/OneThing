package com.example.focus.ui.history

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.focus.data.db.FocusSession
import com.example.focus.ui.components.MonthCalendar
import com.example.focus.ui.formatDuration
import com.example.focus.ui.formatDurationCompact
import com.example.focus.ui.formatTimeOfDay
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 历史记录板块：日历视图（自绘月历 + 当日列表）与日程视图（全部会话按日期分组）。
 * 支持：同步到系统日历、删除（联动日历事件）、编辑（改名称/起止时间，联动日历）、手动添加记录。
 */
@Composable
fun HistoryScreen(viewModel: HistoryViewModel = viewModel()) {
    val currentMonth by viewModel.currentMonth.collectAsState()
    val selectedDate by viewModel.selectedDate.collectAsState()
    val markedDates by viewModel.markCounts.collectAsState()
    val daySessions by viewModel.selectedDaySessions.collectAsState()
    val autoRecords by viewModel.autoRecords.collectAsState()
    val syncedIds by viewModel.syncedIds.collectAsState()
    val syncUndo by viewModel.syncUndo.collectAsState()
    val hiddenUndo by viewModel.hiddenUndo.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    // 从后台切回时重新拉取系统使用记录（专注 App 自动记录为实时派生数据，不落库）
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshAutoRecords()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val pendingSync by viewModel.pendingSync.collectAsState()
    val pendingSyncAll by viewModel.pendingSyncAll.collectAsState()
    val debugPending by viewModel.debugPending.collectAsState()
    val error by viewModel.error.collectAsState()
    val showSettingsGuide by viewModel.showSettingsGuide.collectAsState()

    // 日历默认收起为一周，需要时展开整月
    var collapsed by rememberSaveable { mutableStateOf(true) }
    var deleteTarget by remember { mutableStateOf<FocusSession?>(null) }
    var editingSession by remember { mutableStateOf<FocusSession?>(null) }
    // 自动记录（派生数据）没有自己的行，删除/编辑走单独的状态
    var autoDeleteTarget by remember { mutableStateOf<AutoRecord?>(null) }
    var editingAuto by remember { mutableStateOf<AutoRecord?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showDebugDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // 日历读写权限要一起申请：查询日历需要 READ，写入事件需要 WRITE
    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val readGranted = result[Manifest.permission.READ_CALENDAR] == true
        val writeGranted = result[Manifest.permission.WRITE_CALENDAR] == true
        if (readGranted && writeGranted) {
            viewModel.retryPendingSync()
            // 如果是在等权限开诊断，授权后直接打开
            if (debugPending) {
                viewModel.debugPermissionGranted()
                showDebugDialog = true
            }
        } else {
            viewModel.debugPermissionDenied()
            val activity = context as? Activity
            val canAskAgain = activity?.let {
                ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.READ_CALENDAR) ||
                    ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.WRITE_CALENDAR)
            } ?: false
            // 还能弹窗就等下次点同步再申请；被永久拒绝则引导去设置页
            if (canAskAgain) viewModel.dismissPendingSync()
            else viewModel.onPermissionDeniedPermanently()
        }
    }

    // 有待同步会话且需要权限时，触发权限申请；授权回调里由 ViewModel 重试同步
    LaunchedEffect(pendingSync) {
        pendingSync?.let {
            calendarPermissionLauncher.launch(
                arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            )
        }
    }

    // 点批量同步但没日历权限时，先申请权限，授权后自动继续
    LaunchedEffect(pendingSyncAll) {
        if (pendingSyncAll) {
            calendarPermissionLauncher.launch(
                arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            )
        }
    }

    // 点诊断按钮但没日历权限时，先申请权限，授权后自动打开诊断
    LaunchedEffect(debugPending) {
        if (debugPending) {
            calendarPermissionLauncher.launch(
                arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "历史记录",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = "日历与日程 · 支持同步到系统日历",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
                )
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = "更多",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("日历字段诊断") },
                        onClick = {
                            showMenu = false
                            if (viewModel.hasCalendarPermission()) {
                                showDebugDialog = true
                            } else {
                                viewModel.requestDebugPermission()
                            }
                        },
                    )
                }
            }
            IconButton(onClick = { showAddDialog = true }) {
                Icon(
                    Icons.Outlined.Add,
                    contentDescription = "添加记录",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        // 月份 + 切换 + 折叠/展开 + 添加记录
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${currentMonth.year}年${currentMonth.monthValue.toString().padStart(2, '0')}月",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = { viewModel.prevMonth() }) {
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                    contentDescription = "上个月",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { viewModel.nextMonth() }) {
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = "下个月",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { collapsed = !collapsed }) {
                Icon(
                    imageVector = if (collapsed) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,
                    contentDescription = if (collapsed) "展开整个月" else "折叠为一周",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        MonthCalendar(
            month = currentMonth,
            selected = selectedDate,
            markCounts = markedDates,
            collapsed = collapsed,
            onDayClick = { viewModel.select(it) },
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 选中日期的记录（时间线，按时间顺序）
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = selectedDate.format(dayTitleFormatter),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                if (daySessions.isNotEmpty() || autoRecords.isNotEmpty()) {
                    val totalMs = daySessions.sumOf { it.durationMs } +
                        autoRecords.sumOf { it.durationMs }
                    val countText = if (daySessions.isNotEmpty()) " · ${daySessions.size} 次" else ""
                    Text(
                        text = formatDurationCompact(totalMs) + countText,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            // 一键把当天全部记录同步到系统日历（已同步的会自动跳过）
            if (daySessions.isNotEmpty()) {
                IconButton(
                    onClick = { viewModel.syncSelectedDay() },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Outlined.Sync,
                        contentDescription = "同步当天全部记录到日历",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))

        // 时间线 = 计时记录 + 专注 App 自动记录，按开始时间排序
        val timelineEntries = remember(daySessions, autoRecords, syncedIds) {
            buildTimelineEntries(daySessions, autoRecords, syncedIds)
        }

        if (timelineEntries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 28.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "这一天没有专注记录",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
            ) {
                itemsIndexed(timelineEntries, key = { _, e -> e.key }) { index, entry ->
                    when (entry) {
                        is TimelineEntry.Session -> TimelineRow(
                            startMs = entry.startMs,
                            endMs = entry.endMs,
                            name = entry.name,
                            synced = entry.synced,
                            isFirst = index == 0,
                            isLast = index == timelineEntries.lastIndex,
                            onSync = { viewModel.syncToCalendar(entry.session) },
                            onEdit = { editingSession = entry.session },
                            onDelete = { deleteTarget = entry.session },
                        )

                        is TimelineEntry.Auto -> TimelineRow(
                            startMs = entry.startMs,
                            endMs = entry.endMs,
                            name = entry.name,
                            auto = true,
                            isFirst = index == 0,
                            isLast = index == timelineEntries.lastIndex,
                            onEdit = { editingAuto = entry.record },
                            onDelete = { autoDeleteTarget = entry.record },
                        )
                    }
                }
            }
        }
    }

    // 一键同步后的撤回浮条（6 秒后自动收起）
    syncUndo?.let { undo ->
        UndoBar(
            key = undo,
            text = "已同步 ${undo.count} 条到日历",
            onUndo = { viewModel.undoLastSync() },
            onTimeout = { viewModel.dismissSyncUndo() },
        )
    }

    // 隐藏自动记录后的撤销浮条
    hiddenUndo?.let { undo ->
        UndoBar(
            key = undo,
            text = "已隐藏「${undo.label}」",
            onUndo = { viewModel.undoHideAutoRecord() },
            onTimeout = { viewModel.dismissHiddenUndo() },
        )
    }

    // 删除自动记录的确认（数据来自系统，只能隐藏）
    autoDeleteTarget?.let { record ->
        AlertDialog(
            onDismissRequest = { autoDeleteTarget = null },
            title = { Text("删除这条自动记录？") },
            text = {
                Text(
                    "它来自系统使用记录，删掉后不再出现在时间线（可立即撤销）。\n" +
                        "如果只是想改个名字，用「编辑」。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.hideAutoRecord(record)
                    autoDeleteTarget = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { autoDeleteTarget = null }) { Text("取消") }
            },
        )
    }

    // 删除确认对话框：已同步的记录可选用是否同时删日历事件
    deleteTarget?.let { session ->
        val synced = session.id in syncedIds
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除这条记录？") },
            text = {
                Text(
                    if (synced) "这条记录已同步到系统日历，请选择是否同时删除日历事件。"
                    else "这条记录尚未同步到日历，删除不会影响日历。"
                )
            },
            confirmButton = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (synced) {
                        TextButton(onClick = {
                            viewModel.delete(session, deleteCalendarEvent = false)
                            deleteTarget = null
                        }) { Text("仅删软件记录") }
                        TextButton(onClick = {
                            viewModel.delete(session, deleteCalendarEvent = true)
                            deleteTarget = null
                        }) { Text("同时删日历") }
                    } else {
                        TextButton(onClick = {
                            viewModel.delete(session)
                            deleteTarget = null
                        }) { Text("删除") }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }

    // 编辑记录对话框
    editingSession?.let { session ->
        SessionEditDialog(
            title = "编辑记录",
            initialName = session.name,
            initialStartMs = session.startTimeMs,
            initialEndMs = session.endTimeMs,
            onDismiss = { editingSession = null },
            onSave = { name, startMs, endMs ->
                viewModel.updateSession(session, name, startMs, endMs)
                editingSession = null
            },
        )
    }

    // 编辑自动记录：保存后转为一条真实记录（原自动段一并隐藏）
    editingAuto?.let { record ->
        SessionEditDialog(
            title = "编辑自动记录",
            hint = "自动记录来自系统使用记录，不能就地改。保存后会变成一条记录，" +
                "可以继续编辑、删除或同步到日历。",
            initialName = record.displayName,
            initialStartMs = record.startMs,
            initialEndMs = record.endMs,
            onDismiss = { editingAuto = null },
            onSave = { name, startMs, endMs ->
                viewModel.convertAutoRecord(record, name, startMs, endMs)
                editingAuto = null
            },
        )
    }

    // 手动添加记录对话框
    if (showAddDialog) {
        val nowMs = remember(showAddDialog) { System.currentTimeMillis() }
        SessionEditDialog(
            title = "添加记录",
            initialName = "",
            initialStartMs = nowMs,
            initialEndMs = nowMs + 3_600_000,
            onDismiss = { showAddDialog = false },
            onSave = { name, startMs, endMs ->
                viewModel.addSession(name, startMs, endMs)
                showAddDialog = false
            },
        )
    }

    // 同步失败提示
    error?.let { message ->
        AlertDialog(
            onDismissRequest = { viewModel.clearError() },
            title = { Text("同步失败") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearError() }) { Text("知道了") }
            },
        )
    }

    // 日历字段诊断（用于排查国产 ROM 的"个人/工作"分类等自定义字段）
    val debugColumns by viewModel.debugColumns.collectAsState()
    val debugRow by viewModel.debugRow.collectAsState()
    val debugCalendars by viewModel.debugCalendars.collectAsState()
    if (showDebugDialog) {
        DebugDialog(
            columns = debugColumns,
            row = debugRow,
            calendars = debugCalendars,
            onLoadColumns = { viewModel.loadDebugColumns() },
            onLoadRow = { viewModel.loadDebugRow(it) },
            onDismiss = {
                showDebugDialog = false
                viewModel.clearDebug()
            },
        )
    }

    // 权限被永久拒绝，引导去系统设置开启
    if (showSettingsGuide) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissSettingsGuide() },
            title = { Text("需要日历权限") },
            text = { Text("日历权限被拒绝且系统不再询问。请到系统设置中手动开启：应用管理 > 一事 > 权限 > 日历") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.dismissSettingsGuide()
                    val intent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    )
                    runCatching { context.startActivity(intent) }
                }) { Text("去设置") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissSettingsGuide() }) { Text("取消") }
            },
        )
    }
}

/** 底部浮条：一句话 + 一个立即撤销按钮，6 秒后自动收起 */
@Composable
private fun UndoBar(key: Any?, text: String, onUndo: () -> Unit, onTimeout: () -> Unit) {
    LaunchedEffect(key) {
        delay(6000)
        onTimeout()
    }
    Popup(alignment = Alignment.BottomCenter) {
        Box(modifier = Modifier.padding(bottom = 28.dp)) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
            ) {
                Row(
                    modifier = Modifier.padding(start = 18.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = text,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = onUndo) {
                        Text(
                            text = "撤销",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

/** 时间线条目：计时记录与「专注 App 自动记录」的统一包装（列表按开始时间排序） */
private sealed interface TimelineEntry {
    val key: String
    val startMs: Long
    val endMs: Long
    val name: String

    data class Session(val session: FocusSession, val synced: Boolean) : TimelineEntry {
        override val key: String get() = "s_${session.id}"
        override val startMs: Long get() = session.startTimeMs
        override val endMs: Long get() = session.endTimeMs
        override val name: String get() = session.name
    }

    data class Auto(val record: AutoRecord) : TimelineEntry {
        override val key: String get() = "a_${record.startMs}"
        override val startMs: Long get() = record.startMs
        override val endMs: Long get() = record.endMs
        override val name: String get() = record.displayName
    }
}

private fun buildTimelineEntries(
    sessions: List<FocusSession>,
    autoRecords: List<AutoRecord>,
    syncedIds: Set<Long>,
): List<TimelineEntry> = buildList {
    sessions.forEach { add(TimelineEntry.Session(it, synced = it.id in syncedIds)) }
    autoRecords.forEach { add(TimelineEntry.Auto(it)) }
}.sortedBy { it.startMs }

/**
 * 时间线形式的单条记录：左侧开始时间、中间竖线与节点、右侧事项名与时长。
 * isFirst / isLast 控制竖线首尾不伸出。
 * auto = true 表示「专注 App 自动记录」：空心节点、右侧标「自动」。
 * 这种记录是派生数据，只能编辑（转为一条记录）或删除（隐藏），不能同步。
 */
@Composable
private fun TimelineRow(
    startMs: Long,
    endMs: Long,
    name: String,
    isFirst: Boolean,
    isLast: Boolean,
    auto: Boolean = false,
    synced: Boolean = false,
    onSync: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
    ) {
        // 开始时间
        Text(
            text = formatTimeOfDay(startMs),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier
                .width(44.dp)
                .padding(top = 2.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))

        // 时间线：上段线 + 节点 + 下段线（自动记录用空心节点区分）
        Column(
            modifier = Modifier
                .width(12.dp)
                .fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .width(1.5.dp)
                    .weight(1f)
                    .background(
                        if (isFirst) Color.Transparent else MaterialTheme.colorScheme.outlineVariant
                    )
            )
            if (auto) {
                Box(
                    modifier = Modifier
                        .padding(vertical = 2.dp)
                        .size(9.dp)
                        .border(
                            width = 1.5.dp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                            shape = CircleShape,
                        )
                )
            } else {
                Box(
                    modifier = Modifier
                        .padding(vertical = 2.dp)
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
            Box(
                modifier = Modifier
                    .width(1.5.dp)
                    .weight(1f)
                    .background(
                        if (isLast) Color.Transparent else MaterialTheme.colorScheme.outlineVariant
                    )
            )
        }
        Spacer(modifier = Modifier.width(12.dp))

        // 事项内容：操作收进右侧的 ⋯ 菜单
        var menuOpen by remember { mutableStateOf(false) }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = 18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (auto) {
                    Text(
                        text = "自动",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.outline,
                    )
                } else if (synced) {
                    Text(
                        text = "已同步",
                        fontSize = 10.sp,
                        color = Color(0xFF48B59B),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                if (onEdit != null || onDelete != null) {
                    Box {
                        IconButton(
                            onClick = { menuOpen = true },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                Icons.Outlined.MoreVert,
                                contentDescription = "更多操作",
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("编辑") },
                                onClick = {
                                    menuOpen = false
                                    onEdit?.invoke()
                                },
                            )
                            // 自动记录没有对应的日历事件，不提供同步
                            if (!auto) {
                                DropdownMenuItem(
                                    text = { Text(if (synced) "已同步到日历" else "同步到日历") },
                                    enabled = !synced,
                                    onClick = {
                                        menuOpen = false
                                        onSync?.invoke()
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("删除") },
                                onClick = {
                                    menuOpen = false
                                    onDelete?.invoke()
                                },
                            )
                        }
                    }
                }
            }
            Text(
                text = "${formatDuration(endMs - startMs)} · 至 ${formatTimeOfDay(endMs)}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
    }
}

/**
 * 编辑/添加记录对话框。标题与初值由调用方给（记录、手动添加、自动记录转为记录都用它）。
 * 名称必填，结束时间必须晚于开始时间。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionEditDialog(
    title: String,
    initialName: String,
    initialStartMs: Long,
    initialEndMs: Long,
    hint: String? = null,
    onDismiss: () -> Unit,
    onSave: (name: String, startMs: Long, endMs: Long) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var startDate by remember { mutableStateOf(toLocalDate(initialStartMs)) }
    var startTime by remember { mutableStateOf(toLocalTime(initialStartMs)) }
    var endDate by remember { mutableStateOf(toLocalDate(initialEndMs)) }
    var endTime by remember { mutableStateOf(toLocalTime(initialEndMs)) }
    var picking by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                hint?.let {
                    Text(
                        text = it,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                DateTimeField(
                    label = "开始",
                    date = startDate,
                    time = startTime,
                    onPickDate = { picking = "startDate" },
                    onPickTime = { picking = "startTime" },
                )
                DateTimeField(
                    label = "结束",
                    date = endDate,
                    time = endTime,
                    onPickDate = { picking = "endDate" },
                    onPickTime = { picking = "endTime" },
                )
                error?.let {
                    Text(
                        text = it,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val startMs = toEpochMillis(startDate, startTime)
                val endMs = toEpochMillis(endDate, endTime)
                when {
                    name.isBlank() -> error = "名称不能为空"
                    endMs <= startMs -> error = "结束时间必须晚于开始时间"
                    else -> onSave(name.trim(), startMs, endMs)
                }
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )

    // 日期选择
    if (picking == "startDate" || picking == "endDate") {
        val target = picking
        val initialDate = if (target == "startDate") startDate else endDate
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = initialDate
                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { picking = null },
            confirmButton = {
                TextButton(onClick = {
                    dateState.selectedDateMillis?.let { ms ->
                        val d = toLocalDate(ms)
                        if (target == "startDate") startDate = d else endDate = d
                    }
                    picking = null
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { picking = null }) { Text("取消") }
            },
        ) {
            DatePicker(state = dateState)
        }
    }

    // 开始时间选择
    if (picking == "startTime") {
        val timeState = rememberTimePickerState(
            initialHour = startTime.hour,
            initialMinute = startTime.minute,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { picking = null },
            title = { Text("开始时间") },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(onClick = {
                    startTime = LocalTime.of(timeState.hour, timeState.minute)
                    picking = null
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { picking = null }) { Text("取消") }
            },
        )
    }

    // 结束时间选择
    if (picking == "endTime") {
        val timeState = rememberTimePickerState(
            initialHour = endTime.hour,
            initialMinute = endTime.minute,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { picking = null },
            title = { Text("结束时间") },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(onClick = {
                    endTime = LocalTime.of(timeState.hour, timeState.minute)
                    picking = null
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { picking = null }) { Text("取消") }
            },
        )
    }
}

/**
 * 日历字段诊断对话框：显示 events/calendars 表列名，
 * 并按标题查询事件的所有字段值，用于排查国产 ROM 日历的自定义字段
 * （如 vivo/iQOO 的"个人/工作"分类）。
 */
@Composable
private fun DebugDialog(
    columns: Pair<List<String>, List<String>>?,
    row: Map<String, String>?,
    calendars: List<String>?,
    onLoadColumns: () -> Unit,
    onLoadRow: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var queryTitle by remember { mutableStateOf("") }
    var showColumns by remember { mutableStateOf(false) }
    var showCalendars by remember { mutableStateOf(false) }
    var channelInfo by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    LaunchedEffect(Unit) { onLoadColumns() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("日历字段诊断") },
        text = {
            Column {
                // 搜索框固定在顶部，始终可见
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = queryTitle,
                        onValueChange = { queryTitle = it },
                        label = { Text("事件标题") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { onLoadRow(queryTitle.trim()) }) { Text("查询") }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 查询结果：单独滚动区
                row?.let { map ->
                    if (map.isEmpty()) {
                        Text(
                            text = "没有找到该事件",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .heightIn(max = 260.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            map.forEach { (key, value) ->
                                val isEventType = key.equals("eventType", ignoreCase = true) ||
                                    key.equals("event_type", ignoreCase = true)
                                Text(
                                    text = "$key = $value",
                                    fontSize = 11.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    color = if (isEventType) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                    fontWeight = if (isEventType) FontWeight.Bold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 日历列表折叠区：确认"个人/工作"是否挂在日历维度
                TextButton(onClick = { showCalendars = !showCalendars }) {
                    Text(
                        text = if (showCalendars) {
                            "收起日历列表"
                        } else {
                            "查看日历列表（${calendars?.size ?: 0}）"
                        },
                        fontSize = 12.sp,
                    )
                }
                if (showCalendars) {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 160.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        (calendars ?: listOf("加载中…")).forEach { line ->
                            Text(
                                text = line,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // 列名折叠区：默认收起，需要时展开
                TextButton(onClick = { showColumns = !showColumns }) {
                    Text(
                        text = if (showColumns) {
                            "收起列名"
                        } else {
                            "查看列名（${columns?.first?.size ?: 0} + ${columns?.second?.size ?: 0}）"
                        },
                        fontSize = 12.sp,
                    )
                }
                if (showColumns) {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 160.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            text = "events 列名：",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = columns?.first?.joinToString(", ") ?: "加载中…",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "calendars 列名：",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = columns?.second?.joinToString(", ") ?: "加载中…",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    val nm = context.getSystemService(android.app.NotificationManager::class.java)
                    val ch = nm?.getNotificationChannel("focus_timer_v4")
                    channelInfo = if (ch == null) {
                        "通知渠道尚未创建，先开始一次专注再查看"
                    } else {
                        "id = ${ch.id}\n" +
                            "重要性 = ${ch.importance}（0=无 1=低 2=默认 3=高 4=紧急）\n" +
                            "锁屏可见 = ${ch.lockscreenVisibility}（1=公开 0=私密 -1=保密）"
                    }
                }) {
                    Text(
                        text = if (channelInfo == null) "查看通知渠道状态" else "刷新通知渠道状态",
                        fontSize = 12.sp,
                    )
                }
                channelInfo?.let {
                    Text(
                        text = it,
                        fontSize = 11.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = {
                    val report = buildDebugReport(columns, row, calendars)
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "一事 日历诊断")
                        putExtra(Intent.EXTRA_TEXT, report)
                    }
                    runCatching {
                        context.startActivity(Intent.createChooser(shareIntent, "导出诊断数据"))
                    }
                }) { Text("导出") }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
}

/** 把诊断数据整理成可分享的文本报告 */
private fun buildDebugReport(
    columns: Pair<List<String>, List<String>>?,
    row: Map<String, String>?,
    calendars: List<String>?,
): String {
    val sb = StringBuilder()
    sb.appendLine("一事 日历诊断报告")
    sb.appendLine("生成时间: ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}")
    sb.appendLine()
    sb.appendLine("=== 日历列表 ===")
    if (calendars.isNullOrEmpty()) sb.appendLine("(无)")
    else calendars.forEach { sb.appendLine(it) }
    sb.appendLine()
    sb.appendLine("=== events 列名 (${columns?.first?.size ?: 0}) ===")
    sb.appendLine(columns?.first?.joinToString(", ") ?: "(无)")
    sb.appendLine()
    sb.appendLine("=== calendars 列名 (${columns?.second?.size ?: 0}) ===")
    sb.appendLine(columns?.second?.joinToString(", ") ?: "(无)")
    sb.appendLine()
    sb.appendLine("=== 事件字段查询结果 ===")
    if (row.isNullOrEmpty()) sb.appendLine("(未查询或未找到)")
    else row.forEach { (key, value) -> sb.appendLine("$key = $value") }
    return sb.toString()
}

@Composable
private fun DateTimeField(
    label: String,
    date: LocalDate,
    time: LocalTime,
    onPickDate: () -> Unit,
    onPickTime: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(36.dp),
        )
        TextButton(onClick = onPickDate) {
            Text(date.toString())
        }
        TextButton(onClick = onPickTime) {
            Text(time.format(timeFormatter))
        }
    }
}

private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** 选中日期的标题格式：8月27日 星期三 */
private val dayTitleFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA)

private fun toEpochMillis(date: LocalDate, time: LocalTime): Long =
    date.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

private fun toLocalDate(epochMs: Long): LocalDate =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()

private fun toLocalTime(epochMs: Long): LocalTime =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalTime()
