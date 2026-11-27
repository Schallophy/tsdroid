package dev.tsdroid

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.PowerManager
import android.net.Uri
import android.provider.OpenableColumns
import dev.tsdroid.core.AccountClient
import dev.tsdroid.core.AccountNotice
import dev.tsdroid.core.AudioBridge
import dev.tsdroid.core.NativeClient
import dev.tsdroid.core.ServerConfig
import dev.tsdroid.data.AudioFocusAction
import dev.tsdroid.data.ServerStore
import dev.tsdroid.data.Settings
import dev.tsdroid.data.withAnnouncedVersion
import dev.tsdroid.model.AccountState
import dev.tsdroid.model.BookmarkSecrets
import dev.tsdroid.model.ChatMessage
import dev.tsdroid.model.ChatScope
import dev.tsdroid.model.FileEntry
import dev.tsdroid.model.PermissionEditorState
import dev.tsdroid.model.PermissionEntry
import dev.tsdroid.model.PermissionTarget
import dev.tsdroid.model.PermissionValue
import dev.tsdroid.model.ServerEvent
import dev.tsdroid.model.Transfer
import dev.tsdroid.model.TransferDirection
import dev.tsdroid.model.TransferState
import dev.tsdroid.model.User
import androidx.compose.runtime.getValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns one live session: the native client, the audio bridge, and the state the
 * UI renders.
 *
 * Kept out of the ViewModel so its lifetime is not tied to a screen. It is
 * created and held by [SessionService]; the ViewModel only forwards to it, which
 * is what lets a connection survive rotation, backgrounding and a lock screen.
 */
class SessionController(
    private val app: Application,
    private val settings: Settings,
    private val servers: ServerStore,
    private val account: AccountClient,
) {

    /** Owns the polling and event collectors for the life of the session. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** One identity lookup at a time: a second tap must not tear down the first connect. */
    private val adopting = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * The volume last sent to the core for each UID, with the client id it was
     * sent for. A reconnect gives the same person a new `clid`, so the pair has
     * to change before the value is re-sent.
     */
    private val appliedVolumes = mutableMapOf<String, Pair<Int, Float>>()

    /**
     * Called once when the session is over (a leave, or a disconnect the user did
     * not start). The service uses it to stop itself; the UI does not care.
     */
    var onSessionEnded: (() -> Unit)? = null

    /** Called whenever something the notification shows (mic, speaker) changes. */
    var onStateChanged: (() -> Unit)? = null

    /**
     * Asks the UI to switch bottom-navigation destination.
     *
     * The tab is UI state and lives on the ViewModel; the controller only says
     * where a session event wants it to go (a connect lands on channels, a
     * private chat jumps to chat). Keeping it here would tie the navigation bar
     * to a live session, which is why the bar did nothing before the first
     * connect.
     */
    var onNavigate: ((NavTab) -> Unit)? = null

    private val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var focusRequest: AudioFocusRequest? = null
    private var focusListener: AudioManager.OnAudioFocusChangeListener? = null

    /** Set while another app holds focus and our chosen action muted us. */
    private var micMutedByFocus = false
    private var speakerPausedByFocus = false

    private val audio = AudioBridge(app)

    // The account is app-scoped and handed in: it can be signed in before any
    // server is joined, and its handle rides into the connection so the myTS ID
    // is announced in `clientinit`.

    val accountState: StateFlow<AccountState> = account.state
    val accountError: StateFlow<String?> = account.startError
    val accountNotices: SharedFlow<AccountNotice> = account.notices

    private var client: NativeClient? = null
    private val messageKey = AtomicLong(0)

    /** Set while the goodbye is in flight; the UI waits for it to land. */
    private var goodbyePending = false

    /**
     * The config to connect with once the goodbye lands, or null for a plain
     * leave.
     *
     * A reconnect cannot just tear the session down and build a new one: the
     * old session has to say goodbye first, or the server keeps it listed until
     * its timeout. Both the event path ([applyEvent]'s `Disconnected` branch) and
     * the timeout path (the grace delay) check this, so whichever arrives first
     * performs the reconnect exactly once.
     */
    private var reconnectAfterGoodbye: ServerConfig? = null

    private val _ui = MutableStateFlow(SessionUiState())
    val ui: StateFlow<SessionUiState> = _ui.asStateFlow()

    private val _permissions = MutableStateFlow(PermissionEditorState())
    val permissions: StateFlow<PermissionEditorState> = _permissions.asStateFlow()

    /**
     * The bottom-navigation destination.
     *
     * Kept in the ViewModel rather than in a composable so it survives
     * recomposition and rotation: tapping a tab is a decision that should not
     * be lost when the screen redraws.
     */

    val micLevel = audio.level
    val audioError = audio.error

    init {
        audio.micGain = settings.micGain
        audio.outputGain = settings.outputGain
        audio.noiseSuppressionEnabled = settings.noiseSuppression
        audio.bluetoothMicEnabled = settings.bluetoothMic
    }

    // ── session lifecycle ───────────────────────────────────────────────────

    fun connect(config: ServerConfig) {
        goodbyePending = false
        reconnectAfterGoodbye = null
        appliedVolumes.clear()
        cleanupSession()
        acquireWakeLock()
        requestAudioFocus()
        // A connection starts muted unless the user turned that off: joining is
        // not consent to be heard. The mute travels inside the config (see
        // `withAnnouncedVersion`), because it has to be declared in
        // `clientinit` — correcting it afterwards does not reach the server's
        // record of this client.
        val startMuted = config.startMuted
        audio.micEnabled = !startMuted
        _ui.value = SessionUiState(
            status = SessionStatus.Connecting,
            config = config,
            micEnabled = !startMuted,
        )

        val session = NativeClient(
            config,
            if (config.announceMyts) account.handle else 0L,
        ) { identity -> servers.writeIdentity(identity) }
        val started = try {
            session.start()
        } catch (t: Throwable) {
            // Last line of defence: connect runs on the main thread, so anything
            // thrown here would take the whole process down instead of showing.
            session.startError = "启动失败：${t::class.java.simpleName}: ${t.message}"
            false
        }
        if (!started) {
            _ui.value = _ui.value.copy(
                status = SessionStatus.Failed,
                lastError = session.startError ?: "无法建立连接",
            )
            return
        }
        client = session
        // Gain belongs to the core so every platform clips the same way.
        session.setMicGain(settings.micGain)

        // A successful connection lands on the channel list: that is where the
        // user expects to see people and where the mic bar's context lives.
        selectTab(SessionTab.Channels)
        onNavigate?.invoke(NavTab.Channels)

        scope.launch {
            session.state.collect { state ->
                applyUserVolumes(state.users)
                _ui.update { current ->
                    current.copy(
                        state = state,
                        status = if (state.connected) SessionStatus.Online else current.status,
                        // Mirror the server-side flags, so a mute set from another
                        // device shows up here instead of drifting out of sync.
                        // Deliberately the mute button only: the device flags are
                        // not something this button can change, so folding them in
                        // would make a tap claim "on" and the next snapshot take it
                        // back. The bar combines them when it renders.
                        micEnabled = if (state.ownClientId != null) !state.inputMuted else current.micEnabled,
                        speakerEnabled = if (state.ownClientId != null) !state.outputMuted else current.speakerEnabled,
                        peer = current.peer?.let { state.user(it.id) },
                    )
                }
            }
        }

        scope.launch {
            session.events.collect { event ->
                // One malformed event must not end the collector: losing the
                // event stream is silent, a crash is at least visible.
                try {
                    applyEvent(event)
                } catch (t: Throwable) {
                    _ui.update { it.copy(toast = "事件处理失败：${t.message ?: t::class.java.simpleName}") }
                }
            }
        }

        scope.launch {
            // Voice starts only once the socket is up; the timeout keeps this
            // coroutine from waiting forever on a session that never connects.
            val connected = withTimeoutOrNull(30_000) {
                session.state.first { it.connected }
            } != null
            if (connected) {
                try {
                    withContext(Dispatchers.IO) { audio.start(session) }
                    if (_ui.value.filesChannelId == null) {
                        _ui.value.state.ownChannelId?.let { openFiles(it) }
                    }
                } catch (t: Throwable) {
                    // Audio is a side channel: a failure here degrades to text
                    // chat, it must not tear down a working connection.
                    _ui.update {
                        it.copy(toast = "语音启动失败：${t::class.java.simpleName}: ${t.message}")
                    }
                }
            }
        }
    }

    /**
     * Tears the session down right away. Only for paths where no goodbye is
     * expected — a failed connect, or a client that never existed.
     */
    private fun cleanupSession() {
        audio.stop()
        client?.close()
        client = null
        _permissions.value = PermissionEditorState()
    }

    /**
     * Leaves through the front door: say goodbye, wait for the server to drop
     * us, then land back on the connect screen.
     *
     * The client is kept alive during the wait — closing it immediately would
     * cancel the goodbye write and the server would list us until its timeout.
     * The native side gives up after a second, and this grace here is the
     * safety net in case even that never produces a [ServerEvent.Disconnected].
     */
    fun leave() {
        // A leave cancels any pending reconnect: the user asked to go back, not
        // to come straight back to the same server.
        reconnectAfterGoodbye = null
        if (client == null) {
            _ui.value = SessionUiState()
            onNavigate?.invoke(NavTab.Home)
            onSessionEnded?.invoke()
            return
        }
        goodbyePending = true
        client?.disconnect()
        scope.launch {
            delay(GOODBYE_GRACE_MS)
            if (goodbyePending) {
                goodbyePending = false
                cleanupSession()
                _ui.value = SessionUiState()
                onNavigate?.invoke(NavTab.Home)
                onSessionEnded?.invoke()
            }
        }
    }

    /** Same action as [leave]; the header button reads "disconnect". */
    fun disconnect() = leave()

    /** True when a session exists that a reconnect would replace. */
    fun isLive(): Boolean = client != null

    /**
     * Reconnects now, after a setting that only applies to a fresh connection
     * changed (the announced client version).
     *
     * Two things the naive "call connect(config)" would get wrong. First, the
     * config is rebuilt from the current settings, because the stored one still
     * carries the version the session started with — reusing it would reconnect
     * with the old version and the change would look like it never happened.
     * Second, the old session is told to say goodbye before its handle is
     * dropped: destroying the handle without a `clientdisconnect` leaves the
     * client listed on the server until its timeout, which makes the reconnect
     * look like the original connection never left.
     */
    fun reconnectNow() {
        val config = _ui.value.config ?: return
        val fresh = settings.withAnnouncedVersion(config)
        if (client == null) {
            connect(fresh)
            return
        }
        goodbyePending = true
        reconnectAfterGoodbye = fresh
        client?.disconnect()
        scope.launch {
            delay(GOODBYE_GRACE_MS)
            // The server may answer the goodbye first, in which case the event
            // path already reconnected and this is a no-op.
            if (goodbyePending) {
                goodbyePending = false
                cleanupSession()
                val pending = reconnectAfterGoodbye
                reconnectAfterGoodbye = null
                if (pending != null) connect(pending)
            }
        }
    }

    /**
     * Retries the last attempt with the same settings.
     *
     * A failed connection starts no connection thread, so there is nothing to
     * tear down first beyond what [connect] already does.
     */
    fun reconnect() {
        val config = _ui.value.config ?: return
        connect(config)
    }

    /**
     * Reconnects with a privilege key the server asked for.
     *
     * The key is single-use and never stored: it rides into `clientinit` on the
     * new connection and is gone from memory when that connection ends. This is
     * what the official client's token dialog does after the server reports
     * `ask_for_privilegekey`.
     */
    fun reconnectWithToken(token: String) {
        val config = _ui.value.config ?: return
        connect(config.copy(token = token.trim()))
    }

    /** Stops the session: audio, the native client and the collectors. */
    fun shutdown() {
        goodbyePending = false
        releaseWakeLock()
        abandonAudioFocus()
        audio.stop()
        client?.close()
        client = null
        scope.cancel()
    }

    // ── background keep-alive ───────────────────────────────────────────────

    /**
     * Holds a partial wake lock while a session exists.
     *
     * The foreground service keeps the process alive; it does not keep the CPU
     * awake. On a locked screen the system would otherwise let the 20 ms audio
     * tick drift, which shows up as stuttering audio. Released in [shutdown].
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val manager = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tsdroid:voice").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    /**
     * Asks for voice-communication audio focus and reacts to losing it.
     *
     * A phone call taking focus must not leave our microphone sending to the
     * server. What happens then is the user's choice in settings; the default is
     * to mute our mic and pause the others' audio, restoring both when focus
     * comes back.
     */
    private fun requestAudioFocus() {
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> onFocusLost()
                AudioManager.AUDIOFOCUS_GAIN -> onFocusGained()
            }
        }
        focusListener = listener
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener(listener)
            .build()
        focusRequest = request
        audioManager.requestAudioFocus(request)
    }

    private fun abandonAudioFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
        focusListener = null
        micMutedByFocus = false
        speakerPausedByFocus = false
    }

    private fun onFocusLost() {
        when (settings.audioFocusAction) {
            AudioFocusAction.IGNORE -> Unit
            AudioFocusAction.DISCONNECT -> leave()
            AudioFocusAction.MUTE_SELF -> {
                if (_ui.value.micEnabled) {
                    micMutedByFocus = true
                    toggleMic()
                }
            }
            else -> {
                if (_ui.value.micEnabled) {
                    micMutedByFocus = true
                    toggleMic()
                }
                if (_ui.value.speakerEnabled) {
                    speakerPausedByFocus = true
                    toggleSpeaker()
                }
            }
        }
    }

    private fun onFocusGained() {
        if (micMutedByFocus) {
            micMutedByFocus = false
            if (!_ui.value.micEnabled) toggleMic()
        }
        if (speakerPausedByFocus) {
            speakerPausedByFocus = false
            if (!_ui.value.speakerEnabled) toggleSpeaker()
        }
    }

    // ── commands ────────────────────────────────────────────────────────────

    fun selectTab(tab: SessionTab) {
        _ui.update {
            it.copy(
                tab = tab,
                unreadChannel = if (tab == SessionTab.Chat && it.scope == ChatScope.Channel) 0 else it.unreadChannel,
                unreadServer = if (tab == SessionTab.Chat && it.scope == ChatScope.Server) 0 else it.unreadServer,
                unreadPrivate = if (tab == SessionTab.Chat && it.scope == ChatScope.Private) emptyMap() else it.unreadPrivate,
            )
        }
    }

    /**
     * Switches the bottom-navigation destination.
     *
     * Selecting a session tab also selects the matching [SessionTab], so the
     * two stay in step: the bar is the entry point, the in-session pills are
     * not needed once the bar exists.
     */
    fun selectNavTab(tab: NavTab) {
        onNavigate?.invoke(tab)
        if (tab == NavTab.Channels) selectTab(SessionTab.Channels)
        else if (tab == NavTab.Chat) selectTab(SessionTab.Chat)
        else if (tab == NavTab.Files) selectTab(SessionTab.Files)
    }

    fun selectScope(scope: ChatScope) {
        _ui.update {
            it.copy(
                scope = scope,
                peer = if (scope == ChatScope.Private) it.peer else null,
                unreadChannel = if (scope == ChatScope.Channel) 0 else it.unreadChannel,
                unreadServer = if (scope == ChatScope.Server) 0 else it.unreadServer,
                unreadPrivate = if (scope == ChatScope.Private) emptyMap() else it.unreadPrivate,
            )
        }
    }

    fun openPrivateChat(user: User) {
        _ui.update {
            it.copy(
                tab = SessionTab.Chat,
                scope = ChatScope.Private,
                peer = user,
                unreadPrivate = it.unreadPrivate - user.id,
            )
        }
        onNavigate?.invoke(NavTab.Chat)
    }

    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val client = client ?: return
        val current = _ui.value
        val scope = current.scope
        val peer = current.peer

        client.sendText(
            scope = scope,
            message = trimmed,
            clientId = if (scope == ChatScope.Private) peer?.id ?: 0 else 0,
        )

        _ui.update {
            it.copy(
                messages = (it.messages + ChatMessage(
                    key = messageKey.incrementAndGet(),
                    scope = scope,
                    fromId = it.state.ownClientId ?: 0,
                    fromName = it.state.nickname,
                    text = trimmed,
                    timestamp = System.currentTimeMillis(),
                    mine = true,
                    peerId = if (scope == ChatScope.Private) peer?.id ?: 0 else 0,
                )).takeLast(MAX_MESSAGES),
            )
        }
    }

    fun poke(user: User, message: String) {
        client?.poke(user.id, message.ifBlank { "戳了你一下" })
        setToast("已戳 ${user.nickname}")
    }

    /** The stored playback volume for one client, applied by [applyUserVolumes]. */
    fun userVolume(user: User): Float = settings.userVolume(user.uid)

    fun setUserVolume(user: User, volume: Float) {
        val applied = volume.coerceIn(0.0f, 2.0f)
        settings.setUserVolume(user.uid, applied)
        client?.setUserVolume(user.id, applied)
        appliedVolumes[volumeKey(user)] = user.id to applied
    }

    fun isWhisperingTo(user: User): Boolean =
        user.id in _ui.value.state.whisperClients

    /**
     * Add or remove one client from the whisper set. The command replaces the
     * whole set, so the current snapshot is the baseline; the core echoes the
     * accepted set back in the next snapshot, which is what the sheet reads.
     */
    fun setWhisperTo(user: User, enabled: Boolean) {
        val current = _ui.value.state
        val clients = current.whisperClients.toMutableSet()
        if (enabled) clients.add(user.id) else clients.remove(user.id)
        client?.setWhisperTargets(
            clients.toList(),
            current.whisperChannels,
            current.whisperGroups,
        )
    }

    fun setWhisperTargets(
        clients: List<Int>,
        channels: List<Long>,
        groups: List<dev.tsdroid.model.WhisperGroup>,
    ) {
        client?.setWhisperTargets(clients, channels, groups)
        setToast(
            if (clients.isEmpty() && channels.isEmpty() && groups.isEmpty()) {
                "已关闭悄悄话"
            } else {
                "悄悄话目标已更新"
            },
        )
    }

    fun loadPermissions(target: PermissionTarget) {
        _permissions.update {
            it.copy(target = target, values = emptyList(), loading = true)
        }
        if (_permissions.value.catalog.isEmpty()) {
            client?.permissionCatalog()
        }
        requestPermissionList(target)
    }

    fun addPermission(target: PermissionTarget, name: String, value: Int) {
        _permissions.update { it.copy(loading = true, values = emptyList()) }
        when (target.kind) {
            "channel" -> client?.channelAddPermission(target.id, name, value)
            "server_group" -> client?.serverGroupAddPermission(target.id, name, value)
            "channel_group" -> client?.channelGroupAddPermission(target.id, name, value)
            "client" -> client?.clientAddPermission(target.id, name, value)
            "channel_client" -> client?.channelClientAddPermission(
                target.secondaryId,
                target.id,
                name,
                value,
            )
        }
        requestPermissionList(target)
    }

    fun deletePermission(target: PermissionTarget, name: String) {
        _permissions.update { it.copy(loading = true, values = emptyList()) }
        when (target.kind) {
            "channel" -> client?.channelDeletePermission(target.id, name)
            "server_group" -> client?.serverGroupDeletePermission(target.id, name)
            "channel_group" -> client?.channelGroupDeletePermission(target.id, name)
            "client" -> client?.clientDeletePermission(target.id, name)
            "channel_client" -> client?.channelClientDeletePermission(
                target.secondaryId,
                target.id,
                name,
            )
        }
        requestPermissionList(target)
    }

    private fun requestPermissionList(target: PermissionTarget) {
        when (target.kind) {
            "channel" -> client?.channelPermissionList(target.id)
            "server_group" -> client?.serverGroupPermissionList(target.id)
            "channel_group" -> client?.channelGroupPermissionList(target.id)
            "client" -> client?.clientPermissionList(target.id)
            "channel_client" -> client?.channelClientPermissionList(
                target.secondaryId,
                target.id,
            )
        }
    }

    private fun applyPermissionEvent(event: ServerEvent) {
        when (event) {
            is ServerEvent.PermListed -> _permissions.update { state ->
                if (state.catalog.any { it.id == event.permissionId }) {
                    state
                } else {
                    state.copy(
                        catalog = state.catalog + PermissionEntry(
                            id = event.permissionId,
                            name = event.name,
                            description = event.description,
                        ),
                    )
                }
            }

            is ServerEvent.ChannelPermission -> _permissions.update { state ->
                if (state.target?.kind != "channel" || state.target.id != event.channelId) {
                    return@update state
                }
                state.withValue(
                    PermissionValue(
                        name = event.name,
                        value = event.value,
                        negated = event.negated,
                        skip = event.skip,
                    ),
                )
            }

            is ServerEvent.GroupPermission -> _permissions.update { state ->
                val targetKind = when (event.groupKind) {
                    "server" -> "server_group"
                    "channel" -> "channel_group"
                    else -> return@update state
                }
                if (state.target?.kind != targetKind || state.target.id != event.groupId) {
                    return@update state
                }
                state.withValue(
                    PermissionValue(
                        name = event.name,
                        value = event.value,
                        negated = event.negated,
                        skip = event.skip,
                    ),
                )
            }

            is ServerEvent.ClientPermission -> _permissions.update { state ->
                if (state.target?.kind != "client" || state.target.id != event.clientDbId) {
                    return@update state
                }
                state.withValue(
                    PermissionValue(
                        name = event.name,
                        value = event.value,
                        negated = event.negated,
                        skip = event.skip,
                    ),
                )
            }

            is ServerEvent.ChannelPermissionListDone -> _permissions.update {
                if (it.target?.kind == "channel") it.copy(loading = false) else it
            }

            is ServerEvent.GroupPermissionListDone -> _permissions.update {
                val targetKind = when (event.groupKind) {
                    "server" -> "server_group"
                    "channel" -> "channel_group"
                    else -> return@update it
                }
                if (it.target?.kind == targetKind) it.copy(loading = false) else it
            }

            is ServerEvent.ClientPermissionListDone -> _permissions.update {
                if (it.target?.kind == "client") it.copy(loading = false) else it
            }

            is ServerEvent.ChannelClientPermission -> _permissions.update { state ->
                val target = state.target
                if (target?.kind != "channel_client" ||
                    target.id != event.clientDbId ||
                    target.secondaryId != event.channelId
                ) {
                    return@update state
                }
                state.withValue(
                    PermissionValue(
                        name = event.name,
                        value = event.value,
                        negated = event.negated,
                        skip = event.skip,
                    ),
                )
            }

            is ServerEvent.ChannelClientPermissionListDone -> _permissions.update {
                if (it.target?.kind == "channel_client") it.copy(loading = false) else it
            }

            else -> Unit
        }
    }

    private fun dev.tsdroid.model.PermissionEditorState.withValue(
        value: PermissionValue,
    ): dev.tsdroid.model.PermissionEditorState {
        val values = this.values.toMutableList()
        val index = values.indexOfFirst { it.name == value.name }
        if (index >= 0) values[index] = value else values.add(value)
        return copy(values = values.sortedBy { it.name })
    }

    private fun volumeKey(user: User): String =
        user.uid.ifEmpty { "id:${user.id}" }

    /**
     * Push a stored volume to the core when a client appears, reconnects under a
     * new `clid`, or the user moved the slider. The state collector calls this
     * on every snapshot; the map makes it a no-op when nothing changed.
     */
    private fun applyUserVolumes(users: List<User>) {
        val session = client ?: return
        for (user in users) {
            val key = volumeKey(user)
            val current = appliedVolumes[key]
            if (current?.first == user.id) continue
            val desired = settings.userVolume(user.uid)
            session.setUserVolume(user.id, desired)
            appliedVolumes[key] = user.id to desired
        }
    }

    fun joinChannel(channelId: Long, password: String? = null) {
        if (channelId == _ui.value.state.ownChannelId) return
        client?.joinChannel(channelId, password)
        // The file browser follows you: it always shows the channel you are in.
        _ui.update { it.copy(tab = SessionTab.Channels, filesChannelId = channelId, filesLoading = true) }
        onNavigate?.invoke(NavTab.Channels)
        client?.listFiles(channelId)
    }

    fun toggleMic() {
        val enabled = !_ui.value.micEnabled
        audio.micEnabled = enabled
        client?.setInputMuted(!enabled)
        _ui.update { it.copy(micEnabled = enabled) }
        onStateChanged?.invoke()
    }

    fun toggleSpeaker() {
        val enabled = !_ui.value.speakerEnabled
        audio.speakerEnabled = enabled
        client?.setOutputMuted(!enabled)
        _ui.update { it.copy(speakerEnabled = enabled) }
        onStateChanged?.invoke()
    }

    fun setAway(away: Boolean, message: String = "") {
        settings.awayMessage = message
        client?.setAway(away, message)
        setToast(if (away) "已切换为离开" else "已切换为在线")
    }

    fun setNickname(nickname: String) {
        if (nickname.isBlank()) return
        client?.setNickname(nickname.trim())
        setToast("昵称已更新")
    }

    fun editChannel(
        channelId: Long,
        name: String,
        topic: String,
        maxClients: Int,
        maxFamilyClients: Int,
        neededTalkPower: Int,
        codec: String,
        codecQuality: Int,
        codecLatencyFactor: Int,
        deleteDelay: Long,
        isDefault: Boolean,
        isPermanent: Boolean,
        isSemiPermanent: Boolean,
        password: String,
    ) {
        val properties = buildMap<String, Any?> {
            put("channel_codec", codecNumber(codec))
            put("channel_codec_quality", codecQuality.coerceIn(0, 10))
            put("channel_codec_latency_factor", codecLatencyFactor.coerceIn(1, 10))
            put("channel_needed_talk_power", neededTalkPower.coerceAtLeast(0))
            put("channel_delete_delay", deleteDelay.coerceAtLeast(0))
            put("channel_flag_default", isDefault)
            put("channel_flag_permanent", isPermanent)
            put("channel_flag_semi_permanent", isSemiPermanent)
            if (maxFamilyClients >= 0) {
                put("channel_maxfamilyclients", maxFamilyClients)
            } else {
                put("channel_flag_maxfamilyclients_unlimited", true)
            }
            if (maxClients >= 0) {
                put("channel_flag_maxclients_unlimited", false)
            } else {
                put("channel_flag_maxclients_unlimited", true)
            }
            if (password.isNotBlank()) {
                put("channel_password", password)
                put("channel_flag_password", true)
            }
        }
        val args = buildMap<String, Any?> {
            put("name", name.trim())
            put("topic", topic)
            if (maxClients >= 0) put("maxClients", maxClients)
            put("properties", properties)
        }
        client?.editChannel(channelId, args)
        setToast("频道设置已更新")
    }

    fun editServer(
        name: String,
        welcomeMessage: String,
        maxClients: Int,
        password: String,
        hostMessage: String,
        hostBannerUrl: String,
        neededSecurityLevel: Int,
    ) {
        val properties = buildMap<String, Any?> {
            if (password.isNotBlank()) {
                put("virtualserver_password", password)
                put("virtualserver_flag_password", true)
            }
            if (hostMessage.isNotBlank()) {
                put("virtualserver_hostmessage", hostMessage)
            }
            if (hostBannerUrl.isNotBlank()) {
                put("virtualserver_hostbanner_url", hostBannerUrl)
            }
            put(
                "virtualserver_needed_identity_security_level",
                neededSecurityLevel.coerceIn(0, 255),
            )
        }
        client?.editServer(
            mapOf(
                "name" to name.trim(),
                "welcomeMessage" to welcomeMessage,
                "maxClients" to maxClients,
                "properties" to properties,
            ),
        )
        setToast("服务器设置已更新")
    }

    private fun codecNumber(codec: String): Int = when (codec) {
        "speex_narrowband" -> 0
        "speex_wideband" -> 1
        "speex_ultrawideband" -> 2
        "celt_mono" -> 3
        "opus_voice" -> 4
        "opus_music" -> 5
        else -> 4
    }

    fun applyAudioSettings(gain: Float, noiseSuppression: Boolean, bluetoothMic: Boolean) {
        settings.micGain = gain
        settings.noiseSuppression = noiseSuppression
        settings.bluetoothMic = bluetoothMic
        audio.micGain = gain.coerceIn(0.2f, 4f)
        client?.setMicGain(audio.micGain)
        audio.noiseSuppressionEnabled = noiseSuppression
        audio.bluetoothMicEnabled = bluetoothMic
        audio.refreshRoute()
    }

    /** Playback boost belongs to this device, so it never round-trips the core. */
    fun applyOutputGain(gain: Float) {
        settings.outputGain = gain
        audio.outputGain = gain.coerceIn(0.5f, 2f)
    }

    fun settings(): Settings = settings

    fun serverStore(): ServerStore = servers

    // ── account ─────────────────────────────────────────────────────────────

    /**
     * A null return means the command was accepted; its result arrives through
     * [accountState] and [accountNotices]. A non-null return is an argument
     * error the caller can show without waiting for the worker.
     */
    fun accountLogin(email: String, password: String, otp: String = ""): String? =
        account.login(email.trim(), password, otp.trim())

    fun accountLogout(): String? = account.logout()

    fun accountBackup(): String? = account.backup()

    /**
     * The passwords cached after the last login/refresh, or null when the sync
     * has not produced them (yet, or because the account read failed).
     */
    fun accountBookmarkSecrets(uuid: String): BookmarkSecrets? =
        account.bookmarkSecrets(uuid)

    /** Deletes one bookmark from the cloud account. Null means accepted. */
    fun accountDeleteBookmark(uuid: String): String? = account.deleteBookmark(uuid)

    fun accountIdentities(): List<dev.tsdroid.model.AccountIdentity> = account.accountIdentities

    /**
     * Adopts the identity the account says this server knows, then connects.
     *
     * This is the automatic choice: the account's bookmark for this address
     * names the key the official client used, and when that is missing the
     * identity nickname matching the account name decides. When the account
     * cannot decide (several identities, none matching), the connection goes
     * ahead with whatever identity is already local and the caller is told why
     * the rights may be missing.
     *
     * The sign-in answer is always awaited first. On a cold start the bookmark
     * rows are drawn from the local cache before the remembered login has been
     * restored; connecting in that window would carry no account identity and
     * no myTS badge, which is exactly the wrong UID for the server's groups.
     * The wait is local — a file read — so it is short.
     */
    fun adoptAccountIdentityAndConnect(config: ServerConfig) {
        if (!adopting.compareAndSet(false, true)) return
        scope.launch {
            try {
                if (account.hasRememberedLogin() && !account.settled.value) {
                    setToast("正在恢复账号身份…")
                    if (!account.awaitSettled()) {
                        setToast("账号恢复超时，本次连接不带账号身份")
                    }
                }

                // A server already seen with a known identity costs no round
                // trip: the first successful connection stored the winner. If
                // the account's identity list is loaded, check it still
                // contains that key; a stale cache from an older build must not
                // keep shadowing the account.
                settings.identityForServer(config.address)?.let { known ->
                    val identities = account.accountIdentities
                    if (identities.isEmpty() || identities.any { it.value == known }) {
                        connect(config.copy(identityIni = known))
                        return@launch
                    }
                }

                if (!account.state.value.loggedIn) {
                    if (account.hasRememberedLogin()) {
                        setToast("账号没有恢复成功，本次连接不带账号身份")
                    }
                    connect(config)
                    return@launch
                }

                val picked = account.pickIdentityAndWait(config.address)
                if (picked.error != null) {
                    setToast("账号里选不出这台服务器用的身份：${picked.error}")
                    connect(config)
                    return@launch
                }
                settings.rememberIdentityForServer(config.address, picked.value)
                if (servers.readIdentity() != picked.value) {
                    servers.writeIdentity(picked.value)
                    val label = picked.nickname.ifEmpty { picked.name.ifEmpty { "默认" } }
                    setToast("已采用账号里的身份「$label」，这台服务器的权限会跟过来")
                }
                connect(config.copy(identityIni = picked.value))
            } finally {
                adopting.set(false)
            }
        }
    }

    /**
     * Writes the identity this app is using into the account so server groups
     * and admin rights survive a reinstall. Null means accepted; an error is
     * the immediate argument/session problem.
     */
    fun accountSyncIdentity(): String? {
        val identity = servers.readIdentity()
        if (identity.isBlank()) return "本机还没有身份"
        return account.importIdentity(identity)
    }

    /** Adopts one of the account's identities locally, so this install uses its UID. */
    fun useAccountIdentity(value: String) {
        servers.writeIdentity(value)
        setToast("已改用账号里的身份，下次连接生效")
    }

    fun accountHasRememberedLogin(): Boolean = account.hasRememberedLogin()

    // ── files ───────────────────────────────────────────────────────────────

    fun openFiles(channelId: Long) {
        _ui.update { it.copy(filesChannelId = channelId, filesLoading = true) }
        client?.listFiles(channelId)
    }

    fun refreshFiles() {
        val channelId = _ui.value.filesChannelId ?: _ui.value.state.ownChannelId ?: return
        _ui.update { it.copy(filesLoading = true) }
        client?.listFiles(channelId)
    }

    fun downloadFile(file: FileEntry) {
        val client = client ?: return
        val directory = app
            .getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
            ?: app.filesDir
        val target = File(directory, file.name)
        client.download(file.channelId, joinPath(file.path, file.name), target.absolutePath)
        setToast("开始下载 ${file.name}")
    }

    /** Copies the picked document into the cache dir, then uploads it. */
    fun uploadFile(uri: Uri, channelId: Long) {
        val client = client ?: return
        scope.launch(Dispatchers.IO) {
            val resolver = app.contentResolver
            val name = resolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            } ?: "upload.bin"

            val cache = File(app.cacheDir, name)
            val copied = runCatching {
                resolver.openInputStream(uri)?.use { input ->
                    cache.outputStream().use { output -> input.copyTo(output) }
                }
            }.isSuccess

            if (!copied) {
                setToast("读取文件失败")
                return@launch
            }
            client.upload(channelId, "/$name", cache.absolutePath)
            withContext(Dispatchers.Main) { setToast("开始上传 $name") }
        }
    }

    // ── events ──────────────────────────────────────────────────────────────

    private fun applyEvent(event: ServerEvent) {
        // While the goodbye is in flight the session screen no longer exists;
        // the only event that matters is the one that lets the client die.
        if (goodbyePending) {
            if (event is ServerEvent.Disconnected) {
                goodbyePending = false
                cleanupSession()
                // A reconnect asked for this goodbye: land the new connection
                // instead of the connect screen. The timeout path in
                // [reconnectNow] checks the same flag and does nothing if this
                // ran first.
                val pending = reconnectAfterGoodbye
                reconnectAfterGoodbye = null
                if (pending != null) {
                    connect(pending)
                } else {
                    _ui.value = SessionUiState()
                    onNavigate?.invoke(NavTab.Home)
                    onSessionEnded?.invoke()
                }
            }
            return
        }

        if (event is ServerEvent.TextMessage) {
            appendMessage(event)
        }
        applyPermissionEvent(event)

        _ui.update { current ->
            when (event) {
                is ServerEvent.Connected -> current.copy(status = SessionStatus.Online)
                is ServerEvent.Disconnected -> if (current.status == SessionStatus.Failed) {
                    current
                } else {
                    current.copy(status = SessionStatus.Disconnected)
                }
                is ServerEvent.Reconnecting -> current.copy(status = SessionStatus.Connecting)
                // A failure is always followed by the connection thread's own
                // `disconnected`, which would overwrite it with "已断开" and lose
                // the reason before the user reads it. The failure wins.
                is ServerEvent.Failure -> current.copy(
                    status = SessionStatus.Failed,
                    lastError = event.message,
                    failureKind = event.kind,
                )

                is ServerEvent.CommandFailure -> current.copy(toast = event.message)
                is ServerEvent.Poked -> {
                    val now = System.currentTimeMillis()
                    val line = if (event.message.isBlank()) {
                        "戳了你一下"
                    } else {
                        "戳了你一下：${event.message}"
                    }
                    val poke = ChatMessage(
                        key = messageKey.incrementAndGet(),
                        scope = ChatScope.Private,
                        fromId = event.fromId,
                        fromName = event.fromName,
                        text = line,
                        timestamp = now,
                        mine = false,
                        peerId = event.fromId,
                        poke = true,
                    )
                    // Reading that conversation right now? Then it is already
                    // seen; otherwise count it like an unread message so the
                    // 私信 chip lights up and the poke cannot be missed.
                    val viewing = current.tab == SessionTab.Chat &&
                        current.scope == ChatScope.Private &&
                        current.peer?.id == event.fromId
                    current.copy(
                        messages = (current.messages + poke).takeLast(MAX_MESSAGES),
                        // Nobody selected yet? Then selecting the person who poked
                        // costs the user nothing and makes the line visible at
                        // once, instead of an unread badge on an empty screen.
                        peer = current.peer ?: current.state.user(event.fromId),
                        unreadPrivate = if (viewing) {
                            current.unreadPrivate
                        } else {
                            current.unreadPrivate +
                                (event.fromId to (current.unreadPrivate[event.fromId] ?: 0) + 1)
                        },
                        toast = "${event.fromName} 戳了你一下" +
                            if (event.message.isNotBlank()) "：${event.message}" else "",
                    )
                }

                is ServerEvent.FileListed -> current.copy(
                    filesLoading = false,
                    filesChannelId = event.channelId,
                )

                is ServerEvent.TransferStarted -> current.copy(
                    transfers = current.transfers.upsert(
                        Transfer(
                            id = transferId(event.direction, event.name),
                            name = event.name,
                            direction = event.direction,
                            done = 0,
                            total = event.total,
                            state = TransferState.Running,
                        ),
                    ),
                )

                is ServerEvent.TransferProgress -> current.copy(
                    transfers = current.transfers.upsert(
                        Transfer(
                            id = transferId(event.direction, event.name),
                            name = event.name,
                            direction = event.direction,
                            done = event.done,
                            total = event.total,
                            state = TransferState.Running,
                        ),
                    ),
                )

                is ServerEvent.TransferDone -> {
                    if (event.direction == TransferDirection.Download) {
                        current.filesChannelId?.let { channel -> refreshAfterTransfer(channel) }
                    }
                    current.copy(
                        transfers = current.transfers.upsert(
                            Transfer(
                                id = transferId(event.direction, event.name),
                                name = event.name,
                                direction = event.direction,
                                done = event.total,
                                total = event.total,
                                state = TransferState.Done,
                                localPath = event.local.ifEmpty { null },
                            ),
                        ),
                        toast = if (event.direction == TransferDirection.Download) {
                            "已保存 ${event.name}"
                        } else {
                            "已上传 ${event.name}"
                        },
                    )
                }

                is ServerEvent.TransferFailed -> current.copy(
                    transfers = current.transfers.upsert(
                        Transfer(
                            id = transferId(TransferDirection.Download, event.name),
                            name = event.name,
                            direction = TransferDirection.Download,
                            done = 0,
                            total = 0,
                            state = TransferState.Failed,
                            error = event.message,
                        ),
                    ),
                    toast = if (event.message.isBlank()) {
                        "传输失败：${event.name}"
                    } else {
                        "传输失败：${event.name}（${event.message}）"
                    },
                )

                else -> current
            }
        }

        if (event !is ServerEvent.TransferProgress) {
            appendLog(event.describe())
        }
    }

    private fun refreshAfterTransfer(channelId: Long) {
        scope.launch { openFiles(channelId) }
    }

    private fun appendMessage(event: ServerEvent.TextMessage) {
        val state = _ui.value.state
        val mine = event.fromSelf || event.fromId == state.ownClientId

        // The server may echo our own message back as a notify; the optimistic
        // copy already in the list is the same line, so drop the duplicate.
        if (mine) {
            val now = System.currentTimeMillis()
            val duplicate = _ui.value.messages.any { existing ->
                existing.mine &&
                    existing.text == event.message &&
                    existing.scope == event.scope &&
                    now - existing.timestamp < ECHO_WINDOW_MS
            }
            if (duplicate) return
        }
        val peerId = if (event.scope == ChatScope.Private) {
            if (mine) event.toId else event.fromId
        } else {
            0
        }

        val message = ChatMessage(
            key = messageKey.incrementAndGet(),
            scope = event.scope,
            fromId = event.fromId,
            fromName = if (mine) state.nickname else event.fromName,
            text = event.message,
            timestamp = when {
                event.timestampMs > 0 -> event.timestampMs
                event.timestamp > 0 -> event.timestamp * 1000
                else -> System.currentTimeMillis()
            },
            mine = mine,
            peerId = peerId,
        )

        _ui.update { current ->
            val viewing = current.tab == SessionTab.Chat && when (event.scope) {
                ChatScope.Channel -> current.scope == ChatScope.Channel
                ChatScope.Server -> current.scope == ChatScope.Server
                ChatScope.Private -> current.scope == ChatScope.Private && current.peer?.id == peerId
            }
            current.copy(
                messages = (current.messages + message).takeLast(MAX_MESSAGES),
                unreadChannel = if (!mine && !viewing && event.scope == ChatScope.Channel) {
                    current.unreadChannel + 1
                } else {
                    current.unreadChannel
                },
                unreadServer = if (!mine && !viewing && event.scope == ChatScope.Server) {
                    current.unreadServer + 1
                } else {
                    current.unreadServer
                },
                unreadPrivate = if (!mine && !viewing && event.scope == ChatScope.Private) {
                    current.unreadPrivate + (peerId to (current.unreadPrivate[peerId] ?: 0) + 1)
                } else {
                    current.unreadPrivate
                },
            )
        }
    }

    private fun appendLog(line: String) {
        val stamped = "${LocalTime.now().format(TIME_FORMAT)} $line"
        _ui.update { it.copy(log = (listOf(stamped) + it.log).take(MAX_LOG)) }
    }

    fun setToast(message: String) {
        _ui.update { it.copy(toast = message) }
    }

    fun clearToast() {
        _ui.update { it.copy(toast = null) }
    }

    private fun joinPath(path: String, name: String): String {
        val base = if (path.endsWith("/")) path else "$path/"
        return base + name
    }

    private fun transferId(direction: TransferDirection, name: String): String =
        "${direction.name}:$name"

    private fun List<Transfer>.upsert(transfer: Transfer): List<Transfer> {
        val index = indexOfFirst { it.id == transfer.id }
        return if (index >= 0) {
            toMutableList().also { it[index] = transfer }
        } else {
            (this + transfer).takeLast(MAX_TRANSFERS)
        }
    }

    private companion object {
        const val MAX_MESSAGES = 500
        const val MAX_LOG = 120
        const val MAX_TRANSFERS = 20
        const val ECHO_WINDOW_MS = 5_000L

        /** How long to keep the client alive for the goodbye before giving up. */
        const val GOODBYE_GRACE_MS = 1500L
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}
