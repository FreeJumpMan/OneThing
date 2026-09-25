package com.example.focus.ui.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.focus.data.prefs.ThemeMode
import com.example.focus.ui.parseHexColor
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 设置页：集中管理权限、记录规则、数据备份与外观。
 * 全应用只有「专注页右上角」一个设置入口。
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenFocusApps: () -> Unit,
    onOpenCategories: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by viewModel.settings.collectAsState()
    val permissions by viewModel.permissions.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    var showThemeDialog by remember { mutableStateOf(false) }
    var showToleranceDialog by remember { mutableStateOf(false) }
    var showCategorySyncDialog by remember { mutableStateOf(false) }
    var showImportConfirm by remember { mutableStateOf(false) }

    // 从系统设置返回时刷新权限状态
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshPermissions()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { viewModel.refreshPermissions() }

    val calendarLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { viewModel.refreshPermissions() }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val json = viewModel.buildBackupJson()
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                }
                Toast.makeText(context, "数据已导出", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val json = runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull() ?: ""
                val error = viewModel.restoreFromJson(json)
                Toast.makeText(
                    context,
                    if (error == null) "数据已恢复" else "导入失败：$error",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
    ) {
        item {
            Row(
                modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
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
                    text = "设置",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }

        // ===== 专注 =====
        item {
            SettingsSection("专注") {
                SettingRow(
                    title = "专注 App",
                    subtitle = "这些 App 的使用时间会进入专注统计",
                    onClick = onOpenFocusApps,
                    showArrow = true,
                )
            }
        }

        // ===== 自动记录 =====
        item {
            SettingsSection("自动记录") {
                StatusRow(
                    title = "App 时间记录",
                    subtitle = "统计各 App 使用时长",
                    enabled = permissions.usageAccess,
                    actionLabel = "去开启",
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                        }
                    },
                )
                SettingRow(
                    title = "短暂切换阈值",
                    subtitle = "离开专注 App 不超过该时长不打断连续专注",
                    value = "${settings.switchToleranceMinutes} 分钟",
                    onClick = { showToleranceDialog = true },
                )
                SettingRow(
                    title = "时间分类",
                    subtitle = "给 App 指定所属分类（学习 / 社交 / 刷视频…）",
                    onClick = onOpenCategories,
                    showArrow = true,
                )
            }
        }

        // ===== 同步 =====
        item {
            SettingsSection("同步") {
                StatusRow(
                    title = "日历同步",
                    subtitle = "把专注记录写入系统日历",
                    enabled = permissions.calendar,
                    actionLabel = "去开启",
                    onClick = {
                        if (!permissions.calendar) {
                            calendarLauncher.launch(
                                arrayOf(
                                    Manifest.permission.READ_CALENDAR,
                                    Manifest.permission.WRITE_CALENDAR,
                                )
                            )
                        } else {
                            context.openAppSettings()
                        }
                    },
                )
                SettingRow(
                    title = "同步的分类",
                    subtitle = "选择哪些分类的时间块写入日历",
                    value = if (settings.excludedCalendarCategories.isEmpty()) {
                        "全部"
                    } else {
                        "${settings.excludedCalendarCategories.size} 个已关闭"
                    },
                    onClick = { showCategorySyncDialog = true },
                )
            }
        }

        // ===== 通知与后台 =====
        item {
            SettingsSection("通知与后台") {
                StatusRow(
                    title = "通知权限",
                    subtitle = "通知栏与锁屏显示专注状态",
                    enabled = permissions.notification,
                    actionLabel = "去开启",
                    onClick = {
                        if (Build.VERSION.SDK_INT >= 33 && !permissions.notification) {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            context.openAppSettings()
                        }
                    },
                )
                StatusRow(
                    title = "后台运行",
                    subtitle = "关闭电池优化，避免息屏后计时被冻结",
                    enabled = permissions.ignoringBatteryOptimization,
                    actionLabel = "去设置",
                    onClick = { context.requestIgnoreBatteryOptimization() },
                )
                SettingRow(
                    title = "自启动 / 后台高耗电",
                    subtitle = "在系统设置中允许（vivo 需手动开启）",
                    value = "去设置",
                    onClick = { context.openAppSettings() },
                )
            }
        }

        // ===== 数据 =====
        item {
            SettingsSection("数据") {
                SettingRow(
                    title = "备份数据",
                    subtitle = "导出为 JSON 文件（专注记录、事项、日历映射）",
                    value = "导出",
                    onClick = {
                        exportLauncher.launch("一事备份_${LocalDate.now()}.json")
                    },
                )
                SettingRow(
                    title = "恢复数据",
                    subtitle = "从备份文件导入，会覆盖当前数据",
                    value = "导入",
                    onClick = { showImportConfirm = true },
                )
            }
        }

        // ===== 外观 =====
        item {
            SettingsSection("外观") {
                SettingRow(
                    title = "深色模式",
                    value = when (settings.themeMode) {
                        ThemeMode.FOLLOW_SYSTEM -> "跟随系统"
                        ThemeMode.LIGHT -> "浅色"
                        ThemeMode.DARK -> "深色"
                    },
                    onClick = { showThemeDialog = true },
                )
            }
        }

        // ===== 关于 =====
        item {
            SettingsSection("关于") {
                SettingRow(
                    title = "版本",
                    value = "1.4",
                    onClick = null,
                )
            }
        }

        item { Spacer(modifier = Modifier.height(28.dp)) }
    }

    // 同步的分类选择
    if (showCategorySyncDialog) {
        AlertDialog(
            onDismissRequest = { showCategorySyncDialog = false },
            title = { Text("同步的分类") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = "在 App 页面把时间轴同步到日历时，关闭的分类不会写入。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                    categories.forEach { category ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(parseHexColor(category.colorHex))
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = category.name,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = category.id.toString() !in settings.excludedCalendarCategories,
                                onCheckedChange = { enabled ->
                                    viewModel.setCategoryCalendarSync(category.id, enabled)
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                                ),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCategorySyncDialog = false }) { Text("完成") }
            },
        )
    }

    // 深色模式选择
    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text("深色模式") },
            text = {
                Column {
                    ThemeMode.entries.forEach { mode ->
                        val label = when (mode) {
                            ThemeMode.FOLLOW_SYSTEM -> "跟随系统"
                            ThemeMode.LIGHT -> "浅色"
                            ThemeMode.DARK -> "深色"
                        }
                        DialogOption(
                            text = label,
                            selected = settings.themeMode == mode,
                            onClick = {
                                viewModel.setThemeMode(mode)
                                showThemeDialog = false
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemeDialog = false }) { Text("取消") }
            },
        )
    }

    // 短暂切换阈值选择
    if (showToleranceDialog) {
        AlertDialog(
            onDismissRequest = { showToleranceDialog = false },
            title = { Text("短暂切换阈值") },
            text = {
                Column {
                    listOf(1, 2, 5, 10).forEach { minutes ->
                        DialogOption(
                            text = "$minutes 分钟",
                            selected = settings.switchToleranceMinutes == minutes,
                            onClick = {
                                viewModel.setSwitchTolerance(minutes)
                                showToleranceDialog = false
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showToleranceDialog = false }) { Text("取消") }
            },
        )
    }

    // 导入确认
    if (showImportConfirm) {
        AlertDialog(
            onDismissRequest = { showImportConfirm = false },
            title = { Text("恢复数据？") },
            text = { Text("导入将替换当前所有数据（专注记录、事项、日历映射）。建议先备份当前数据。") },
            confirmButton = {
                TextButton(onClick = {
                    showImportConfirm = false
                    importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                }) { Text("选择文件") }
            },
            dismissButton = {
                TextButton(onClick = { showImportConfirm = false }) { Text("取消") }
            },
        )
    }
}

/** 设置分组：小标题 + 细分隔线的条目区 */
@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(modifier = Modifier.height(18.dp))
        Text(
            text = title,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        content()
    }
}

/** 普通设置项 */
@Composable
private fun SettingRow(
    title: String,
    subtitle: String? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    showArrow: Boolean = false,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            if (value != null) {
                Text(
                    text = value,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (showArrow) {
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outline,
            thickness = 0.5.dp,
        )
    }
}

/** 带权限状态的设置项 */
@Composable
private fun StatusRow(
    title: String,
    subtitle: String,
    enabled: Boolean,
    actionLabel: String,
    onClick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (enabled) {
                Text(
                    text = "已开启",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF48B59B),
                )
            } else {
                Box(
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.primary,
                            androidx.compose.foundation.shape.RoundedCornerShape(50),
                        )
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                ) {
                    Text(
                        text = actionLabel,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outline,
            thickness = 0.5.dp,
        )
    }
}

/** 对话框里的单选项 */
@Composable
private fun DialogOption(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = text,
            fontSize = 15.sp,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
        if (selected) {
            Text(
                text = "已选",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

// ===== 跳转系统设置 =====

internal fun android.content.Context.openAppSettings() {
    runCatching {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName"),
            )
        )
    }
}

internal fun android.content.Context.requestIgnoreBatteryOptimization() {
    val direct = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:$packageName"),
    )
    val launched = runCatching { startActivity(direct) }.isSuccess
    if (!launched) {
        runCatching {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}
