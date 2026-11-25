package dev.tsdroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.tsdroid.core.AccountClient
import dev.tsdroid.data.ServerStore
import dev.tsdroid.data.Settings

/**
 * Hosts the session so it outlives the Activity.
 *
 * The connection, audio and the per-session state live in [SessionController],
 * which this service owns. The UI binds here and reads through the controller;
 * a rotation, backgrounding or the screen turning off does not touch any of it,
 * because none of it belongs to a screen.
 *
 * The service is a foreground service for exactly as long as a session exists:
 * Android only keeps a microphone alive in the background for a foreground
 * service of type `microphone`, and only such a service is exempt from the
 * process freeze that would otherwise stop the audio tick on a locked screen.
 */
class SessionService : Service() {

    private val binder = LocalBinder()

    /** The live session, or null before the first connect and after the last leave. */
    var controller: SessionController? = null
        private set

    inner class LocalBinder : Binder() {
        /** The service itself, so a bound client can reach [controller]. */
        fun service(): SessionService = this@SessionService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    /**
     * Starts the session for a connection, or returns the running one.
     *
     * Called by the UI on the main thread. The controller is created here rather
     * than in the Activity so its lifetime is the service's, not the screen's.
     */
    fun session(): SessionController {
        controller?.let { return it }
        val app = application as TsDroidApp
        val created = SessionController(
            app,
            Settings(this),
            ServerStore(this),
            app.account as AccountClient,
        )
        created.onSessionEnded = { endSession() }
        controller = created
        return created
    }

    /**
     * Ends the session and stops the service.
     *
     * The controller's own shutdown stops audio and the native client; the
     * service then has nothing to host, so it stops rather than lingering as an
     * empty process.
     */
    fun endSession() {
        controller?.let {
            it.onSessionEnded = null
            it.shutdown()
        }
        controller = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        controller?.let {
            it.onSessionEnded = null
            it.shutdown()
        }
        controller = null
        super.onDestroy()
    }

    // ── foreground notification ───────────────────────────────────────────

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "通话",
            // Low: the notification is a status line and a couple of controls,
            // not something that should buzz or pop.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "连接服务器期间显示"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_headset)
            .setContentTitle("已连接")
            .setContentText(controller?.ui?.value?.state?.server?.name?.ifEmpty { null } ?: "语音通话中")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startInForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A started (not just bound) foreground service has to call
        // startForeground promptly or the system kills it.
        startInForeground()
        return START_NOT_STICKY
    }

    private companion object {
        const val CHANNEL_ID = "session"
        const val NOTIFICATION_ID = 1
    }
}
