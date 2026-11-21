package dev.tsdroid

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.tsdroid.core.ServerConfig
import dev.tsdroid.ui.ConnectScreen
import dev.tsdroid.ui.SessionScreen
import dev.tsdroid.ui.SettingsScreen
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
 * Three screens, one state machine: no navigation library for something this
 * shallow — the session decides which screen exists.
 */
@Composable
private fun App() {
    val context = LocalContext.current
    val view = LocalView.current
    val session: SessionViewModel = viewModel()
    val ui by session.ui.collectAsStateWithLifecycle()

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

    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var pendingConfig by remember { mutableStateOf<ServerConfig?>(null) }
    var crashLog by remember { mutableStateOf<String?>(null) }

    // Read once, before anything else can fail again: the trace of the previous
    // run is the only evidence a field crash leaves behind.
    LaunchedEffect(Unit) {
        crashLog = CrashLog.read(context)
    }

    crashLog?.let { trace ->
        CrashReportDialog(
            trace = trace,
            onCopy = {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard?.setPrimaryClip(ClipData.newPlainText("crash", trace))
                CrashLog.clear(context)
                crashLog = null
            },
            onDismiss = { crashLog = null },
        )
    }

    fun hasMicPermission(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val config = pendingConfig
        pendingConfig = null
        if (!granted) {
            session.setToast("没有录音权限，语音功能将不可用")
        }
        config?.let(session::connect)
    }

    // Ask once, before the socket exists: the bridge cannot open the microphone
    // later without it, and a prompt in the middle of a call is worse than one here.
    LaunchedEffect(Unit) {
        if (!hasMicPermission()) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    when {
        ui.status == SessionStatus.Idle -> ConnectScreen(
            onConnect = { config ->
                if (hasMicPermission()) {
                    session.connect(config)
                } else {
                    pendingConfig = config
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
        )

        settingsOpen -> {
            BackHandler { settingsOpen = false }
            SettingsScreen(vm = session, onBack = { settingsOpen = false })
        }

        else -> {
            BackHandler { session.leave() }
            SessionScreen(
                vm = session,
                onOpenSettings = { settingsOpen = true },
                onLeave = { session.leave() },
            )
        }
    }
}

/**
 * Shows the previous run's stack trace.
 *
 * Selectable rather than a plain `Text` so it can be copied by hand, and with a
 * one-tap copy button because the usual next step is pasting it into a chat.
 */
@Composable
private fun CrashReportDialog(trace: String, onCopy: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("上次运行崩溃了") },
        text = {
            SelectionContainer {
                Text(
                    text = trace,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = androidx.compose.ui.Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = { TextButton(onClick = onCopy) { Text("复制并清除") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/** Walks the context chain: `setContent` hands out a themed wrapper, not the Activity. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
