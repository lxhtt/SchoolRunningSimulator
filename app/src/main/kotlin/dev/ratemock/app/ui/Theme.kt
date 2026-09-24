package dev.ratemock.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Neutral surfaces with separate motion and alert accents in both modes.
private val LightColors = lightColorScheme(
    primary = Color(0xFF176750),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD1F2DF),
    onPrimaryContainer = Color(0xFF082C1C),
    secondary = Color(0xFFA74632),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE1D9),
    onSecondaryContainer = Color(0xFF451410),
    tertiary = Color(0xFF2F647B),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD6EBF3),
    onTertiaryContainer = Color(0xFF0A2B38),
    background = Color(0xFFF4F7F5),
    onBackground = Color(0xFF19312B),
    surface = Color(0xFFF4F7F5),
    onSurface = Color(0xFF19312B),
    surfaceVariant = Color(0xFFE0E9E2),
    onSurfaceVariant = Color(0xFF42594D),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8FAF9),
    surfaceContainer = Color(0xFFEFF3F0),
    surfaceContainerHigh = Color(0xFFE8EEEA),
    surfaceContainerHighest = Color(0xFFE0E9E2),
    outline = Color(0xFF788B7E),
    outlineVariant = Color(0xFFC7D3C9),
    surfaceTint = Color(0xFF176750),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF93E1BB),
    onPrimary = Color(0xFF063D27),
    primaryContainer = Color(0xFF1B553E),
    onPrimaryContainer = Color(0xFFC7F7D8),
    secondary = Color(0xFFFFB099),
    onSecondary = Color(0xFF642919),
    secondaryContainer = Color(0xFF7D3624),
    onSecondaryContainer = Color(0xFFFFE2D8),
    tertiary = Color(0xFFAAD3E5),
    onTertiary = Color(0xFF173A4B),
    tertiaryContainer = Color(0xFF2B5365),
    onTertiaryContainer = Color(0xFFD6ECF5),
    background = Color(0xFF121714),
    onBackground = Color(0xFFE5EEE6),
    surface = Color(0xFF121714),
    onSurface = Color(0xFFE5EEE6),
    surfaceVariant = Color(0xFF303D33),
    onSurfaceVariant = Color(0xFFC4D6C7),
    surfaceContainerLowest = Color(0xFF0E1310),
    surfaceContainerLow = Color(0xFF1A231D),
    surfaceContainer = Color(0xFF202A23),
    surfaceContainerHigh = Color(0xFF28342B),
    surfaceContainerHighest = Color(0xFF303D33),
    outline = Color(0xFF84998A),
    outlineVariant = Color(0xFF425548),
    surfaceTint = Color(0xFF93E1BB),
)

@Composable
fun RateMockTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
