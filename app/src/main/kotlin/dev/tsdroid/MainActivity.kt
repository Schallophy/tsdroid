package dev.tsdroid

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
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
    val accountError by session.accountError.collectAsStateWithLifecycle()
    val navTab by session.navTab.collectAsStateWithLifecycle()
    val filesEnabled = session.filesTabEnabled

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
                        onConnect = { config ->
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
                            val bookmark = row.bookmark
                            if (bookmark != null) {
                                // Bookmarks carry their own passwords from the cloud
                                // sync. Resolve them synchronously from the cache the
                                // account client filled after the last login/refresh.
                                val secrets = session.accountBookmarkSecrets(row.uuid)
                                val missing = (bookmark.hasServerPassword && secrets?.serverPassword.isNullOrEmpty()) ||
                                    (bookmark.hasChannelPassword && secrets?.channelPassword.isNullOrEmpty())
                                val config = ServerConfig(
                                    address = bookmark.connectAddress,
                                    nickname = bookmark.nickname.ifBlank { account.username },
                                    password = secrets?.serverPassword.orEmpty(),
                                    channel = bookmark.defaultChannel,
                                    channelPassword = secrets?.channelPassword.orEmpty(),
                                    identityIni = session.serverStore().readIdentity(),
                                    announceMyts = bookmark.sendMytsid,
                                    version = session.settings().versionProfile,
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
                    )
                }

                dev.tsdroid.NavTab.Settings -> SettingsScreen(
                    vm = session,
                    accountError = accountError,
                    onBack = { session.selectNavTab(dev.tsdroid.NavTab.Home) },
                )

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
                        text = "这台服务器要求输入权限密钥（启动日志里的 token=…）才会授予分组。",
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
}

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
            text = "在首页填写服务器地址连接，或从书签中选一个。",
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
