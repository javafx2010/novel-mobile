package com.wildenglish.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF007AFF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE3F0FF),
    onPrimaryContainer = Color(0xFF003A70),
    secondary = Color(0xFF5856D6),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF34C759),
    background = Color(0xFFE6EDF6),
    onBackground = Color(0xFF000000),
    surface = Color(0xFFF7FAFD),
    onSurface = Color(0xFF000000),
    surfaceVariant = Color(0xFFE5E5EA),
    onSurfaceVariant = Color(0xFF6C6C70),
    outline = Color(0xFFC6C6C8),
    outlineVariant = Color(0xFFE5E5EA),
    error = Color(0xFFFF3B30),
    onError = Color(0xFFFFFFFF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF0A84FF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF10284A),
    onPrimaryContainer = Color(0xFFCFE2FF),
    secondary = Color(0xFF5E5CE6),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF30D158),
    background = Color(0xFF0B1018),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF1C2430),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF3A3A3C),
    onSurfaceVariant = Color(0xFFC7C7CC),
    outline = Color(0xFF5A5A5E),
    outlineVariant = Color(0xFF48484A),
    error = Color(0xFFFF453A),
    onError = Color(0xFFFFFFFF),
)

@Composable
fun AudioBookTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
}
