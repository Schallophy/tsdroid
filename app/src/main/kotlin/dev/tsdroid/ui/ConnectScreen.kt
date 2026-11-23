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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import dev.tsdroid.data.SavedServer
import dev.tsdroid.data.ServerStore
import dev.tsdroid.data.Settings
import dev.tsdroid.model.AccountState
import dev.tsdroid.ui.components.IconPlate
import dev.tsdroid.ui.components.MemberAvatar

/** Values a cloud bookmark fills in; [notice] says what still needs typing. */
data class ConnectPrefill(
    val address: String,
    val nickname: String,
    val channel: String,
    /** The bookmark's `sendMytsid`; null keeps the current/default choice. */
    val announceMyts: Boolean? = null,
    /** A password revealed for this use; null falls back to the saved one. */
    val serverPassword: String? = null,
    val channelPassword: String? = null,
    val notice: String? = null,
)

/**
 * The first screen: address, nickname, go.
 *
 * The server password sits in the open because a locked server refuses the
 * connection outright — hiding the field behind "更多选项" made people conclude the
 * client was broken. The channel and its password do hide: they are refinements
 * for the minority who care which room they land in.
 */
@Composable
fun ConnectScreen(
    account: AccountState,
    accountError: String?,
    onOpenAccount: () -> Unit,
    prefill: ConnectPrefill?,
    onPrefillApplied: () -> Unit,
    onConnect: (ServerConfig) -> Unit,
) {
    val context = LocalContext.current
    val store = remember { ServerStore(context) }
    val settings = remember { Settings(context) }
    var saved by remember { mutableStateOf(store.load()) }

    var address by rememberSaveable { mutableStateOf("") }
    var nickname by rememberSaveable { mutableStateOf("") }
    var serverPassword by rememberSaveable { mutableStateOf("") }
    var serverPasswordVisible by rememberSaveable { mutableStateOf(false) }
    var channel by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var announceMyts by rememberSaveable { mutableStateOf(true) }
    var versionProfile by rememberSaveable { mutableStateOf(settings.versionProfile) }
    var prefillNotice by remember { mutableStateOf<String?>(null) }

    val canConnect = address.isNotBlank() && nickname.isNotBlank()

    // A bookmark from the account screen fills the form and leaves the fields
    // editable: it is a starting point, not a locked-in destination. A password
    // saved for the same address is restored the same way picking a recent
    // server restores it.
    LaunchedEffect(prefill) {
        val incoming = prefill ?: return@LaunchedEffect
        address = incoming.address
        nickname = incoming.nickname
        // A revealed bookmark password wins; otherwise reuse the one saved for
        // this address, the same way picking a recent server does.
        serverPassword = incoming.serverPassword ?: store.passwordFor(incoming.address).orEmpty()
        channel = incoming.channel
        password = incoming.channelPassword.orEmpty()
        if (incoming.channel.isNotBlank() || !incoming.channelPassword.isNullOrEmpty()) {
            advanced = true
        }
        incoming.announceMyts?.let { announceMyts = it }
        settings.versionProfile = versionProfile
        prefillNotice = incoming.notice
        onPrefillApplied()
    }

    fun submit() {
        if (!canConnect) return
        prefillNotice = null
        val entry = SavedServer(
            address = address.trim(),
            nickname = nickname.trim(),
            lastUsed = System.currentTimeMillis(),
        )
        store.upsert(entry)
        // Remembered for the next visit. The ciphertext is AES-GCM under a key
        // the Android Keystore holds, so the plaintext never touches disk —
        // emptying the field here forgets the entry instead of saving "".
        store.setPassword(entry.address, serverPassword)
        saved = store.load()
        onConnect(
            ServerConfig(
                address = entry.address,
                nickname = entry.nickname,
                password = serverPassword,
                channel = channel.trim(),
                channelPassword = password,
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
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                        MaterialTheme.colorScheme.background,
                    ),
                ),
            )
            // The gradient runs edge to edge, but the fields must not sit under
            // the status bar or the gesture bar — without this the address field
            // is half hidden on a tall phone and the button under the keyboard.
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(24.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            // TeamSpeak's own capture icon on the same fixed dark plate the
            // self-view uses: the bulb is a baked-in blue, so it needs a dark
            // ground in both themes rather than the primary colour, which would
            // swallow it.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(IconPlate),
            ) {
                Image(
                    painter = painterResource(R.drawable.ts_input_unmuted),
                    contentDescription = null,
                    modifier = Modifier.size(30.dp),
                )
            }
            Column(modifier = Modifier.padding(start = 14.dp)) {
                Text(text = "TS Droid", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = "TeamSpeak 3 / 6 客户端",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        AccountEntry(
            account = account,
            accountError = accountError,
            onClick = onOpenAccount,
        )

        Spacer(Modifier.height(16.dp))

        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(16.dp),
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
                    // A wrong password is the single most common connection
                    // failure, and it is invisible once masked — so offer a
                    // plain-text look rather than making people retype it blind.
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
                    imageVector = if (advanced) {
                        Icons.Default.KeyboardArrowDown
                    } else {
                        Icons.AutoMirrored.Filled.KeyboardArrowRight
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
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
                    value = password,
                    onValueChange = { password = it },
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

        prefillNotice?.let { notice ->
            Spacer(Modifier.height(10.dp))
            Text(
                text = notice,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }

        Spacer(Modifier.height(26.dp))

        if (saved.isNotEmpty()) {
            Text(
                text = "最近连接",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                saved.forEach { entry ->
                    SavedRow(
                        entry = entry,
                        onPick = {
                            address = entry.address
                            nickname = entry.nickname
                            serverPassword = store.passwordFor(entry.address).orEmpty()
                        },
                        onDelete = {
                            store.remove(entry.address)
                            saved = store.load()
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(32.dp))
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
        "debug" to "调试版 3.?.?",
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

@Composable
private fun AccountEntry(account: AccountState, accountError: String?, onClick: () -> Unit) {
    val loggedIn = account.loggedIn
    val title = when {
        loggedIn -> account.username.ifEmpty { account.email }
        account.busy -> "myTeamSpeak"
        else -> "登录 myTeamSpeak"
    }
    val subtitle = when {
        accountError != null -> "账号服务不可用"
        loggedIn && account.hasMytsId -> "已登录 · 身份已获取 · 管理书签"
        loggedIn -> "已登录 · 该账号无可用身份 · 管理书签"
        account.busy -> account.progress.ifEmpty { "正在登录…" }
        account.lastError.isNotEmpty() -> "上次登录失败，点按查看"
        else -> "同步云端书签，连接时展示身份"
    }
    val subtitleIsError = accountError != null || (!loggedIn && account.lastError.isNotEmpty())

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
    ) {
        MemberAvatar(name = if (loggedIn) title.ifEmpty { "M" } else "M", size = 30.dp)
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                text = title.ifEmpty { "myTeamSpeak" },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = if (subtitleIsError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun SavedRow(entry: SavedServer, onPick: () -> Unit, onDelete: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onPick)
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
    ) {
        MemberAvatar(name = entry.nickname, size = 30.dp)
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                text = entry.address,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = entry.nickname,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "删除",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
