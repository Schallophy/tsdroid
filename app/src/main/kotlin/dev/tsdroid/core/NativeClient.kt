package dev.tsdroid.core

import dev.tsdroid.model.ChatScope
import dev.tsdroid.model.ServerEvent
import dev.tsdroid.model.ServerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class ServerConfig(
    val address: String,
    val nickname: String,
    /** Unlocks the server itself; empty when it is open. */
    val password: String = "",
    val channel: String = "",
    /** Unlocks the channel the client lands in; unrelated to [password]. */
    val channelPassword: String = "",
    /** A TS3 `counterVkey` identity from a previous session, or "" for a fresh one. */
    val identityIni: String = "",
)

/**
 * One live session: owns the native handle, drains its event queue on a fixed
 * tick and republishes the state snapshot as a flow.
 *
 * Polling rather than callbacks is deliberate — it keeps the native library free
 * of any reference to app classes, and a 20 ms tick costs less than the JNI
 * bookkeeping a push model would need (cached env per thread, re-entrancy into
 * the UI thread at arbitrary points).
 */
class NativeClient(
    private val config: ServerConfig,
    /**
     * Called once the native side has built the identity, with its TS3
     * `counterVkey` form. The caller stores it so the next launch reuses the
     * same UID instead of registering as a new user.
     */
    private val onIdentity: (String) -> Unit = {},
) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var ptr: Long = 0
    private var pollJob: Job? = null
    private var lastRev = -1L

    /** The identity is handed over once; it never changes within a session. */
    private var identityCaptured = false

    private val _state = MutableStateFlow(ServerState())
    val state: StateFlow<ServerState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<ServerEvent> = _events.asSharedFlow()

    /** Why [start] returned false; null when it succeeded. */
    var startError: String? = null
        internal set

    /** Returns false when the native side rejected the arguments (address empty, …). */
    @Synchronized
    fun start(): Boolean {
        if (ptr != 0L) return true

        // A missing, mismatched or stale .so must read as a message, not as a
        // crash on the connect button.
        Native.loadError?.let {
            startError = it
            return false
        }
        Native.abiError?.let {
            startError = it
            return false
        }

        ptr = try {
            Native.nativeCreate(
                config.address,
                config.identityIni,
                config.nickname,
                config.password.ifEmpty { null },
                config.channel.ifEmpty { null },
                config.channelPassword.ifEmpty { null },
            )
        } catch (t: Throwable) {
            // A symbol that exists but failed to resolve lands here, not at load
            // time: the JVM resolves each native method on first use.
            startError = "调用原生库失败：${t::class.java.simpleName}: ${t.message}"
            0L
        }

        if (ptr == 0L) {
            if (startError == null) startError = "原生层拒绝了连接参数（地址为空或无效）"
            return false
        }
        pollJob = scope.launch { pollLoop() }
        return true
    }

    private suspend fun pollLoop() {
        while (currentCoroutineContext().isActive && ptr != 0L) {
            try {
                drainEvents()
                refreshState()
            } catch (t: Throwable) {
                // A throwing poll would silently end the loop and leave the UI
                // frozen on stale data, so report it as an event and keep going.
                _events.tryEmit(ServerEvent.Failure(t.message ?: t.toString(), "poll"))
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private fun drainEvents() {
        val batch = Native.nativePoll(ptr) ?: return
        for (map in batch) {
            val event = ServerEvent.from(map) ?: continue
            _events.tryEmit(event)
        }
    }

    private fun refreshState() {
        val snapshot = Native.nativeSnapshot(ptr) ?: return
        val parsed = json.decodeFromString<ServerState>(snapshot)
        if (parsed.rev != lastRev) {
            lastRev = parsed.rev
            _state.value = parsed
        }
        // `identityReady` is the core's signal that the key has been built. The
        // own client id covers a library from before that flag existed: both
        // appear only after the worker has moved past identity creation.
        if (!identityCaptured && (parsed.identityReady || parsed.ownClientId != null)) {
            Native.nativeIdentity(ptr)?.let {
                identityCaptured = true
                onIdentity(it)
            }
        }
    }

    // ── commands ────────────────────────────────────────────────────────────

    /**
     * One command, one JNI call. Errors are returned rather than thrown: a
     * rejected poke must not take the session down.
     */
    fun exec(name: String, args: Map<String, Any?> = emptyMap()): String? {
        val handle = ptr
        if (handle == 0L) return "not connected"
        val payload: JsonObject = buildJsonObject {
            for ((key, value) in args) {
                when (value) {
                    null -> Unit
                    is Boolean -> put(key, value)
                    is Int -> put(key, value)
                    is Long -> put(key, value)
                    is Number -> put(key, value.toLong())
                    else -> put(key, value.toString())
                }
            }
        }
        val error = Native.nativeExec(handle, name, payload.toString())
        return error?.takeIf { it.isNotEmpty() }
    }

    fun sendText(scope: ChatScope, message: String, clientId: Int = 0) {
        exec(
            "send_text",
            mapOf(
                "target" to when (scope) {
                    ChatScope.Server -> "server"
                    ChatScope.Channel -> "channel"
                    ChatScope.Private -> "client"
                },
                "clientId" to clientId,
                "message" to message,
            ),
        )
    }

    fun poke(clientId: Int, message: String) {
        exec("poke", mapOf("clientId" to clientId, "message" to message))
    }

    fun joinChannel(channelId: Long, password: String? = null) {
        exec("join_channel", mapOf("channelId" to channelId, "password" to password))
    }

    fun setInputMuted(muted: Boolean) = exec("set_input_muted", mapOf("muted" to muted))

    fun setOutputMuted(muted: Boolean) = exec("set_output_muted", mapOf("muted" to muted))

    fun setAway(away: Boolean, message: String = "") =
        exec("set_away", mapOf("away" to away, "message" to message))

    fun setNickname(nickname: String) = exec("set_nickname", mapOf("nickname" to nickname))

    fun listFiles(channelId: Long, path: String = "/") =
        exec("list_files", mapOf("channelId" to channelId, "path" to path))

    fun download(channelId: Long, path: String, localPath: String) =
        exec("download", mapOf("channelId" to channelId, "path" to path, "local" to localPath))

    fun upload(channelId: Long, path: String, localPath: String) =
        exec("upload", mapOf("channelId" to channelId, "path" to path, "local" to localPath))

    fun setAudioEnabled(enabled: Boolean) = exec("audio_enabled", mapOf("enabled" to enabled))

    /** Linear capture gain; the core applies it before the encoder on every platform. */
    fun setMicGain(gain: Float) = exec("set_mic_gain", mapOf("gain" to gain))

    // ── voice ───────────────────────────────────────────────────────────────

    fun audioWrite(samples: ShortArray): Int {
        val handle = ptr
        return if (handle == 0L) 0 else Native.nativeAudioWrite(handle, samples)
    }

    fun audioRead(out: ShortArray): Int {
        val handle = ptr
        return if (handle == 0L) 0 else Native.nativeAudioRead(handle, out)
    }

    fun audioReset() {
        val handle = ptr
        if (handle != 0L) Native.nativeAudioReset(handle)
    }

    // ── lifecycle ───────────────────────────────────────────────────────────

    fun disconnect() {
        if (ptr != 0L) Native.nativeDisconnect(ptr)
    }

    /**
     * Ends the session. The poll job is joined before the handle is freed:
     * destroying the handle while a poll is in flight would read freed memory.
     */
    @Synchronized
    override fun close() {
        val handle = ptr
        if (handle == 0L) return
        ptr = 0
        runBlocking { pollJob?.cancelAndJoin() }
        pollJob = null
        Native.nativeDestroy(handle)
        scope.coroutineContext[Job]?.cancel()
    }

    private companion object {
        /** 50 Hz keeps the member list feeling live without spinning a core. */
        const val POLL_INTERVAL_MS = 20L
    }
}
