package dev.tsdroid

import android.app.Application
import dev.tsdroid.core.AccountClient

/**
 * The application entry point.
 *
 * Owns the two things that outlive any screen: the myTeamSpeak account, and the
 * binding to [SessionService]. The account is not part of a server connection —
 * it can be signed in before any server is joined and it survives a disconnect.
 * The service binding is here so the UI can reach the session the service owns
 * without the session being owned by the UI.
 */
class TsDroidApp : Application() {

    /** The account side, started once at process start. */
    lateinit var account: AccountClient
        private set

    override fun onCreate() {
        super.onCreate()
        account = AccountClient(this)
        account.start()
    }
}
