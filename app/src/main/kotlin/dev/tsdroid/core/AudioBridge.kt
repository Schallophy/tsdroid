package dev.tsdroid.core

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Capture and playback, kept on the Android side on purpose.
 *
 * Everything that is hard about voice on Android lives here — the record source,
 * echo cancellation, the call-mode route that a Bluetooth headset's microphone
 * requires — and none of it has to be reimplemented in Rust. The native layer
 * only sees PCM16 frames in and PCM16 frames out.
 */
class AudioBridge(context: Context) {

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var suppressor: NoiseSuppressor? = null
    private var captureThread: Thread? = null
    private var playbackThread: Thread? = null
    private var client: NativeClient? = null

    @Volatile
    private var running = false

    /** Local mic gate; the server-side muted flag is separate and also exists. */
    @Volatile
    var micEnabled: Boolean = true

    @Volatile
    var speakerEnabled: Boolean = true

    @Volatile
    var noiseSuppressionEnabled: Boolean = true

    @Volatile
    var bluetoothMicEnabled: Boolean = false

    @Volatile
    var micGain: Float = 1.0f

    /** Linear playback gain applied before the write; 1.0 is untouched. */
    @Volatile
    var outputGain: Float = 1.0f

    private val _level = MutableStateFlow(0f)

    /** 0..1 input level, for the meter around the mic button. */
    val level: StateFlow<Float> = _level.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    @SuppressLint("MissingPermission")
    fun start(target: NativeClient) {
        if (running) return
        client = target
        _error.value = null

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(FRAME_BYTES * 4)

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer,
            )
        } catch (t: Throwable) {
            _error.value = "无法打开麦克风：${t.message}"
            return
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            _error.value = "麦克风初始化失败（可能没有录音权限）"
            return
        }
        record = recorder

        val player = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    // Media usage, not voice communication. On several OEM
                    // builds (MIUI included) USAGE_VOICE_COMMUNICATION plays
                    // through the call stream or the earpiece, and the volume
                    // keys then move the call volume instead of media — which
                    // is exactly the "everyone is too quiet and I cannot make
                    // it louder" report. Capture stays VOICE_COMMUNICATION so
                    // the platform keeps running its voice processing on the
                    // microphone.
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(FRAME_BYTES * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (t: Throwable) {
            _error.value = "无法打开扬声器：${t.message}"
            recorder.release()
            record = null
            return
        }
        track = player

        if (noiseSuppressionEnabled) attachEffects(recorder.audioSessionId)

        applyRoute()

        running = true
        recorder.startRecording()
        player.play()

        captureThread = thread(name = "voice-in", priority = Thread.MAX_PRIORITY) { captureLoop() }
        playbackThread = thread(name = "voice-out", priority = Thread.MAX_PRIORITY) { playbackLoop() }
    }

    fun stop() {
        running = false
        captureThread?.join(500)
        playbackThread?.join(500)
        captureThread = null
        playbackThread = null

        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null

        track?.let {
            runCatching { it.stop() }
            it.release()
        }
        track = null

        echoCanceler?.let { runCatching { it.release() } }
        suppressor?.let { runCatching { it.release() } }
        echoCanceler = null
        suppressor = null

        releaseRoute()
        _level.value = 0f
        client?.audioReset()
        client = null
    }

    /** Re-applies the routing decision after the user changes a setting. */
    fun refreshRoute() {
        if (!running) return
        applyRoute()
        if (noiseSuppressionEnabled) {
            record?.let { attachEffects(it.audioSessionId) }
        } else {
            suppressor?.let { runCatching { it.enabled = false; it.release() } }
            suppressor = null
        }
    }

    private fun captureLoop() {
        val buffer = ShortArray(FRAME_SAMPLES)
        while (running) {
            val recorder = record ?: break
            val read = try {
                recorder.read(buffer, 0, buffer.size)
            } catch (t: Throwable) {
                _error.value = "录音中断：${t.message}"
                break
            }
            if (read <= 0) continue

            // Gain is applied by the core, identically on every platform. The
            // meter scales the raw peak only for display so it still shows what
            // will be transmitted.
            var peak = 0f
            val gain = micGain
            for (index in 0 until read) {
                val magnitude = abs(buffer[index].toInt()) * gain
                if (magnitude > peak) peak = magnitude
            }
            _level.value = (peak / 32768f).coerceIn(0f, 1f)

            if (micEnabled) {
                val target = client ?: break
                if (read == buffer.size) {
                    target.audioWrite(buffer)
                } else {
                    target.audioWrite(buffer.copyOf(read))
                }
            }
        }
    }

    private fun playbackLoop() {
        val buffer = ShortArray(FRAME_SAMPLES)
        while (running) {
            val target = client ?: break
            val count = target.audioRead(buffer)
            if (count <= 0) {
                Thread.sleep(5)
                continue
            }
            if (!speakerEnabled) continue
            // The gain lives here rather than in the core because it is a
            // loudness preference of this device's output chain, not part of
            // the protocol; capture gain is the opposite and stays in the core.
            val gain = outputGain
            if (gain != 1.0f) {
                for (index in 0 until count) {
                    buffer[index] = softClip(buffer[index] * gain)
                }
            }
            try {
                track?.write(buffer, 0, count)
            } catch (t: Throwable) {
                _error.value = "播放中断：${t.message}"
                break
            }
        }
    }

    /** Same soft-knee curve the core uses for mic gain, so boosts never wrap. */
    private fun softClip(sample: Float): Short {
        val limit = Short.MAX_VALUE.toFloat()
        val value = sample / limit
        val limited = if (value > 0.5f) {
            0.5f + 0.5f * (1f - 1f / (1f + (value - 0.5f) * 2f))
        } else if (value < -0.5f) {
            -(0.5f + 0.5f * (1f - 1f / (1f + (-value - 0.5f) * 2f)))
        } else {
            value
        }
        return (limited.coerceIn(-1f, 1f) * limit).toInt().toShort()
    }

    private fun attachEffects(sessionId: Int) {
        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceler?.release()
            echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
        }
        if (NoiseSuppressor.isAvailable()) {
            suppressor?.release()
            suppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
        }
    }

    /**
     * Bluetooth headsets only expose their microphone over the call route, which
     * means entering communication mode. The option is off by default because
     * that route narrow-bands every other sound the phone plays.
     */
    private fun applyRoute() {
        if (!bluetoothMicEnabled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audioManager.stopBluetoothSco()
                @Suppress("DEPRECATION")
                audioManager.isBluetoothScoOn = false
            }
            audioManager.mode = AudioManager.MODE_NORMAL
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val sco = audioManager.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
            if (sco == null) {
                _error.value = "没有连接支持通话音频的蓝牙耳机，已改用手机麦克风"
                audioManager.mode = AudioManager.MODE_NORMAL
                return
            }
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            if (!audioManager.setCommunicationDevice(sco)) {
                _error.value = "切换到蓝牙耳机失败，已改用手机麦克风"
                audioManager.mode = AudioManager.MODE_NORMAL
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
            @Suppress("DEPRECATION")
            audioManager.startBluetoothSco()
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = true
        }
    }

    private fun releaseRoute() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audioManager.clearCommunicationDevice() }
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
            }
        }
        runCatching { audioManager.mode = AudioManager.MODE_NORMAL }
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        const val FRAME_SAMPLES = 960
        private const val FRAME_BYTES = FRAME_SAMPLES * 2
    }
}
