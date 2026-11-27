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
import dev.tsdroid.model.PermissionEditorState
import dev.tsdroid.model.PermissionTarget
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.SharingStarted

/** A bookmark change that is shown before the server confirms it. */
private sealed interface BookmarkEdit {
    data class Patched(val name: String, val address: String, val port: Int, val nickname: String) : BookmarkEdit
    data object Removed : BookmarkEdit

    /** Whether the account now shows this change, i.e. the write has landed. */
    fun appliedTo(uuid: String, state: AccountState): Boolean {
        val item = state.items.firstOrNull { it.uuid == uuid }
        return when (this) {
            Removed -> item == null
            is Patched -> item?.bookmark != null &&
                item.bookmark.name == name &&
                item.bookmark.address == address &&
                item.bookmark.port == port &&
                item.bookmark.nickname == nickname
        }
    }
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

    /**
     * Bumped when a reorder is refused, so the home list stops showing the order
     * that was dragged. A successful move needs no signal: the local order and
     * the server's agree.
     */
    private val _bookmarkOrderReset = MutableStateFlow(0)
    val bookmarkOrderReset: StateFlow<Int> = _bookmarkOrderReset.asStateFlow()

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
    val accountState: StateFlow<AccountState> =
        combine(account.state, account.settled, account.itemsFresh, localBookmarkEdits) { state, settled, fresh, edits ->
            // Before the list has been read this launch, an empty live list
            // means "not read yet", not "no bookmarks". The cache is what was on
            // screen last time: it stands in until the read lands, and only the
            // read can replace it — including with an empty result.
            // The cache speaks only for a launch that may still be signed in:
            // either the sign-in question is unanswered, or it answered yes.
            val mayBeSignedIn = !settled || state.loggedIn
            val base = if (mayBeSignedIn && !fresh && state.items.isEmpty()) {
                cachedAccount ?: state
            } else {
                state
            }
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

    /** False while a launch still has to find out whether a login is remembered. */
    val accountSettled: StateFlow<Boolean> = account.settled

    /** False until the bookmark list has been read from the account this launch. */
    val bookmarksFresh: StateFlow<Boolean> = account.itemsFresh

    init {
        // The overlay outlives the dispatch: it is dropped when the write has
        // actually landed (the account's state then shows it) or when the
        // account says it did not (a failed notice). Either way the UI never
        // swaps back to the old row for the length of a round trip.
        viewModelScope.launch {
            account.notices.collect { notice ->
                when {
                    notice.command == "sync_bookmark_edit" ->
                        localBookmarkEdits.update { edits ->
                            edits.filterValues { it !is BookmarkEdit.Patched }
                        }
                    notice.command == "sync_delete" ->
                        localBookmarkEdits.update { edits ->
                            edits.filterValues { it !is BookmarkEdit.Removed }
                        }
                    notice.command == "sync_bookmark_move" && !notice.ok ->
                        _bookmarkOrderReset.update { it + 1 }
                    notice.command == "account_logout" ->
                        localBookmarkEdits.value = emptyMap()
                }
            }
        }
        // A successful write updates the list; overlays that the new state
        // already agrees with are no longer needed.
        viewModelScope.launch {
            account.state.collect { state ->
                if (!account.itemsFresh.value) return@collect
                localBookmarkEdits.update { edits ->
                    edits.filter { (uuid, edit) -> !edit.appliedTo(uuid, state) }
                }
            }
        }
        // Keep the cache current: every state the UI would render is what the
        // next launch should draw. It follows the account only once settled —
        // the blank state at the start of a launch is not a logout, and clearing
        // on it would delete the very cache being restored. A read that agrees
        // with the account is worth keeping even when it is empty, so a deletion
        // does not leave the old rows to reappear next launch.
        viewModelScope.launch {
            combine(account.state, account.settled, account.itemsFresh) { state, settled, fresh ->
                Triple(state, settled, fresh)
            }.collect { (state, settled, fresh) ->
                when {
                    !settled -> Unit
                    !state.loggedIn -> bookmarkCache.clear()
                    fresh -> bookmarkCache.save(state)
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val ui: StateFlow<SessionUiState> = controllerFlow
        .flatMapLatest { it?.ui ?: flowOf(SessionUiState()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SessionUiState())

    @OptIn(ExperimentalCoroutinesApi::class)
    val permissions: StateFlow<PermissionEditorState> = controllerFlow
        .flatMapLatest { it?.permissions ?: flowOf(PermissionEditorState()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, PermissionEditorState())

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

    /**
     * Whether the file tab appears in the bottom bar.
     *
     * A `StateFlow`, not a getter over [Settings]: the toggle lives in Settings
     * but the bar lives in the activity shell, and only an observable value
     * makes the bar redraw while the settings screen is still on screen.
     */
    private val _filesTabEnabled = MutableStateFlow(settings.filesTabEnabled)
    val filesTabEnabled: StateFlow<Boolean> = _filesTabEnabled.asStateFlow()

    /**
     * Stores the switch and, when it hides the tab the user is standing on, moves
     * them off it: the bar no longer has a destination to show, and leaving the
     * nav state on a hidden tab would strand the file screen with no way back.
     */
    fun setFilesTabEnabled(enabled: Boolean) {
        settings.filesTabEnabled = enabled
        _filesTabEnabled.value = enabled
        if (!enabled && _navTab.value == NavTab.Files) {
            selectNavTab(NavTab.Channels)
        }
    }

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
    fun userVolume(user: User): Float = controllerFlow.value?.userVolume(user) ?: 1.0f
    fun setUserVolume(user: User, volume: Float) = withController { it.setUserVolume(user, volume) }
    fun isWhisperingTo(user: User): Boolean =
        controllerFlow.value?.isWhisperingTo(user) ?: false
    fun setWhisperTo(user: User, enabled: Boolean) =
        withController { it.setWhisperTo(user, enabled) }
    fun setWhisperTargets(
        clients: List<Int>,
        channels: List<Long>,
        groups: List<dev.tsdroid.model.WhisperGroup>,
    ) = withController { it.setWhisperTargets(clients, channels, groups) }

    fun loadPermissions(target: PermissionTarget) =
        withController { it.loadPermissions(target) }
    fun addPermission(target: PermissionTarget, name: String, value: Int) =
        withController { it.addPermission(target, name, value) }
    fun deletePermission(target: PermissionTarget, name: String) =
        withController { it.deletePermission(target, name) }
    fun joinChannel(channelId: Long, password: String? = null) =
        withController { it.joinChannel(channelId, password) }
    fun toggleMic() = withController { it.toggleMic() }
    fun toggleSpeaker() = withController { it.toggleSpeaker() }
    fun setAway(away: Boolean, message: String = "") = withController { it.setAway(away, message) }
    fun setNickname(nickname: String) = withController { it.setNickname(nickname) }
    fun editServer(
        name: String,
        welcomeMessage: String,
        maxClients: Int,
        password: String,
        hostMessage: String,
        hostBannerUrl: String,
        neededSecurityLevel: Int,
    ) = withController {
        it.editServer(
            name = name,
            welcomeMessage = welcomeMessage,
            maxClients = maxClients,
            password = password,
            hostMessage = hostMessage,
            hostBannerUrl = hostBannerUrl,
            neededSecurityLevel = neededSecurityLevel,
        )
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
    ) = withController {
        it.editChannel(
            channelId = channelId,
            name = name,
            topic = topic,
            maxClients = maxClients,
            maxFamilyClients = maxFamilyClients,
            neededTalkPower = neededTalkPower,
            codec = codec,
            codecQuality = codecQuality,
            codecLatencyFactor = codecLatencyFactor,
            deleteDelay = deleteDelay,
            isDefault = isDefault,
            isPermanent = isPermanent,
            isSemiPermanent = isSemiPermanent,
            password = password,
        )
    }
    fun applyAudioSettings(gain: Float, noiseSuppression: Boolean, bluetoothMic: Boolean) =
        withController { it.applyAudioSettings(gain, noiseSuppression, bluetoothMic) }
    fun applyOutputGain(gain: Float) = withController { it.applyOutputGain(gain) }
    fun openFiles(channelId: Long) = withController { it.openFiles(channelId) }
    fun refreshFiles() = withController { it.refreshFiles() }
    fun downloadFile(file: FileEntry) = withController { it.downloadFile(file) }
    fun uploadFile(uri: Uri, channelId: Long) = withController { it.uploadFile(uri, channelId) }
    /**
     * A message for the user, independent of any session.
     *
     * The session's own `ui.toast` only exists while a controller is bound — so
     * account results and bookmark errors were silently dropped whenever the
     * user was on the home screen with nothing connected. This flow is the
     * screen-facing one; the controller keeps its own for session events.
     */
    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    fun setToast(message: String) { _toast.value = message }

    fun clearToast() {
        _toast.value = null
        withController { it.clearToast() }
    }

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
        // On acceptance the row stays hidden until the account's read-back
        // actually lacks it. Dropping the overlay here made the row reappear
        // for the length of the round trip — the opposite of optimistic.
        account.deleteBookmark(uuid)?.let { error ->
            localBookmarkEdits.update { it - uuid }
            setToast(error)
        }
    }

    /** Edits a bookmark, showing the new fields at once. */
    fun accountEditBookmark(uuid: String, name: String, address: String, port: Int, nickname: String) {
        localBookmarkEdits.update {
            it + (uuid to BookmarkEdit.Patched(name, address, port, nickname))
        }
        account.editBookmark(uuid, name, address, port, nickname)?.let { error ->
            localBookmarkEdits.update { it - uuid }
            setToast(error)
        }
    }

    /**
     * Reorders, which the list already showed locally.
     *
     * The drag moved the row before this was called, so there is nothing to
     * apply here: only a refusal needs reporting, and the next snapshot puts the
     * rows back where the server has them.
     */
    fun accountMoveBookmark(uuid: String, after: String) {
        account.moveBookmark(uuid, after)?.let { error ->
            // Refused before it was sent: the list was already reordered by the
            // drag, so undo that too.
            _bookmarkOrderReset.update { it + 1 }
            setToast(error)
        }
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
