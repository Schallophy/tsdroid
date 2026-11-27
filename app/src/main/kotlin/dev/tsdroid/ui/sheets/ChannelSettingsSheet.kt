package dev.tsdroid.ui.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import dev.tsdroid.model.Channel
import kotlin.math.roundToInt

// TeamSpeak Server 3.11.0 migrated channels using the deprecated Speex/CELT
// codecs to Opus and removed the ability to select them; 3.13.9 only accepts
// Opus. Offering the dead values would be a button that the server refuses.
private val CODECS = listOf(
    "opus_voice" to "Opus Voice",
    "opus_music" to "Opus Music",
)

/**
 * The channel editor: the fields the official client exposes for one channel.
 *
 * Values the core already reports start filled in; an empty password means
 * "leave it alone" rather than "clear it", because the server never sends the
 * password back and an empty field cannot be told apart from no change.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelSettingsSheet(
    channel: Channel,
    onDismiss: () -> Unit,
    onPermissions: () -> Unit,
    onApply: (
        name: String,
        topic: String,
        maxClients: Int,
        maxFamilyClients: Int,
        neededTalkPower: Int,
        codec: String,
        codecQuality: Int,
        codecLatencyFactor: Int,
        deleteDelay: Long,
        isDefault: Boolean,
        isPermanent: Boolean,
        isSemiPermanent: Boolean,
        password: String,
    ) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember(channel.id) { mutableStateOf(channel.name) }
    var topic by remember(channel.id) { mutableStateOf(channel.topic) }
    var maxClients by remember(channel.id) {
        mutableStateOf(channel.maxClients.takeIf { it >= 0 }?.toString().orEmpty())
    }
    var maxFamily by remember(channel.id) {
        mutableStateOf(channel.maxFamilyClients.takeIf { it >= 0 }?.toString().orEmpty())
    }
    var neededTalk by remember(channel.id) {
        mutableStateOf(channel.neededTalkPower.toString())
    }
    var deleteDelay by remember(channel.id) { mutableStateOf(channel.deleteDelay.toString()) }
    var codec by remember(channel.id) { mutableStateOf(channel.codec.ifEmpty { "opus_voice" }) }
    var quality by remember(channel.id) { mutableStateOf(channel.codecQuality.toFloat()) }
    var latency by remember(channel.id) { mutableStateOf(channel.codecLatencyFactor.toFloat()) }
    var isDefault by remember(channel.id) { mutableStateOf(channel.isDefault) }
    var isPermanent by remember(channel.id) { mutableStateOf(channel.deleteDelay == 0L) }
    var isSemiPermanent by remember(channel.id) { mutableStateOf(false) }
    var password by remember(channel.id) { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 22.dp, end = 22.dp, bottom = 20.dp),
        ) {
            item { Text("频道设置", style = MaterialTheme.typography.titleLarge) }
            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = topic,
                    onValueChange = { topic = it },
                    label = { Text("主题") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码（留空不改）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(
                        label = "人数上限",
                        value = maxClients,
                        onChange = { maxClients = it },
                        modifier = Modifier.weight(1f),
                    )
                    NumberField(
                        label = "家族上限",
                        value = maxFamily,
                        onChange = { maxFamily = it },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(
                        label = "发言所需权限",
                        value = neededTalk,
                        onChange = { neededTalk = it },
                        modifier = Modifier.weight(1f),
                    )
                    NumberField(
                        label = "删除延迟（秒）",
                        value = deleteDelay,
                        onChange = { deleteDelay = it },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            item { Text("编码", style = MaterialTheme.typography.labelMedium) }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(CODECS) { (value, label) ->
                        FilterChip(
                            selected = codec == value,
                            onClick = { codec = value },
                            label = { Text(label) },
                        )
                    }
                }
            }
            item {
                Text("音质 ${quality.roundToInt()}", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = quality,
                    onValueChange = { quality = it },
                    valueRange = 0f..10f,
                    steps = 9,
                )
            }
            item {
                Text("延迟因子 ${latency.roundToInt()}", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = latency,
                    onValueChange = { latency = it },
                    valueRange = 1f..10f,
                    steps = 8,
                )
            }
            item {
                CheckRow("默认频道", isDefault) { isDefault = it }
            }
            item {
                CheckRow("永久频道", isPermanent) { isPermanent = it }
            }
            item {
                CheckRow("半永久频道", isSemiPermanent) { isSemiPermanent = it }
            }
            item {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TextButton(onClick = onPermissions) { Text("权限") }
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Button(onClick = {
                        onApply(
                            name,
                            topic,
                            maxClients.toIntOrNull() ?: -1,
                            maxFamily.toIntOrNull() ?: -1,
                            neededTalk.toIntOrNull() ?: 0,
                            codec,
                            quality.roundToInt(),
                            latency.roundToInt(),
                            deleteDelay.toLongOrNull() ?: 0L,
                            isDefault,
                            isPermanent,
                            isSemiPermanent,
                            password,
                        )
                        onDismiss()
                    }) { Text("应用") }
                }
            }
        }
    }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { text ->
            if (text.all { it.isDigit() } || text.isEmpty()) onChange(text)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
