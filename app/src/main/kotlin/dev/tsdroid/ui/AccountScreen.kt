package dev.tsdroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tsdroid.SessionViewModel
import dev.tsdroid.model.AccountBookmark
import dev.tsdroid.model.AccountIdentity
import dev.tsdroid.model.AccountRow
import dev.tsdroid.model.AccountState
import dev.tsdroid.ui.components.MemberAvatar

/**
 * The myTeamSpeak account, usable without a server.
 *
 * The account side of the core is a handle of its own, so this screen can sign
 * in, list bookmarks and back them up before the user has picked a server. A
 * bookmark fills the connect form and the account handle then rides into the
 * connection, where the core announces the myTeamSpeak ID in `clientinit`.
 */
@Composable
fun AccountScreen(
    vm: SessionViewModel,
    onBack: () -> Unit,
    onSyncIdentity: () -> Unit,
    onUseIdentity: (String) -> Unit,
    onUseBookmark: (AccountBookmark, String?) -> Unit,
) {
    val account by vm.accountState.collectAsStateWithLifecycle()
    val startError by vm.accountError.collectAsStateWithLifecycle()

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    var showOtp by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var actionInfo by remember { mutableStateOf<String?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // Cheap and read-only: keeps `pickedIdentity` (and thus the "this
        // server knows you as …" line) current without waiting for a connect.
        run { vm.accountRefresh() }
    }

    LaunchedEffect(Unit) {
        vm.accountNotices.collect { notice ->
            if (notice.ok) {
                actionError = null
                actionInfo = when (notice.command) {
                    "account_login" -> "已登录 myTeamSpeak"
                    "account_logout" -> "已退出登录，本机记住的凭据已删除"
                    "account_refresh" -> "已从账号服务器刷新"
                    "sync_backup" -> notice.message.ifEmpty { "备份完成" }
                    else -> null
                }
            } else {
                actionInfo = null
                actionError = notice.message.ifEmpty { "账号操作失败" }
            }
        }
    }

    fun run(command: () -> String?) {
        actionInfo = null
        actionError = command()
    }

    /**
     * Passwords were fetched once after login/refresh; a click is a cache
     * lookup. A missing entry means that fetch has not succeeded yet.
     */
    fun choose(uuid: String, bookmark: AccountBookmark) {
        if (!bookmark.hasServerPassword && !bookmark.hasChannelPassword) {
            onUseBookmark(bookmark, null)
            return
        }
        val secrets = vm.accountBookmarkSecrets(uuid)
        if (secrets == null) {
            actionError = "书签密码还没同步好，请稍后重试或点“刷新”"
            return
        }
        val missing = (bookmark.hasServerPassword && secrets.serverPassword.isNullOrEmpty()) ||
            (bookmark.hasChannelPassword && secrets.channelPassword.isNullOrEmpty())
        onUseBookmark(
            bookmark.copy(
                serverPassword = secrets.serverPassword,
                channelPassword = secrets.channelPassword,
            ),
            if (missing) "该书签的密码未能读取，请手动填写" else null,
        )
    }

    val busy = account.busy
    val errorLine = actionError ?: account.lastError.ifEmpty { null }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .imePadding(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 16.dp, top = 6.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text("myTeamSpeak 账号", style = MaterialTheme.typography.titleLarge)
        }

        if (startError != null) {
            AccountSection("不可用") {
                Text(
                    text = startError.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            return@Column
        }

        if (account.loggedIn) {
            SignedIn(
                account = account,
                busy = busy,
                accountIdentities = account.identities,
                errorLine = errorLine,
                infoLine = actionInfo,
                onRefresh = { run { vm.accountRefresh() } },
                onBackup = { run { vm.accountBackup() } },
                onLogout = { confirmLogout = true },
                onSyncIdentity = onSyncIdentity,
                onUseIdentity = onUseIdentity,
                onChooseBookmark = { uuid, bookmark -> choose(uuid, bookmark) },
            )
        } else {
            AccountSection("登录") {
                Text(
                    text = "登录后可以读取云端书签、备份同步项；连接服务器时会展示你的 " +
                        "myTeamSpeak 身份。登录凭据只保存在本应用的私有目录，退出登录即删除。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("邮箱") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                    ),
                    trailingIcon = {
                        TextButton(onClick = { passwordVisible = !passwordVisible }) {
                            Text(
                                text = if (passwordVisible) "隐藏" else "显示",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (showOtp) {
                    OutlinedTextField(
                        value = otp,
                        onValueChange = { otp = it },
                        label = { Text("两步验证码") },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    TextButton(onClick = { showOtp = true }) { Text("需要两步验证码？") }
                }
                if (busy) InlineBusy(account)
                if (busy && account.command == "account_login") {
                    Text(
                        text = "正在访问 myTeamSpeak；网络不通时会在约 25 秒后报错。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = { run { vm.accountLogin(email, password, otp) } },
                    enabled = email.isNotBlank() && password.isNotBlank() && !busy,
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                ) {
                    Text(if (busy) "正在登录…" else "登录")
                }
                StatusLines(errorLine = errorLine, infoLine = actionInfo)
                Text(
                    text = if (vm.accountHasRememberedLogin()) {
                        "本机已记住上次登录；下次启动会自动登录。"
                    } else {
                        "登录成功后会在本机记住一个凭据，下次启动自动登录。"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("退出 myTeamSpeak 账号？") },
            text = { Text("会删除本机记住的登录，云端书签和服务器数据不受影响。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmLogout = false
                        run { vm.accountLogout() }
                    },
                ) { Text("退出登录") }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogout = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SignedIn(
    account: AccountState,
    busy: Boolean,
    errorLine: String?,
    infoLine: String?,
    onRefresh: () -> Unit,
    onBackup: () -> Unit,
    onLogout: () -> Unit,
    onSyncIdentity: () -> Unit,
    onUseIdentity: (String) -> Unit,
    onChooseBookmark: (String, AccountBookmark) -> Unit,
    accountIdentities: List<AccountIdentity>,
) {
    AccountSection("账号") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MemberAvatar(name = account.username.ifEmpty { account.email }, size = 38.dp)
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(
                    text = account.username.ifEmpty { account.email },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = account.email,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = if (account.hasMytsId) {
                "myTeamSpeak 身份已获取：连接服务器时会展示名称和徽章。"
            } else {
                "这个账号没有可用的 myTeamSpeak 身份：服务器不会展示徽章，" +
                    "连接本身不受影响。"
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (account.hasMytsId) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        if (account.uuid.isNotEmpty()) {
            SelectionContainer {
                Text(
                    text = "UUID ${account.uuid}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (busy) InlineBusy(account)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onRefresh, enabled = !busy) { Text("刷新") }
            TextButton(onClick = onBackup, enabled = !busy) { Text("备份") }
            TextButton(onClick = onLogout, enabled = !busy) { Text("退出登录") }
        }
        StatusLines(errorLine = errorLine, infoLine = infoLine)
        if (account.lastBackup.isNotEmpty()) {
            Text(
                text = "上次备份：${account.lastBackup}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    AccountSection("身份") {
        Text(
            text = "服务器把权限绑在 UID 上，UID 来自本机身份。连接时客户端会自动采用账号里这台服务器用过的身份；下面的列表只用于核对或手动改。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val picked = account.pickedIdentity
        if (picked != null && picked.value.isNotEmpty()) {
            Text(
                text = "这台服务器认你为「${picked.nickname.ifEmpty { picked.name.ifEmpty { "该身份" } }}」，" +
                    "连接时会自动用它。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onSyncIdentity, enabled = !busy) {
                Text("把本机身份存进账号")
            }
            if (picked != null && picked.value.isNotEmpty()) {
                TextButton(onClick = { onUseIdentity(picked.value) }, enabled = !busy) {
                    Text("立即采用")
                }
            }
        }
        val stored = accountIdentities
        if (stored.isEmpty()) {
            Text(
                text = "账号里还没有可读的身份项。先在官方客户端登录一次，它会创建一条。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            stored.forEach { identity ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = identity.nickname.ifEmpty { identity.name.ifEmpty { "身份" } },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = if (identity.default) "默认身份" else "备用身份",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = { onUseIdentity(identity.value) },
                        enabled = !busy,
                    ) { Text("采用") }
                }
            }
        }
        StatusLines(errorLine = errorLine, infoLine = infoLine)
    }

    AccountSection("云书签") {
        if (busy && account.command == "sync_list") {
            Text(
                text = "正在同步书签密码…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val rows = account.bookmarkRows()
        if (rows.isEmpty()) {
            Text(
                text = if (busy) "正在读取书签…" else "账号里没有可用的书签。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@AccountSection
        }
        rows.forEach { row ->
            if (row.folder) {
                Text(
                    text = row.name.ifEmpty { "文件夹" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = (row.depth * 14).dp, top = 4.dp),
                )
            } else {
                BookmarkRow(
                    row = row,
                    onUse = { bookmark ->
                        if (!busy) onChooseBookmark(row.uuid, bookmark)
                    },
                )
            }
        }
    }
}

@Composable
private fun BookmarkRow(row: AccountRow, onUse: (AccountBookmark) -> Unit) {
    val bookmark = row.bookmark ?: return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable { onUse(bookmark) }
            .padding(start = (8 + row.depth * 14).dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Text(
            text = bookmark.name.ifEmpty { bookmark.address },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = bookmark.connectAddress,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (bookmark.nickname.isNotEmpty()) {
                Text(
                    text = "· ${bookmark.nickname}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (bookmark.hasServerPassword || bookmark.hasChannelPassword) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = "书签带有密码",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(11.dp),
                )
            }
        }
    }
}

@Composable
private fun InlineBusy(account: AccountState) {
    LinearProgressIndicator(
        modifier = Modifier.fillMaxWidth().height(2.dp),
    )
    Text(
        text = account.progress.ifEmpty { "正在${commandLabel(account.command)}…" },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun commandLabel(command: String): String = when (command) {
    "account_login" -> "登录"
    "account_logout" -> "退出登录"
    "account_refresh" -> "刷新"
    "sync_list" -> "读取书签"
    "sync_backup" -> "备份"
    "sync_bookmark_create" -> "创建书签"
    "sync_delete" -> "删除同步项"
    "sync_restore" -> "恢复"
    "sync_merge" -> "比对"
    else -> "处理"
}

@Composable
private fun StatusLines(errorLine: String?, infoLine: String?) {
    if (errorLine != null) {
        Text(
            text = errorLine,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (infoLine != null) {
        Text(
            text = infoLine,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun AccountSection(title: String, content: @Composable () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(14.dp),
        ) {
            content()
        }
    }
}
