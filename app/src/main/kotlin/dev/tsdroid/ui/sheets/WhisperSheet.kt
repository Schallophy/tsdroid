package dev.tsdroid.ui.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.unit.dp
import dev.tsdroid.SessionUiState

/**
 * The whisper target picker: people and channels.
 *
 * The core replaces the whole set, so this sheet starts from the snapshot and
 * applies both lists together. Server/channel group targets are not in this
 * sheet yet; the core already carries them in `set_whisper_targets`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhisperSheet(
    ui: SessionUiState,
    onDismiss: () -> Unit,
    onApply: (clients: List<Int>, channels: List<Long>) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var clients by remember { mutableStateOf(ui.state.whisperClients.toSet()) }
    var channels by remember { mutableStateOf(ui.state.whisperChannels.toSet()) }

    val people = ui.state.users.filter { it.clientType == 0 && it.id != ui.state.ownClientId }
    val channelNames = ui.state.channels.associate { it.id to it.name }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 22.dp, end = 22.dp, bottom = 20.dp),
        ) {
            Text("悄悄话", style = MaterialTheme.typography.titleLarge)
            Text(
                "选中的人会听到你说的话，而不是频道里的人。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                if (people.isNotEmpty()) {
                    item { SectionTitle("成员") }
                    items(people, key = { "u${it.id}" }) { user ->
                        CheckRow(
                            label = user.nickname,
                            checked = user.id in clients,
                            onChange = { checked ->
                                clients = if (checked) clients + user.id else clients - user.id
                            },
                        )
                    }
                }
                item { SectionTitle("频道") }
                items(ui.state.channels, key = { "c${it.id}" }) { channel ->
                    CheckRow(
                        label = channelNames[channel.id] ?: channel.name,
                        checked = channel.id in channels,
                        onChange = { checked ->
                            channels = if (checked) channels + channel.id else channels - channel.id
                        },
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(onClick = {
                    clients = emptySet()
                    channels = emptySet()
                }) {
                    Text("清除")
                }
                Button(onClick = {
                    onApply(clients.toList(), channels.toList())
                    onDismiss()
                }) {
                    Text("应用")
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun CheckRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
