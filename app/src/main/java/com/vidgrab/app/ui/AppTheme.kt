package com.vidgrab.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import com.vidgrab.app.core.designsystem.VidGrabTheme
import com.vidgrab.app.core.model.AppSettings
import com.vidgrab.app.core.model.ThemeMode

@Composable
fun AppSettings.isDark(): Boolean = when (themeMode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/** The app theme as configured in Settings → Appearance. */
@Composable
fun AppTheme(settings: AppSettings, content: @Composable () -> Unit) {
    VidGrabTheme(
        darkTheme = settings.isDark(),
        accent = settings.accent,
        amoled = settings.amoled,
        dynamicColor = settings.dynamicColor,
        content = content,
    )
}
