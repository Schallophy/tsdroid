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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.unit.Dp
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
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
import dev.tsdroid.data.withAnnouncedVersion
import dev.tsdroid.model.AccountRow
import dev.tsdroid.model.AccountState
import dev.tsdroid.ui.components.IconPlate

/**
 * The home screen: the app's front door.
 *
 * Two things live here, in this order. The temporary-connect form sits at the
 * top, collapsed by default: it is the one-off entry point, and it deliberately
 * does not save anything — type an address, connect, and the server is not
 * remembered for next time. Below it, the account's cloud bookmarks appear as
 * cards, because those are the connections the user *has* saved. Connecting
 * from a bookmark brings its own passwords; nothing is written locally.
 *
 * There is no "recent connections" list: bookmarks are the saved connections,
 * and the temporary form is for the one you do not want to keep.
 */
@Composable
fun HomeScreen(
    account: AccountState,
    accountSettled: Boolean,
    bookmarksFresh: Boolean,
    bookmarkOrderReset: Int,
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

    // The scratch form survives a relaunch, passwords included: ServerStore keeps
    // those encrypted, so what is on disk is ciphertext tied to this device.
    var address by rememberSaveable { mutableStateOf(settings.draftAddress) }
    var nickname by rememberSaveable { mutableStateOf(settings.draftNickname) }
    var serverPassword by rememberSaveable { mutableStateOf(store.draftSecret("server").orEmpty()) }
    var serverPasswordVisible by rememberSaveable { mutableStateOf(false) }
    var channel by rememberSaveable { mutableStateOf(settings.draftChannel) }
    var channelPassword by rememberSaveable { mutableStateOf(store.draftSecret("channel").orEmpty()) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var tempExpanded by rememberSaveable { mutableStateOf(false) }
    var announceMyts by rememberSaveable { mutableStateOf(true) }

    // Persist as it is typed, so a relaunch or a rotation lands on the same form.
    LaunchedEffect(address) { settings.draftAddress = address }
    LaunchedEffect(nickname) { settings.draftNickname = nickname }
    LaunchedEffect(channel) { settings.draftChannel = channel }
    // Passwords persist too, but only as ciphertext (ServerStore encrypts).
    LaunchedEffect(serverPassword) { store.setDraftSecret("server", serverPassword) }
    LaunchedEffect(channelPassword) { store.setDraftSecret("channel", channelPassword) }

    val canConnect = address.isNotBlank() && nickname.isNotBlank()

    fun submit() {
        if (!canConnect) return
        // Temporary connect: the server entry and its password are not saved.
        // The identity is still written by the native client's connection
        // callback — that is protocol, not "remembering the server".
        onConnect(
            settings.withAnnouncedVersion(
                ServerConfig(
                    address = address.trim(),
                    nickname = nickname.trim(),
                    password = serverPassword,
                    channel = channel.trim(),
                    channelPassword = channelPassword,
                    identityIni = store.readIdentity(),
                    announceMyts = announceMyts,
                ),
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
        // Everything above the bookmarks is one item: it is a fixed block, and
        // keeping it out of the item list means the bookmark rows below are the
        // only things that move when one is dragged.
        Column {
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
                    painter = painterResource(R.drawable.icon_capture),
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
                placeholder = { Text("没有就留空") },
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
        }

        if (account.loggedIn) {
            val rows = account.bookmarkRows()
            if (rows.isEmpty() && !bookmarksFresh) {
                // Nothing to say yet: the list has not been read this launch and
                // the cache had no rows either.
            } else if (rows.isEmpty()) {
                Text(
                    text = "账号里还没有书签。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                BookmarkItems(
                    rows = rows,
                    orderReset = bookmarkOrderReset,
                    onUse = onConnectBookmark,
                        onEdit = onEditBookmark,
                        onDelete = onDeleteBookmark,
                    onMove = onMoveBookmark,
                )
            }
        } else if (accountSettled) {
            // Only once the launch has actually found no remembered login: before
            // that the blank state simply means "still restoring", and prompting
            // would flash "sign in" over a list that is about to appear.
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
 * it, so a tap still connects. A drag is committed on drop: the list is re-sent
 * as "this one now follows that one", which is the account's own model (a
 * linked list, not an index).
 *
 * A long press opens the row's menu. It is an overlay rather than an inline row
 * of buttons, so opening it does not push the list around.
 */
@Composable
private fun BookmarkItems(
    rows: List<AccountRow>,
    orderReset: Int,
    onUse: (AccountRow) -> Unit,
    onEdit: (AccountRow) -> Unit,
    onDelete: (AccountRow) -> Unit,
    onMove: (row: AccountRow, afterUuid: String) -> Unit,
) {
    val bookmarks = rows.filter { !it.folder && it.bookmark != null }
    val order = remember { mutableStateListOf<String>() }
    val serverOrder = bookmarks.map { it.uuid }
    // `orderReset` advances when a reorder was refused: the server's order is
    // unchanged then, and this is what puts the rows back where they were.
    LaunchedEffect(serverOrder, orderReset) {
        order.clear()
        order.addAll(serverOrder)
    }

    BookmarkRows(
        order = order,
        bookmarks = bookmarks,
        onDragTo = { from, to ->
            if (from in order.indices && to in order.indices && from != to) {
                val moved = order.removeAt(from)
                order.add(to, moved)
                val after = if (to == 0) "" else order[to - 1]
                onMove(bookmarks.first { it.uuid == moved }, after)
            }
        },
        onUse = onUse,
        onEdit = onEdit,
        onDelete = onDelete,
    )
}

@Composable
private fun BookmarkRows(
    order: List<String>,
    bookmarks: List<AccountRow>,
    onDragTo: (from: Int, to: Int) -> Unit,
    onUse: (AccountRow) -> Unit,
    onEdit: (AccountRow) -> Unit,
    onDelete: (AccountRow) -> Unit,
) {
    // Rows are laid out by `offset` inside a Box, so a row can animate from its
    // old slot to its new one. The Box has no spacing of its own; the offset
    // carries both the height and the gap.
    Box(modifier = Modifier.fillMaxWidth().height((ROW_HEIGHT + ROW_GAP) * order.size)) {
        order.forEachIndexed { index, uuid ->
            val row = bookmarks.firstOrNull { it.uuid == uuid }
            if (row != null) {
                val target = (ROW_HEIGHT + ROW_GAP) * index.toFloat()
                val y by animateDpAsState(
                    targetValue = target,
                    animationSpec = tween(200),
                    label = "bookmarkSlot",
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset { IntOffset(0, y.roundToPx()) },
                ) {
                    BookmarkRow(
                        row = row,
                        index = index,
                        rowHeight = ROW_HEIGHT + ROW_GAP,
                        onDragTo = onDragTo,
                        onUse = onUse,
                        onEdit = onEdit,
                        onDelete = onDelete,
                    )
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BookmarkRow(
    row: AccountRow,
    index: Int,
    rowHeight: Dp,
    onDragTo: (from: Int, to: Int) -> Unit,
    onUse: (AccountRow) -> Unit,
    onEdit: (AccountRow) -> Unit,
    onDelete: (AccountRow) -> Unit,
) {
    val bookmark = row.bookmark ?: return
    var menuOpen by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val rowHeightPx = with(LocalDensity.current) { rowHeight.toPx() }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (dragging) 1f else 0f)
            .offset { IntOffset(0, if (dragging) dragOffset.roundToInt() else 0) }
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (dragging) MaterialTheme.colorScheme.surfaceContainerHigh
                else MaterialTheme.colorScheme.surfaceContainerLow,
            )
            .combinedClickable(
                onClick = { onUse(row) },
                onLongClick = { menuOpen = true },
            )
            .height(ROW_HEIGHT)
            .padding(start = 4.dp, end = 12.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .pointerInput(index, rowHeightPx) {
                    detectDragGestures(
                        onDragStart = {
                            dragging = true
                            dragOffset = 0f
                        },
                        onDragEnd = {
                            val steps = (dragOffset / rowHeightPx).roundToInt()
                            onDragTo(index, (index + steps).coerceAtLeast(0))
                            dragging = false
                            dragOffset = 0f
                        },
                        onDragCancel = {
                            dragging = false
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
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("编辑") },
                onClick = {
                    menuOpen = false
                    onEdit(row)
                },
            )
            DropdownMenuItem(
                text = { Text("删除") },
                onClick = {
                    menuOpen = false
                    onDelete(row)
                },
            )
        }
    }
}

private val ROW_HEIGHT = 56.dp
private val ROW_GAP = 8.dp
