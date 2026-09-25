package com.example.focus.ui.todo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.focus.data.db.TodoItem
import com.example.focus.service.TimerUiState
import com.example.focus.ui.formatClock
import com.example.focus.ui.formatDurationCompact

/**
 * 专注板块（首页）：品牌区 + 当前专注卡 + 轻量列表。
 *
 * 视觉原则：事项名与计时数字是主角，圆环只作氛围（低透明度、细线）。
 * 删除操作默认收起，长按列表进入编辑模式才出现。
 */
@Composable
fun TodoScreen(
    onOpenSettings: () -> Unit,
    viewModel: TodoViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val items by viewModel.items.collectAsState()
    val todayByName by viewModel.todayByName.collectAsState()

    var deleteTarget by remember { mutableStateOf<TodoItem?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editMode by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 拒绝后计时仍可用，只是通知不显示 */ }

    fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val todayTotal = todayByName.values.sum()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        // 顶部：问候语 + 设置
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "一事",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = "一次，只做一件事。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(
                    Icons.Outlined.Settings,
                    contentDescription = "设置",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        FocusCard(
            state = state,
            onPause = { viewModel.pause() },
            onResume = { viewModel.resume() },
            onStop = { viewModel.stop() },
            onAddFirst = { showAddDialog = true },
        )

        Spacer(modifier = Modifier.height(20.dp))

        // 列表头
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "要做的事",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(modifier = Modifier.weight(1f))
            if (editMode) {
                TextButton(onClick = { editMode = false }) {
                    Text("完成", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                }
            } else {
                Text(
                    text = "今日 ${formatDurationCompact(todayTotal)}",
                    fontSize = 12.sp,
                    fontWeight = if (todayTotal > 0) FontWeight.Medium else FontWeight.Normal,
                    color = if (todayTotal > 0) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        if (items.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "还没有要做的事，添加一件吧",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(items, key = { _, item -> item.id }) { _, item ->
                    TodoItemRow(
                        item = item,
                        todayMs = todayByName[item.name] ?: 0L,
                        enabled = !state.started,
                        showDelete = editMode,
                        onClick = {
                            if (editMode) {
                                editMode = false
                            } else {
                                ensureNotificationPermission()
                                viewModel.start(item)
                            }
                        },
                        onLongClick = { editMode = true },
                        onDelete = { deleteTarget = item },
                    )
                }
            }
        }

        // 添加待办的入口在「当前专注卡」的圆形加号上，列表底部不再重复放一个
    }

    if (showAddDialog) {
        AddTodoDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { name ->
                viewModel.add(name)
                showAddDialog = false
            },
        )
    }

    deleteTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除这件事？") },
            text = { Text("「${item.name}」将被移除，不影响已完成的专注记录。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(item)
                    deleteTarget = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }
}

/**
 * 当前专注卡：事项名与计时数字为视觉中心，圆环仅作氛围（细线 + 低透明度）。
 * 进度按当前小时推进，带缓动动画避免每秒跳动。
 */
@Composable
private fun FocusCard(
    state: TimerUiState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onAddFirst: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 28.dp, horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!state.started) {
                Text(
                    text = "选择一件事开始专注",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(22.dp))
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFFFF8A6B), Color(0xFFFF5F3F))
                            )
                        )
                        .clickable(onClick = onAddFirst),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.Add,
                        contentDescription = "添加一件事",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "点击加号添加一件事",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = if (state.running) "正在专注" else "已暂停",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = state.sessionName,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(14.dp))

                val ringColor = MaterialTheme.colorScheme.primary
                val targetProgress = ((state.totalMs % 3_600_000L) / 3_600_000f)
                    .coerceIn(0f, 1f)
                val animatedProgress by animateFloatAsState(
                    targetValue = targetProgress,
                    animationSpec = tween(durationMillis = 600),
                    label = "timerProgress",
                )

                Box(
                    modifier = Modifier.size(240.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val strokeWidth = 6.dp.toPx()
                        val inset = strokeWidth / 2f
                        val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)

                        // 底环：低透明度，只作氛围
                        drawArc(
                            color = ringColor.copy(alpha = 0.15f),
                            startAngle = -90f,
                            sweepAngle = 360f,
                            useCenter = false,
                            topLeft = Offset(inset, inset),
                            size = arcSize,
                            style = Stroke(width = strokeWidth),
                        )
                        if (animatedProgress > 0.002f) {
                            drawArc(
                                color = ringColor,
                                startAngle = -90f,
                                sweepAngle = 360f * animatedProgress,
                                useCenter = false,
                                topLeft = Offset(inset, inset),
                                size = arcSize,
                                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                            )
                        }
                    }
                    Text(
                        text = formatClock(state.totalMs),
                        fontSize = 56.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    CircleActionButton(
                        icon = if (state.running) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                        contentDescription = if (state.running) "暂停" else "继续",
                        filled = true,
                        onClick = if (state.running) onPause else onResume,
                    )
                    CircleActionButton(
                        icon = Icons.Outlined.Stop,
                        contentDescription = "结束",
                        filled = false,
                        onClick = onStop,
                    )
                }
            }
        }
    }
}

@Composable
private fun CircleActionButton(
    icon: ImageVector,
    contentDescription: String,
    filled: Boolean,
    onClick: () -> Unit,
) {
    val shape = CircleShape
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(shape)
            .then(
                if (filled) {
                    Modifier.background(
                        Brush.linearGradient(listOf(Color(0xFFFF8A6B), Color(0xFFFF5F3F)))
                    )
                } else {
                    Modifier.border(1.dp, MaterialTheme.colorScheme.outline, shape)
                }
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (filled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * 轻量列表行：默认只显示名称与今日投入，长按进入编辑模式后才出现删除。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TodoItemRow(
    item: TodoItem,
    todayMs: Long,
    enabled: Boolean,
    showDelete: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                enabled = enabled || showDelete,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(start = 8.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Schedule,
            contentDescription = null,
            tint = if (enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outline
            },
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = item.name,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (showDelete) {
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(17.dp),
                )
            }
        } else if (todayMs > 0) {
            Text(
                text = formatDurationCompact(todayMs),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
    }
}

@Composable
private fun AddTodoDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加一件事") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank(),
            ) { Text("添加") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
