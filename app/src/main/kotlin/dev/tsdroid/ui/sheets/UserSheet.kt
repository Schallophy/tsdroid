package dev.tsdroid.ui.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tsdroid.model.User
import dev.tsdroid.ui.components.MemberAvatar
import kotlin.math.roundToInt

/**
 * What you get when you tap somebody.
 *
 * Private message, poke, their details, and their playback volume. The volume
 * is local to this device and keyed by the person's UID, so it survives a
 * reconnect and a nickname change; it does not need a server permission.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserSheet(
    user: User,
    ownClientId: Int?,
    onDismiss: () -> Unit,
    onPrivate: () -> Unit,
    onPoke: (String) -> Unit,
    initialVolume: Float,
    onVolume: (Float) -> Unit,
    whispering: Boolean,
    onWhisper: (Boolean) -> Unit,
    onPermissions: () -> Unit,
    onChannelPermissions: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isSelf = user.id == ownClientId
    var pokeOpen by remember { mutableStateOf(false) }
    var pokeText by remember { mutableStateOf("") }
    var volume by remember(user.uid) { mutableStateOf(initialVolume) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 22.dp, end = 22.dp, bottom = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MemberAvatar(
                    name = user.nickname,
                    size = 52.dp,
                    talking = user.talking,
                    away = user.away,
                )
                Column(modifier = Modifier.padding(start = 14.dp)) {
                    Text(
                        text = user.nickname,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = when {
                            user.away -> user.awayMessage.ifEmpty { "离开" }
                            user.talking -> "正在说话"
                            else -> "在线"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            user.away -> MaterialTheme.colorScheme.secondary
                            user.talking -> MaterialTheme.colorScheme.secondary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            DetailRow("UID", user.uid.ifEmpty { "未知" }, mono = true)

            if (!isSelf) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("音量", style = MaterialTheme.typography.labelSmall)
                        Text(
                            "${(volume * 100).roundToInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Slider(
                        value = volume,
                        onValueChange = { volume = it },
                        onValueChangeFinished = { onVolume(volume) },
                        valueRange = 0f..2f,
                    )
                }
                OutlinedButton(onClick = { onWhisper(!whispering) }) {
                    Text(if (whispering) "停止悄悄话" else "悄悄话")
                }
                OutlinedButton(onClick = onPermissions) {
                    Text("权限")
                }
                OutlinedButton(onClick = onChannelPermissions) {
                    Text("频道内权限")
                }
            }

            if (user.description.isNotBlank()) {
                DetailRow("描述", user.description)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        // Same precedence as the badge in the member list and
                        // the self-view bar: the switched-off device first, the
                        // mute button second. Both mean "nothing is arriving";
                        // the device is the harder fact, and picking one order
                        // everywhere keeps the row, the badge and this sheet from
                        // answering with different words.
                        text = when {
                            !user.inputHardwareEnabled -> "麦克风设备关闭"
                            user.inputMuted -> "麦克风静音"
                            else -> "麦克风开启"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (user.inputMuted || !user.inputHardwareEnabled) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = when {
                            !user.outputHardwareEnabled -> "扬声器设备关闭"
                            user.outputMuted -> "不听声音"
                            else -> "可以听"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (!isSelf) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onPrivate, modifier = Modifier.weight(1f)) {
                        Text("私信")
                    }
                    OutlinedButton(
                        onClick = { pokeOpen = !pokeOpen },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("戳一戳")
                    }
                }
            }

            if (pokeOpen) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = pokeText,
                        onValueChange = { pokeText = it },
                        placeholder = { Text("留空就是「戳了你一下」", style = MaterialTheme.typography.bodySmall) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onPoke(pokeText) }) {
                        Text("发送")
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, mono: Boolean = false) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (mono) FontFamily.Monospace else null,
        )
    }
}
