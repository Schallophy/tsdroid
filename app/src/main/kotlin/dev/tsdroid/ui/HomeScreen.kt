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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
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
) {
    val context = LocalContext.current
    val store = remember { ServerStore(context) }
    val settings = remember { Settings(context) }

    var address by rememberSaveable { mutableStateOf("") }
    var nickname by rememberSaveable { mutableStateOf("") }
    var serverPassword by rememberSaveable { mutableStateOf("") }
    var serverPasswordVisible by rememberSaveable { mutableStateOf(false) }
    var channel by rememberSaveable { mutableStateOf("") }
    var channelPassword by rememberSaveable { mutableStateOf("") }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var tempExpanded by rememberSaveable { mutableStateOf(false) }
    var announceMyts by rememberSaveable { mutableStateOf(true) }
    var versionProfile by rememberSaveable { mutableStateOf(settings.versionProfile) }

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
                version = versionProfile,
            ),
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                        MaterialTheme.colorScheme.background,
                    ),
                ),
            )
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
                VersionProfilePicker(
                    value = versionProfile,
                    onValue = {
                        versionProfile = it
                        settings.versionProfile = it
                    },
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
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    rows.forEach { row ->
                        if (row.folder) {
                            Text(
                                text = row.name.ifEmpty { "文件夹" },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = (row.depth * 14).dp, top = 4.dp),
                            )
                        } else {
                            BookmarkCard(
                                row = row,
                                onUse = { onConnectBookmark(row) },
                            )
                        }
                    }
                }
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

@Composable
private fun BookmarkCard(row: AccountRow, onUse: () -> Unit) {
    val bookmark = row.bookmark ?: return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onUse)
            .padding(start = (12 + row.depth * 14).dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MemberAvatar(name = bookmark.name, size = 32.dp)
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
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

/**
 * Which signed client version to announce.
 *
 * The server compares the text with its signature, so free text is refused
 * outright (`ClientHacked`) and the only options are the profiles the core
 * ships. Each is a chip rather than a free field: typing "4.0.0" would just
 * fail the connection, and on an official server repeated attempts get the IP
 * banned.
 */
@Composable
private fun VersionProfilePicker(value: String, onValue: (String) -> Unit) {
    val options = listOf(
        "latest" to "最新 3.6.3",
        "debug" to "调试版 3.?",
        "ancient" to "远古 0.0.1",
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "对外显示的客户端版本",
            style = MaterialTheme.typography.labelLarge,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((key, label) in options) {
                val active = key == value
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                            else MaterialTheme.colorScheme.surfaceContainerHigh,
                        )
                        .clickable { onValue(key) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
        Text(
            text = "版本号必须对上官方签名，只能从这几项里选；乱填会被服务器当成改包拒连，反复试还可能封 IP。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
