package dev.tsdroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tsdroid.SessionViewModel
import dev.tsdroid.core.Native
import dev.tsdroid.data.AudioFocusAction
import dev.tsdroid.model.AccountState
import dev.tsdroid.ui.components.MemberAvatar

/**
 * Settings, grouped into five sections: 账号 / 连接 / 语音 / 聊天 / 文件.
 *
 * Each section is one rounded container, the same card language the rest of
 * the app uses. The account is first because it is the one thing a new install
 * needs before anything else, and it holds the login and the read-only identity
 * display — the identity is never a choice the user makes
 * here, it is simply shown as the one the client automatically adopted.
 */
@Composable
fun SettingsScreen(vm: SessionViewModel) {
    val account by vm.accountState.collectAsStateWithLifecycle()
    val settings = remember { vm.settings() }

    var away by remember { mutableStateOf(false) }
    var awayMessage by remember { mutableStateOf(settings.awayMessage) }
    var gain by remember { mutableFloatStateOf(settings.micGain) }
    var output by remember { mutableFloatStateOf(settings.outputGain) }
    var noise by remember { mutableStateOf(settings.noiseSuppression) }
    var bluetooth by remember { mutableStateOf(settings.bluetoothMic) }
    // Read from the ViewModel's flow, not from a local copy: the bar in the app
    // shell follows the same value, and they must not be able to disagree.
    val filesEnabled by vm.filesTabEnabled.collectAsStateWithLifecycle()
    var keepInBackground by remember { mutableStateOf(settings.keepInBackground) }
    var startMuted by remember { mutableStateOf(settings.startMuted) }
    var focusAction by remember { mutableStateOf(settings.audioFocusAction) }
    // A stored pair can be impossible (an older build wrote a version number
    // this table has no signature for), so the screen opens on what would
    // actually be announced rather than on the stale value.
    val announced = remember { settings.announceableVersion() }
    var clientVersion by remember { mutableStateOf(announced.first) }
    var clientPlatform by remember { mutableStateOf(announced.second) }
    // Set when the version changed while a session is live: the choice only
    // takes effect on the next connection, so offer an immediate reconnect.
    var pendingReconnect by remember { mutableStateOf(false) }
    // Whether the reconnect prompt has already been shown for the current
    // editing session. Without it every pick would re-open the dialog after
    // the user dismissed it.
    var reconnectPromptShown by remember { mutableStateOf(false) }

    var confirmLogout by remember { mutableStateOf(false) }

    // Email/password for the login form inside the account section
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    var showOtp by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }

    fun run(command: () -> String?) {
        command()?.let(vm::setToast)
    }

    /**
     * Saves the version choice and, when a session is live, offers a reconnect.
     *
     * The picker only offers pairs that can connect, so this cannot be handed an
     * impossible one; the check is kept as the boundary that makes that true
     * regardless of what the UI does. "Can connect" means the version is in the
     * platform's offered list — a signature that resolves is not enough, since
     * servers refuse anything older than 3.1.
     *
     * The version is announced only in `clientinit`, so it cannot change on an
     * open connection; the dialog asks whether to drop and rejoin now or keep
     * the setting for the next connection.
     */
    fun saveVersion(version: String, platform: String) {
        val offered = Native.nativeVersionChoices(platform).split(',')
        if (!offered.contains(version)) return
        clientVersion = version
        clientPlatform = platform
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
        Text(
            text = "设置",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 14.dp),
        )

        // ── 账号 ──────────────────────────────────────────────────────────
        AccountSection(
            account = account,
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
            onLogout = { confirmLogout = true },
            confirmLogout = confirmLogout,
            onConfirmLogout = {
                confirmLogout = false
                run { vm.accountLogout() }
            },
            onDismissLogout = { confirmLogout = false },
        )

        // ── 连接 ──────────────────────────────────────────────────────────
        Section("连接") {
            // The announced client version. Global: one choice for every server.
            // Both fields are picked from the signed pairs the native side
            // offers, so the selection cannot name a version it has no
            // signature for.
            Text(
                text = "客户端版本",
                style = MaterialTheme.typography.labelLarge,
            )
            VersionSettings(
                version = clientVersion,
                platform = clientPlatform,
                onChoose = ::saveVersion,
            )

        }

        // ── 语音 ──────────────────────────────────────────────────────────
        Section("语音") {
            ToggleRow(
                label = "连接时静音",
                checked = startMuted,
                onChange = {
                    startMuted = it
                    settings.startMuted = it
                },
            )
            ToggleRow(
                label = "麦克风降噪",
                checked = noise,
                onChange = {
                    noise = it
                    vm.applyAudioSettings(gain, it, bluetooth)
                },
            )
            ToggleRow(
                label = "使用蓝牙耳机麦克风",
                hint = "通话期间其他声音音质下降；无耳机时用手机麦克风",
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
                    text = "超过 1.0× 会自动限幅，不破音；媒体音量需单独调",
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
            ToggleRow(
                label = "显示文件入口",
                checked = filesEnabled,
                onChange = { vm.setFilesTabEnabled(it) },
            )
        }

    }

    // The version is announced only in `clientinit`, so a change on a live
    // session needs a reconnect to take effect. Asking beats silently dropping
    // the call, and "取消" keeps the setting for the next connection.
    if (pendingReconnect) {
        AlertDialog(
            onDismissRequest = { pendingReconnect = false },
            title = { Text("版本改动") },
            text = { Text("重连后生效。现在重连？") },
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
 * The account section: login and account info.
 *
 * The identity is never a choice here and is not shown as one: the client
 * adopts it automatically. Bookmarks are managed from the home screen, not
 * here.
 */
@Composable
private fun AccountSection(
    account: AccountState,
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
    onLogout: () -> Unit,
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
                    if (account.username.isNotEmpty() && account.email.isNotEmpty()) {
                        Text(
                            text = account.email,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // The row is already tall enough for the buttons, so progress can
            // appear beside them without changing the card's height.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = onLogout, enabled = !busy) { Text("退出登录") }
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        text = account.progress.ifEmpty { "正在${commandLabel(account.command)}…" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

        } else {
            // Not signed in: the login form.
            Text(
                text = "登录后可读取云端书签，连接时展示 myTeamSpeak 身份。",
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
            Button(
                onClick = onLogin,
                enabled = email.isNotBlank() && password.isNotBlank() && !busy,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    )
                }
                Text(
                    text = if (busy) {
                        account.progress.ifEmpty { "正在${commandLabel(account.command)}…" }
                    } else {
                        "登录"
                    },
                    modifier = Modifier.padding(start = if (busy) 8.dp else 0.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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



private fun commandLabel(command: String): String = when (command) {
    "account_login" -> "登录"
    "account_logout" -> "退出登录"
    "account_resume" -> "恢复登录"
    "account_refresh" -> "刷新"
    "identity_pick" -> "选择身份"
    "sync_list" -> "读取书签"
    "sync_backup" -> "备份"
    "sync_bookmark_create" -> "创建书签"
    "sync_bookmark_edit" -> "编辑书签"
    "sync_bookmark_move" -> "调整书签顺序"
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
                text = "在系统设置中允许",
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
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    hint: String? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            if (hint != null) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
