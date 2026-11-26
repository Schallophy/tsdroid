package dev.tsdroid

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.Context
import android.os.Build
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.tsdroid.core.ServerConfig
import dev.tsdroid.data.withAnnouncedVersion
import dev.tsdroid.ui.HomeScreen
import dev.tsdroid.ui.MicBar
import dev.tsdroid.ui.SessionScreen
import dev.tsdroid.ui.SettingsScreen
import dev.tsdroid.ui.components.BottomNav
import dev.tsdroid.ui.theme.TSDroidTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            TSDroidTheme { App() }
        }
    }
}

/**
 * One shell, five destinations.
 *
 * The bottom navigation bar is the spine: every screen is one tap away, and the
 * bar never moves. The microphone bar stacks directly above it when a session
 * is live — two fixed layers at the bottom, the content scrolls above them.
 *
 * The state machine is still the session's: the connection status decides what
 * the session tabs show (live content or a "connect first" prompt), while Home
 * and Settings exist with or without a connection.
 */
@Composable
private fun App() {
    val context = LocalContext.current
    val view = LocalView.current
    val session: SessionViewModel = viewModel()
    val ui by session.ui.collectAsStateWithLifecycle()
    val account by session.accountState.collectAsStateWithLifecycle()
    val accountSettled by session.accountSettled.collectAsStateWithLifecycle()
    val bookmarksFresh by session.bookmarksFresh.collectAsStateWithLifecycle()
    val bookmarkOrderReset by session.bookmarkOrderReset.collectAsStateWithLifecycle()
    val accountError by session.accountError.collectAsStateWithLifecycle()
    val navTab by session.navTab.collectAsStateWithLifecycle()
    val filesEnabled by session.filesTabEnabled.collectAsStateWithLifecycle()

    // The manifest declares `configChanges="…|uiMode"`, so switching the system
    // theme does not recreate the Activity — `enableEdgeToEdge()` therefore never
    // re-runs, and the status-bar icons would keep the old contrast. Setting the
    // appearance here is what makes them follow the theme live.
    val dark = isSystemInDarkTheme()
    SideEffect {
        val window = context.findActivity()?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    var tokenPromptAddress by remember { mutableStateOf<String?>(null) }
    var editingBookmark by remember { mutableStateOf<dev.tsdroid.model.AccountRow?>(null) }
    var deletingBookmark by remember { mutableStateOf<dev.tsdroid.model.AccountRow?>(null) }
    var tokenText by remember { mutableStateOf("") }

    // A skipped prompt is remembered for that connection only: leaving the
    // server forgets it, so the next attempt asks again.
    LaunchedEffect(ui.status) {
        if (ui.status == SessionStatus.Idle) tokenPromptAddress = null
    }

    fun hasMicPermission(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    var pendingConfig by rememberSaveable { mutableStateOf<ServerConfig?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val config = pendingConfig
        pendingConfig = null
        if (!granted) {
            session.setToast("没有录音权限，语音功能将不可用")
        }
        config?.let { session.adoptAccountIdentityAndConnect(it) }
    }

    // Android 13+ hides the ongoing call notification without this. It is asked
    // once before the first connect, because that is when the notification
    // starts to exist; a refusal does not block the call, it just means no
    // notification.
    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { /* either way, the session proceeds */ }

    fun askNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // Ask once, before the socket exists: the bridge cannot open the microphone
    // later without it, and a prompt in the middle of a call is worse than one here.
    LaunchedEffect(Unit) {
        if (!hasMicPermission()) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val snackbar = remember { SnackbarHostState() }
    val micLevel by session.micLevel.collectAsStateWithLifecycle()

    LaunchedEffect(ui.toast) {
        val message = ui.toast ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        session.clearToast()
    }

    val screenToast by session.toast.collectAsStateWithLifecycle()
    LaunchedEffect(screenToast) {
        val message = screenToast ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        session.clearToast()
    }

    // Account results are reported wherever the user happens to be. They used to
    // be collected on the settings tab only, so a bookmark the cloud refused was
    // silently reverted while the user was looking at the home screen.
    LaunchedEffect(Unit) {
        session.accountNotices.collect { notice ->
            val text = if (notice.ok) {
                // A background refresh is not worth a toast; everything the user
                // explicitly did is.
                when (notice.command) {
                    "account_login" -> "已登录 myTeamSpeak"
                    "account_logout" -> "已退出登录"
                    "sync_backup" -> notice.message.ifEmpty { "备份完成" }
                    else -> null
                }
            } else {
                when (notice.command) {
                    // Automatic reads have their own state: an offline launch
                    // must not greet the user with a toast.
                    "sync_list", "identity_pick", "account_resume" -> null
                    // The bookmark row already went back; the user needs to
                    // know why it did not stay changed, not the HTTP detail.
                    "sync_bookmark_create", "sync_bookmark_edit",
                    "sync_bookmark_move", "sync_delete",
                    -> "书签未能同步到云端，请检查网络后重试"
                    else -> notice.message.ifEmpty { "账号操作失败" }
                }
            }
            text?.let(session::setToast)
        }
    }

    LaunchedEffect(accountError) {
        accountError?.let(session::setToast)
    }

    // A one-time, first-success nudge: on ROMs that kill background processes
    // hard, an uninterrupted call depends on the app being exempt from battery
    // optimisation. It is offered as an action, not a forced redirect, and never
    // shown again once dismissed.
    LaunchedEffect(ui.status) {
        if (ui.status != SessionStatus.Online) return@LaunchedEffect
        val settings = session.settings()
        if (settings.batteryHintShown) return@LaunchedEffect
        settings.batteryHintShown = true
        val pm = context.getSystemService(android.os.PowerManager::class.java)
        if (pm != null && pm.isIgnoringBatteryOptimizations(context.packageName)) return@LaunchedEffect
        val result = snackbar.showSnackbar(
            message = "锁屏或切后台时可能被系统断开，建议允许后台运行",
            actionLabel = "去设置",
            withDismissAction = true,
            duration = androidx.compose.material3.SnackbarDuration.Long,
        )
        if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
            runCatching {
                context.startActivity(
                    Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:${context.packageName}"),
                    ),
                )
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            BottomNav(
                current = navTab,
                filesEnabled = filesEnabled,
                chatUnread = ui.unreadTotal,
                onSelect = { session.selectNavTab(it) },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Box(modifier = Modifier.weight(1f)) {
                when (navTab) {
                dev.tsdroid.NavTab.Home -> {
                    HomeScreen(
                        account = account,
                        accountSettled = accountSettled,
                        bookmarksFresh = bookmarksFresh,
                        bookmarkOrderReset = bookmarkOrderReset,
                        onConnect = { config ->
                            askNotificationPermissionOnce()
                            if (hasMicPermission()) {
                                // The account knows which identity this server was
                                // joined with; adopting it here is what brings the
                                // right UID (and its groups) back without asking
                                // the user.
                                session.adoptAccountIdentityAndConnect(config)
                            } else {
                                pendingConfig = config
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        onConnectBookmark = { row ->
                            askNotificationPermissionOnce()
                            val bookmark = row.bookmark
                            if (bookmark != null) {
                                // Bookmarks carry their own passwords from the cloud
                                // sync. Resolve them synchronously from the cache the
                                // account client filled after the last login/refresh.
                                val secrets = session.accountBookmarkSecrets(row.uuid)
                                val missing = (bookmark.hasServerPassword && secrets?.serverPassword.isNullOrEmpty()) ||
                                    (bookmark.hasChannelPassword && secrets?.channelPassword.isNullOrEmpty())
                                val settings = session.settings()
                                val config = settings.withAnnouncedVersion(
                                    ServerConfig(
                                        address = bookmark.connectAddress,
                                        nickname = bookmark.nickname.ifBlank { account.username },
                                        password = secrets?.serverPassword.orEmpty(),
                                        channel = bookmark.defaultChannel,
                                        channelPassword = secrets?.channelPassword.orEmpty(),
                                        identityIni = session.serverStore().readIdentity(),
                                        announceMyts = bookmark.sendMytsid,
                                    ),
                                )
                if (hasMicPermission()) {
                                    if (missing) {
                                        session.setToast("该书签的密码未能读取，请手动填写")
                                    }
                                    session.adoptAccountIdentityAndConnect(config)
                                } else {
                                    pendingConfig = config
                                    if (missing) {
                                        session.setToast("该书签的密码未能读取，请手动填写")
                                    }
                                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        },
                        onOpenSettings = { session.selectNavTab(dev.tsdroid.NavTab.Settings) },
                        onSaveBookmark = { name, address, nickname ->
                            val (host, port) = splitAddress(address)
                            session.accountCreateBookmark(name, host, port, nickname)
                        },
                        onEditBookmark = { row -> editingBookmark = row },
                        onDeleteBookmark = { row -> deletingBookmark = row },
                        onMoveBookmark = { row, after ->
                            session.accountMoveBookmark(row.uuid, after)
                        },
                    )
                }

                dev.tsdroid.NavTab.Settings -> SettingsScreen(vm = session)

                dev.tsdroid.NavTab.Channels,
                dev.tsdroid.NavTab.Chat,
                dev.tsdroid.NavTab.Files -> {
                    if (ui.status == SessionStatus.Idle) {
                        // The session tabs need a connection. Show the prompt
                        // instead of empty content.
                        ConnectFirstPrompt(onConnect = { session.selectNavTab(dev.tsdroid.NavTab.Home) })
                    } else {
                        SessionScreen(
                            vm = session,
                            onLeave = { session.leave() },
                            onOpenSettings = { session.selectNavTab(dev.tsdroid.NavTab.Settings) },
                        )
                    }
                }
            }
            }

            // The mic bar is stacked directly above the bottom nav, and it stays
            // there whenever a session is live — Home and Settings included, so
            // muting is never more than one tap away.
            if (ui.status != SessionStatus.Idle) {
                MicBar(
                    ui = ui,
                    level = micLevel,
                    onMic = session::toggleMic,
                    onSpeaker = session::toggleSpeaker,
                )
            }
        }
    }

    // The privilege-key dialog: it appears only when the server itself asks for
    // a token (`virtualserver_ask_for_privilegekey`), exactly like the official
    // client. Asking once per connection; a skip stays skipped until the next
    // server or a fresh connect.
    if (
        ui.status != SessionStatus.Idle &&
        ui.state.server.askPrivilegeKey &&
        ui.config?.address != tokenPromptAddress
    ) {
        AlertDialog(
            onDismissRequest = { tokenPromptAddress = ui.config?.address },
            title = { Text("服务器要求权限密钥") },
            text = {
                Column {
                    Text(
                        text = "启动日志里的 token=… 会授予对应分组。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = tokenText,
                        onValueChange = { tokenText = it },
                        label = { Text("权限密钥") },
                        singleLine = true,
                        modifier = Modifier.padding(top = 12.dp).fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        tokenPromptAddress = ui.config?.address
                        session.reconnectWithToken(tokenText.trim())
                        tokenText = ""
                    },
                    enabled = tokenText.isNotBlank(),
                ) { Text("兑换并重连") }
            },
            dismissButton = {
                TextButton(onClick = { tokenPromptAddress = ui.config?.address }) {
                    Text("跳过")
                }
            },
        )
    }

    // Edit a bookmark: the four fields the account lets us change. Everything
    // else (password, placement, profile) is preserved by the native side.
    editingBookmark?.let { row ->
        val bookmark = row.bookmark
        if (bookmark != null) {
            BookmarkEditDialog(
                initialName = bookmark.name,
                initialAddress = bookmark.connectAddress,
                initialNickname = bookmark.nickname,
                onDismiss = { editingBookmark = null },
                onConfirm = { name, address, nickname ->
                    val (host, port) = splitAddress(address)
                    session.accountEditBookmark(row.uuid, name, host, port, nickname)
                    editingBookmark = null
                },
            )
        }
    }

    deletingBookmark?.let { row ->
        AlertDialog(
            onDismissRequest = { deletingBookmark = null },
            title = { Text("删除书签？") },
            text = { Text(row.bookmark?.name?.ifEmpty { row.bookmark?.address } ?: "") },
            confirmButton = {
                TextButton(
                    onClick = {
                        session.accountDeleteBookmark(row.uuid)
                        deletingBookmark = null
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deletingBookmark = null }) { Text("取消") }
            },
        )
    }
}

/**
 * The bookmark editor: name, address, nickname.
 *
 * A dialog rather than a screen: it is three fields and it is always opened from
 * a specific row, so keeping the list behind it is the right context. The address
 * is one field (`host` or `host:port`); [splitAddress] turns it back into the two
 * values the account stores.
 */
@Composable
private fun BookmarkEditDialog(
    initialName: String,
    initialAddress: String,
    initialNickname: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String, address: String, nickname: String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var address by remember { mutableStateOf(initialAddress) }
    var nickname by remember { mutableStateOf(initialNickname) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑书签") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("地址") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = nickname,
                    onValueChange = { nickname = it },
                    label = { Text("昵称") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), address.trim(), nickname.trim()) },
                enabled = name.isNotBlank() && address.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * Splits `host`, `host:port` or `[v6]:port` into a host and a port.
 *
 * The account stores the two separately; the form shows one field because a
 * person types one. A missing port means the protocol default.
 */
private fun splitAddress(address: String): Pair<String, Int> {
    val text = address.trim()
    if (text.startsWith("[")) {
        val end = text.indexOf(']')
        if (end > 0) {
            val host = text.substring(1, end)
            val port = text.substring(end + 1).removePrefix(":").toIntOrNull() ?: DEFAULT_PORT
            return host to port
        }
    }
    val colon = text.lastIndexOf(':')
    if (colon > 0 && text.indexOf(':') == colon) {
        val port = text.substring(colon + 1).toIntOrNull()
        if (port != null && port in 1..65535) {
            return text.substring(0, colon) to port
        }
    }
    return text to DEFAULT_PORT
}

private const val DEFAULT_PORT = 9987

/**
 * Shown when a session tab is selected but no server is connected.
 *
 * The bottom nav is always there, so the user can tap it without thinking —
 * this state catches that and points them at the home screen instead of showing
 * an empty channel list.
 */
@Composable
private fun ConnectFirstPrompt(onConnect: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
    ) {
        Text(
            text = "还没有连接服务器",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "填写服务器地址，或从书签选一个。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 4.dp),
        )
        TextButton(onClick = onConnect, modifier = Modifier.padding(top = 16.dp)) {
            Text("去首页")
        }
    }
}

/** Walks the context chain: `setContent` hands out a themed wrapper, not the Activity. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
