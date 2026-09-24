package dev.ratemock.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// A quiet instrument-panel palette; no network fonts or decorative animation.
private val LightColors = lightColorScheme(
    primary = Color(0xFF126B59),
    onPrimary = Color.White,
    secondary = Color(0xFFB14C38),
    background = Color(0xFFF5F7F6),
    onBackground = Color(0xFF19312B),
    surface = Color(0xFFF5F7F6),
    onSurface = Color(0xFF19312B),
    surfaceVariant = Color(0xFFDCE9E1),
    onSurfaceVariant = Color(0xFF42594D),
    surfaceContainerHighest = Color(0xFFE4ECE6),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8BDBB2),
    onPrimary = Color(0xFF113A2B),
    secondary = Color(0xFFFFB097),
    background = Color(0xFF151B19),
    onBackground = Color(0xFFE7EFE9),
    surface = Color(0xFF151B19),
    onSurface = Color(0xFFE7EFE9),
    surfaceVariant = Color(0xFF26382F),
    onSurfaceVariant = Color(0xFFC4D5C9),
    surfaceContainerHighest = Color(0xFF26382F),
)

@Composable
fun RateMockTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
