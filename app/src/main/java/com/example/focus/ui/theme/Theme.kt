package com.example.focus.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 一事 主题：米白 + 炭黑 + 珊瑚橙 + 少量暖黄。
 * 极简、克制、留白、超大圆角、极少边框、轻阴影。
 * 橙色只用在焦点处（计时中、主按钮、重要数据、选中状态），避免满屏橙。
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFFFF684A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE5DE),
    onPrimaryContainer = Color(0xFF8A2E1E),
    secondary = Color(0xFFFFB547),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFEFD6),
    onSecondaryContainer = Color(0xFF6B4700),
    tertiary = Color(0xFF48B59B),
    onTertiary = Color.White,
    background = Color(0xFFFAF7F5),
    onBackground = Color(0xFF242424),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF242424),
    surfaceVariant = Color(0xFFF6F1EF),
    onSurfaceVariant = Color(0xFF8E8A88),
    outline = Color(0xFFEDE9E6),
    outlineVariant = Color(0xFFF2EEEC),
    error = Color(0xFFD93B2B),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF7357),
    onPrimary = Color(0xFF3D1108),
    primaryContainer = Color(0xFF7A2A1B),
    onPrimaryContainer = Color(0xFFFFE4DC),
    secondary = Color(0xFFFFC96B),
    onSecondary = Color(0xFF4A3000),
    secondaryContainer = Color(0xFF5C4000),
    onSecondaryContainer = Color(0xFFFFEFD6),
    tertiary = Color(0xFF5CC9A7),
    onTertiary = Color(0xFF07301F),
    background = Color(0xFF101010),
    onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF191919),
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF222222),
    onSurfaceVariant = Color(0xFF8D8D8D),
    outline = Color(0xFF2E2E2E),
    outlineVariant = Color(0xFF262626),
    error = Color(0xFFFF8A7A),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

/** 顶部柔光渐变：浅珊瑚粉过渡到页面底色（深色模式用暗暖色过渡） */
@Composable
fun screenGradient(): List<Color> {
    val dark = isSystemInDarkTheme()
    return if (dark) {
        listOf(Color(0xFF1E1412), MaterialTheme.colorScheme.background)
    } else {
        listOf(Color(0xFFFFE9E2), MaterialTheme.colorScheme.background)
    }
}

@Composable
fun FocusTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = AppShapes,
        content = content,
    )
}
