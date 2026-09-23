package dev.ratemock.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// A quiet instrument-panel palette; no network fonts or decorative animation.
private val LightColors = lightColorScheme(
    primary = Color(0xFF174C63),
    onPrimary = Color.White,
    background = Color(0xFFF2F6F8),
    onBackground = Color(0xFF172D37),
    surface = Color(0xFFF2F6F8),
    onSurface = Color(0xFF172D37),
    surfaceVariant = Color(0xFFDCE7ED),
    onSurfaceVariant = Color(0xFF354D59),
    surfaceContainerHighest = Color(0xFFDCE7ED),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA7D3EA),
    onPrimary = Color(0xFF103545),
    background = Color(0xFF10191D),
    onBackground = Color(0xFFE7EFF3),
    surface = Color(0xFF10191D),
    onSurface = Color(0xFFE7EFF3),
    surfaceVariant = Color(0xFF243840),
    onSurfaceVariant = Color(0xFFC1D4DE),
    surfaceContainerHighest = Color(0xFF243840),
)

@Composable
fun RateMockTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
