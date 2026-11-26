package dev.tsdroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tsdroid.SessionUiState
import dev.tsdroid.model.ChatMessage
import dev.tsdroid.model.ChatScope
import dev.tsdroid.ui.components.MemberAvatar
import dev.tsdroid.ui.components.formatClock

/**
 * Chat: three scopes in one list, chosen at the top.
 *
 * Own messages are the only right-aligned ones, which is what makes a busy
 * channel readable at a glance. Pokes appear inline as an amber system line —
 * they are transient, and hiding them in a toast means missing them entirely.
 */
@Composable
fun ChatTab(
    ui: SessionUiState,
    onScope: (ChatScope) -> Unit,
    onSend: (String) -> Unit,
) {
    val peer = ui.peer
    val canSend = ui.isOnline && (ui.scope != ChatScope.Private || peer != null)

    val visible = remember(ui.messages, ui.scope, peer?.id) {
        ui.messages.filter { message ->
            when (ui.scope) {
                ChatScope.Channel -> message.scope == ChatScope.Channel
                ChatScope.Server -> message.scope == ChatScope.Server
                ChatScope.Private -> message.scope == ChatScope.Private && message.peerId == peer?.id
            }
        }
    }

    var draft by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(visible.size) {
        if (visible.isNotEmpty()) listState.animateScrollToItem(visible.lastIndex)
    }

    Column(modifier = Modifier.fillMaxWidth().imePadding()) {
        ScopeRow(ui, onScope)

        Box(modifier = Modifier.weight(1f)) {
            if (visible.isEmpty()) {
                EmptyState(
                    title = if (ui.scope == ChatScope.Private && peer == null) {
                        "还没选私信对象"
                    } else {
                        "还没有消息"
                    },
                    hint = if (ui.scope == ChatScope.Private && peer == null) {
                        "在频道列表里点一个成员，就能私信或戳他"
                    } else {
                        "说点什么吧"
                    },
                )
            } else {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(visible, key = { it.key }) { message ->
                        MessageRow(message)
                    }
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = {
                    Text(
                        when (ui.scope) {
                            ChatScope.Channel -> "发到当前频道…"
                            ChatScope.Server -> "发到服务器…"
                            ChatScope.Private -> peer?.let { "私信 ${it.nickname}…" } ?: "先选一个人"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                enabled = canSend,
                maxLines = 3,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    onSend(draft)
                    draft = ""
                },
                enabled = canSend && draft.isNotBlank(),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "发送",
                    tint = if (canSend && draft.isNotBlank()) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    },
                )
            }
        }
    }
}

@Composable
private fun ScopeRow(ui: SessionUiState, onScope: (ChatScope) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
    ) {
        ScopeChip(
            label = "频道",
            unread = ui.unreadChannel,
            active = ui.scope == ChatScope.Channel,
            onClick = { onScope(ChatScope.Channel) },
        )
        ScopeChip(
            label = "服务器",
            unread = ui.unreadServer,
            active = ui.scope == ChatScope.Server,
            onClick = { onScope(ChatScope.Server) },
        )
        ScopeChip(
            label = ui.peer?.nickname?.let { "私信·$it" } ?: "私信",
            unread = ui.unreadPrivate.values.sum(),
            active = ui.scope == ChatScope.Private,
            enabled = ui.peer != null,
            onClick = { onScope(ChatScope.Private) },
        )
    }
}

@Composable
private fun ScopeChip(
    label: String,
    unread: Int,
    active: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val color = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
        active -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (unread > 0) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(14.dp)
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

@Composable
private fun MessageRow(message: ChatMessage) {
    if (message.poke) {
        PokeRow(message)
        return
    }
    if (message.mine) {
        Row(
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.fillMaxWidth().padding(start = 56.dp, end = 12.dp, top = 2.dp),
        ) {
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatClock(message.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "  ${message.fromName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Box(
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .clip(RoundedCornerShape(14.dp, 4.dp, 14.dp, 14.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(text = message.text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 56.dp, top = 2.dp),
        ) {
            MemberAvatar(name = message.fromName, size = 28.dp)
            Column(modifier = Modifier.padding(start = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = message.fromName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "  " + formatClock(message.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box(
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .clip(RoundedCornerShape(4.dp, 14.dp, 14.dp, 14.dp))
                        // surfaceContainerHigh rather than surfaceVariant: on the
                        // dark palette the former is visibly lighter than the page,
                        // which is what makes a bubble read as a bubble.
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(text = message.text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/**
 * A poke, drawn as a centred system line rather than a bubble.
 *
 * It carries no reply and no avatar, so giving it the shape of a message would
 * be a lie; the amber pill says "this happened to you" and stays out of the way
 * of the actual conversation around it.
 */
@Composable
private fun PokeRow(message: ChatMessage) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f))
                .padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            Text(text = "👉", fontSize = 12.sp)
            Text(
                text = "${message.fromName} ${message.text}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            Text(
                text = formatClock(message.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.65f),
            )
        }
    }
}
