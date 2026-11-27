package dev.tsdroid.data

import android.content.Context
import dev.tsdroid.core.Native

/**
 * Audio and identity settings that live only on this device.
 *
 * SharedPreferences rather than DataStore: a handful of scalars read once at
 * session start, with no reactivity requirement beyond restarting the audio
 * bridge by hand.
 */
/** What to do when another app takes the audio focus. */
object AudioFocusAction {
    /** Mute our microphone and pause the others' audio; restore when focus returns. */
    const val MUTE_BOTH = "mute_both"
    /** Mute our microphone only. */
    const val MUTE_SELF = "mute_self"
    /** Drop the connection. */
    const val DISCONNECT = "disconnect"
    /** Do nothing. */
    const val IGNORE = "ignore"
}

class Settings(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var micGain: Float
        get() = prefs.getFloat(KEY_MIC_GAIN, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_MIC_GAIN, value.coerceIn(0.2f, 4f)).apply()

    /**
     * Linear playback boost. 1.0 is untouched; above that a soft-knee limiter
     * keeps a loud talker from wrapping around to distortion.
     */
    var outputGain: Float
        get() = prefs.getFloat(KEY_OUTPUT_GAIN, 1.0f).coerceIn(0.5f, 2f)
        set(value) = prefs.edit().putFloat(KEY_OUTPUT_GAIN, value.coerceIn(0.5f, 2f)).apply()

    /**
     * Playback volume for one client, keyed by their TeamSpeak UID so it
     * survives a reconnect and a nickname change. 1.0 is untouched; the core
     * clips at 2.0.
     */
    fun userVolume(uid: String): Float =
        prefs.getFloat(KEY_USER_VOLUME + uid.ifEmpty { "unknown" }, 1.0f)
            .coerceIn(0.0f, 2.0f)

    fun setUserVolume(uid: String, volume: Float) {
        prefs.edit()
            .putFloat(
                KEY_USER_VOLUME + uid.ifEmpty { "unknown" },
                volume.coerceIn(0.0f, 2.0f),
            )
            .apply()
    }

    var noiseSuppression: Boolean
        get() = prefs.getBoolean(KEY_NOISE, true)
        set(value) = prefs.edit().putBoolean(KEY_NOISE, value).apply()

    var bluetoothMic: Boolean
        get() = prefs.getBoolean(KEY_BLUETOOTH_MIC, false)
        set(value) = prefs.edit().putBoolean(KEY_BLUETOOTH_MIC, value).apply()

    /**
     * The client version number announced to servers (`3.6.3`). Paired with
     * [clientPlatform]; the native side resolves the pair to the full signed
     * version text. Global: one choice applies to every server.
     */
    var clientVersion: String
        get() = prefs.getString(KEY_CLIENT_VERSION, DEFAULT_VERSION).orEmpty()
        set(value) = prefs.edit().putString(KEY_CLIENT_VERSION, value.trim()).apply()

    /**
     * The pair actually announced, repaired to something that can connect.
     *
     * Two different questions, and only the second one matters here:
     *
     * - Does the signature resolve? `3.0.13` on Android does — the table still
     *   carries a valid signature for it.
     * - Will a current server accept it? No: anything below 3.1 is refused with
     *   `ClientVersionOutdated`, measured against a live server.
     *
     * So this checks membership in [Native.nativeVersionChoices], the list of
     * versions that both resolve and are new enough, rather than resolving the
     * pair on its own. A stored value from an older build (or one the table has
     * since dropped) falls back to the standard tuple instead of costing the
     * user a refused connection they cannot explain.
     *
     * Used through [withAnnouncedVersion], which is the only place a connection
     * should pick the version up.
     */
    fun announceableVersion(): Pair<String, String> {
        val version = clientVersion
        val platform = clientPlatform
        val offered = Native.nativeVersionChoices(platform).split(',')
        if (version.isNotEmpty() && offered.contains(version)) {
            return version to platform
        }
        // The platform itself may be one the table no longer signs; fall back to
        // the standard pair, which is known to resolve.
        val fallbackPlatform = if (offered.any { it.isNotEmpty() }) platform else DEFAULT_PLATFORM
        val fallbackOffered = Native.nativeVersionChoices(fallbackPlatform).split(',')
        val fallbackVersion = if (fallbackOffered.contains(DEFAULT_VERSION)) {
            DEFAULT_VERSION
        } else {
            fallbackOffered.firstOrNull { it.isNotEmpty() } ?: DEFAULT_VERSION
        }
        // Write the repair back. Reading is where the bad value is discovered,
        // and leaving it on disk means every launch repairs it again — and the
        // settings screen would keep opening on a value the list does not
        // contain.
        clientVersion = fallbackVersion
        clientPlatform = fallbackPlatform
        return fallbackVersion to fallbackPlatform
    }

    /**
     * The platform announced with [clientVersion]. Only platforms with a signed
     * entry are accepted by the native side; the settings screen offers exactly
     * those.
     */
    var clientPlatform: String
        get() = prefs.getString(KEY_CLIENT_PLATFORM, DEFAULT_PLATFORM).orEmpty()
        set(value) = prefs.edit().putString(KEY_CLIENT_PLATFORM, value).apply()

    /**
     * A privilege key redeemed on the next connect, then cleared by the user.
     * Single-use on the server, so keeping it around does nothing.
     */
    var privilegeKey: String
        get() = prefs.getString(KEY_PRIVILEGE_KEY, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_PRIVILEGE_KEY, value).apply()

    /**
     * The identity that worked on a server, per address.
     *
     * Which identity a server knows cannot always be read from the account
     * (two identities may share a name), so the first successful connection
     * records the winner and later connects reuse it without probing.
     *
     * The key carries a generation on purpose: entries written before the
     * identity pick was awaited may name the wrong key, and one of those would
     * otherwise shadow the correct account identity forever.
     */
    fun identityForServer(address: String): String? =
        prefs.getString(KEY_IDENTITY_PREFIX + address.lowercase(), null)?.takeIf { it.isNotEmpty() }

    fun rememberIdentityForServer(address: String, identity: String) {
        prefs.edit().putString(KEY_IDENTITY_PREFIX + address.lowercase(), identity).apply()
    }

    /** Away message restored on the next connect. */
    var awayMessage: String
        get() = prefs.getString(KEY_AWAY_MESSAGE, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_AWAY_MESSAGE, value).apply()

    /**
     * Whether the file browser tab appears in the bottom navigation.
     *
     * File transfer is a minority feature: keeping it off by default leaves the
     * navigation clean, and the setting lives in the file section of Settings so
     * turning it on is a deliberate act.
     */
    var filesTabEnabled: Boolean
        get() = prefs.getBoolean(KEY_FILES_TAB, false)
        set(value) = prefs.edit().putBoolean(KEY_FILES_TAB, value).apply()

    /**
     * Keep the session alive when the app is swiped off the recent-tasks list.
     *
     * On, the foreground service survives the swipe and the notification is the
     * way back; off, the swipe ends the call like closing any other app.
     */
    var keepInBackground: Boolean
        get() = prefs.getBoolean(KEY_KEEP_BACKGROUND, true)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_BACKGROUND, value).apply()

    /**
     * What to do when another app takes the audio focus (a call, navigation).
     *
     * One of [AudioFocusAction.MUTE_BOTH], [AudioFocusAction.MUTE_SELF],
     * [AudioFocusAction.DISCONNECT], [AudioFocusAction.IGNORE]. The first is the
     * default because leaking the microphone into a phone call is the worst
     * outcome.
     */
    var audioFocusAction: String
        get() = prefs.getString(KEY_FOCUS_ACTION, AudioFocusAction.MUTE_BOTH).orEmpty()
        set(value) = prefs.edit().putString(KEY_FOCUS_ACTION, value).apply()

    /**
     * The temporary-connect form, kept so a relaunch shows what was last typed.
     *
     * This is a convenience, not a saved server: it is one scratch form, not an
     * entry in a list, and it is cleared by the user rather than by connecting.
     * The password is deliberately *not* stored here — see [ServerStore], which
     * keeps passwords encrypted per address.
     */
    var draftAddress: String
        get() = prefs.getString(KEY_DRAFT_ADDRESS, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_DRAFT_ADDRESS, value).apply()

    var draftNickname: String
        get() = prefs.getString(KEY_DRAFT_NICKNAME, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_DRAFT_NICKNAME, value).apply()

    var draftChannel: String
        get() = prefs.getString(KEY_DRAFT_CHANNEL, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_DRAFT_CHANNEL, value).apply()

    /**
     * Whether a connection starts with the microphone muted.
     *
     * Joining a server is not consent to be heard: a hot mic in a channel of
     * strangers is the failure this prevents. Muted is the default, and the mic
     * bar is one tap away for anyone who does want to talk.
     */
    var startMuted: Boolean
        get() = prefs.getBoolean(KEY_START_MUTED, true)
        set(value) = prefs.edit().putBoolean(KEY_START_MUTED, value).apply()

    /** Whether the one-time battery-optimisation hint has been shown. */
    var batteryHintShown: Boolean
        get() = prefs.getBoolean(KEY_BATTERY_HINT, false)
        set(value) = prefs.edit().putBoolean(KEY_BATTERY_HINT, value).apply()

    private companion object {
        const val KEY_MIC_GAIN = "mic_gain"
        const val KEY_OUTPUT_GAIN = "output_gain"
        const val KEY_USER_VOLUME = "user_volume_"
        const val KEY_NOISE = "noise_suppression"
        const val KEY_BLUETOOTH_MIC = "bluetooth_mic"
        const val KEY_AWAY_MESSAGE = "away_message"
        const val KEY_CLIENT_VERSION = "client_version"
        const val KEY_CLIENT_PLATFORM = "client_platform"
        const val KEY_PRIVILEGE_KEY = "privilege_key"
        const val KEY_IDENTITY_PREFIX = "identity_for_v2_"
        const val KEY_FILES_TAB = "files_tab_enabled"
        const val KEY_KEEP_BACKGROUND = "keep_in_background"
        const val KEY_FOCUS_ACTION = "audio_focus_action"
        const val KEY_BATTERY_HINT = "battery_hint_shown"
        const val KEY_START_MUTED = "start_muted"
        const val KEY_DRAFT_ADDRESS = "draft_address"
        const val KEY_DRAFT_NICKNAME = "draft_nickname"
        const val KEY_DRAFT_CHANNEL = "draft_channel"

        /** `3.6.3` + `Windows`: the standard signed tuple, unchanged from before. */
        const val DEFAULT_VERSION = "3.6.3"
        const val DEFAULT_PLATFORM = "Windows"

    }
}
