package dev.tsdroid.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tsdroid.R
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A member's avatar: initials on a colour derived from the name, so the same
 * person keeps the same colour for the whole session without any server data.
 */
@Composable
fun MemberAvatar(
    name: String,
    size: Dp = 34.dp,
    talking: Boolean = false,
    away: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val palette = avatarPalette
    // floorMod: hashCode can be Int.MIN_VALUE, whose absoluteValue is still
    // negative and would index out of bounds.
    val color = palette[Math.floorMod(name.hashCode(), palette.size)]
    val ring by animateColorAsState(
        targetValue = if (talking) MaterialTheme.colorScheme.secondary else Color.Transparent,
        animationSpec = tween(220),
        label = "ring",
    )
    val ringWidth by animateFloatAsState(
        targetValue = if (talking) 2.2f else 1f,
        animationSpec = tween(220),
        label = "ringWidth",
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color.copy(alpha = if (away) 0.45f else 0.9f))
            .border(ringWidth.dp, ring, CircleShape),
    ) {
        Text(
            text = initials(name),
            color = Color.White,
            fontSize = (size.value * 0.38f).sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
    }
}

private fun initials(name: String): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return "?"
    // A CJK name is already one "letter" per character; one character reads
    // better than two at this size.
    return trimmed.take(if (trimmed.first().code > 0x2E80) 1 else 2).uppercase()
}

private val avatarPalette = listOf(
    Color(0xFF3E7CB1),
    Color(0xFF5B8C5A),
    Color(0xFF9C6B3F),
    Color(0xFF7A5FA8),
    Color(0xFFB05A6D),
    Color(0xFF3F8C8C),
    Color(0xFF8A7B3F),
)

/** Small dot used for online/away/talking states. */
@Composable
fun StatusDot(color: Color, size: Dp = 7.dp, modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(size).clip(CircleShape).background(color))
}

/**
 * The plate the TeamSpeak icons sit on.
 *
 * They are not tintable: the grey microphone, the blue bulb and the red cross are
 * baked into the path colours, and the pack was drawn for a dark client. Putting
 * them on `surfaceVariant` therefore works in the dark theme and washes them out
 * in the light one, so the plate is one fixed dark colour in both. It reads as a
 * badge rather than as part of the surface, which is what these are.
 *
 * The plate matches the neutral near-black surface steps so the icons read as
 * marks on the same ink the rest of the app is drawn on.
 */
val IconPlate = Color(0xFF161618)

/**
 * Which of TeamSpeak's input icons applies, or `null` when the microphone is
 * fine (the member list then draws the speaking ball).
 *
 * The order lives here once because three screens ask the same question: the
 * member badge, the user sheet and the self-view bar. They used to answer it in
 * two different orders, so the same person read as "muted" in the list and
 * "device off" in the sheet.
 *
 * The switched-off device outranks the mute button: the device is the harder
 * fact, the one that explains the silence even when the button is also down.
 * With both true, the row says "this client cannot transmit at all" rather than
 * "the button is down", because pressing the button would not bring the audio
 * back.
 */
fun inputStatusIcon(muted: Boolean, hardwareEnabled: Boolean): Int? = when {
    !hardwareEnabled -> R.drawable.icon_hardware_input_muted
    muted -> R.drawable.icon_input_muted
    else -> null
}

/** The output twin of [inputStatusIcon]; `null` means "you can hear them". */
fun outputStatusIcon(deafened: Boolean, hardwareEnabled: Boolean): Int? = when {
    !hardwareEnabled -> R.drawable.icon_hardware_output_muted
    deafened -> R.drawable.icon_output_muted
    else -> null
}

/**
 * The one mark a member row can show, or `null` when the speaking ball applies.
 *
 * The desktop client's status legend orders the marks: speakers/headphones
 * first, then the microphone, and a switched-off device before the mute
 * button. That means the output side always claims the slot when anything is
 * wrong with it — even a mute is more consequential than an input device that
 * another client switched off in its own settings.
 */
fun memberStatusIcon(
    away: Boolean,
    deafened: Boolean,
    muted: Boolean,
    inputHardwareEnabled: Boolean,
    outputHardwareEnabled: Boolean,
): Int? = when {
    away -> R.drawable.icon_away
    else -> outputStatusIcon(deafened = deafened, hardwareEnabled = outputHardwareEnabled)
        ?: inputStatusIcon(muted = muted, hardwareEnabled = inputHardwareEnabled)
}

/** The reader text that belongs to [memberStatusIcon]; `null` means "just a ball". */
fun memberStatusDescription(
    away: Boolean,
    deafened: Boolean,
    muted: Boolean,
    inputHardwareEnabled: Boolean,
    outputHardwareEnabled: Boolean,
): String? = when {
    away -> "离开"
    outputStatusIcon(deafened = deafened, hardwareEnabled = outputHardwareEnabled) != null ->
        if (!outputHardwareEnabled) "已关闭扬声器设备" else "已关闭听筒"
    inputStatusIcon(muted = muted, hardwareEnabled = inputHardwareEnabled) != null ->
        if (!inputHardwareEnabled) "麦克风设备已关闭" else "麦克风已静音"
    else -> null
}

/**
 * Disconnect glyph: a doorway with an arrow leaving it, copied stroke for
 * stroke from the logout icon TS6 Droid CN uses, so both clients read the same
 * picture for the same action.
 */
@Composable
fun ExitGlyph(tint: Color, modifier: Modifier = Modifier) {
    // Lucide's log-out, the three strokes as-is; the 24-unit grid scales to
    // whatever the header wants.
    val path = remember {
        Path().apply {
            for (segment in listOf(
                "M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4",
                "M16 17l5-5-5-5",
                "M21 12H9",
            )) {
                addPath(PathParser().parsePathString(segment).toPath())
            }
        }
    }
    Canvas(modifier) {
        val scale = size.minDimension / 24f
        withTransform({ scale(scale, scale, pivot = Offset.Zero) }) {
            drawPath(
                path,
                color = tint,
                style = Stroke(
                    width = 2f,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
        }
    }
}

// ── member status ───────────────────────────────────────────────────────────

/**
 * The slot on the left of a member row. It used to hold initials, which said
 * nothing useful; it now answers the only question the list is scanned for:
 * can I hear this person, and are they even here.
 *
 * Five of the states use TeamSpeak's own icons, converted straight from the
 * `dark` icon pack the desktop client is wearing — a hand-drawn approximation
 * would only look close. The remaining state is the player ball the client
 * draws before every name: bright while talking, dim otherwise.
 *
 * Everything shares one slot, so the states overwrite each other in a fixed
 * order: away, then the output side, then the input side. Away wins because
 * someone who walked off is absent whichever way their audio is set; the output
 * side outranks the input side because it is the rarer, more consequential one —
 * an open mic that nobody hears is worse than a mic that is simply off.
 *
 * A hardware mute uses a different icon from the mute button because it means
 * something different: the device is switched off in that client's own settings,
 * and the client is silent no matter what the server thinks. Drawing the open
 * microphone there was the bug this exists to fix — the row said "you can hear
 * this person" and nothing arrived.
 */
@Composable
fun MemberStatusBadge(
    away: Boolean,
    deafened: Boolean,
    muted: Boolean,
    talking: Boolean,
    inputHardwareEnabled: Boolean = true,
    outputHardwareEnabled: Boolean = true,
    size: Dp = 26.dp,
    modifier: Modifier = Modifier,
) {
    // One call decides the slot so the mark and its reader text cannot disagree:
    // away first, then output (device off before mute), then input (same order).
    val statusIcon = memberStatusIcon(
        away = away,
        deafened = deafened,
        muted = muted,
        inputHardwareEnabled = inputHardwareEnabled,
        outputHardwareEnabled = outputHardwareEnabled,
    )
    if (statusIcon != null) {
        Image(
            painter = painterResource(statusIcon),
            contentDescription = memberStatusDescription(
                away = away,
                deafened = deafened,
                muted = muted,
                inputHardwareEnabled = inputHardwareEnabled,
                outputHardwareEnabled = outputHardwareEnabled,
            ),
            modifier = modifier.size(size),
        )
        return
    }

    // The speaking ball is the pack's own `player_on` / `player_off`. The drawn
    // approximation that used to live here sampled those two SVGs for its
    // colours but could not reproduce their two vertical gradients and rim, so
    // it read as a flatter disc next to the TeamSpeak marks it sits among.
    Image(
        painter = painterResource(
            if (talking) R.drawable.icon_player_on else R.drawable.icon_player_off,
        ),
        contentDescription = if (talking) "正在说话" else null,
        modifier = modifier.size(size),
    )
}

// ── formatting ──────────────────────────────────────────────────────────────

private val clockFormat = DateTimeFormatter.ofPattern("HH:mm")
private val dayFormat = DateTimeFormatter.ofPattern("MM-dd HH:mm")

fun formatClock(timestamp: Long): String =
    Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalTime()
        .format(clockFormat)

fun formatStamp(timestamp: Long): String {
    val dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault())
    return if (dateTime.toLocalDate() == LocalDate.now()) {
        dateTime.toLocalTime().format(clockFormat)
    } else {
        dateTime.format(dayFormat)
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return if (value >= 10) {
        "${value.toInt()} ${units[index]}"
    } else {
        String.format(java.util.Locale.US, "%.1f %s", value, units[index])
    }
}
