package com.suhas.globaledgeai.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Background = Color(0xFF071017)
private val Surface = Color(0xFF0D1820)
private val Surface2 = Color(0xFF12232D)
private val Accent = Color(0xFF00D29A)
private val Accent2 = Color(0xFF5FD2FF)
private val Warning = Color(0xFFFFB84D)
private val Danger = Color(0xFFFF6B6B)

private val DarkScheme = darkColorScheme(
    primary = Accent,
    secondary = Accent2,
    tertiary = Warning,
    background = Background,
    surface = Surface,
    surfaceVariant = Surface2,
    error = Danger,
    onPrimary = Color(0xFF00251B),
    onSecondary = Color(0xFF001F2A),
    onBackground = Color(0xFFE8F0F3),
    onSurface = Color(0xFFE8F0F3),
    onSurfaceVariant = Color(0xFFB6C6CE)
)

@Composable
fun GlobalEdgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkScheme,
        typography = Typography(),
        content = content
    )
}
