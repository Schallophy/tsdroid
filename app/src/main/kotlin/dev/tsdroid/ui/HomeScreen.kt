package dev.tsdroid.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tsdroid.R
import dev.tsdroid.core.ServerConfig
import dev.tsdroid.data.ServerStore
import dev.tsdroid.data.Settings
import dev.tsdroid.model.AccountRow
import dev.tsdroid.model.AccountState
import dev.tsdroid.ui.components.IconPlate
import dev.tsdroid.ui.components.MemberAvatar

/**
 * The home screen: the app's front door.
 *
 * Two things live here, in this order. The temporary-connect form sits at the
 * top, always visible: it is the one-off entry point, and it deliberately does
 * not save anything — type an address, connect, and the server is not remembered
 * for next time. Below it, the account's cloud bookmarks appear as cards,
 * because those are the connections the user *has* saved. Connecting from a
 * bookmark brings its own passwords; nothing is written locally.
 *
 * There is no "recent connections" list: bookmarks are the saved connections,
 * and the temporary form is for the one you do not want to keep.
 */
@Composable
fun HomeScreen(
    account: AccountState,
    onConnect: (ServerConfig) -> Unit,
    onConnectBookmark: (AccountRow) -> Unit,
    onOpenSettings: () -> Unit,
    onSaveBookmark: (name: String, address: String, nickname: String) -> Unit,
    onEditBookmark: (AccountRow) -> Unit,
    onDeleteBookmark: (AccountRow) -> Unit,
    onMoveBookmark: (row: AccountRow, afterUuid: String) -> Unit,
) {
    val context = LocalContext.current
    val store = remember { ServerStore(context) }
    val settings = remember { Settings(context) }

    // The scratch form survives a relaunch; the password does not (it is a
    // secret, and ServerStore keeps those encrypted per address).
    var address by rememberSaveable { mutableStateOf(settings.draftAddress) }
    var nickname by rememberSaveable { mutableStateOf(settings.draftNickname) }
    var serverPassword by rememberSaveable { mutableStateOf("") }
    var serverPasswordVisible by rememberSaveable { mutableStateOf(false) }
    var channel by rememberSaveable { mutableStateOf(settings.draftChannel) }
    var channelPassword by rememberSaveable { mutableStateOf("") }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var tempExpanded by rememberSaveable { mutableStateOf(false) }
    var announceMyts by rememberSaveable { mutableStateOf(true) }

    // Persist as it is typed, so a relaunch or a rotation lands on the same form.
    LaunchedEffect(address) { settings.draftAddress = address }
    LaunchedEffect(nickname) { settings.draftNickname = nickname }
    LaunchedEffect(channel) { settings.draftChannel = channel }

    val canConnect = address.isNotBlank() && nickname.isNotBlank()

    fun submit() {
        if (!canConnect) return
        // Temporary connect: the server entry and its password are not saved.
        // The identity is still written by the native client's connection
        // callback — that is protocol, not "remembering the server".
        onConnect(
            ServerConfig(
                address = address.trim(),
                nickname = nickname.trim(),
                password = serverPassword,
                channel = channel.trim(),
                channelPassword = channelPassword,
                identityIni = store.readIdentity(),
                announceMyts = announceMyts,
                version = settings.clientVersion,
                platform = settings.clientPlatform,
            ),
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(24.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(IconPlate),
            ) {
                Image(
                    painter = painterResource(R.drawable.ts_input_unmuted),
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                )
            }
            Column(modifier = Modifier.padding(start = 14.dp)) {
                Text(text = "TS Droid", style = MaterialTheme.typography.headlineSmall)
            }
        }

        Spacer(Modifier.height(20.dp))

        // Temporary connect card — the always-visible entry point, collapsible
        // so the bookmark list can reclaim the screen when it is not in use.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { tempExpanded = !tempExpanded },
            ) {
                Text(
                    text = "临时连接",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    // Arrow without a shaft: the point is the only direction cue.
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = if (tempExpanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(22.dp)
                        .rotate(if (tempExpanded) 180f else 0f),
                )
            }

            if (tempExpanded) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { Text("服务器地址") },
                placeholder = { Text("example.com 或 example.com:9987") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = nickname,
                onValueChange = { nickname = it },
                label = { Text("昵称") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = serverPassword,
                onValueChange = { serverPassword = it },
                label = { Text("服务器密码") },
                placeholder = { Text("服务器没设密码就留空") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                visualTransformation = if (serverPasswordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                trailingIcon = {
                    TextButton(
                        onClick = { serverPasswordVisible = !serverPasswordVisible },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text(
                            text = if (serverPasswordVisible) "隐藏" else "显示",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { advanced = !advanced },
            ) {
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(18.dp)
                        .rotate(if (advanced) 180f else 0f),
                )
                Text(
                    text = "更多选项",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }

            if (advanced) {
                OutlinedTextField(
                    value = channel,
                    onValueChange = { channel = it },
                    label = { Text("直接进入的频道") },
                    placeholder = { Text("留空则进默认频道") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = channelPassword,
                    onValueChange = { channelPassword = it },
                    label = { Text("频道密码") },
                    placeholder = { Text("频道有密码时填写") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Button(
                onClick = { submit() },
                enabled = canConnect,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Text("连接", fontWeight = FontWeight.SemiBold)
            }

            // Saves the same form as a cloud bookmark. Only offered when signed
            // in, because a bookmark lives in the account; without one there is
            // nowhere to put it.
            if (account.loggedIn) {
                TextButton(
                    onClick = {
                        val name = nickname.ifBlank { address }
                        onSaveBookmark(name.trim(), address.trim(), nickname.trim())
                    },
                    enabled = canConnect,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("保存到书签")
                }
            }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // Bookmarks — the saved connections.
        Text(
            text = "书签",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        if (account.loggedIn) {
            val rows = account.bookmarkRows()
            if (rows.isEmpty()) {
                Text(
                    text = "账号里没有可用的书签。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                BookmarkList(
                    rows = rows,
                    onUse = onConnectBookmark,
                    onEdit = onEditBookmark,
                    onDelete = onDeleteBookmark,
                    onMove = onMoveBookmark,
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(16.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "登录 myTeamSpeak 后可使用云端书签",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onOpenSettings) {
                        Text("去登录")
                    }
                }
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}


/**
 * The bookmark list, with a drag handle per row.
 *
 * Dragging is the reorder gesture and the handle is the only place that starts
 * it, so a tap still connects and a horizontal scroll still works. A drag is
 * committed on drop: the list is re-sent as "this one now follows that one",
 * which is the account's own model (a linked list, not an index).
 *
 * A long press opens the row's menu. It is a dialog rather than an inline row of
 * buttons, so opening it does not push the list around.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BookmarkList(
    rows: List<AccountRow>,
    onUse: (AccountRow) -> Unit,
    onEdit: (AccountRow) -> Unit,
    onDelete: (AccountRow) -> Unit,
    onMove: (row: AccountRow, afterUuid: String) -> Unit,
) {
    // Only real bookmarks participate in ordering; folders are labels.
    val bookmarks = remember(rows) { rows.filter { !it.folder && it.bookmark != null } }
    var order by remember(bookmarks) { mutableStateOf(bookmarks.map { it.uuid }) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var menuFor by remember { mutableStateOf<AccountRow?>(null) }

    // One row's height plus the gap; used to turn a drag distance into a target
    // index without measuring each child.
    val rowHeightPx = with(LocalDensity.current) { (ROW_HEIGHT + ROW_GAP).toPx() }

    Column(verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
        for (uuid in order) {
            val row = bookmarks.firstOrNull { it.uuid == uuid } ?: continue
            val bookmark = row.bookmark ?: continue
            val isDragging = dragging == uuid

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .zIndex(if (isDragging) 1f else 0f)
                    .offset { IntOffset(0, if (isDragging) dragOffset.roundToInt() else 0) },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (isDragging) MaterialTheme.colorScheme.surfaceContainerHigh
                            else MaterialTheme.colorScheme.surfaceContainerLow,
                        )
                        .combinedClickable(
                            onClick = { onUse(row) },
                            onLongClick = { menuFor = row },
                        )
                        .height(ROW_HEIGHT)
                        .padding(start = 4.dp, end = 12.dp),
                ) {
                    // The handle: a drag surface and the row's affordance that it
                    // can be moved.
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(40.dp)
                            .pointerInput(uuid, order) {
                                detectDragGestures(
                                    onDragStart = {
                                        dragging = uuid
                                        dragOffset = 0f
                                    },
                                    onDragEnd = {
                                        val from = order.indexOf(uuid)
                                        val to = (from + (dragOffset / rowHeightPx).roundToInt())
                                            .coerceIn(0, order.lastIndex)
                                        if (from >= 0 && to != from) {
                                            val next = order.toMutableList()
                                            next.removeAt(from)
                                            next.add(to, uuid)
                                            order = next
                                            // `after` is the item now above it,
                                            // or empty when it landed first.
                                            val after = if (to == 0) "" else next[to - 1]
                                            onMove(row, after)
                                        }
                                        dragging = null
                                        dragOffset = 0f
                                    },
                                    onDragCancel = {
                                        dragging = null
                                        dragOffset = 0f
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dragOffset += amount.y
                                    },
                                )
                            },
                    ) {
                        Icon(
                            Icons.Default.Menu,
                            contentDescription = "拖动排序",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = bookmark.name.ifEmpty { bookmark.address },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = bookmark.connectAddress,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (bookmark.hasServerPassword || bookmark.hasChannelPassword) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = "书签带有密码",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }
            }
        }
    }

    menuFor?.let { row ->
        AlertDialog(
            onDismissRequest = { menuFor = null },
            title = { Text(row.bookmark?.name?.ifEmpty { row.bookmark?.address } ?: "") },
            text = {
                Column {
                    MenuItem("编辑") {
                        menuFor = null
                        onEdit(row)
                    }
                    MenuItem("删除", destructive = true) {
                        menuFor = null
                        onDelete(row)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { menuFor = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun MenuItem(label: String, destructive: Boolean = false, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        color = if (destructive) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
    )
}

private val ROW_HEIGHT = 56.dp
private val ROW_GAP = 8.dp
