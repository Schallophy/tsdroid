package dev.tsdroid.core

import android.content.Context
import dev.tsdroid.model.AccountState
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/** One finished account command, from the `account_status` event. */
data class AccountNotice(val command: String, val ok: Boolean, val message: String)

/**
 * The myTeamSpeak side, on its own native handle.
 *
 * An account has nothing to do with a server connection: signing in, reading
 * bookmarks and backing them up have to work before the first join and keep
 * working after a disconnect. The core models that as a handle without a
 * socket, and this class owns one for the app's lifetime.
 *
 * The handle is also handed to [Native.nativeCreateWithAccount] when a session
 * connects, which is how the myTeamSpeak ID is announced in `clientinit`. The
 * proof is signed per connection, so the account handle has to still be alive
 * when the connection is created — it is.
 */
class AccountClient(context: Context) : AutoCloseable {

    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var ptr: Long = 0
    private var pollJob: Job? = null

    private val accountDir = File(appContext.filesDir, "account")
    private val credentialFile = File(accountDir, "login.json")
    private val baselineFile = File(accountDir, "sync-baseline.json")
    private val backupDir = File(accountDir, "backups")

    private val _state = MutableStateFlow(AccountState())
    val state: StateFlow<AccountState> = _state.asStateFlow()

    private val _notices = MutableSharedFlow<AccountNotice>(extraBufferCapacity = 32)
    val notices: SharedFlow<AccountNotice> = _notices.asSharedFlow()

    private val _startError = MutableStateFlow<String?>(null)
    val startError: StateFlow<String?> = _startError.asStateFlow()

    /** The live handle, or 0 when the account side could not start. */
    val handle: Long get() = ptr

    /** Whether a remembered login exists right now; logout deletes it. */
    fun hasRememberedLogin(): Boolean = credentialFile.isFile

    /**
     * Starts the account side and silently reuses a remembered login.
     *
     * The remembered login is only attempted when its file exists: a first
     * launch calling a bare login would otherwise surface the core's "nothing
     * is remembered" as an error on a screen the user has not even opened.
     */
    @Synchronized
    fun start(): Boolean {
        if (ptr != 0L) return true
        Native.loadError?.let {
            _startError.value = it
            return false
        }
        Native.abiError?.let {
            _startError.value = it
            return false
        }

        accountDir.mkdirs()
        ptr = try {
            Native.nativeCreateAccount(
                credentialFile.absolutePath,
                baselineFile.absolutePath,
                backupDir.absolutePath,
            )
        } catch (t: Throwable) {
            _startError.value = "调用账号原生接口失败：${t::class.java.simpleName}: ${t.message}"
            0L
        }
        if (ptr == 0L) {
            if (_startError.value == null) _startError.value = "账号服务启动失败"
            return false
        }

        pollJob = scope.launch { pollLoop() }
        if (hasRememberedLogin()) {
            exec("account_login")
        }
        return true
    }

    private suspend fun pollLoop() {
        while (currentCoroutineContext().isActive && ptr != 0L) {
            try {
                drainEvents()
                refresh()
            } catch (t: Throwable) {
                // A malformed snapshot must not end the loop, and it must not
                // turn the whole account pane into "unavailable" either: the
                // next tick retries and the last good state stays visible.
                android.util.Log.w("tsdroid", "account poll failed", t)
            }
            // Busy commands move fast enough to justify a tighter clock; an
            // idle account has nothing to say and is polled at human speed.
            delay(if (_state.value.busy) 200L else 700L)
        }
    }

    private fun drainEvents() {
        val batch = Native.nativePoll(ptr) ?: return
        for (map in batch) {
            if (map["type"] != "account_status") continue
            _notices.tryEmit(
                AccountNotice(
                    command = map["command"] as? String ?: "",
                    ok = map["ok"] as? Boolean ?: false,
                    message = map["message"] as? String ?: "",
                ),
            )
        }
    }

    private fun refresh() {
        val snapshot = Native.nativeSnapshot(ptr) ?: return
        val parsed = json.decodeFromString<SnapshotEnvelope>(snapshot).account
        if (parsed != _state.value) _state.value = parsed
    }

    // ── commands ────────────────────────────────────────────────────────────

    /**
     * One account command. Returns an immediate argument error, or null when
     * the command was accepted on the account worker; its result arrives as a
     * snapshot change plus one [AccountNotice].
     */
    fun exec(name: String, args: Map<String, Any?> = emptyMap()): String? {
        val handle = ptr
        if (handle == 0L) return "账号服务未启动"
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
        return Native.nativeExec(handle, name, payload.toString())?.takeIf { it.isNotEmpty() }
    }

    fun login(email: String, password: String, otp: String = ""): String? =
        exec(
            "account_login",
            buildMap {
                put("email", email)
                put("password", password)
                if (otp.isNotBlank()) put("otp", otp)
            },
        )

    fun logout(): String? = exec("account_logout")

    fun refreshAccount(): String? = exec("account_refresh", mapOf("revealSecrets" to false))

    fun listBookmarks(): String? = exec("sync_list", mapOf("revealSecrets" to false))

    fun backup(): String? = exec("sync_backup")

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

    /** The snapshot is large; only the `account` object is used here. */
    @Serializable
    private data class SnapshotEnvelope(val account: AccountState = AccountState())
}
