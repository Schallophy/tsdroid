package dev.tsdroid.data

import android.content.Context

/**
 * Audio and identity settings that live only on this device.
 *
 * SharedPreferences rather than DataStore: a handful of scalars read once at
 * session start, with no reactivity requirement beyond restarting the audio
 * bridge by hand.
 */
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

    var noiseSuppression: Boolean
        get() = prefs.getBoolean(KEY_NOISE, true)
        set(value) = prefs.edit().putBoolean(KEY_NOISE, value).apply()

    var bluetoothMic: Boolean
        get() = prefs.getBoolean(KEY_BLUETOOTH_MIC, false)
        set(value) = prefs.edit().putBoolean(KEY_BLUETOOTH_MIC, value).apply()

    /**
     * Which signed client version to announce: `latest` (default), `debug` or
     * `ancient`. The server checks the announced text against its signature,
     * so this is a profile name, never free text.
     */
    var versionProfile: String
        get() = prefs.getString(KEY_VERSION_PROFILE, "latest").orEmpty()
        set(value) = prefs.edit().putString(KEY_VERSION_PROFILE, value).apply()

    /** Away message restored on the next connect. */
    var awayMessage: String
        get() = prefs.getString(KEY_AWAY_MESSAGE, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_AWAY_MESSAGE, value).apply()

    private companion object {
        const val KEY_MIC_GAIN = "mic_gain"
        const val KEY_OUTPUT_GAIN = "output_gain"
        const val KEY_NOISE = "noise_suppression"
        const val KEY_BLUETOOTH_MIC = "bluetooth_mic"
        const val KEY_AWAY_MESSAGE = "away_message"
        const val KEY_VERSION_PROFILE = "version_profile"
    }
}
