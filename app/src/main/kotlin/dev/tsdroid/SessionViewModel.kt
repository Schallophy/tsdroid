package dev.tsdroid

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.tsdroid.core.AccountClient
import dev.tsdroid.core.AccountNotice
import dev.tsdroid.core.ServerConfig
import dev.tsdroid.data.ServerStore
import dev.tsdroid.data.Settings
import dev.tsdroid.model.AccountState
import dev.tsdroid.model.BookmarkSecrets
import dev.tsdroid.model.ChatScope
import dev.tsdroid.model.FileEntry
import dev.tsdroid.model.User
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.SharingStarted

/** A bookmark change that is shown before the server confirms it. */
private sealed interface BookmarkEdit {
    data class Patched(val name: String, val address: String, val port: Int, val nickname: String) : BookmarkEdit
    data object Removed : BookmarkEdit
}

/**
 * The screen-facing facade over the session the [SessionService] owns.
 *
 * The connection, audio and session state live in a [SessionController] inside
 * the service, so they survive rotation, backgrounding and the screen turning
 * off. This ViewModel binds to that service and forwards commands and state; it
 * owns nothing that a screen would take with it when it goes away.
 *
 * Commands that arrive before the bind completes (the first connect) are held
 * until the service is connected, because a bind is asynchronous and the user
 * should not have to tap twice.
 */
class SessionViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = Settings(application)
    private val bookmarkCache = dev.tsdroid.data.BookmarkCache(application)
    private val servers = ServerStore(application)
    private val account: AccountClient = (application as TsDroidApp).account

    /** Bookmark changes shown before the server confirms them. */
    private val localBookmarkEdits = MutableStateFlow<Map<String, BookmarkEdit>>(emptyMap())

    /** The list shown last time, so a launch draws rows instead of a spinner. */
    private val cachedAccount: AccountState? = bookmarkCache.load()

    /** The controller currently hosted by the service, or null before the bind. */
    private val controllerFlow = MutableStateFlow<SessionController?>(null)

    private var service: SessionService? = null
    private var bound = false

    /** A connect that arrived before the service was bound. */
    private var pendingConnect: ServerConfig? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as? SessionService.LocalBinder)?.service() ?: return
            service = svc
            bound = true
            svc.controller?.let { wire(it) }
            controllerFlow.value = svc.controller
            pendingConnect?.let { config ->
                pendingConnect = null
                begin(svc, config)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            controllerFlow.value = null
        }
    }

    init {
        // If the process outlived the screen and a session is still hosted, pick
        // it up. A plain bind (no AUTO_CREATE) returns false when the service is
        // not running, so an idle launch does not start one just to look at it.
        val intent = Intent(application, SessionService::class.java)
        runCatching { application.bindService(intent, connection, 0) }
    }

    /**
     * The account, with local bookmark edits applied on top.
     *
     * A bookmark change is shown at once and the server's answer replaces the
     * override when it lands. Without this the list would sit unchanged for the
     * length of a round trip and then jump — the write is fast, but not instant.
     * The override is keyed by item uuid so an unrelated change from the account
     * still comes through.
     */
    val accountState: StateFlow<AccountState> = combine(account.state, localBookmarkEdits) { state, edits ->
        // Until the first real read lands, the live state is a bare "signed in"
        // with no items. The cached list is what was on screen last time, so it
        // stands in: the rows are there at the first frame, and the read replaces
        // them when it arrives.
        val base = if (state.items.isEmpty() && state.loggedIn) cachedAccount ?: state else state
        if (edits.isEmpty()) {
            base
        } else {
            val items = base.items.mapNotNull { item ->
                when (val edit = edits[item.uuid]) {
                    is BookmarkEdit.Removed -> null
                    is BookmarkEdit.Patched -> {
                        val bookmark = item.bookmark ?: return@mapNotNull item
                        item.copy(
                            bookmark = bookmark.copy(
                                name = edit.name,
                                address = edit.address,
                                port = edit.port,
                                nickname = edit.nickname,
                            ),
                        )
                    }
                    null -> item
                }
            }
            base.copy(items = items)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, cachedAccount ?: account.state.value)

    val accountError: StateFlow<String?> = account.startError
    val accountNotices: SharedFlow<AccountNotice> = account.notices

    init {
        // Keep the cache current: every state the UI would render is what the
        // next launch should draw.
        viewModelScope.launch {
            account.state.collect { state ->
                if (!state.loggedIn) {
                    bookmarkCache.clear()
                } else if (state.items.isNotEmpty()) {
                    bookmarkCache.save(state)
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val ui: StateFlow<SessionUiState> = controllerFlow
        .flatMapLatest { it?.ui ?: flowOf(SessionUiState()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SessionUiState())

    /**
     * The bottom-navigation destination.
     *
     * Held here, not on the controller: the bar must work with no session at
     * all. The controller only asks for a change through [wire].
     */
    private val _navTab = MutableStateFlow(NavTab.Home)
    val navTab: StateFlow<NavTab> = _navTab.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val micLevel: StateFlow<Float> = controllerFlow
        .flatMapLatest { it?.micLevel ?: flowOf(0f) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0f)

    @OptIn(ExperimentalCoroutinesApi::class)
    val audioError: StateFlow<String?> = controllerFlow
        .flatMapLatest { it?.audioError ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val filesTabEnabled: Boolean get() = settings.filesTabEnabled

    // ── binding ───────────────────────────────────────────────────────────

    private fun ensureBound() {
        val app = getApplication<Application>()
        val intent = Intent(app, SessionService::class.java)
        ContextCompat.startForegroundService(app, intent)
        if (!bound) {
            app.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun begin(svc: SessionService, config: ServerConfig) {
        val controller = svc.session()
        wire(controller)
        controllerFlow.value = controller
        controller.connect(config)
    }

    /** Hands the controller the two callbacks that talk back to the UI. */
    private fun wire(controller: SessionController) {
        controller.onNavigate = { _navTab.value = it }
    }

    // ── session commands ──────────────────────────────────────────────────

    fun connect(config: ServerConfig) {
        val svc = service
        if (bound && svc != null) {
            begin(svc, config)
        } else {
            // Hold the request until the bind lands; the service is started here
            // so it survives the Activity going away once the session is live.
            pendingConnect = config
            ensureBound()
        }
    }

    fun leave() = withController { it.leave() }
    fun disconnect() = withController { it.disconnect() }
    fun isLive(): Boolean = service?.controller != null
    fun reconnectNow() = withController { it.reconnectNow() }
    fun reconnect() = withController { it.reconnect() }
    fun reconnectWithToken(token: String) = withController { it.reconnectWithToken(token) }
    fun selectTab(tab: SessionTab) = withController { it.selectTab(tab) }
    fun selectNavTab(tab: NavTab) {
        _navTab.value = tab
        withController { it.selectNavTab(tab) }
    }
    fun selectScope(scope: ChatScope) = withController { it.selectScope(scope) }
    fun openPrivateChat(user: User) = withController { it.openPrivateChat(user) }
    fun sendMessage(text: String) = withController { it.sendMessage(text) }
    fun poke(user: User, message: String) = withController { it.poke(user, message) }
    fun joinChannel(channelId: Long, password: String? = null) =
        withController { it.joinChannel(channelId, password) }
    fun toggleMic() = withController { it.toggleMic() }
    fun toggleSpeaker() = withController { it.toggleSpeaker() }
    fun setAway(away: Boolean, message: String = "") = withController { it.setAway(away, message) }
    fun setNickname(nickname: String) = withController { it.setNickname(nickname) }
    fun applyAudioSettings(gain: Float, noiseSuppression: Boolean, bluetoothMic: Boolean) =
        withController { it.applyAudioSettings(gain, noiseSuppression, bluetoothMic) }
    fun applyOutputGain(gain: Float) = withController { it.applyOutputGain(gain) }
    fun openFiles(channelId: Long) = withController { it.openFiles(channelId) }
    fun refreshFiles() = withController { it.refreshFiles() }
    fun downloadFile(file: FileEntry) = withController { it.downloadFile(file) }
    fun uploadFile(uri: Uri, channelId: Long) = withController { it.uploadFile(uri, channelId) }
    fun setToast(message: String) = withController { it.setToast(message) }
    fun clearToast() = withController { it.clearToast() }

    fun adoptAccountIdentityAndConnect(config: ServerConfig) {
        val svc = service
        if (bound && svc != null) {
            val controller = svc.session()
            wire(controller)
            controllerFlow.value = controller
            controller.adoptAccountIdentityAndConnect(config)
        } else {
            pendingConnect = config
            ensureBound()
        }
    }

    private inline fun withController(block: (SessionController) -> Unit) {
        controllerFlow.value?.let(block)
    }

    fun settings(): Settings = settings
    fun serverStore(): ServerStore = servers

    // ── account ───────────────────────────────────────────────────────────
    fun accountLogin(email: String, password: String, otp: String = ""): String? =
        account.login(email.trim(), password, otp.trim())
    fun accountLogout(): String? = account.logout()
    fun accountRefresh(): String? = account.refreshAccount()
    fun accountBackup(): String? = account.backup()
    fun accountBookmarkSecrets(uuid: String): BookmarkSecrets? = account.bookmarkSecrets(uuid)
    /**
     * Deletes a bookmark, hiding it at once.
     *
     * The row disappears on the tap and comes back only if the server refuses;
     * waiting for the round trip made a delete feel like a freeze.
     */
    fun accountDeleteBookmark(uuid: String) {
        localBookmarkEdits.update { it + (uuid to BookmarkEdit.Removed) }
        val error = account.deleteBookmark(uuid)
        if (error != null) {
            // Refused: put the row back and say why.
            localBookmarkEdits.update { it - uuid }
            setToast(error)
        } else {
            // Accepted: the server's list is authoritative now.
            localBookmarkEdits.update { it - uuid }
        }
    }

    /** Edits a bookmark, showing the new fields at once. */
    fun accountEditBookmark(uuid: String, name: String, address: String, port: Int, nickname: String) {
        localBookmarkEdits.update {
            it + (uuid to BookmarkEdit.Patched(name, address, port, nickname))
        }
        val error = account.editBookmark(uuid, name, address, port, nickname)
        localBookmarkEdits.update { it - uuid }
        error?.let(::setToast)
    }

    /**
     * Reorders, which the list already showed locally.
     *
     * The drag moved the row before this was called, so there is nothing to
     * apply here: only a refusal needs reporting, and the next snapshot puts the
     * rows back where the server has them.
     */
    fun accountMoveBookmark(uuid: String, after: String) {
        account.moveBookmark(uuid, after)?.let(::setToast)
    }

    fun accountCreateBookmark(name: String, address: String, port: Int, nickname: String) {
        account.createBookmark(name, address, port, nickname)?.let(::setToast)
    }
    fun accountIdentities(): List<dev.tsdroid.model.AccountIdentity> = account.accountIdentities
    fun accountSyncIdentity(): String? {
        val identity = servers.readIdentity()
        if (identity.isBlank()) return "本机还没有身份"
        return account.importIdentity(identity)
    }
    fun useAccountIdentity(value: String) {
        servers.writeIdentity(value)
        setToast("已改用账号里的身份，下次连接生效")
    }
    fun accountHasRememberedLogin(): Boolean = account.hasRememberedLogin()

    override fun onCleared() {
        // The service keeps the session; only the binding goes away with the UI.
        if (bound) {
            runCatching { getApplication<Application>().unbindService(connection) }
            bound = false
        }
    }
}
