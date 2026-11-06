package com.vidgrab.app.core.designsystem

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vidgrab.app.core.model.ThemeAccent

/** Accent colours: (dark-theme primary, light-theme primary, gradient partner). */
private data class AccentSpec(val dark: Color, val light: Color, val partner: Color)

private val accents = mapOf(
    ThemeAccent.ORANGE to AccentSpec(Color(0xFFFF6A1A), Color(0xFFFF5200), Color(0xFFFF2D55)),
    ThemeAccent.MINT to AccentSpec(Color(0xFF1DD8B8), Color(0xFF00A38A), Color(0xFF6C8CFF)),
    ThemeAccent.OCEAN to AccentSpec(Color(0xFF5AA9FF), Color(0xFF1B6FE0), Color(0xFF39D5E8)),
    ThemeAccent.VIOLET to AccentSpec(Color(0xFFAE94FF), Color(0xFF6E48E8), Color(0xFFFF7AC6)),
    ThemeAccent.SUNSET to AccentSpec(Color(0xFFFF9257), Color(0xFFE5541B), Color(0xFFFF5C8A)),
    ThemeAccent.ROSE to AccentSpec(Color(0xFFFF7AA8), Color(0xFFD02A68), Color(0xFFB98CFF)),
    ThemeAccent.LIME to AccentSpec(Color(0xFFB3EE4A), Color(0xFF4F8A00), Color(0xFF2FD3A7)),
    ThemeAccent.GOLD to AccentSpec(Color(0xFFFFCB52), Color(0xFFB07A00), Color(0xFFFF8A3D)),
)

fun accentPreview(accent: ThemeAccent): Pair<Color, Color> = accents.getValue(accent).let { it.dark to it.partner }

object QtColors {
    val Coral = Color(0xFFFF6B6B)
    val Success = Color(0xFF2FCB7A)
    val Warning = Color(0xFFFFB547)
}

private fun onColor(c: Color) = if (c.luminance() > 0.45f) Color(0xFF0B0F14) else Color.White

private fun darkScheme(a: AccentSpec, amoled: Boolean): ColorScheme {
    val bg = if (amoled) Color.Black else Color(0xFF0B0B0C)
    val p = a.dark
    return darkColorScheme(
        primary = p,
        onPrimary = onColor(p),
        primaryContainer = p.copy(alpha = 0.18f).compositeOver(bg),
        onPrimaryContainer = p.copy(alpha = 0.95f).compositeOver(Color.White),
        secondary = a.partner,
        onSecondary = onColor(a.partner),
        secondaryContainer = a.partner.copy(alpha = 0.18f).compositeOver(bg),
        onSecondaryContainer = Color.White,
        tertiary = QtColors.Warning,
        background = bg,
        onBackground = Color(0xFFF4F4F5),
        surface = bg,
        onSurface = Color(0xFFF4F4F5),
        surfaceVariant = if (amoled) Color(0xFF121214) else Color(0xFF17171A),
        onSurfaceVariant = Color(0xFF9A9AA2),
        surfaceContainerLowest = if (amoled) Color.Black else Color(0xFF080809),
        surfaceContainerLow = if (amoled) Color(0xFF0A0A0B) else Color(0xFF111113),
        surfaceContainer = if (amoled) Color(0xFF101012) else Color(0xFF17171A),
        surfaceContainerHigh = if (amoled) Color(0xFF151517) else Color(0xFF1D1D21),
        surfaceContainerHighest = if (amoled) Color(0xFF1C1C1F) else Color(0xFF26262B),
        outline = Color(0xFF3B3B42),
        outlineVariant = Color(0xFF262629),
        error = QtColors.Coral,
        scrim = Color.Black,
    )
}

private fun lightScheme(a: AccentSpec): ColorScheme {
    val bg = Color.White
    val p = a.light
    return lightColorScheme(
        primary = p,
        onPrimary = onColor(p),
        primaryContainer = p.copy(alpha = 0.10f).compositeOver(Color.White),
        onPrimaryContainer = Color.Black.copy(alpha = 0.42f).compositeOver(p),
        secondary = a.partner,
        onSecondary = onColor(a.partner),
        secondaryContainer = a.partner.copy(alpha = 0.10f).compositeOver(Color.White),
        onSecondaryContainer = Color.Black.copy(alpha = 0.42f).compositeOver(a.partner),
        tertiary = Color(0xFFE09400),
        background = bg,
        onBackground = Color(0xFF111418),
        surface = bg,
        onSurface = Color(0xFF111418),
        surfaceVariant = Color(0xFFF1F3F5),
        onSurfaceVariant = Color(0xFF6B737C),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(0xFFF8F9FA),
        surfaceContainer = Color(0xFFF3F4F6),
        surfaceContainerHigh = Color(0xFFEEF0F2),
        surfaceContainerHighest = Color(0xFFE7EAED),
        outline = Color(0xFFD5D9DE),
        outlineVariant = Color(0xFFEBEDF0),
        error = Color(0xFFD93F3F),
    )
}

private val typography = Typography().let { t ->
    fun TextStyle.j(weight: FontWeight? = null, ls: Float? = null) =
        copy(fontWeight = weight ?: fontWeight, letterSpacing = ls?.sp ?: letterSpacing)
    Typography(
        displayLarge = t.displayLarge.j(FontWeight.Bold, -0.5f),
        displayMedium = t.displayMedium.j(FontWeight.Bold, -0.4f),
        displaySmall = t.displaySmall.j(FontWeight.Bold, -0.2f),
        headlineLarge = t.headlineLarge.j(FontWeight.Bold, -0.2f),
        headlineMedium = t.headlineMedium.j(FontWeight.Bold, -0.2f),
        headlineSmall = t.headlineSmall.j(FontWeight.Bold, -0.1f),
        titleLarge = t.titleLarge.j(FontWeight.Bold, 0f),
        titleMedium = t.titleMedium.j(FontWeight.SemiBold, 0f),
        titleSmall = t.titleSmall.j(FontWeight.SemiBold, 0f),
        bodyLarge = t.bodyLarge.j(FontWeight.Normal, 0f),
        bodyMedium = t.bodyMedium.j(FontWeight.Normal, 0f),
        bodySmall = t.bodySmall.j(FontWeight.Normal, 0.1f),
        labelLarge = t.labelLarge.j(FontWeight.SemiBold, 0.1f),
        labelMedium = t.labelMedium.j(FontWeight.SemiBold, 0.2f),
        labelSmall = t.labelSmall.j(FontWeight.Normal, 0.2f),
    )
}

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

@Immutable
data class QtExtras(val isDark: Boolean, val brand: Brush, val brandStart: Color, val brandEnd: Color)

val LocalQtExtras = staticCompositionLocalOf { QtExtras(true, Brush.linearGradient(listOf(Color.Cyan, Color.Blue)), Color.Cyan, Color.Blue) }

val BrandTitle = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.6).sp)

@Composable
fun VidGrabTheme(
    darkTheme: Boolean,
    accent: ThemeAccent = ThemeAccent.GOLD,
    amoled: Boolean = false,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val spec = accents.getValue(accent)
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= 31 ->
            (if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)).let {
                if (darkTheme && amoled) it.copy(background = Color.Black, surface = Color.Black) else it
            }
        darkTheme -> darkScheme(spec, amoled)
        else -> lightScheme(spec)
    }
    val start = scheme.primary
    val end = if (dynamicColor && Build.VERSION.SDK_INT >= 31) scheme.tertiary else spec.partner
    // Soft, accent-tinted ripple instead of the default grey splash.
    val ripple = RippleConfiguration(
        color = scheme.primary,
        rippleAlpha = RippleAlpha(draggedAlpha = 0.16f, focusedAlpha = 0.12f, hoveredAlpha = 0.08f, pressedAlpha = 0.14f),
    )
    CompositionLocalProvider(
        LocalQtExtras provides QtExtras(darkTheme, Brush.linearGradient(listOf(start, end)), start, end),
        LocalRippleConfiguration provides ripple,
    ) {
        MaterialTheme(colorScheme = scheme, typography = typography, shapes = shapes) {
            // Default text/icon colour for everything not inside a Surface: without this, plain Text()
            // falls back to black, which is unreadable in dark mode.
            CompositionLocalProvider(LocalContentColor provides scheme.onBackground, content = content)
        }
    }
}
