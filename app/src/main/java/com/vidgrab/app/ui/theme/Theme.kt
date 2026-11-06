package com.vidgrab.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val VidGrabColors = darkColorScheme(
    primary = Color(0xFF22D3A6),
    onPrimary = Color(0xFF04211A),
    primaryContainer = Color(0xFF0E3B31),
    onPrimaryContainer = Color(0xFFB8F5E4),
    secondary = Color(0xFF7AA2FF),
    onSecondary = Color(0xFF0A1633),
    secondaryContainer = Color(0xFF1B2A4A),
    onSecondaryContainer = Color(0xFFD8E4FF),
    background = Color(0xFF0F1115),
    onBackground = Color(0xFFE8EAF0),
    surface = Color(0xFF141821),
    onSurface = Color(0xFFE8EAF0),
    surfaceVariant = Color(0xFF1C2230),
    onSurfaceVariant = Color(0xFFAAB3C5),
    outline = Color(0xFF3A4356),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF2A0606),
    errorContainer = Color(0xFF43181A),
    onErrorContainer = Color(0xFFFFD9D9)
)

@Composable
fun VidGrabTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = VidGrabColors,
        content = content
    )
}
