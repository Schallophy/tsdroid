package dev.tsdroid.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tsdroid.R
import dev.tsdroid.SessionStatus
import dev.tsdroid.SessionViewModel
import dev.tsdroid.model.Channel
import dev.tsdroid.model.PermissionTarget
import dev.tsdroid.model.User
import dev.tsdroid.ui.components.ExitGlyph
import dev.tsdroid.ui.components.IconPlate
import dev.tsdroid.ui.components.StatusDot
import dev.tsdroid.ui.components.inputStatusIcon
import dev.tsdroid.ui.components.outputStatusIcon
import dev.tsdroid.ui.sheets.ChannelSettingsSheet
import dev.tsdroid.ui.sheets.GroupTargetPickerSheet
import dev.tsdroid.ui.sheets.PermissionSheet
import dev.tsdroid.ui.sheets.ServerSettingsSheet
import dev.tsdroid.ui.sheets.UserSheet
import dev.tsdroid.ui.sheets.WhisperSheet

/**
 * The session content: the server header and the active tab's content.
 *
 * The tab selection and the microphone bar live outside this screen — the
 * bottom navigation replaces the old in-screen pill tabs, and the mic bar is
 * composed by the app shell above the bottom nav. What remains here is what
 * only the session itself owns: the server header and the three tab contents.
 */
@Composable
fun SessionScreen(
    vm: SessionViewModel,
    onLeave: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val permissions by vm.permissions.collectAsStateWithLifecycle()
    var sheetUser by remember { mutableStateOf<User?>(null) }
    var whisperOpen by remember { mutableStateOf(false) }
    var serverSettingsOpen by remember { mutableStateOf(false) }
    var editChannel by remember { mutableStateOf<Channel?>(null) }
    var permissionTarget by remember { mutableStateOf<PermissionTarget?>(null) }
    var groupPickerOpen by remember { mutableStateOf(false) }

    // The server never reports our own voice, so the core derives this from the
    // capture path (gain, silence gate and a tail). The local meter is only for
    // the level ring around the button.
    val ownTalking = ui.state.ownTalking

    Column(modifier = modifier.fillMaxSize()) {
        ServerHeader(
            title = ui.state.server.name.ifEmpty { ui.config?.address.orEmpty() },
            subtitle = statusLine(ui.status, ui.state.server.clientsOnline, ui.state.server.maxClients),
            connected = ui.status == SessionStatus.Online,
            onDisconnect = vm::disconnect,
            onWhisper = { whisperOpen = true },
            onServerSettings = { serverSettingsOpen = true },
        )

        Box(modifier = Modifier.weight(1f)) {
            when (ui.tab) {
                dev.tsdroid.SessionTab.Channels -> ChannelsTab(
                    ui = ui,
                    ownTalking = ownTalking,
                    onJoinChannel = vm::joinChannel,
                    onSelectUser = { sheetUser = it },
                    onEditChannel = { editChannel = it },
                )

                dev.tsdroid.SessionTab.Chat -> ChatTab(
                    ui = ui,
                    onScope = vm::selectScope,
                    onSend = vm::sendMessage,
                )

                dev.tsdroid.SessionTab.Files -> FilesTab(
                    ui = ui,
                    onRefresh = vm::refreshFiles,
                    onDownload = vm::downloadFile,
                    onUpload = { uri: android.net.Uri -> ui.filesChannelId?.let { vm.uploadFile(uri, it) } },
                )
            }

            // Overlay, not an inline row: a failure banner that pushes the list
            // down as it appears is exactly the kind of layout jump that reads
            // as broken. It floats over the top of the content instead.
            if (ui.status == SessionStatus.Failed) {
                Box(modifier = Modifier.align(Alignment.TopCenter)) {
                    ConnectErrorBanner(
                        message = ui.lastError?.takeIf { it.isNotBlank() }
                            ?: "请检查服务器地址和网络",
                        // A refused client version cannot be fixed by retrying:
                        // the same value would be announced again. The banner
                        // offers the setting instead of a button that cannot work.
                        versionRejected = ui.failureKind == "client_version",
                        onRetry = vm::reconnect,
                        onEdit = onLeave,
                        onOpenSettings = onOpenSettings,
                    )
                }
            }
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
            initialVolume = vm.userVolume(user),
            onVolume = { volume -> vm.setUserVolume(user, volume) },
            whispering = vm.isWhisperingTo(user),
            onWhisper = { enabled -> vm.setWhisperTo(user, enabled) },
            onPermissions = {
                permissionTarget = PermissionTarget(
                    kind = "client",
                    id = user.databaseId,
                    title = "客户端权限 · ${user.nickname}",
                )
                sheetUser = null
            },
            onChannelPermissions = {
                permissionTarget = PermissionTarget(
                    kind = "channel_client",
                    id = user.databaseId,
                    title = "频道内权限 · ${user.nickname}",
                    secondaryId = user.channelId,
                )
                sheetUser = null
            },
        )
    }

    if (whisperOpen) {
        WhisperSheet(
            ui = ui,
            onDismiss = { whisperOpen = false },
            onApply = { clients, channels, groups ->
                vm.setWhisperTargets(clients, channels, groups)
            },
        )
    }

    if (serverSettingsOpen) {
        ServerSettingsSheet(
            server = ui.state.server,
            onDismiss = { serverSettingsOpen = false },
            onGroupPermissions = {
                serverSettingsOpen = false
                groupPickerOpen = true
            },
            onApply = { name, welcome, maxClients, password, hostMessage, hostBanner, level ->
                vm.editServer(
                    name = name,
                    welcomeMessage = welcome,
                    maxClients = maxClients,
                    password = password,
                    hostMessage = hostMessage,
                    hostBannerUrl = hostBanner,
                    neededSecurityLevel = level,
                )
            },
        )
    }

    if (groupPickerOpen) {
        GroupTargetPickerSheet(
            ui = ui,
            onDismiss = { groupPickerOpen = false },
            onPick = { target ->
                groupPickerOpen = false
                permissionTarget = target
            },
        )
    }

    permissionTarget?.let { target ->
        PermissionSheet(
            target = target,
            state = permissions,
            onDismiss = { permissionTarget = null },
            onLoad = vm::loadPermissions,
            onAdd = { picked, name, value -> vm.addPermission(picked, name, value) },
            onDelete = { picked, name -> vm.deletePermission(picked, name) },
        )
    }

    editChannel?.let { channel ->
        ChannelSettingsSheet(
            channel = channel,
            onDismiss = { editChannel = null },
            onPermissions = {
                permissionTarget = PermissionTarget(
                    kind = "channel",
                    id = channel.id,
                    title = "频道权限 · ${channel.name}",
                )
                editChannel = null
            },
            onApply = { name, topic, maxClients, maxFamily, neededTalk, codec, quality, latency, deleteDelay, isDefault, isPermanent, isSemiPermanent, password ->
                vm.editChannel(
                    channelId = channel.id,
                    name = name,
                    topic = topic,
                    maxClients = maxClients,
                    maxFamilyClients = maxFamily,
                    neededTalkPower = neededTalk,
                    codec = codec,
                    codecQuality = quality,
                    codecLatencyFactor = latency,
                    deleteDelay = deleteDelay,
                    isDefault = isDefault,
                    isPermanent = isPermanent,
                    isSemiPermanent = isSemiPermanent,
                    password = password,
                )
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
private fun ConnectErrorBanner(
    message: String,
    versionRejected: Boolean,
    onRetry: () -> Unit,
    onEdit: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 6.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Row(
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.fillMaxWidth(),
        ) {
            androidx.compose.material3.TextButton(onClick = onEdit) { Text("修改设置") }
            if (versionRejected) {
                androidx.compose.material3.TextButton(onClick = onOpenSettings) { Text("换版本") }
            } else {
                androidx.compose.material3.TextButton(onClick = onRetry) { Text("重试") }
            }
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
    onDisconnect: () -> Unit,
    onWhisper: () -> Unit,
    onServerSettings: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 6.dp, top = 6.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
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
        IconButton(onClick = onServerSettings) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "服务器设置",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
        // Whisper is a first-class voice mode in the official client; the
        // target picker is one tap from the header rather than a settings page.
        IconButton(onClick = onWhisper) {
            Text(
                "密聊",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
 * already has a tab in the bottom nav, and a second entry point made the bar look
 * like a toolbar instead of a control people reach for without looking.
 *
 * Composed by the app shell above the bottom navigation bar, so it stays fixed
 * at the bottom of the content area and never scrolls away.
 */
@Composable
fun MicBar(
    ui: dev.tsdroid.SessionUiState,
    level: Float,
    onMic: () -> Unit,
    onSpeaker: () -> Unit,
) {
    // Two separate facts decide whether our voice leaves the phone: the mute
    // button (which this bar toggles) and the capture device that TeamSpeak
    // reports. The icon answers both, through the same helper the member list
    // uses, so the row cannot claim "open microphone" while nothing arrives.
    val deviceOn = ui.state.inputHardwareEnabled
    val speakerDeviceOn = ui.state.outputHardwareEnabled
    val micIcon = inputStatusIcon(muted = !ui.micEnabled, hardwareEnabled = deviceOn)
        ?: R.drawable.icon_capture
    val speakerIcon = outputStatusIcon(deafened = !ui.speakerEnabled, hardwareEnabled = speakerDeviceOn)
        ?: R.drawable.icon_volume

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            MicButton(
                icon = micIcon,
                active = ui.micEnabled && deviceOn,
                level = if (ui.micEnabled && deviceOn) level else 0f,
                contentDescription = when {
                    // Same order as the icon: the switched-off device is the
                    // harder fact, so it is what the reader must hear first.
                    !deviceOn -> "麦克风设备已关闭"
                    !ui.micEnabled -> "麦克风已静音"
                    else -> "麦克风已开启"
                },
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
                        // Same order as the icon and as [MemberStatusBadge]: the
                        // switched-off device leads, the mute button follows.
                        // Both are true answers to "why is nothing arriving";
                        // picking one order for both keeps the row from
                        // contradicting itself.
                        !deviceOn -> "麦克风设备已关闭"
                        !ui.micEnabled -> "麦克风已静音"
                        ui.state.away -> ui.state.awayMessage.ifEmpty { "离开" }
                        else -> "按下麦克风静音自己"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (!ui.micEnabled || !deviceOn) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            RoundGlyphButton(onClick = onSpeaker) {
                Image(
                    painter = painterResource(speakerIcon),
                    contentDescription = when {
                        !speakerDeviceOn -> "扬声器设备已关闭"
                        !ui.speakerEnabled -> "已关闭听筒"
                        else -> "可以听"
                    },
                    modifier = Modifier.size(26.dp),
                )
            }
        }
    }
}

@Composable
private fun MicButton(
    icon: Int,
    active: Boolean,
    level: Float,
    contentDescription: String,
    onClick: () -> Unit,
) {
    // `active` is "audio can leave the phone right now" — both the mute button
    // and the device flag — because that is what the glow means. The icon above
    // says which of the two stopped it.
    val halo = if (active) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.error
    }

    Box(contentAlignment = Alignment.Center) {
        // Two rings: a static halo, and the level, which grows with the voice.
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(halo.copy(alpha = 0.12f + level * 0.35f), CircleShape),
        )
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(IconPlate)
                .clickable(onClick = onClick),
        ) {
            // The icon carries its own colours from TeamSpeak's pack, so the
            // button is a plate rather than a tinted circle. That is what makes
            // "muted" and "device off" readable as different marks instead of
            // the same silhouette in two colours.
            Image(
                painter = painterResource(icon),
                contentDescription = contentDescription,
                modifier = Modifier.size(26.dp),
            )
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
            // Same plate as the mic button, so the two read as one pair of
            // controls rather than a button and a floating icon.
            //
            // No glow around it, unlike the microphone. The microphone's glow
            // exists because the level ring already lives there and has to say
            // "sound can leave" without a tintable fill; the speaker has no
            // level to show, and its three TeamSpeak marks (X, red dot, open
            // bulb) already differ from each other. A second red ring around
            // them only read as a badge the button never had.
            .background(IconPlate)
            .clickable(onClick = onClick),
    ) {
        content()
    }
}
