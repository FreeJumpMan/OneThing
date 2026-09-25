package com.example.focus.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.focus.ui.parseHexColor

/**
 * 分类详情：给这个分类指派 App。
 * App 候选来自最近 30 天使用过的记录；勾选即写入用户规则（优先级高于内置规则）。
 */
@Composable
fun CategoryDetailScreen(
    categoryId: Long,
    onBack: () -> Unit,
    viewModel: CategorySettingsViewModel = viewModel(),
) {
    val categories by viewModel.categories.collectAsState()
    val apps by viewModel.apps.collectAsState()
    val rules by viewModel.rules.collectAsState()
    val loading by viewModel.loadingApps.collectAsState()
    var showEditDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.loadApps() }

    val item = categories.firstOrNull { it.category.id == categoryId }
    val category = item?.category

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
    ) {
        item {
            Row(
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
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
                if (category != null) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(parseHexColor(category.colorHex))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = category?.name ?: "分类",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                if (category != null) {
                    IconButton(
                        onClick = { showEditDialog = true },
                        modifier = Modifier.size(34.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Edit,
                            contentDescription = "编辑分类",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                }
            }
            Text(
                text = "勾选的 App，其使用时间会计入「${category?.name ?: ""}」分类。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }

        if (loading) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 28.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "正在读取最近 30 天的使用记录…",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else if (apps.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 28.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "没有可归类的 App（需要先开启 App 时间记录）",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            items(apps, key = { it.packageName }) { app ->
                val assigned = category != null && rules[app.packageName]?.categoryId == category.id
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (category == null) return@clickable
                                if (assigned) {
                                    viewModel.clearAppRule(app.packageName)
                                } else {
                                    viewModel.assignApp(app, category)
                                }
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIconSmall(icon = app.icon, fallback = app.appName.take(1))
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = app.appName,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val currentRule = rules[app.packageName]
                            if (currentRule != null && !assigned) {
                                Text(
                                    text = "已归到其他分类",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                        Checkbox(
                            checked = assigned,
                            onCheckedChange = { checked ->
                                if (category == null) return@Checkbox
                                if (checked) viewModel.assignApp(app, category)
                                else viewModel.clearAppRule(app.packageName)
                            },
                            colors = CheckboxDefaults.colors(
                                checkedColor = MaterialTheme.colorScheme.primary,
                                uncheckedColor = MaterialTheme.colorScheme.outline,
                            ),
                        )
                    }
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline,
                        thickness = 0.5.dp,
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(28.dp)) }
    }

    if (showEditDialog && category != null) {
        CategoryEditDialog(
            initial = category,
            onDismiss = { showEditDialog = false },
            onConfirm = { name, color ->
                viewModel.updateCategory(category, name, color)
                showEditDialog = false
            },
            onDelete = {
                viewModel.deleteCategory(category)
                onBack()
            },
            hasApps = (item?.appCount ?: 0) > 0,
        )
    }
}

@Composable
private fun AppIconSmall(icon: ImageBitmap?, fallback: String) {
    if (icon != null) {
        Image(
            bitmap = icon,
            contentDescription = null,
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(9.dp)),
        )
    } else {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = fallback,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
