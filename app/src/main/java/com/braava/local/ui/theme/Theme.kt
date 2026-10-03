package com.braava.local.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF1E6F5C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB6E3D3),
    onPrimaryContainer = Color(0xFF05201A),
    secondary = Color(0xFF4A635B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDE8DF),
    onSecondaryContainer = Color(0xFF05201A),
    background = Color(0xFFF7F9F8),
    surface = Color(0xFFF7F9F8),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9AD3C1),
    onPrimary = Color(0xFF00382C),
    primaryContainer = Color(0xFF00513F),
    onPrimaryContainer = Color(0xFFB6E3D3),
    secondary = Color(0xFFB1CCC2),
    onSecondary = Color(0xFF1C352E),
    background = Color(0xFF101412),
    surface = Color(0xFF101412),
)

@Composable
fun BraavaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
