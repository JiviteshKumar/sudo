package com.technewz.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.technewz.app.R
import com.technewz.app.data.ThemeMode

// ---------- Type ----------
val Display = FontFamily(
    Font(R.font.space_grotesk_regular, FontWeight.Normal),
    Font(R.font.space_grotesk_medium, FontWeight.Medium),
    Font(R.font.space_grotesk_bold, FontWeight.SemiBold),
    Font(R.font.space_grotesk_bold, FontWeight.Bold),
)
val Body = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

private val AppTypography = Typography(
    displayLarge = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 44.sp, lineHeight = 48.sp, letterSpacing = (-1.2).sp),
    displayMedium = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 40.sp, letterSpacing = (-1).sp),
    displaySmall = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 34.sp, letterSpacing = (-0.8).sp),
    headlineLarge = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 32.sp, letterSpacing = (-0.6).sp),
    headlineMedium = TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 28.sp, letterSpacing = (-0.4).sp),
    headlineSmall = TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 26.sp, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 24.sp, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = (-0.1).sp),
    titleSmall = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Body, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Body, fontWeight = FontWeight.Normal, fontSize = 14.5.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = Body, fontWeight = FontWeight.Normal, fontSize = 12.5.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp),
    labelMedium = TextStyle(fontFamily = Body, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.1.sp),
    labelSmall = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp, lineHeight = 14.sp, letterSpacing = 0.4.sp),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

// ---------- Color ----------
private val Light = lightColorScheme(
    primary = Color(0xFF4F46E5), onPrimary = Color.White,
    primaryContainer = Color(0xFFE6E4FF), onPrimaryContainer = Color(0xFF1E1B6B),
    secondary = Color(0xFF0891B2), onSecondary = Color.White,
    secondaryContainer = Color(0xFFD6F4FA), onSecondaryContainer = Color(0xFF053544),
    tertiary = Color(0xFFC026D3), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFBE2FF), onTertiaryContainer = Color(0xFF4A0552),
    background = Color(0xFFF6F7FB), onBackground = Color(0xFF0E1220),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF0E1220),
    surfaceVariant = Color(0xFFEEF0F6), onSurfaceVariant = Color(0xFF5B6275),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF9FAFD),
    surfaceContainer = Color(0xFFF1F3F8), surfaceContainerHigh = Color(0xFFEBEDF4), surfaceContainerHighest = Color(0xFFE4E7EF),
    outline = Color(0xFFCDD2DE), outlineVariant = Color(0xFFE3E6EE),
    error = Color(0xFFDC2626), errorContainer = Color(0xFFFEE2E2), onErrorContainer = Color(0xFF7F1D1D),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9D98FF), onPrimary = Color(0xFF15124A),
    primaryContainer = Color(0xFF2B2774), onPrimaryContainer = Color(0xFFE2E0FF),
    secondary = Color(0xFF4FD8F0), onSecondary = Color(0xFF00333F),
    secondaryContainer = Color(0xFF0B3A47), onSecondaryContainer = Color(0xFFCDF5FD),
    tertiary = Color(0xFFF0A3FA), onTertiary = Color(0xFF4A0552),
    tertiaryContainer = Color(0xFF5A1665), onTertiaryContainer = Color(0xFFFBE2FF),
    background = Color(0xFF0A0B10), onBackground = Color(0xFFECEEF5),
    surface = Color(0xFF12141C), onSurface = Color(0xFFECEEF5),
    surfaceVariant = Color(0xFF1B1E29), onSurfaceVariant = Color(0xFF9AA1B5),
    surfaceContainerLowest = Color(0xFF0D0F15), surfaceContainerLow = Color(0xFF12141C),
    surfaceContainer = Color(0xFF171A24), surfaceContainerHigh = Color(0xFF1D2030), surfaceContainerHighest = Color(0xFF242838),
    outline = Color(0xFF3A3F52), outlineVariant = Color(0xFF242836),
    error = Color(0xFFF87171), errorContainer = Color(0xFF4C1515), onErrorContainer = Color(0xFFFECACA),
)

@Immutable
data class Accent(val start: Color, val end: Color) {
    val brush: Brush get() = Brush.linearGradient(listOf(start, end))
    val horizontal: Brush get() = Brush.horizontalGradient(listOf(start, end))
}

object Accents {
    val tech = Accent(Color(0xFF6366F1), Color(0xFF06B6D4))
    val ai = Accent(Color(0xFFA855F7), Color(0xFFEC4899))
    val jobs = Accent(Color(0xFF10B981), Color(0xFF84CC16))
    val tracker = Accent(Color(0xFFF59E0B), Color(0xFFEF4444))
    val saved = Accent(Color(0xFF0EA5E9), Color(0xFF6366F1))

    private val avatarPalette = listOf(tech, ai, jobs, tracker, saved, Accent(Color(0xFF14B8A6), Color(0xFF3B82F6)), Accent(Color(0xFFF43F5E), Color(0xFFF97316)))
    fun forName(name: String) = avatarPalette[(name.hashCode() and 0x7fffffff) % avatarPalette.size]
}

@Immutable
data class ExtraColors(
    val success: Color,
    val successContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val cardBorder: Color,
    val scrim: Color,
    val isDark: Boolean,
)

val LocalExtra = staticCompositionLocalOf {
    ExtraColors(Color(0xFF059669), Color(0xFFD1FAE5), Color(0xFFD97706), Color(0xFFFEF3C7), Color(0x14000000), Color(0x99000000), false)
}

@Composable
fun TechNewzTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val extra = if (dark) ExtraColors(
        success = Color(0xFF34D399), successContainer = Color(0xFF0B3B2C),
        warning = Color(0xFFFBBF24), warningContainer = Color(0xFF3F2E07),
        cardBorder = Color(0x1AFFFFFF), scrim = Color(0xCC000000), isDark = true,
    ) else ExtraColors(
        success = Color(0xFF059669), successContainer = Color(0xFFD1FAE5),
        warning = Color(0xFFB45309), warningContainer = Color(0xFFFEF3C7),
        cardBorder = Color(0x0F0E1220), scrim = Color(0x99000000), isDark = false,
    )
    val scheme: ColorScheme = if (dark) Dark else Light
    CompositionLocalProvider(LocalExtra provides extra) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes) {
            // Default text/icon colour for custom surfaces that are not Material Surfaces.
            CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides scheme.onBackground, content = content)
        }
    }
}
