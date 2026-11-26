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
    private lateinit var settings: Settings

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
        settings = Settings(this)
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
            app.account,
        )
        created.onSessionEnded = { endSession() }
        created.onStateChanged = { refreshNotification() }
        controller = created
        return created
    }

    /**
     * Ends the session and stops the service.
     *
     * The controller's own shutdown stops audio, the wake lock and the native
     * client; the service then has nothing to host, so it stops rather than
     * lingering as an empty process.
     */
    fun endSession() {
        controller?.let {
            it.onSessionEnded = null
            it.onStateChanged = null
            it.shutdown()
        }
        controller = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        controller?.let {
            it.onSessionEnded = null
            it.onStateChanged = null
            it.shutdown()
        }
        controller = null
        super.onDestroy()
    }

    /**
     * The app was swiped off the recent-tasks list.
     *
     * With "keep in background" on, the session keeps running and the
     * notification is the way back. Off, the swipe ends the call — the same as
     * closing any other app.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!settings.keepInBackground) {
            controller?.leave()
            endSession()
        }
        super.onTaskRemoved(rootIntent)
    }

    // ── foreground notification ───────────────────────────────────────────

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "通话",
            // Low: the notification is a status line and a few controls, not
            // something that should buzz or pop.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "连接服务器期间显示"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val ui = controller?.ui?.value
        val server = ui?.state?.server?.name?.ifEmpty { null }
            ?: ui?.config?.address
            ?: "语音通话"
        val status = when (ui?.status) {
            SessionStatus.Online -> server
            SessionStatus.Connecting -> "连接中…"
            SessionStatus.Failed -> "连接失败"
            SessionStatus.Disconnected -> "已断开"
            else -> server
        }
        val micOn = ui?.micEnabled ?: true
        val speakerOn = ui?.speakerEnabled ?: true

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_headset)
            .setContentTitle(status)
            .setContentText(if (micOn) "麦克风开" else "麦克风关")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                action(micOn, ACTION_MIC, if (micOn) "静音" else "取消静音"),
            )
            .addAction(
                action(!speakerOn, ACTION_SPEAKER, if (speakerOn) "关听筒" else "开听筒"),
            )
            .addAction(action(false, ACTION_DISCONNECT, "断开"))
            .build()
    }

    private fun action(active: Boolean, name: String, label: String): NotificationCompat.Action {
        val intent = Intent(this, SessionService::class.java).setAction(name)
        val pending = PendingIntent.getService(
            this,
            name.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Action(0, label, pending)
    }

    private fun refreshNotification() {
        if (controller == null) return
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification())
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

        when (intent?.action) {
            ACTION_MIC -> controller?.toggleMic()
            ACTION_SPEAKER -> controller?.toggleSpeaker()
            ACTION_DISCONNECT -> {
                controller?.leave()
                endSession()
            }
        }
        return START_NOT_STICKY
    }

    private companion object {
        const val CHANNEL_ID = "session"
        const val NOTIFICATION_ID = 1
        const val ACTION_MIC = "dev.tsdroid.MIC"
        const val ACTION_SPEAKER = "dev.tsdroid.SPEAKER"
        const val ACTION_DISCONNECT = "dev.tsdroid.DISCONNECT"
    }
}
