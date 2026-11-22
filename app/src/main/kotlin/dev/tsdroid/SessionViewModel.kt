package dev.tsdroid

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.tsdroid.core.AudioBridge
import dev.tsdroid.core.NativeClient
import dev.tsdroid.core.ServerConfig
import dev.tsdroid.data.ServerStore
import dev.tsdroid.data.Settings
import dev.tsdroid.model.ChatMessage
import dev.tsdroid.model.ChatScope
import dev.tsdroid.model.FileEntry
import dev.tsdroid.model.ServerEvent
import dev.tsdroid.model.ServerState
import dev.tsdroid.model.Transfer
import dev.tsdroid.model.TransferDirection
import dev.tsdroid.model.TransferState
import dev.tsdroid.model.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
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

enum class SessionStatus { Idle, Connecting, Online, Disconnected, Failed }

enum class SessionTab { Channels, Chat, Files }

data class SessionUiState(
    val status: SessionStatus = SessionStatus.Idle,
    val config: ServerConfig? = null,
    val state: ServerState = ServerState(),
    val tab: SessionTab = SessionTab.Channels,
    val messages: List<ChatMessage> = emptyList(),
    val transfers: List<Transfer> = emptyList(),
    val scope: ChatScope = ChatScope.Channel,
    val peer: User? = null,
    val filesChannelId: Long? = null,
    val filesLoading: Boolean = false,
    val unreadChannel: Int = 0,
    val unreadServer: Int = 0,
    val unreadPrivate: Map<Int, Int> = emptyMap(),
    val log: List<String> = emptyList(),
    val lastError: String? = null,
    val toast: String? = null,
    val micEnabled: Boolean = true,
    val speakerEnabled: Boolean = true,
) {
    val isOnline: Boolean get() = status == SessionStatus.Online
    val unreadTotal: Int get() = unreadChannel + unreadServer + unreadPrivate.values.sum()
}

/**
 * Owns the session: the native client, the audio bridge and every piece of UI
 * state that has to outlive a recomposition.
 *
 * A ViewModel rather than a composable-held object so that a rotation does not
 * tear down a live voice connection.
 */
class SessionViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = Settings(application)
    private val servers = ServerStore(application)
    private val audio = AudioBridge(application)

    private var client: NativeClient? = null
    private val messageKey = AtomicLong(0)

    /** Set while the goodbye is in flight; the UI waits for it to land. */
    private var goodbyePending = false

    private val _ui = MutableStateFlow(SessionUiState())
    val ui: StateFlow<SessionUiState> = _ui.asStateFlow()

    val micLevel = audio.level
    val audioError = audio.error

    init {
        audio.micGain = settings.micGain
        audio.noiseSuppressionEnabled = settings.noiseSuppression
        audio.bluetoothMicEnabled = settings.bluetoothMic
    }

    // ── session lifecycle ───────────────────────────────────────────────────

    fun connect(config: ServerConfig) {
        goodbyePending = false
        cleanupSession()
        val app = getApplication<Application>()
        // Breadcrumb: if the process dies from here on without a Java stack,
        // the next launch reports this line instead of "it just closed".
        CrashLog.markPhase(app, "正在连接 ${config.address}（原生库创建前）")
        _ui.value = SessionUiState(status = SessionStatus.Connecting, config = config)

        val session = NativeClient(config) { identity -> servers.writeIdentity(identity) }
        val started = try {
            session.start()
        } catch (t: Throwable) {
            // Last line of defence: connect runs on the main thread, so anything
            // thrown here would take the whole process down instead of showing.
            session.startError = "启动失败：${t::class.java.simpleName}: ${t.message}"
            false
        }
        if (!started) {
            CrashLog.clearPhase(app)
            _ui.value = _ui.value.copy(
                status = SessionStatus.Failed,
                lastError = session.startError ?: "无法建立连接",
            )
            return
        }
        client = session
        // Gain belongs to the core so every platform clips the same way.
        session.setMicGain(settings.micGain)
        CrashLog.markPhase(app, "已创建原生会话，等待服务器响应 ${config.address}")

        viewModelScope.launch {
            session.state.collect { state ->
                if (state.connected) {
                    CrashLog.markPhase(app, "已连接 ${state.server.name}（${config.address}）")
                }
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

        viewModelScope.launch {
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

        viewModelScope.launch {
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
        // Reaching here with no crash means the last run ended on purpose.
        CrashLog.clearPhase(getApplication())
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
        if (client == null) {
            _ui.value = SessionUiState()
            return
        }
        goodbyePending = true
        client?.disconnect()
        viewModelScope.launch {
            delay(GOODBYE_GRACE_MS)
            if (goodbyePending) {
                goodbyePending = false
                cleanupSession()
                _ui.value = SessionUiState()
            }
        }
    }

    /** Same action as [leave]; the header button reads "disconnect". */
    fun disconnect() = leave()

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

    override fun onCleared() {
        goodbyePending = false
        audio.stop()
        client?.close()
        client = null
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

    fun joinChannel(channelId: Long, password: String? = null) {
        if (channelId == _ui.value.state.ownChannelId) return
        client?.joinChannel(channelId, password)
        // The file browser follows you: it always shows the channel you are in.
        _ui.update { it.copy(tab = SessionTab.Channels, filesChannelId = channelId, filesLoading = true) }
        client?.listFiles(channelId)
    }

    fun toggleMic() {
        val enabled = !_ui.value.micEnabled
        audio.micEnabled = enabled
        client?.setInputMuted(!enabled)
        _ui.update { it.copy(micEnabled = enabled) }
    }

    fun toggleSpeaker() {
        val enabled = !_ui.value.speakerEnabled
        audio.speakerEnabled = enabled
        client?.setOutputMuted(!enabled)
        _ui.update { it.copy(speakerEnabled = enabled) }
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

    fun settings(): Settings = settings

    fun serverStore(): ServerStore = servers

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
        val directory = getApplication<Application>()
            .getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
            ?: getApplication<Application>().filesDir
        val target = File(directory, file.name)
        client.download(file.channelId, joinPath(file.path, file.name), target.absolutePath)
        setToast("开始下载 ${file.name}")
    }

    /** Copies the picked document into the cache dir, then uploads it. */
    fun uploadFile(uri: Uri, channelId: Long) {
        val client = client ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            val name = resolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            } ?: "upload.bin"

            val cache = File(getApplication<Application>().cacheDir, name)
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
                _ui.value = SessionUiState()
            }
            return
        }

        if (event is ServerEvent.TextMessage) {
            appendMessage(event)
        }

        _ui.update { current ->
            when (event) {
                is ServerEvent.Connected -> current.copy(status = SessionStatus.Online)
                is ServerEvent.Disconnected -> current.copy(status = SessionStatus.Disconnected)
                is ServerEvent.Reconnecting -> current.copy(status = SessionStatus.Connecting)
                is ServerEvent.Failure -> current.copy(
                    status = SessionStatus.Failed,
                    lastError = event.message,
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
                    toast = "传输失败：${event.name}",
                )

                else -> current
            }
        }

        if (event !is ServerEvent.TransferProgress) {
            appendLog(event.describe())
        }
    }

    private fun refreshAfterTransfer(channelId: Long) {
        viewModelScope.launch { openFiles(channelId) }
    }

    private fun appendMessage(event: ServerEvent.TextMessage) {
        val state = _ui.value.state
        val mine = event.fromId == state.ownClientId

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
            timestamp = if (event.timestamp > 0) event.timestamp * 1000 else System.currentTimeMillis(),
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
