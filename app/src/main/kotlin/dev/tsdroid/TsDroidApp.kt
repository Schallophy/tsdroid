package dev.tsdroid

import android.app.Application
import dev.tsdroid.core.AccountClient
import dev.tsdroid.data.Settings

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
        // Repair a stored version pair before anything can announce it. The
        // native table decides what is offered, and a value written by an older
        // build may name a version that resolves but that no current server
        // accepts — a connection refused for a reason the user cannot see.
        Settings(this).announceableVersion()
        account = AccountClient(this)
        account.start()
    }
}
