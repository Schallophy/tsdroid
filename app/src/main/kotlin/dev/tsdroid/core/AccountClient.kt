package dev.tsdroid.core

import android.content.Context
import dev.tsdroid.model.AccountState
import dev.tsdroid.model.BookmarkSecrets
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/** One finished account command, from the `account_status` event. */
data class AccountNotice(val command: String, val ok: Boolean, val message: String)

/**
 * The outcome of `identity_pick`. [value] is empty when nothing could be
 * chosen; [error] then says why, in a form the connect screen can show.
 */
data class PickedIdentity(
    val value: String,
    val name: String,
    val nickname: String,
    val error: String? = null,
)

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

    /** Serialises our own sync_list calls: the password read and its scrub. */
    private val syncMutex = Mutex()

    /** Advanced when a sync_list completion arrives; waiters key off it. */
    private val syncGeneration = MutableStateFlow(0L)
    @Volatile
    private var lastSyncNotice: AccountNotice? = null

    /** Same idea for `identity_pick`: the result is read from the snapshot. */
    private val identityGeneration = MutableStateFlow(0L)
    @Volatile
    private var lastIdentityNotice: AccountNotice? = null

    /** The passwords the account last reported, kept on disk so a cold start can use them. */
    private val secretCache = dev.tsdroid.data.BookmarkSecretsCache(appContext)

    /** Passwords by bookmark uuid. Never in the polled snapshot. */
    @Volatile
    private var secrets: Map<String, BookmarkSecrets> = secretCache.load()

    /**
     * The account's stored identities, as last read. A returning install uses
     * one of these to keep the UID its server rights are bound to.
     */
    @Volatile
    var accountIdentities: List<dev.tsdroid.model.AccountIdentity> = emptyList()
        private set

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

    /**
     * Whether the account's sign-in state is known.
     *
     * `false` for the first frames of a launch, while the remembered login is
     * still being restored. The UI must not read that as "signed out": showing
     * the sign-in prompt and then a bookmark list is a flash the user notices.
     */
    private val _settled = MutableStateFlow(false)
    val settled: StateFlow<Boolean> = _settled.asStateFlow()

    /**
     * Whether the bookmark list has been read from the account since this
     * launch. Until it has, the list is the previous launch's cache, not an
     * authoritative emptiness — so "no bookmarks" must not be shown yet.
     */
    private val _itemsFresh = MutableStateFlow(false)
    val itemsFresh: StateFlow<Boolean> = _itemsFresh.asStateFlow()

    /** The live handle, or 0 when the account side could not start. */
    val handle: Long get() = ptr

    /** Whether a remembered login exists right now; logout deletes it. */
    fun hasRememberedLogin(): Boolean = credentialFile.isFile

    /**
     * Starts the account side and silently restores a remembered login.
     *
     * `account_resume` is local: the file a login wrote carries the session, the
     * account key and the myTS identity, so a second launch shows "signed in"
     * immediately — no round-trip, and it works offline. A session the server has
     * since retired is renewed by the first read or `identity_pick`, not here.
     *
     * The resume is only attempted when its file exists: a first launch calling
     * it would otherwise surface "nothing is remembered" on a screen the user has
     * not even opened.
     */
    @Synchronized
    fun start(): Boolean {
        if (ptr != 0L) return true
        Native.loadError?.let {
            _startError.value = it
            _settled.value = true
            return false
        }
        Native.abiError?.let {
            _startError.value = it
            _settled.value = true
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
            _settled.value = true
            return false
        }

        pollJob = scope.launch { pollLoop() }
        if (hasRememberedLogin()) {
            exec("account_resume")
        } else {
            // Nothing was remembered, so there is nothing to wait for — and no
            // account that could own the passwords left on disk.
            secrets = emptyMap()
            secretCache.clear()
            _settled.value = true
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
            val notice = AccountNotice(
                command = map["command"] as? String ?: "",
                ok = map["ok"] as? Boolean ?: false,
                message = map["message"] as? String ?: "",
            )
            _notices.tryEmit(notice)

            if (notice.command == "sync_list") {
                lastSyncNotice = notice
                if (notice.ok) _itemsFresh.value = true
                syncGeneration.value += 1
            }
            if (notice.command == "account_resume" || notice.command == "account_login") {
                // Either way the sign-in question is answered, and a fresh
                // account starts with no list read.
                _settled.value = true
                _itemsFresh.value = false
            }
            if (notice.command == "identity_pick") {
                lastIdentityNotice = notice
                identityGeneration.value += 1
            }
            if (notice.ok && notice.command == "account_login") {
                // A login may be a different account: its bookmarks are not the
                // ones whose passwords are on disk.
                secrets = emptyMap()
                secretCache.clear()
            }
            if (notice.ok && (notice.command == "account_login" || notice.command == "account_resume")) {
                // The fetch below fills the map for this account. A resume keeps
                // the cached passwords until it lands, so a bookmark tapped in
                // the meantime still has one.
                scope.launch { syncBookmarkSecrets() }
            }
            if (notice.ok && notice.command == "account_refresh") {
                scope.launch { syncBookmarkSecrets() }
            }
            if (notice.ok && notice.command.startsWith("sync_bookmark_")) {
                // A bookmark was created, edited, moved or deleted: the item set
                // and the uuid->password map both changed. Re-fetch so a click on
                // any row still resolves its password.
                scope.launch { syncBookmarkSecrets() }
            }
            if (notice.ok && notice.command == "account_logout") {
                secrets = emptyMap()
                secretCache.clear()
            }
        }
    }

    /**
     * The account snapshot as it stands, passwords included when the last list
     * read asked for them.
     */
    private fun readSnapshot(): AccountState? {
        val snapshot = Native.nativeSnapshot(ptr) ?: return null
        return json.decodeFromString<SnapshotEnvelope>(snapshot).account
    }

    private fun refresh() {
        val parsed = readSnapshot() ?: return
        if (!parsed.loggedIn && _settled.value && secrets.isNotEmpty()) {
            // A settled signed-out account really has no bookmarks of ours: the
            // blank state at the start of a launch is not one.
            secrets = emptyMap()
            secretCache.clear()
        }
        if (parsed.loggedIn && parsed.identities != accountIdentities) {
            accountIdentities = parsed.identities
        }
        if (parsed.loggedIn) _settled.value = true
        // The bookmark password sync reads the list with revealed secrets, and
        // a poll tick can see that state before it is scrubbed again. That state
        // must not reach the UI: the UI writes the account to bookmarks.json,
        // which would put plaintext passwords in a readable file.
        val public = parsed.withoutSecrets()
        if (public != _state.value) _state.value = public
    }

    /** The same account with bookmark passwords left out. */
    private fun AccountState.withoutSecrets(): AccountState {
        val exposed = items.any { item ->
            val bookmark = item.bookmark
            bookmark?.serverPassword != null || bookmark?.channelPassword != null
        }
        if (!exposed) return this
        return copy(
            items = items.map { item ->
                val bookmark = item.bookmark
                when {
                    bookmark == null -> item
                    bookmark.serverPassword == null && bookmark.channelPassword == null -> item
                    else -> item.copy(
                        bookmark = bookmark.copy(serverPassword = null, channelPassword = null),
                    )
                }
            },
        )
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

    fun logout(): String? {
        val error = exec("account_logout")
        if (error == null) {
            secrets = emptyMap()
            secretCache.clear()
        }
        return error
    }

    fun refreshAccount(): String? = exec("account_refresh", mapOf("revealSecrets" to false))

    fun backup(): String? = exec("sync_backup")

    /**
     * Copies an identity into the account, so another install signing in with
     * this account keeps the UID. The library validates the string and writes
     * nothing when the item already holds it.
     */
    /**
     * Asks the account which identity the given server knows this user by.
     * Read-only; the answer arrives in [state].`pickedIdentity`.
     *
     * The command is asynchronous on the native side, so reading the snapshot
     * immediately after `exec` returns the *previous* pick (or none at all).
     * This waits for the matching completion event and then reads the snapshot.
     */
    suspend fun pickIdentityAndWait(address: String, timeoutMs: Long = 30_000): PickedIdentity {
        if (ptr == 0L) return PickedIdentity("", "", "", "账号服务未启动")
        // Serialise with the password fetch that follows a login: the account
        // side runs one command at a time and would reject the pick as "already
        // running sync_list" if it landed in that window.
        return syncMutex.withLock {
            refresh()
            val idle = withTimeoutOrNull(60_000) {
                state.first { !it.busy }
                true
            } ?: false
            if (!idle) {
                return@withLock PickedIdentity("", "", "", "账号正忙，请稍后再试")
            }

            val generation = identityGeneration.value
            var rejection = exec("identity_pick", mapOf("address" to address))
            // The snapshot can lag a command that was just dispatched; retry a
            // few times instead of falling back to a local identity.
            var attempts = 0
            while (rejection != null && rejection.contains("already running") && attempts < 5) {
                delay(300)
                refresh()
                rejection = exec("identity_pick", mapOf("address" to address))
                attempts++
            }
            if (rejection != null) return@withLock PickedIdentity("", "", "", rejection)

            val notice = withTimeoutOrNull(timeoutMs) {
                identityGeneration.first { it > generation }
                lastIdentityNotice
            } ?: return@withLock PickedIdentity("", "", "", "读取账号身份超时")
            refresh()
            if (!notice.ok) {
                return@withLock PickedIdentity(
                    "",
                    "",
                    "",
                    notice.message.ifEmpty { "账号里没有能对上这台服务器的身份" },
                )
            }
            val picked = state.value.pickedIdentity
                ?: return@withLock PickedIdentity("", "", "", "账号里没有可用的身份")
            if (picked.value.isEmpty()) {
                return@withLock PickedIdentity("", "", "", "账号里没有能对上这台服务器的身份")
            }
            PickedIdentity(picked.value, picked.name, picked.nickname)
        }
    }

    /**
     * Waits until a launch knows whether a login is remembered.
     *
     * Resume is local — the credential file is on disk — so this is normally
     * a fraction of a second. It returns false only if that took longer than
     * [timeoutMs], which means the account side is stuck and the caller should
     * go ahead without it.
     */
    suspend fun awaitSettled(timeoutMs: Long = 10_000): Boolean {
        if (!_settled.value) {
            withTimeoutOrNull(timeoutMs) { _settled.first { it } } ?: return false
        }
        // Settled is set by the event that answered the question; the snapshot
        // the UI reads is refreshed on the poll tick just after it. A caller
        // that reads `state` now would still see "signed out, no identities" —
        // which is the very race this wait exists to avoid.
        withContext(Dispatchers.Default) { refresh() }
        return true
    }

    fun pickIdentity(address: String): String? =
        exec("identity_pick", mapOf("address" to address))

    fun importIdentity(identity: String, uuid: String? = null): String? =
        exec(
            "identity_import",
            buildMap {
                put("identity", identity)
                if (!uuid.isNullOrBlank()) put("uuid", uuid)
            },
        )

    /** Passwords for one bookmark, or null when the cache has no entry yet. */
    fun bookmarkSecrets(uuid: String): BookmarkSecrets? = secrets[uuid]

    /**
     * Deletes one bookmark from the cloud account.
     *
     * The core's `sync_delete` takes the item uuid and its class; a bookmark is
     * `BOOKMARK`. The deletion is reflected in the next snapshot, which the UI
     * already polls.
     */
    fun deleteBookmark(uuid: String): String? =
        exec("sync_delete", mapOf("uuid" to uuid, "class" to "BOOKMARK"))

    /** Rewrites a bookmark's four editable fields; the item keeps its identity. */
    fun editBookmark(
        uuid: String,
        name: String,
        address: String,
        port: Int,
        nickname: String,
    ): String? = exec(
        "sync_bookmark_edit",
        mapOf(
            "uuid" to uuid,
            "name" to name,
            "address" to address,
            "port" to port,
            "nickname" to nickname,
        ),
    )

    /** Moves a bookmark to follow `after` (empty means first). */
    fun moveBookmark(uuid: String, after: String): String? =
        exec("sync_bookmark_move", mapOf("uuid" to uuid, "after" to after))

    /** Creates a bookmark from the temporary-connect form. */
    fun createBookmark(name: String, address: String, port: Int, nickname: String): String? =
        exec(
            "sync_bookmark_create",
            mapOf("name" to name, "address" to address, "port" to port, "nickname" to nickname),
        )

    /**
     * Reads the whole item list once with passwords and keeps them here.
     *
     * Runs after a login or a refresh, so a bookmark click is a synchronous
     * cache lookup. The values never stay in the polled snapshot: the follow-up
     * list without `revealSecrets` puts it back to presence-only, while the
     * cache above remains the only copy the UI uses.
     */
    private suspend fun syncBookmarkSecrets() {
        syncMutex.withLock {
            val notice = syncListLocked(reveal = true)
            if (notice?.ok == true) {
                // Read the snapshot directly rather than through the published
                // state: that state is scrubbed of passwords, and this is the
                // one reader that needs them.
                val revealed = readSnapshot()
                if (revealed != null && revealed.loggedIn && revealed.lastError.isEmpty()) {
                    secrets = revealed.items.mapNotNull { item ->
                        val bookmark = item.bookmark ?: return@mapNotNull null
                        if (!bookmark.hasServerPassword && !bookmark.hasChannelPassword) {
                            return@mapNotNull null
                        }
                        item.uuid to BookmarkSecrets(
                            serverPassword = if (bookmark.hasServerPassword) {
                                bookmark.serverPassword
                            } else {
                                null
                            },
                            channelPassword = if (bookmark.hasChannelPassword) {
                                bookmark.channelPassword
                            } else {
                                null
                            },
                        )
                    }.toMap()
                    secretCache.save(secrets)
                }
            }
            // Presence-only for the snapshot; the cache keeps what the UI needs.
            syncListLocked(reveal = false)
        }
    }

    /**
     * One sync_list while [syncMutex] is held. Returns the completion notice,
     * or null when the command was rejected or never reported.
     */
    private suspend fun syncListLocked(reveal: Boolean, timeoutMs: Long = 60_000): AccountNotice? {
        val generation = syncGeneration.value
        if (exec("sync_list", mapOf("revealSecrets" to reveal)) != null) return null
        withTimeoutOrNull(timeoutMs) { syncGeneration.first { it > generation } }
        return lastSyncNotice
    }

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
