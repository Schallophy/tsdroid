package dev.tsdroid.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tsdroid.SessionStatus
import dev.tsdroid.SessionTab
import dev.tsdroid.SessionViewModel
import dev.tsdroid.model.ChatScope
import dev.tsdroid.model.User
import dev.tsdroid.ui.components.MicGlyph
import dev.tsdroid.ui.components.ExitGlyph
import dev.tsdroid.ui.components.PillTabs
import dev.tsdroid.ui.components.SpeakerGlyph
import dev.tsdroid.ui.components.StatusDot
import dev.tsdroid.ui.sheets.UserSheet

/**
 * Peak level that counts as speech. Room noise on a phone mic sits well below
 * this; normal talking peaks around 0.2-0.5.
 */
private const val OwnTalkThreshold = 0.05f

/**
 * The client proper: one screen, three tabs, and a control bar that never moves.
 *
 * The mic bar is fixed because it is the thing people reach for without looking;
 * everything else is allowed to scroll under it.
 */
@Composable
fun SessionScreen(
    vm: SessionViewModel,
    onOpenSettings: () -> Unit,
    onLeave: () -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val micLevel by vm.micLevel.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var sheetUser by remember { mutableStateOf<User?>(null) }

    // Our own voice never travels through the receive path, so the native
    // talking list only ever names other people. The capture meter is the only
    // signal for ourselves — and it is meaningless while muted.
    val ownTalking = !ui.state.inputMuted && micLevel > OwnTalkThreshold

    LaunchedEffect(ui.toast) {
        val message = ui.toast ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        vm.clearToast()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            ServerHeader(
                title = ui.state.server.name.ifEmpty { ui.config?.address.orEmpty() },
                subtitle = statusLine(ui.status, ui.state.server.clientsOnline, ui.state.server.maxClients),
                connected = ui.status == SessionStatus.Online,
                onSettings = onOpenSettings,
                onDisconnect = vm::disconnect,
                onLeave = onLeave,
            )

            if (ui.status == SessionStatus.Failed) {
                ConnectErrorBanner(
                    message = ui.lastError ?: "连接失败",
                    onRetry = vm::reconnect,
                    onEdit = onLeave,
                )
            }

            PillTabs(
                items = SessionTab.entries,
                selected = ui.tab,
                label = {
                    when (it) {
                        SessionTab.Channels -> "频道"
                        SessionTab.Chat -> "聊天"
                        SessionTab.Files -> "文件"
                    }
                },
                badge = {
                    when (it) {
                        SessionTab.Chat -> ui.unreadTotal
                        else -> 0
                    }
                },
                onSelect = vm::selectTab,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )

            Box(modifier = Modifier.weight(1f)) {
                when (ui.tab) {
                    SessionTab.Channels -> ChannelsTab(
                        ui = ui,
                        ownTalking = ownTalking,
                        onJoinChannel = vm::joinChannel,
                        onSelectUser = { sheetUser = it },
                    )

                    SessionTab.Chat -> ChatTab(
                        ui = ui,
                        onScope = vm::selectScope,
                        onSend = vm::sendMessage,
                    )

                    SessionTab.Files -> FilesTab(
                        ui = ui,
                        onRefresh = vm::refreshFiles,
                        onDownload = vm::downloadFile,
                        onUpload = { uri: Uri -> ui.filesChannelId?.let { vm.uploadFile(uri, it) } },
                    )
                }
            }

            MicBar(
                ui = ui,
                level = micLevel,
                onMic = vm::toggleMic,
                onSpeaker = vm::toggleSpeaker,
            )
        }
    }

    sheetUser?.let { user ->
        UserSheet(
            user = user,
            ownClientId = ui.state.ownClientId,
            onDismiss = { sheetUser = null },
            onPrivate = {
                vm.openPrivateChat(user)
                sheetUser = null
            },
            onPoke = { message ->
                vm.poke(user, message)
                sheetUser = null
            },
        )
    }
}

/**
 * Why the connection failed, in full, right where the user is looking.
 *
 * The header only has room for "连接失败", but the interesting part is *why*:
 * a wrong server password, an unreachable host and a refused identity all look
 * identical from the outside, and guessing means editing settings blindly.
 */
@Composable
private fun ConnectErrorBanner(message: String, onRetry: () -> Unit, onEdit: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 6.dp),
    ) {
        Text(
            text = "无法连接",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.fillMaxWidth(),
        ) {
            TextButton(onClick = onEdit) { Text("修改设置") }
            TextButton(onClick = onRetry) { Text("重试") }
        }
    }
}

private fun statusLine(status: SessionStatus, online: Int, max: Int): String = when (status) {
    SessionStatus.Connecting -> "连接中…"
    SessionStatus.Online -> if (max > 0) "$online / $max 在线" else "$online 在线"
    SessionStatus.Disconnected -> "已断开"
    SessionStatus.Failed -> "连接失败"
    SessionStatus.Idle -> ""
}

@Composable
private fun ServerHeader(
    title: String,
    subtitle: String,
    connected: Boolean,
    onSettings: () -> Unit,
    onDisconnect: () -> Unit,
    onLeave: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 6.dp, top = 6.dp),
    ) {
        IconButton(onClick = onLeave, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatusDot(
                    color = if (connected) Color(0xFF3FB950) else MaterialTheme.colorScheme.error,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onSettings) {
            Icon(
                Icons.Default.Settings,
                contentDescription = "设置",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // The desktop client buries disconnect in a menu; on a phone that is
        // one tap too many for the action you reach for when things go wrong.
        // One tap, straight out — same as TS6 Droid CN.
        IconButton(onClick = onDisconnect) {
            ExitGlyph(
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/**
 * Mic, identity, speaker.
 *
 * The level ring is drawn from the capture peak rather than from server audio, so
 * it responds even when nobody else is talking. Nothing else lives here: settings
 * already has a button in the header, and a second entry point made the bar look
 * like a toolbar instead of a control people reach for without looking.
 */
@Composable
private fun MicBar(
    ui: dev.tsdroid.SessionUiState,
    level: Float,
    onMic: () -> Unit,
    onSpeaker: () -> Unit,
) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            MicButton(
                enabled = ui.micEnabled,
                level = if (ui.micEnabled) level else 0f,
                onClick = onMic,
            )

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = ui.state.nickname.ifEmpty { ui.config?.nickname.orEmpty() },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when {
                        !ui.micEnabled -> "麦克风已静音"
                        ui.state.away -> ui.state.awayMessage.ifEmpty { "离开" }
                        else -> "按下麦克风静音自己"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (!ui.micEnabled) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            RoundGlyphButton(onClick = onSpeaker) {
                SpeakerGlyph(
                    tint = if (ui.speakerEnabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    muted = !ui.speakerEnabled,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun MicButton(enabled: Boolean, level: Float, onClick: () -> Unit) {
    val container = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val tint = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.error

    Box(contentAlignment = Alignment.Center) {
        // Two rings: a static halo, and the level, which grows with the voice.
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(container.copy(alpha = 0.12f + level * 0.35f), CircleShape),
        )
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(container)
                .clickable(onClick = onClick),
        ) {
            MicGlyph(tint = tint, muted = !enabled, modifier = Modifier.size(22.dp))
        }
    }
    Spacer(Modifier.width(2.dp))
}

@Composable
private fun RoundGlyphButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        content()
    }
}
