package dev.tsdroid.core

/**
 * The entire surface of the native library.
 *
 * Eleven functions, and the count is fixed:
 *
 * | feature | crosses the boundary as |
 * |---|---|
 * | connect / state / events | `nativeCreate`, `nativeSnapshot`, `nativePoll` |
 * | everything the UI can *do* | one [nativeExec] call with a name + JSON args |
 * | voice | the three audio calls (a 50 Hz stream does not belong in JSON) |
 *
 * The symbol names must match `native/src/lib.rs` exactly
 * (`Java_dev_tsdroid_core_Native_<name>`), which is why this class lives at a
 * fixed package and is not renamed casually.
 */
internal object Native {

    /**
     * Why the library failed to load, or null when it loaded.
     *
     * `System.loadLibrary` in an `init` block turns a missing or mismatched .so
     * into `ExceptionInInitializerError` at the first call site — which is the
     * connect button, so it reads to the user as "connecting crashes the app".
     * Catching it here turns that into a message the UI can show, and keeps the
     * reason (`dlopen failed: …`) instead of losing it in a class-init failure.
     */
    val loadError: String? = runCatching {
        System.loadLibrary("tsdroid_core")
    }.exceptionOrNull()?.let(::describeLoadFailure)

    private fun describeLoadFailure(error: Throwable): String {
        val detail = error.message.orEmpty()
        val hint = when {
            detail.contains("is 64-bit instead of 32-bit", ignoreCase = true) ->
                "（设备是 32 位，本包只带 arm64-v8a）"
            detail.contains("is 32-bit instead of 64-bit", ignoreCase = true) ->
                "（设备是 64 位，本包只带 arm64-v8a）"
            detail.contains("has unexpected e_machine", ignoreCase = true) ->
                "（CPU 架构不匹配，本包只带 arm64-v8a）"
            detail.contains("not found", ignoreCase = true) ->
                "（APK 里没有 libtsdroid_core.so）"
            detail.contains("cannot locate symbol", ignoreCase = true) ->
                "（so 依赖了设备上没有的系统符号，通常是 Android 版本过低）"
            else -> ""
        }
        return "原生库加载失败$hint：${error::class.java.simpleName}: $detail"
    }

    /**
     * Starts connecting and returns an opaque handle, or 0 if the arguments were
     * rejected outright. Connection progress arrives as events, because the
     * actual connect happens on the native worker thread.
     *
     * `serverPassword` unlocks the server; `channelPassword` unlocks the channel
     * it lands in. They are different secrets and are kept in that order here and
     * in `native/src/lib.rs` — the JNI argument list is positional.
     */
    external fun nativeCreate(
        address: String,
        identityIni: String,
        nickname: String,
        serverPassword: String?,
        channel: String?,
        channelPassword: String?,
    ): Long

    external fun nativeDestroy(ptr: Long)

    external fun nativeIsConnected(ptr: Long): Boolean

    /**
     * Drains queued events. Each map has a `"type"` entry plus type-specific
     * keys; see [dev.tsdroid.model.ServerEvent].
     */
    external fun nativePoll(ptr: Long): Array<HashMap<String, Any>>?

    /** The whole server state as JSON; an unchanged revision returns the same string. */
    external fun nativeSnapshot(ptr: Long): String?

    /** Runs one named command. Returns "" on success, or the error text. */
    external fun nativeExec(ptr: Long, name: String, argsJson: String): String?

    /** Queues captured PCM16 (48 kHz mono) for transmission. */
    external fun nativeAudioWrite(ptr: Long, samples: ShortArray): Int

    /** Fills `out` with decoded PCM16. Returns the number of valid samples. */
    external fun nativeAudioRead(ptr: Long, out: ShortArray): Int

    external fun nativeAudioReset(ptr: Long)

    external fun nativeDisconnect(ptr: Long)
}
