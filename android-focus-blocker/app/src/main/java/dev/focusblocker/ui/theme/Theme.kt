package dev.focusblocker.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Navy = Color(0xFF1E293B)
private val Sky = Color(0xFF0284C7)
private val SkyLight = Color(0xFF7DD3FC)

private val LightScheme = lightColorScheme(
    primary = Sky,
    secondary = Navy,
)

private val DarkScheme = darkColorScheme(
    primary = SkyLight,
    secondary = Color(0xFF94A3B8),
)

@Composable
fun FocusBlockerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        content = content,
    )
}
