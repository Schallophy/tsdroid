package dev.tsdroid.ui.theme

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Voice clients run at night, next to a game, on a dimmed screen: the dark
// palette is the primary one, and amber is reserved for "someone is talking".
//
// The dark scheme is a neutral near-black — no blue tint, so the base reads as
// ink rather than as a cold tech surface. It is built as five explicit surface
// steps rather than black plus one grey: at night a phone's panel crushes
// near-black levels together, so "surface" and "card" that differ by 4 % are
// indistinguishable — the hierarchy has to come from real steps. background is
// the page, surface the control bar, surfaceContainerLow a list card,
// surfaceContainer a settings/account card, surfaceContainerHigh a chat bubble
// and surfaceContainerHighest pressed/hover states. Outline contrast is raised
// for the same reason: a 2 % border is a smudge in the dark.
//
// The blue primary is kept as the app's accent: it is the existing character,
// and on a neutral near-black ground it reads as a clear, deliberate signal
// rather than as the blue-tinted base the user asked to move away from.
private val AccentBlue = Color(0xFF4C9EEB)
private val AccentBlueDeep = Color(0xFF1D4E78)
private val LiveAmber = Color(0xFFE8A33D)
private val AlertRed = Color(0xFFE5484D)

private val DarkScheme = darkColorScheme(
    primary = AccentBlue,
    onPrimary = Color(0xFF05131F),
    primaryContainer = AccentBlueDeep,
    onPrimaryContainer = Color(0xFFD8ECFF),
    secondary = LiveAmber,
    onSecondary = Color(0xFF20160A),
    secondaryContainer = Color(0xFF4A3315),
    onSecondaryContainer = Color(0xFFFFE2B8),
    background = Color(0xFF0D0D0F),
    onBackground = Color(0xFFE8E8EC),
    surface = Color(0xFF151518),
    onSurface = Color(0xFFE8E8EC),
    surfaceVariant = Color(0xFF1E1E22),
    onSurfaceVariant = Color(0xFFA0A0AA),
    surfaceContainerLowest = Color(0xFF0A0A0C),
    surfaceContainerLow = Color(0xFF121214),
    surfaceContainer = Color(0xFF19191D),
    surfaceContainerHigh = Color(0xFF212125),
    surfaceContainerHighest = Color(0xFF2A2A30),
    surfaceDim = Color(0xFF0D0D0F),
    surfaceBright = Color(0xFF33333A),
    inverseSurface = Color(0xFFE8E8EC),
    inverseOnSurface = Color(0xFF19191D),
    inversePrimary = Color(0xFF1F6FB2),
    outline = Color(0xFF484850),
    outlineVariant = Color(0xFF2B2B30),
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
    background = Color(0xFFF5F5F7),
    onBackground = Color(0xFF111113),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111113),
    surfaceVariant = Color(0xFFE8E8EC),
    onSurfaceVariant = Color(0xFF4E4E58),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F0F2),
    surfaceContainer = Color(0xFFE9E9EC),
    surfaceContainerHigh = Color(0xFFE2E2E6),
    surfaceContainerHighest = Color(0xFFD6D6DB),
    surfaceDim = Color(0xFFD9D9DE),
    surfaceBright = Color(0xFFFFFFFF),
    inverseSurface = Color(0xFF2B2B30),
    inverseOnSurface = Color(0xFFF0F0F2),
    inversePrimary = Color(0xFF9CCBF5),
    outline = Color(0xFFB7B7C2),
    outlineVariant = Color(0xFFDCDCE2),
    scrim = Color(0xCC000000),
    error = Color(0xFFC0322F),
    onError = Color(0xFFFFFFFF),
    // Needed by the connect-error banner; without them Material falls back to its
    // default pink container, which clashes with the rest of the palette.
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

// The weight hierarchy is the whole system: a heavier display weight for
// titles, a medium body, and lighter labels. Colour carries meaning, not
// weight — a label is lighter than a body not to look quiet but to sit under it.
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

/**
 * No press highlight, anywhere.
 *
 * A voice client is operated by feel, often while a game has focus, and a ripple
 * or state overlay flashing on every tap reads as the screen reacting to
 * something the user did not intend. Two locals are needed because Material
 * splits the job:
 *
 * * `LocalIndication` covers `Modifier.clickable` — the custom cards, the
 *   navigation bar, the member rows.
 * * `LocalRippleConfiguration` covers Material's own buttons, chips, tabs and
 *   icon buttons, which draw the ripple from the material3-ripple module
 *   instead of reading `LocalIndication`. `null` is the documented "no ripple"
 *   value.
 *
 * Buttons keep their enabled/disabled colours, and selected states (a tab, a
 * scope chip) still change colour — only the transient highlight under the
 * finger is gone.
 */
private object NoIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        object : Modifier.Node(), DrawModifierNode {
            override fun ContentDrawScope.draw() {
                drawContent()
            }
        }

    override fun hashCode(): Int = 0

    override fun equals(other: Any?): Boolean = other === this
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TSDroidTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalIndication provides NoIndication,
        LocalRippleConfiguration provides null,
    ) {
        MaterialTheme(
            colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
            typography = AppTypography,
            content = content,
        )
    }
}
