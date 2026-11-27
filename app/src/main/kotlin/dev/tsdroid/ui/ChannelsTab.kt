package dev.tsdroid.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tsdroid.SessionUiState
import dev.tsdroid.R
import dev.tsdroid.model.Channel
import dev.tsdroid.model.GroupEntry
import dev.tsdroid.model.User
import dev.tsdroid.ui.components.MemberStatusBadge

/**
 * The channel tree, with the people inside each channel.
 *
 * This is the screen TS users live on: who is where, who is muted, who is
 * talking. Rows are dense on purpose — a 30-channel server should fit without
 * endless scrolling.
 */
@Composable
fun ChannelsTab(
    ui: SessionUiState,
    onJoinChannel: (Long, String?) -> Unit,
    onSelectUser: (User) -> Unit,
    onEditChannel: (Channel) -> Unit,
    /** Whether the local user is transmitting; the native list never has us. */
    ownTalking: Boolean = false,
) {
    val collapsed = remember { mutableStateMapOf<Long, Boolean>() }
    var passwordTarget by remember { mutableStateOf<Channel?>(null) }
    var password by remember { mutableStateOf("") }

    val tree = ui.state.channelTree()
    val visible = remember(tree, collapsed.toMap()) {
        val result = mutableListOf<dev.tsdroid.model.ChannelNode>()
        var skipBelowDepth = -1
        for (node in tree) {
            if (skipBelowDepth >= 0 && node.depth > skipBelowDepth) continue
            skipBelowDepth = -1
            result += node
            if (collapsed[node.channel.id] == true) skipBelowDepth = node.depth
        }
        result
    }

    if (visible.isEmpty()) {
        EmptyState(
            title = "还没有频道",
            hint = "服务器信息还没到，稍等一下",
        )
        return
    }

    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        items(visible, key = { it.channel.id }) { node ->
            val channel = node.channel
            val members = ui.state.usersIn(channel.id)
            val hasChildren = ui.state.channels.any { it.parentId == channel.id }
            val expanded = collapsed[channel.id] != true
            val current = channel.id == ui.state.ownChannelId

            ChannelRow(
                channel = channel,
                depth = node.depth,
                memberCount = members.size,
                current = current,
                hasChildren = hasChildren,
                expanded = expanded,
                whispering = channel.id in ui.state.whisperChannels,
                onEdit = { onEditChannel(channel) },
                onToggle = { collapsed[channel.id] = expanded },
                onJoin = {
                    if (channel.hasPassword) {
                        passwordTarget = channel
                        password = ""
                    } else {
                        onJoinChannel(channel.id, null)
                    }
                },
            )

            members.forEach { member ->
                MemberRow(
                    member = member,
                    depth = node.depth,
                    talking = member.talking ||
                        (ownTalking && member.id == ui.state.ownClientId),
                    // A poke now lands in the private conversation, which is only
                    // reachable through this member's sheet — so the list has to
                    // say that something is waiting there.
                    unread = ui.unreadPrivate[member.id] ?: 0,
                    titles = ui.state.titlesFor(member),
                    whispering = member.id in ui.state.whisperClients,
                    onClick = { onSelectUser(member) },
                )
            }
        }
    }

    passwordTarget?.let { channel ->
        AlertDialog(
            onDismissRequest = { passwordTarget = null },
            title = { Text("进入「${channel.name}」") },
            text = {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    label = { Text("频道密码") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onJoinChannel(channel.id, password)
                        passwordTarget = null
                    },
                ) { Text("进入") }
            },
            dismissButton = {
                TextButton(onClick = { passwordTarget = null }) { Text("取消") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelRow(
    channel: Channel,
    depth: Int,
    memberCount: Int,
    current: Boolean,
    hasChildren: Boolean,
    expanded: Boolean,
    whispering: Boolean,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
    onJoin: () -> Unit,
) {
    val background = if (current) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    } else {
        MaterialTheme.colorScheme.background
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .background(background)
            .combinedClickable(onClick = onJoin, onLongClick = onEdit)
            .padding(start = (10 + depth * 14).dp, end = 12.dp),
    ) {
        // A left bar marks the channel you are actually in; colour alone would
        // disappear against the highlight.
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(18.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(
                    if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.background,
                ),
        )
        Spacer(6.dp)
        Box(modifier = Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            if (hasChildren) {
                IconButton(onClick = onToggle, modifier = Modifier.size(22.dp)) {
                    Icon(
                        imageVector = if (expanded) {
                            Icons.Default.KeyboardArrowDown
                        } else {
                            Icons.AutoMirrored.Filled.KeyboardArrowRight
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        if (channel.hasPassword) {
            Icon(
                Icons.Default.Lock,
                contentDescription = "有密码",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(12.dp).padding(end = 0.dp),
            )
            Spacer(4.dp)
        }
        Text(
            text = channel.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
            color = if (current) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (whispering) {
            WhisperMark()
            Spacer(6.dp)
        }
        if (memberCount > 0) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                Text(
                    text = memberCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The official client draws a whisper mark beside a person or channel. */
@Composable
private fun WhisperMark() {
    Text(
        text = "密",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 6.dp),
    )
}

@Composable
private fun MemberRow(
    member: User,
    depth: Int,
    talking: Boolean,
    unread: Int,
    /** Group titles to draw after the name; empty when the server named none. */
    titles: List<GroupEntry>,
    whispering: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clickable(onClick = onClick)
            .padding(start = (36 + depth * 14).dp, end = 12.dp),
    ) {
        MemberStatusBadge(
            away = member.away,
            deafened = member.outputMuted,
            muted = member.inputMuted,
            talking = talking,
            inputHardwareEnabled = member.inputHardwareEnabled,
            outputHardwareEnabled = member.outputHardwareEnabled,
            size = 26.dp,
        )
        Spacer(9.dp)
        Text(
            text = member.nickname,
            style = MaterialTheme.typography.bodyMedium,
            // Speaking is told by the ball alone, the way the desktop client
            // does it. Colouring the name as well made the row shout twice.
            color = if (member.away) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (whispering) {
            WhisperMark()
        }
        // Titles sit after the name and before the unread badge, in the order
        // the desktop client uses: channel group, then server groups. Each is
        // drawn only when it exists, so a member with neither gets no gap.
        for (title in titles) {
            Spacer(6.dp)
            GroupTag(title)
        }
        if (unread > 0) {
            Spacer(6.dp)
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

/** One group title, drawn small and quiet so a row of them stays readable. */
@Composable
private fun GroupTag(group: GroupEntry) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        // The icon pack carries the five group marks a stock server uses, keyed
        // by the same `iconid` the server reports. A group with no icon, or one
        // whose id this build has no picture for, is drawn as its name alone.
        groupIcon(group.iconId)?.let { icon ->
            Image(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(12.dp),
            )
            Spacer(3.dp)
        }
        Text(
            text = group.name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The bundled picture for a server's group icon id, or null when there is none.
 *
 * These are the ids a stock server assigns: `100` Channel Admin, `200` Operator,
 * `300` Server Admin, `500` Admin Server Query, `600` Voice. A server may set any
 * id it likes on its own groups, and those are fetched as server files rather
 * than bundled, so an unknown id is ordinary and must not be an error.
 */
private fun groupIcon(iconId: Long): Int? = when (iconId) {
    100L -> R.drawable.icon_group_100
    200L -> R.drawable.icon_group_200
    300L -> R.drawable.icon_group_300
    500L -> R.drawable.icon_group_500
    600L -> R.drawable.icon_group_600
    else -> null
}

@Composable
fun Spacer(width: androidx.compose.ui.unit.Dp) {
    Box(modifier = Modifier.width(width))
}

@Composable
fun EmptyState(title: String, hint: String) {
    Column(
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(32.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
