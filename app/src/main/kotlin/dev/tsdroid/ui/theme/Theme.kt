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
//
// The dark scheme is built as five explicit surface steps rather than black plus
// one grey. At night a phone's panel crushes near-black levels together, so
// "surface" and "card" that differ by 4 % are indistinguishable — the hierarchy
// has to come from real steps. background is the page, surface the control bar,
// surfaceContainerLow a list card, surfaceContainer a settings/account card,
// surfaceContainerHigh a chat bubble and surfaceContainerHighest pressed/hover
// states. Outline contrast is raised for the same reason: a 2 % border is a
// smudge in the dark.
private val VoiceBlue = Color(0xFF4C9EEB)
private val VoiceBlueDeep = Color(0xFF1D4E78)
private val LiveAmber = Color(0xFFE8A33D)
private val AlertRed = Color(0xFFE5484D)

private val DarkScheme = darkColorScheme(
    primary = VoiceBlue,
    onPrimary = Color(0xFF05131F),
    primaryContainer = VoiceBlueDeep,
    onPrimaryContainer = Color(0xFFD8ECFF),
    secondary = LiveAmber,
    onSecondary = Color(0xFF20160A),
    secondaryContainer = Color(0xFF4A3315),
    onSecondaryContainer = Color(0xFFFFE2B8),
    background = Color(0xFF0E1217),
    onBackground = Color(0xFFE8EEF5),
    surface = Color(0xFF151B22),
    onSurface = Color(0xFFE8EEF5),
    surfaceVariant = Color(0xFF202A34),
    onSurfaceVariant = Color(0xFFA9B6C4),
    surfaceContainerLowest = Color(0xFF0A0D11),
    surfaceContainerLow = Color(0xFF131920),
    surfaceContainer = Color(0xFF1A222B),
    surfaceContainerHigh = Color(0xFF222C36),
    surfaceContainerHighest = Color(0xFF2B3642),
    surfaceDim = Color(0xFF0E1217),
    surfaceBright = Color(0xFF323D4A),
    inverseSurface = Color(0xFFE8EEF5),
    inverseOnSurface = Color(0xFF1A222B),
    inversePrimary = Color(0xFF1F6FB2),
    outline = Color(0xFF4C5968),
    outlineVariant = Color(0xFF2C3743),
    scrim = Color(0xDD000000),
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
    onSurfaceVariant = Color(0xFF4E5A68),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F3F8),
    surfaceContainer = Color(0xFFE9EEF5),
    surfaceContainerHigh = Color(0xFFE2E8F1),
    surfaceContainerHighest = Color(0xFFD6DEE9),
    surfaceDim = Color(0xFFD9E0E9),
    surfaceBright = Color(0xFFFFFFFF),
    inverseSurface = Color(0xFF2B3138),
    inverseOnSurface = Color(0xFFF0F4F8),
    inversePrimary = Color(0xFF9CCBF5),
    outline = Color(0xFFB7C2D0),
    outlineVariant = Color(0xFFDCE3EC),
    scrim = Color(0xCC000000),
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
