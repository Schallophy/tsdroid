package dev.tsdroid.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Voice clients run at night, next to a game, on a dimmed screen: the dark
// palette is the primary one, and amber is reserved for "someone is talking".
private val VoiceBlue = Color(0xFF4C9EEB)
private val VoiceBlueDeep = Color(0xFF2A6FA8)
private val LiveAmber = Color(0xFFE8A33D)
private val AlertRed = Color(0xFFE5484D)

private val DarkScheme = darkColorScheme(
    primary = VoiceBlue,
    onPrimary = Color(0xFF04121E),
    primaryContainer = VoiceBlueDeep,
    onPrimaryContainer = Color(0xFFDCEEFF),
    secondary = LiveAmber,
    onSecondary = Color(0xFF20160A),
    secondaryContainer = Color(0xFF4A3315),
    onSecondaryContainer = Color(0xFFFFE2B8),
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF161B22),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF1E252E),
    onSurfaceVariant = Color(0xFF9AA6B4),
    surfaceContainer = Color(0xFF1A212A),
    surfaceContainerHigh = Color(0xFF222B35),
    outline = Color(0xFF3A4552),
    outlineVariant = Color(0xFF262F3A),
    error = AlertRed,
    onError = Color(0xFF1A0608),
    errorContainer = Color(0xFF4C1A1D),
    onErrorContainer = Color(0xFFFFD9DA),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF1F6FB2),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD7E8F8),
    onPrimaryContainer = Color(0xFF0A2C46),
    secondary = Color(0xFFB0741B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFE6C4),
    onSecondaryContainer = Color(0xFF3D2600),
    background = Color(0xFFF4F6FA),
    onBackground = Color(0xFF11161C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF11161C),
    surfaceVariant = Color(0xFFE7ECF3),
    onSurfaceVariant = Color(0xFF5A6675),
    surfaceContainer = Color(0xFFEDF1F7),
    surfaceContainerHigh = Color(0xFFE2E8F1),
    outline = Color(0xFFC3CCD8),
    outlineVariant = Color(0xFFDCE3EC),
    error = Color(0xFFC0322F),
    onError = Color(0xFFFFFFFF),
    // Needed by the connect-error banner; without them Material falls back to its
    // default pink container, which clashes with the rest of the palette.
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 15.sp,
        lineHeight = 21.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 19.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        letterSpacing = 0.2.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.4.sp,
    ),
)

@Composable
fun TSDroidTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        typography = AppTypography,
        content = content,
    )
}
