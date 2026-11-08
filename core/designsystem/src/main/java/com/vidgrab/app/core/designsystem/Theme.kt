package com.vidgrab.app.core.designsystem

import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vidgrab.app.core.designsystem.R
import com.vidgrab.app.core.model.ThemeAccent

/**
 * VidGrab brand palette.
 *
 * Navy is the anchor, blue is the action colour and teal is the signal colour.
 * Every scheme is built from these instead of tinting a default Material palette,
 * so light and dark feel designed rather than inverted.
 */
object VidGrabPalette {
    val Navy = Color(0xFF101B35)
    val BrandBlue = Color(0xFF4169FF)
    val BrandBlueDark = Color(0xFF3358E8)
    val Teal = Color(0xFF00A98F)
    val Green = Color(0xFF13C99A)

    val LightBackground = Color(0xFFF5F7FC)
    val LightSurface = Color(0xFFFFFFFF)
    val LightSurfaceAlt = Color(0xFFF0F4FA)
    val LightBorder = Color(0xFFE2E8F0)
    val LightText = Color(0xFF111827)
    val LightTextSecondary = Color(0xFF64748B)

    val DarkBackground = Color(0xFF0B1220)
    val DarkSurface = Color(0xFF111C30)
    val DarkSurfaceHigh = Color(0xFF19263C)
    val DarkSurfaceHighest = Color(0xFF223350)
    val DarkBorder = Color(0xFF26364F)
    val DarkText = Color(0xFFF8FAFC)
    val DarkTextSecondary = Color(0xFF94A3B8)
}

/** Accent colours: (dark-theme primary, light-theme primary, gradient partner). */
private data class AccentSpec(val dark: Color, val light: Color, val partner: Color)

private val accents = mapOf(
    ThemeAccent.OCEAN to AccentSpec(Color(0xFF4169FF), Color(0xFF3358E8), Color(0xFF13C99A)),
    ThemeAccent.MINT to AccentSpec(Color(0xFF1DD8B8), Color(0xFF008F79), Color(0xFF4169FF)),
    ThemeAccent.ORANGE to AccentSpec(Color(0xFFFF8A4C), Color(0xFFE5570E), Color(0xFFFF3D68)),
    ThemeAccent.VIOLET to AccentSpec(Color(0xFFAE94FF), Color(0xFF6E48E8), Color(0xFF2EC4B6)),
    ThemeAccent.SUNSET to AccentSpec(Color(0xFFFF9257), Color(0xFFE5541B), Color(0xFFFF5C8A)),
    ThemeAccent.ROSE to AccentSpec(Color(0xFFFF7AA8), Color(0xFFD02A68), Color(0xFF4169FF)),
    ThemeAccent.LIME to AccentSpec(Color(0xFFB3EE4A), Color(0xFF4F8A00), Color(0xFF13C99A)),
    ThemeAccent.GOLD to AccentSpec(Color(0xFFFFCB52), Color(0xFFB07A00), Color(0xFFFF8A3D)),
)

fun accentPreview(accent: ThemeAccent): Pair<Color, Color> = accents.getValue(accent).let { it.dark to it.partner }

object QtColors {
    val Coral = Color(0xFFFF6B7A)
    val Success = Color(0xFF13C99A)
    val Warning = Color(0xFFFFB340)
    val LiveBlue = Color(0xFF4169FF)
    val LiveMint = Color(0xFF13C99A)
    val LiveAmber = Color(0xFFFFB340)

    /** Darker signal inks for light surfaces, where the raw signal colours are too bright to read. */
    val InkMint = Color(0xFF007A63)
    val InkBlue = Color(0xFF2E4BDB)
    val InkAmber = Color(0xFF8A5200)
}

/** A signal colour that stays legible on the current theme's background. */
@Composable
fun signalInk(color: Color): Color {
    if (MaterialTheme.colorScheme.background.luminance() <= 0.5f) return color
    return when {
        color == QtColors.LiveMint || color == QtColors.Success -> QtColors.InkMint
        color == QtColors.LiveBlue -> QtColors.InkBlue
        color == QtColors.LiveAmber || color == QtColors.Warning -> QtColors.InkAmber
        else -> color
    }
}

private fun onColor(c: Color) = if (c.luminance() > 0.45f) Color(0xFF0B1220) else Color.White

private fun darkScheme(a: AccentSpec, amoled: Boolean): ColorScheme {
    val bg = if (amoled) Color.Black else VidGrabPalette.DarkBackground
    val p = a.dark
    val surface = if (amoled) Color(0xFF070B13) else VidGrabPalette.DarkSurface
    val elevated = if (amoled) Color(0xFF0C121D) else VidGrabPalette.DarkSurfaceHigh
    return darkColorScheme(
        primary = p,
        onPrimary = onColor(p),
        primaryContainer = p.copy(alpha = 0.20f).compositeOver(surface),
        onPrimaryContainer = p.copy(alpha = 0.92f).compositeOver(Color.White),
        secondary = a.partner,
        onSecondary = onColor(a.partner),
        secondaryContainer = a.partner.copy(alpha = 0.18f).compositeOver(surface),
        onSecondaryContainer = Color(0xFFD7F8F0),
        tertiary = VidGrabPalette.Green,
        onTertiary = Color(0xFF04231C),
        tertiaryContainer = VidGrabPalette.Green.copy(alpha = 0.18f).compositeOver(surface),
        onTertiaryContainer = VidGrabPalette.Green,
        background = bg,
        onBackground = VidGrabPalette.DarkText,
        surface = surface,
        onSurface = VidGrabPalette.DarkText,
        surfaceVariant = elevated,
        onSurfaceVariant = VidGrabPalette.DarkTextSecondary,
        surfaceContainerLowest = if (amoled) Color.Black else Color(0xFF0D1626),
        surfaceContainerLow = if (amoled) Color(0xFF080D15) else Color(0xFF0F1929),
        surfaceContainer = surface,
        surfaceContainerHigh = elevated,
        surfaceContainerHighest = if (amoled) Color(0xFF182233) else VidGrabPalette.DarkSurfaceHighest,
        outline = VidGrabPalette.DarkBorder,
        outlineVariant = Color(0xFF1C2A42),
        error = QtColors.Coral,
        onError = Color(0xFF2A0810),
        scrim = Color.Black,
    )
}

private fun lightScheme(a: AccentSpec): ColorScheme {
    val bg = VidGrabPalette.LightBackground
    val p = a.light
    return lightColorScheme(
        primary = p,
        onPrimary = onColor(p),
        primaryContainer = p.copy(alpha = 0.10f).compositeOver(Color.White),
        onPrimaryContainer = p.copy(alpha = 0.88f).compositeOver(VidGrabPalette.Navy),
        secondary = VidGrabPalette.Teal,
        onSecondary = Color.White,
        secondaryContainer = VidGrabPalette.Teal.copy(alpha = 0.12f).compositeOver(Color.White),
        onSecondaryContainer = Color(0xFF00584A),
        tertiary = Color(0xFF007A63),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFD6F6EE),
        onTertiaryContainer = Color(0xFF003D33),
        background = bg,
        onBackground = VidGrabPalette.LightText,
        surface = VidGrabPalette.LightSurface,
        onSurface = VidGrabPalette.LightText,
        surfaceVariant = VidGrabPalette.LightSurfaceAlt,
        onSurfaceVariant = VidGrabPalette.LightTextSecondary,
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(0xFFFBFCFE),
        surfaceContainer = Color.White,
        surfaceContainerHigh = Color(0xFFF1F5FB),
        surfaceContainerHighest = Color(0xFFE8EEF7),
        outline = Color(0xFFCBD5E1),
        outlineVariant = VidGrabPalette.LightBorder,
        error = Color(0xFFD92D4B),
        onError = Color.White,
    )
}

private val jakarta = FontFamily(
    Font(R.font.jakarta_400, FontWeight.Normal),
    Font(R.font.jakarta_500, FontWeight.Medium),
    Font(R.font.jakarta_600, FontWeight.SemiBold),
    Font(R.font.jakarta_700, FontWeight.Bold),
    Font(R.font.jakarta_800, FontWeight.ExtraBold),
)

private val typography = Typography().let { t ->
    fun TextStyle.j(size: Int, weight: FontWeight, line: Int, ls: Float) = copy(
        fontFamily = jakarta,
        fontSize = size.sp,
        lineHeight = line.sp,
        fontWeight = weight,
        letterSpacing = ls.sp,
    )
    Typography(
        displayLarge = t.displayLarge.j(54, FontWeight.ExtraBold, 60, -1.6f),
        displayMedium = t.displayMedium.j(44, FontWeight.ExtraBold, 50, -1.2f),
        displaySmall = t.displaySmall.j(36, FontWeight.Bold, 42, -0.8f),
        headlineLarge = t.headlineLarge.j(31, FontWeight.Bold, 38, -0.6f),
        headlineMedium = t.headlineMedium.j(27, FontWeight.Bold, 34, -0.5f),
        headlineSmall = t.headlineSmall.j(23, FontWeight.Bold, 30, -0.3f),
        titleLarge = t.titleLarge.j(20, FontWeight.Bold, 26, -0.2f),
        titleMedium = t.titleMedium.j(16, FontWeight.SemiBold, 22, 0f),
        titleSmall = t.titleSmall.j(14, FontWeight.SemiBold, 19, 0.1f),
        bodyLarge = t.bodyLarge.j(16, FontWeight.Normal, 24, 0.1f),
        bodyMedium = t.bodyMedium.j(14, FontWeight.Normal, 21, 0.1f),
        bodySmall = t.bodySmall.j(12, FontWeight.Normal, 17, 0.2f),
        labelLarge = t.labelLarge.j(14, FontWeight.SemiBold, 18, 0.1f),
        labelMedium = t.labelMedium.j(12, FontWeight.SemiBold, 16, 0.2f),
        labelSmall = t.labelSmall.j(11, FontWeight.Medium, 14, 0.3f),
    )
}

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Immutable
data class QtExtras(val isDark: Boolean, val brand: Brush, val brandStart: Color, val brandEnd: Color)

val LocalQtExtras = staticCompositionLocalOf { QtExtras(true, Brush.linearGradient(listOf(Color.Cyan, Color.Blue)), Color.Cyan, Color.Blue) }

val BrandTitle = TextStyle(fontFamily = jakarta, fontSize = 27.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.7).sp)

@Composable
fun VidGrabTheme(
    darkTheme: Boolean,
    accent: ThemeAccent = ThemeAccent.OCEAN,
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
        rippleAlpha = RippleAlpha(draggedAlpha = 0.16f, focusedAlpha = 0.12f, hoveredAlpha = 0.08f, pressedAlpha = 0.12f),
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
