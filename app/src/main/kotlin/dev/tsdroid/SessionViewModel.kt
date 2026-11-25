package dev.tsdroid

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
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
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The screen-facing facade over [SessionController].
 *
 * For now the controller is created and owned here, one per ViewModel instance.
 * A later step moves its ownership into a foreground service so the session
 * outlives the Activity; this facade stays the same, so the UI does not change.
 */
class SessionViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = Settings(application)
    private val servers = ServerStore(application)
    private val account: AccountClient = (application as TsDroidApp).account

    private val controller = SessionController(application, settings, servers, account)

    val accountState: StateFlow<AccountState> = account.state
    val accountError: StateFlow<String?> = account.startError
    val accountNotices: SharedFlow<AccountNotice> = account.notices

    val ui: StateFlow<SessionUiState> = controller.ui
    val navTab: StateFlow<NavTab> = controller.navTab
    val micLevel: StateFlow<Float> = controller.micLevel
    val audioError: StateFlow<String?> = controller.audioError
    val filesTabEnabled: Boolean get() = settings.filesTabEnabled

    // ── session commands ──────────────────────────────────────────────────
    fun connect(config: ServerConfig) = controller.connect(config)
    fun leave() = controller.leave()
    fun disconnect() = controller.disconnect()
    fun isLive(): Boolean = controller.isLive()
    fun reconnectNow() = controller.reconnectNow()
    fun reconnect() = controller.reconnect()
    fun reconnectWithToken(token: String) = controller.reconnectWithToken(token)
    fun selectTab(tab: SessionTab) = controller.selectTab(tab)
    fun selectNavTab(tab: NavTab) = controller.selectNavTab(tab)
    fun selectScope(scope: ChatScope) = controller.selectScope(scope)
    fun openPrivateChat(user: User) = controller.openPrivateChat(user)
    fun sendMessage(text: String) = controller.sendMessage(text)
    fun poke(user: User, message: String) = controller.poke(user, message)
    fun joinChannel(channelId: Long, password: String? = null) = controller.joinChannel(channelId, password)
    fun toggleMic() = controller.toggleMic()
    fun toggleSpeaker() = controller.toggleSpeaker()
    fun setAway(away: Boolean, message: String = "") = controller.setAway(away, message)
    fun setNickname(nickname: String) = controller.setNickname(nickname)
    fun applyAudioSettings(gain: Float, noiseSuppression: Boolean, bluetoothMic: Boolean) =
        controller.applyAudioSettings(gain, noiseSuppression, bluetoothMic)
    fun applyOutputGain(gain: Float) = controller.applyOutputGain(gain)
    fun openFiles(channelId: Long) = controller.openFiles(channelId)
    fun refreshFiles() = controller.refreshFiles()
    fun downloadFile(file: FileEntry) = controller.downloadFile(file)
    fun uploadFile(uri: Uri, channelId: Long) = controller.uploadFile(uri, channelId)
    fun setToast(message: String) = controller.setToast(message)
    fun clearToast() = controller.clearToast()

    fun adoptAccountIdentityAndConnect(config: ServerConfig) =
        controller.adoptAccountIdentityAndConnect(config)

    fun settings(): Settings = settings
    fun serverStore(): ServerStore = servers

    // ── account ───────────────────────────────────────────────────────────
    fun accountLogin(email: String, password: String, otp: String = ""): String? =
        account.login(email.trim(), password, otp.trim())
    fun accountLogout(): String? = account.logout()
    fun accountRefresh(): String? = account.refreshAccount()
    fun accountBackup(): String? = account.backup()
    fun accountBookmarkSecrets(uuid: String): BookmarkSecrets? = account.bookmarkSecrets(uuid)
    fun accountDeleteBookmark(uuid: String): String? = account.deleteBookmark(uuid)
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
        controller.shutdown()
    }
}
