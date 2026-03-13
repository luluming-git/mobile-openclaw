package com.openclaw.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF7C4DFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF311B92),
    onPrimaryContainer = Color(0xFFE8DDFF),
    secondary = Color(0xFF00E5FF),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF004D56),
    onSecondaryContainer = Color(0xFF97F0FF),
    tertiary = Color(0xFFFF6D00),
    background = Color(0xFF0D0D1A),
    onBackground = Color(0xFFE6E1E5),
    surface = Color(0xFF141428),
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF1E1E3A),
    onSurfaceVariant = Color(0xFFCAC4D0),
    error = Color(0xFFFF5252),
    onError = Color.White,
    outline = Color(0xFF3D3D5C),
)

@Composable
fun OpenClawTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography(),
        content = content
    )
}
