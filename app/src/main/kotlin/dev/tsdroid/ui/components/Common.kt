package dev.tsdroid.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
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

/** Segmented control used for the main tabs. */
@Composable
fun <T> PillTabs(
    items: List<T>,
    selected: T,
    label: (T) -> String,
    badge: (T) -> Int = { 0 },
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp),
    ) {
        for (item in items) {
            val active = item == selected
            val unread = badge(item)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(11.dp))
                    .background(
                        if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
                        else Color.Transparent,
                    )
                    .clickable { onSelect(item) }
                    .padding(vertical = 8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        text = label(item),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (unread > 0) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.error),
                        ) {
                            Text(
                                text = if (unread > 9) "9+" else unread.toString(),
                                color = MaterialTheme.colorScheme.onError,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Small dot used for online/away/talking states. */
@Composable
fun StatusDot(color: Color, size: Dp = 7.dp, modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(size).clip(CircleShape).background(color))
}

/**
 * Microphone glyph.
 *
 * Drawn by hand rather than pulled from `material-icons-extended`: the extended
 * set is ~11 000 classes for the two icons this app needs, and a hand-drawn one
 * also scales its stroke with the button.
 */
@Composable
fun MicGlyph(
    tint: Color,
    muted: Boolean,
    modifier: Modifier = Modifier,
    slashColor: Color = MaterialTheme.colorScheme.error,
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * 0.085f, cap = StrokeCap.Round)

        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.35f, h * 0.10f),
            size = Size(w * 0.30f, h * 0.44f),
            cornerRadius = CornerRadius(w * 0.15f, w * 0.15f),
        )
        drawArc(
            color = tint,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(w * 0.20f, h * 0.26f),
            size = Size(w * 0.60f, h * 0.46f),
            style = stroke,
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.5f, h * 0.70f),
            end = Offset(w * 0.5f, h * 0.86f),
            strokeWidth = w * 0.085f,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.33f, h * 0.88f),
            end = Offset(w * 0.67f, h * 0.88f),
            strokeWidth = w * 0.085f,
            cap = StrokeCap.Round,
        )
        if (muted) {
            drawLine(
                color = slashColor,
                start = Offset(w * 0.18f, h * 0.14f),
                end = Offset(w * 0.82f, h * 0.86f),
                strokeWidth = w * 0.095f,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** Speaker glyph, same reasoning as [MicGlyph]. */@Composable
fun SpeakerGlyph(
    tint: Color,
    muted: Boolean,
    modifier: Modifier = Modifier,
    slashColor: Color = MaterialTheme.colorScheme.error,
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * 0.085f, cap = StrokeCap.Round)

        val body = Path().apply {
            moveTo(w * 0.12f, h * 0.36f)
            lineTo(w * 0.28f, h * 0.36f)
            lineTo(w * 0.48f, h * 0.16f)
            lineTo(w * 0.48f, h * 0.84f)
            lineTo(w * 0.28f, h * 0.64f)
            lineTo(w * 0.12f, h * 0.64f)
            close()
        }
        drawPath(body, tint)

        if (muted) {
            drawLine(
                color = slashColor,
                start = Offset(w * 0.20f, h * 0.18f),
                end = Offset(w * 0.86f, h * 0.84f),
                strokeWidth = w * 0.095f,
                cap = StrokeCap.Round,
            )
        } else {
            drawArc(
                color = tint,
                startAngle = -55f,
                sweepAngle = 110f,
                useCenter = false,
                topLeft = Offset(w * 0.50f, h * 0.28f),
                size = Size(w * 0.28f, h * 0.44f),
                style = stroke,
            )
            drawArc(
                color = tint,
                startAngle = -55f,
                sweepAngle = 110f,
                useCenter = false,
                topLeft = Offset(w * 0.50f, h * 0.16f),
                size = Size(w * 0.42f, h * 0.68f),
                style = stroke,
            )
        }
    }
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
 * order: away, then deafened or a muted output device, then the mute button or
 * a muted input device. Away wins because someone who walked off is absent
 * whichever way their audio is set; the output side outranks the input side
 * because it is the rarer, more consequential one — an open mic that nobody
 * hears is worse than a mic that is simply off.
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
    if (away) {
        Image(
            painter = painterResource(R.drawable.ts_away),
            contentDescription = "离开",
            modifier = modifier.size(size),
        )
        return
    }

    if (deafened) {
        Image(
            painter = painterResource(R.drawable.ts_output_muted),
            contentDescription = "已关闭听筒",
            modifier = modifier.size(size),
        )
        return
    }

    if (!outputHardwareEnabled) {
        Image(
            painter = painterResource(R.drawable.ts_output_hardware_muted),
            contentDescription = "已关闭扬声器设备",
            modifier = modifier.size(size),
        )
        return
    }

    if (muted) {
        Image(
            painter = painterResource(R.drawable.ts_input_muted),
            contentDescription = "麦克风已静音",
            modifier = modifier.size(size),
        )
        return
    }

    if (!inputHardwareEnabled) {
        // Red prohibition sign over a grey microphone: TeamSpeak's own mark for
        // "this client's capture device is off", distinct from the X of a
        // server-side mute so the two are not confused at a glance.
        Image(
            painter = painterResource(R.drawable.ts_input_hardware_muted),
            contentDescription = "麦克风设备已关闭",
            modifier = modifier.size(size),
        )
        return
    }

    val live = talking
    val base = if (live) BallLive else BallIdle
    val highlight = if (live) BallLiveHighlight else BallIdleHighlight
    val shade = if (live) BallLiveShade else BallIdleShade

    Canvas(modifier = modifier.size(size)) {
        val d = this.size.width
        val center = Offset(d / 2f, d / 2f)
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(0f to highlight, 0.45f to base, 1f to shade),
                center = Offset(d * 0.36f, d * 0.30f),
                radius = d * 0.80f,
            ),
            radius = d / 2f,
            center = center,
        )
        // The single specular dot is what turns a flat disc into a marble.
        drawCircle(
            color = Color.White.copy(alpha = if (live) 0.40f else 0.16f),
            radius = d * 0.11f,
            center = Offset(d * 0.35f, d * 0.28f),
        )
    }
}

private val BallLive = Color(0xFF1A91D0)
private val BallLiveHighlight = Color(0xFFA6D4ED)
private val BallLiveShade = Color(0xFF0B5F91)
private val BallIdle = Color(0xFF002F5D)
private val BallIdleHighlight = Color(0xFF8CA1B6)
private val BallIdleShade = Color(0xFF001B33)

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
