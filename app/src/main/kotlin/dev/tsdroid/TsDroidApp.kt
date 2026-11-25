package dev.tsdroid

import android.app.Application
import dev.tsdroid.core.AccountClient

/**
 * The application entry point.
 *
 * Owns the myTeamSpeak account for the whole process. The account is not part of
 * a server connection — it can be signed in before any server is joined and it
 * survives a disconnect — so it lives here rather than on a screen or a session.
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
