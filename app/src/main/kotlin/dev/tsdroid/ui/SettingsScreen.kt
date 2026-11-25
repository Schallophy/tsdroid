package dev.tsdroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import dev.tsdroid.core.Native
import dev.tsdroid.data.AudioFocusAction
import dev.tsdroid.model.AccountBookmark
import dev.tsdroid.model.AccountRow
import dev.tsdroid.model.AccountState
import dev.tsdroid.ui.components.MemberAvatar

/**
 * Settings, grouped into five sections: 通用 / 语音 / 聊天 / 文件 / 账号.
 *
 * Each section is one rounded container, the same card language the rest of
 * the app uses. The account is the last section: it holds the login, the
 * read-only identity display and the bookmark management — the identity is
 * never a choice the user makes here, it is simply shown as the one the
 * client automatically adopted.
 */
@Composable
fun SettingsScreen(vm: SessionViewModel, accountError: String?, onBack: () -> Unit) {
    val account by vm.accountState.collectAsStateWithLifecycle()
    val settings = remember { vm.settings() }

    var away by remember { mutableStateOf(false) }
    var awayMessage by remember { mutableStateOf(settings.awayMessage) }
    var gain by remember { mutableFloatStateOf(settings.micGain) }
    var output by remember { mutableFloatStateOf(settings.outputGain) }
    var noise by remember { mutableStateOf(settings.noiseSuppression) }
    var bluetooth by remember { mutableStateOf(settings.bluetoothMic) }
    var filesEnabled by remember { mutableStateOf(settings.filesTabEnabled) }
    var keepInBackground by remember { mutableStateOf(settings.keepInBackground) }
    var focusAction by remember { mutableStateOf(settings.audioFocusAction) }
    var clientVersion by remember { mutableStateOf(settings.clientVersion) }
    var clientPlatform by remember { mutableStateOf(settings.clientPlatform) }
    // Set when the version changed while a session is live: the choice only
    // takes effect on the next connection, so offer an immediate reconnect.
    var pendingReconnect by remember { mutableStateOf(false) }
    // Whether the reconnect prompt has already been shown for the current
    // editing session. Without it every keystroke that yields a valid version
    // would re-open the dialog after the user dismissed it.
    var reconnectPromptShown by remember { mutableStateOf(false) }

    // Account action notices go to a toast rather than an inline line, so the
    // card does not grow and shrink as messages come and go.
    var confirmLogout by remember { mutableStateOf(false) }

    // Email/password for the login form inside the account section
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    var showOtp by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.accountNotices.collect { notice ->
            if (notice.ok) {
                // A background refresh is not worth a toast; everything the user
                // explicitly did is.
                val text = when (notice.command) {
                    "account_login" -> "已登录 myTeamSpeak"
                    "account_resume" -> "已从本机凭据恢复登录"
                    "account_logout" -> "已退出登录"
                    "sync_backup" -> notice.message.ifEmpty { "备份完成" }
                    else -> null
                }
                text?.let(vm::setToast)
            } else {
                vm.setToast(notice.message.ifEmpty { "账号操作失败" })
            }
        }
    }

    LaunchedEffect(Unit) {
        // Keep `pickedIdentity` current without waiting for a connect.
        run { vm.accountRefresh() }
    }

    // A broken account side is a toast, not a line that appears and pushes the
    // rest of the card down.
    LaunchedEffect(accountError) {
        accountError?.let(vm::setToast)
    }

    fun run(command: () -> String?) {
        command()?.let(vm::setToast)
    }

    /**
     * Saves the version choice and, when a session is live, offers a reconnect.
     *
     * The local state always follows what was typed, so the field shows the
     * user's input even mid-word; only a choice that resolves to a signed entry
     * is persisted and can prompt, because an unresolvable one would silently
     * fall back to the standard tuple on connect.
     *
     * The version is announced only in `clientinit`, so it cannot change on an
     * open connection; the dialog asks whether to drop and rejoin now or keep
     * the setting for the next connection.
     */
    fun saveVersion(version: String, platform: String) {
        clientVersion = version
        clientPlatform = platform
        val valid = Native.nativeVersionResolve(version, platform).isNotEmpty()
        if (!valid) return
        settings.clientVersion = version
        settings.clientPlatform = platform
        // Prompt at most once per editing session, and only on a live connection.
        if (vm.isLive() && !reconnectPromptShown) {
            reconnectPromptShown = true
            pendingReconnect = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
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
            Text("设置", style = MaterialTheme.typography.titleLarge)
        }

        // ── 通用 ──────────────────────────────────────────────────────────
        Section("通用") {
            // The announced client version. Global: one choice for every server.
            // Only the version number is typed; the platform is picked from the
            // signed platforms, and the native side resolves the pair to the full
            // signed text.
            Text(
                text = "客户端版本",
                style = MaterialTheme.typography.labelLarge,
            )
            VersionSettings(
                version = clientVersion,
                platform = clientPlatform,
                onVersionChange = { saveVersion(it, clientPlatform) },
                onPlatformChange = { saveVersion(clientVersion, it) },
            )

            // Clearing saved passwords lives in General: it is a
            // once-in-a-while action, and putting it next to the password field
            // on the home screen invites mis-taps that force a retype.
            val store = remember { vm.serverStore() }
            var hasSaved by remember { mutableStateOf(store.hasPasswords()) }
            var confirmClear by remember { mutableStateOf(false) }
            Text(
                text = "服务器密码用 Android Keystore 持有的密钥加密后存放在本机。换机、抹机或从备份恢复后密文解不开，会当作没保存过，需要重新输入。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = { confirmClear = true },
                enabled = hasSaved,
            ) {
                Text(if (hasSaved) "清除已保存的密码" else "没有保存的密码")
            }
            if (confirmClear) {
                AlertDialog(
                    onDismissRequest = { confirmClear = false },
                    title = { Text("清除已保存的密码？") },
                    text = { Text("下次连接这些服务器要重新输入密码。服务器地址和昵称不受影响。") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                store.clearPasswords()
                                hasSaved = false
                                confirmClear = false
                            },
                        ) { Text("清除") }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmClear = false }) { Text("取消") }
                    },
                )
            }

            Text("关于", style = MaterialTheme.typography.labelLarge)
            Text(
                text = "协议核心：Rust + tsclientlib（钉在固定提交）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "语音：Opus 24 kbps 单声道，采集与播放由 Android 音频栈负责",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "本应用包含 tsclientlib（Apache-2.0 OR MIT）与 Opus（BSD）等第三方组件，" +
                    "完整许可见安装包 assets/licenses/NOTICE",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ── 语音 ──────────────────────────────────────────────────────────
        Section("语音") {
            ToggleRow(
                label = "麦克风降噪",
                hint = "系统级噪声抑制，开会时更干净",
                checked = noise,
                onChange = {
                    noise = it
                    vm.applyAudioSettings(gain, it, bluetooth)
                },
            )
            ToggleRow(
                label = "使用蓝牙耳机麦克风",
                hint = "必须走通话通道：开启期间手机其他声音会变窄带，音质下降。没连耳机时自动用手机麦克风。",
                checked = bluetooth,
                onChange = {
                    bluetooth = it
                    vm.applyAudioSettings(gain, noise, it)
                },
            )
            ToggleRow(
                label = "切后台不断开",
                hint = "划掉最近任务后继续通话",
                checked = keepInBackground,
                onChange = {
                    keepInBackground = it
                    settings.keepInBackground = it
                },
            )
            FocusActionRow(
                value = focusAction,
                onChange = {
                    focusAction = it
                    settings.audioFocusAction = it
                },
            )
            BatterySettingsRow()
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "麦克风增益 ${"%.1f".format(gain)}×",
                    style = MaterialTheme.typography.labelLarge,
                )
                Slider(
                    value = gain,
                    onValueChange = { gain = it },
                    onValueChangeFinished = { vm.applyAudioSettings(gain, noise, bluetooth) },
                    valueRange = 0.2f..4f,
                    colors = sliderColors(),
                )
            }
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "扬声器音量 ${"%.1f".format(output)}×",
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = "拉高会整体放大别人说话的声音；超过 1.0× 时软限幅，不会破音。" +
                        "播放走媒体音量，请同时用音量键确认媒体音量没有调低。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = output,
                    onValueChange = { output = it },
                    onValueChangeFinished = { vm.applyOutputGain(output) },
                    valueRange = 0.5f..2f,
                    colors = sliderColors(),
                )
            }
        }

        // ── 聊天 ──────────────────────────────────────────────────────────
        Section("聊天") {
            ToggleRow(
                label = "离开",
                hint = "别人会看到你处于离开状态",
                checked = away,
                onChange = {
                    away = it
                    vm.setAway(it, awayMessage)
                },
            )
            if (away) {
                OutlinedTextField(
                    value = awayMessage,
                    onValueChange = { awayMessage = it },
                    label = { Text("离开说明") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { vm.setAway(true, awayMessage) }) { Text("更新说明") }
            }
        }

        // ── 文件 ──────────────────────────────────────────────────────────
        Section("文件") {
            Text(
                text = "文件传输是低频功能，默认不在底部导航中显示文件入口。打开后，连接服务器时可浏览和收发当前频道的文件。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ToggleRow(
                label = "显示文件入口",
                hint = "在底部导航栏中加入「文件」标签",
                checked = filesEnabled,
                onChange = {
                    filesEnabled = it
                    settings.filesTabEnabled = it
                },
            )
        }

        // ── 账号 ──────────────────────────────────────────────────────────
        AccountSection(
            vm = vm,
            account = account,
            accountError = accountError,
            email = email,
            password = password,
            otp = otp,
            showOtp = showOtp,
            passwordVisible = passwordVisible,
            onEmailChange = { email = it },
            onPasswordChange = { password = it },
            onOtpChange = { otp = it },
            onShowOtpChange = { showOtp = it },
            onPasswordVisibleChange = { passwordVisible = it },
            onLogin = { run { vm.accountLogin(email, password, otp) } },
            onRefresh = { run { vm.accountRefresh() } },
            onLogout = { confirmLogout = true },
            onDeleteBookmark = { uuid -> run { vm.accountDeleteBookmark(uuid) } },
            confirmLogout = confirmLogout,
            onConfirmLogout = {
                confirmLogout = false
                run { vm.accountLogout() }
            },
            onDismissLogout = { confirmLogout = false },
        )

    }

    // The version is announced only in `clientinit`, so a change on a live
    // session needs a reconnect to take effect. Asking beats silently dropping
    // the call, and "取消" keeps the setting for the next connection.
    if (pendingReconnect) {
        AlertDialog(
            onDismissRequest = { pendingReconnect = false },
            title = { Text("版本改动需要重连才生效") },
            text = {
                Text("客户端版本只在连接时声明一次，当前连接不会改变。要现在断开重连吗？取消则保留设置，下次连接生效。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingReconnect = false
                        vm.reconnectNow()
                    },
                ) { Text("立即重连") }
            },
            dismissButton = {
                TextButton(onClick = { pendingReconnect = false }) { Text("取消") }
            },
        )
    }
}

/**
 * The account section: login, account info, and bookmark management.
 *
 * The identity is never a choice here and is not shown as one: the client
 * adopts it automatically, and this section only manages the login and the
 * saved bookmarks. Bookmark deletion lives here too: the home screen is for
 * connecting, not for managing what is saved.
 */
@Composable
private fun AccountSection(
    vm: SessionViewModel,
    account: AccountState,
    accountError: String?,
    email: String,
    password: String,
    otp: String,
    showOtp: Boolean,
    passwordVisible: Boolean,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onOtpChange: (String) -> Unit,
    onShowOtpChange: (Boolean) -> Unit,
    onPasswordVisibleChange: (Boolean) -> Unit,
    onLogin: () -> Unit,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
    onDeleteBookmark: (String) -> Unit,
    confirmLogout: Boolean,
    onConfirmLogout: () -> Unit,
    onDismissLogout: () -> Unit,
) {
    val busy = account.busy

    Section("账号") {
        if (account.loggedIn) {
            // Signed in: the account's identity and the actions on it.
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
                TextButton(onClick = onLogout, enabled = !busy) { Text("退出登录") }
            }


            // Bookmark management: list with delete. The home screen is for
            // connecting; deleting a saved bookmark is management, and lives here.
            Text(
                text = "云书签",
                style = MaterialTheme.typography.labelLarge,
            )
            val rows = account.bookmarkRows()
            if (rows.isEmpty()) {
                Text(
                    text = if (busy) "正在读取书签…" else "账号里没有可用的书签。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    rows.forEach { row ->
                        if (row.folder) {
                            Text(
                                text = row.name.ifEmpty { "文件夹" },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = (row.depth * 14).dp, top = 4.dp),
                            )
                        } else {
                            BookmarkManageRow(
                                row = row,
                                onDelete = { onDeleteBookmark(row.uuid) },
                            )
                        }
                    }
                }
            }
        } else {
            // Not signed in: the login form.
            Text(
                text = "登录后可以读取云端书签、备份同步项；连接服务器时会展示你的 " +
                    "myTeamSpeak 身份。登录凭据只保存在本应用的私有目录，退出登录即删除。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = email,
                onValueChange = onEmailChange,
                label = { Text("邮箱") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
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
                    TextButton(onClick = { onPasswordVisibleChange(!passwordVisible) }) {
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
                    onValueChange = onOtpChange,
                    label = { Text("两步验证码") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                TextButton(onClick = { onShowOtpChange(true) }) { Text("需要两步验证码？") }
            }
            if (busy) InlineBusy(account)
            Button(
                onClick = onLogin,
                enabled = email.isNotBlank() && password.isNotBlank() && !busy,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) {
                Text(if (busy) "正在登录…" else "登录")
            }
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = onDismissLogout,
            title = { Text("退出 myTeamSpeak 账号？") },
            text = { Text("会删除本机记住的登录，云端书签和服务器数据不受影响。") },
            confirmButton = {
                TextButton(onClick = onConfirmLogout) { Text("退出登录") }
            },
            dismissButton = {
                TextButton(onClick = onDismissLogout) { Text("取消") }
            },
        )
    }
}

@Composable
private fun BookmarkManageRow(row: AccountRow, onDelete: () -> Unit) {
    val bookmark = row.bookmark ?: return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(start = (8 + row.depth * 14).dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
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
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "删除书签",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp),
            )
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



/**
 * A settings section: a label and one rounded container.
 *
 * The container sits one surface step above the page in both palettes, so a
 * group reads as a card instead of blending into the background — the same
 * language the home screen and bookmark cards use.
 */
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
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
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(14.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun BatterySettingsRow() {
    val context = androidx.compose.ui.platform.LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                runCatching {
                    context.startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:${context.packageName}"),
                        ),
                    )
                }
            },
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "后台运行", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "被系统清理时在这里允许",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "设置",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun FocusActionRow(value: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = when (value) {
        AudioFocusAction.MUTE_SELF -> "只静音自己"
        AudioFocusAction.DISCONNECT -> "断开连接"
        AudioFocusAction.IGNORE -> "不处理"
        else -> "静音自己并暂停声音"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { open = true },
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "来电时", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "更改",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("来电时") },
            text = {
                Column {
                    FocusOption("静音自己并暂停声音", value, AudioFocusAction.MUTE_BOTH) {
                        onChange(it); open = false
                    }
                    FocusOption("只静音自己", value, AudioFocusAction.MUTE_SELF) {
                        onChange(it); open = false
                    }
                    FocusOption("断开连接", value, AudioFocusAction.DISCONNECT) {
                        onChange(it); open = false
                    }
                    FocusOption("不处理", value, AudioFocusAction.IGNORE) {
                        onChange(it); open = false
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun FocusOption(label: String, current: String, value: String, onPick: (String) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPick(value) }
            .padding(vertical = 10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (value == current) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f),
        )
        if (value == current) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    hint: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * The slider track, in the near-black palette.
 *
 * Material's defaults paint the inactive track with the secondary container —
 * amber here, because amber is reserved for "someone is talking" — which made a
 * gain slider look like a live signal. The track is a control, not a voice
 * state, so it uses neutral container steps and the blue accent only for what
 * the thumb has actually set.
 */
@Composable
private fun sliderColors() = SliderDefaults.colors(
    thumbColor = MaterialTheme.colorScheme.primary,
    activeTrackColor = MaterialTheme.colorScheme.primary,
    inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    activeTickColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.4f),
    inactiveTickColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
)
