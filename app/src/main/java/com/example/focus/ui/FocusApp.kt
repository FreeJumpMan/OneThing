package com.example.focus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.focus.ui.history.HistoryScreen
import com.example.focus.ui.stats.StatsScreen
import com.example.focus.ui.theme.screenGradient
import com.example.focus.ui.todo.TodoScreen

/**
 * 应用导航骨架：顶部柔光渐变背景 + 极简底部导航（小图标小文字、无胶囊指示器）。
 */
@Composable
fun FocusApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = screenGradient(),
                    startY = 0f,
                    endY = 700f,
                )
            )
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                ) {
                    val itemColors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        // 去掉 Material 胶囊指示器，只用颜色区分选中
                        indicatorColor = Color.Transparent,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    NavigationBarItem(
                        selected = currentRoute == "todo",
                        onClick = {
                            navController.navigate("todo") {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(Icons.Outlined.Timer, contentDescription = "专注") },
                        label = { Text("专注") },
                        colors = itemColors,
                    )
                    NavigationBarItem(
                        selected = currentRoute == "history",
                        onClick = {
                            navController.navigate("history") {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(Icons.Outlined.DateRange, contentDescription = "历史") },
                        label = { Text("历史") },
                        colors = itemColors,
                    )
                    NavigationBarItem(
                        selected = currentRoute == "stats",
                        onClick = {
                            navController.navigate("stats") {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(Icons.Outlined.BarChart, contentDescription = "统计") },
                        label = { Text("统计") },
                        colors = itemColors,
                    )
                }
            }
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = "todo",
                modifier = Modifier.padding(padding),
            ) {
                composable("todo") { TodoScreen() }
                composable("history") { HistoryScreen() }
                composable("stats") { StatsScreen() }
            }
        }
    }
}
