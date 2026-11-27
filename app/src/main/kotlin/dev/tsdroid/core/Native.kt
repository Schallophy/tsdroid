package dev.tsdroid.core

/**
 * The entire surface of the native library.
 *
 * Twelve functions plus two account-only entry points, and the count is fixed:
 *
 * | feature | crosses the boundary as |
 * |---|---|
 * | connect / state / events | `nativeCreate`, `nativeSnapshot`, `nativePoll` |
 * | myTeamSpeak account | `nativeCreateAccount`, `nativeCreateWithAccount`, `nativeExec` |
 * | everything the UI can *do* | one [nativeExec] call with a name + JSON args |
 * | voice | the three audio calls (a 50 Hz stream does not belong in JSON) |
 * | identity | `nativeIdentity`, so the UID survives a restart |
 * | version | `nativeAbiVersion`, so a stale .so is caught before it is used |
 *
 * The .so is `libtsbridge_jni.so`, built from the tsbridge submodule
 * (`crates/jni`) by the `buildTsbridgeJniDebug` / `buildTsbridgeJniRelease`
 * Gradle tasks. The symbol names must
 * match that crate's `lib.rs` exactly (`Java_dev_tsdroid_core_Native_<name>`),
 * which is why this class lives at a fixed package and is not renamed casually.
 */
internal object Native {

    private const val LIBRARY = "tsbridge_jni"

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
        System.loadLibrary(LIBRARY)
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
                "（APK 里没有 lib$LIBRARY.so）"
            detail.contains("cannot locate symbol", ignoreCase = true) ->
                "（so 依赖了设备上没有的系统符号，通常是 Android 版本过低）"
            else -> ""
        }
        return "原生库加载失败$hint：${error::class.java.simpleName}: $detail"
    }

    /**
     * The ABI number this Kotlin code was written against.
     *
     * Must equal `tsbridge_core::ABI_VERSION` and `TSBRIDGE_ABI_VERSION` in
     * tsbridge's header. It only changes when a symbol or a parameter changes
     * meaning — which is exactly the case where silently continuing would produce
     * a session that looks connected and behaves wrongly.
     * `NativeAbiTest` fails the build if the submodule is bumped without this.
     */
    const val EXPECTED_ABI: Int = 8

    /**
     * Why the loaded library is not the one this Kotlin expects, or null when it
     * is fine (or when there is nothing to check, because it did not load).
     *
     * A submodule pin makes the normal case exact, but a stale `.so` on a device,
     * an old APK, or a debug build against a mismatched library all end here
     * rather than at the first confusing native call.
     */
    val abiError: String? = runCatching {
        if (loadError != null) return@runCatching null
        val actual = nativeAbiVersion()
        if (actual == EXPECTED_ABI) {
            null
        } else {
            "原生库版本不匹配：Kotlin 期望 ABI $EXPECTED_ABI，实际 $actual（重新构建 APK）"
        }
    }.getOrElse { t ->
        // A missing symbol is itself the answer: the library predates the check.
        "原生库版本不匹配：无法读取 ABI（${t::class.java.simpleName}），可能是旧版本"
    }

    /**
     * The ABI version reported by the loaded library.
     *
     * Exists so the check above can never be "assumed 1": it reads the value the
     * binary was actually built with.
     */
    external fun nativeAbiVersion(): Int

    /**
     * Starts connecting and returns an opaque handle, or 0 if the arguments were
     * rejected outright. Connection progress arrives as events, because the
     * actual connect happens on the native worker thread.
     *
     * `serverPassword` unlocks the server; `channelPassword` unlocks the channel
     * it lands in. They are different secrets and are kept in that order here and
     * in tsbridge's `crates/jni/src/lib.rs` — the JNI argument list is positional.
     *
     * `identityIni` is the `counterVkey` string from a previous session, or "" to
     * generate a fresh identity.
     *
     * `version` + `platform` pick the announced client version. `version` is a
     * version number (`3.6.3`) and `platform` one of the signed platforms
     * (`Windows`, `OS X`, `Linux`, `Android`, …); the native side resolves the
     * pair to the full version text and its signature. The server compares that
     * text with the signature byte for byte, so only a pair that names a signed
     * table entry connects. [nativeVersionPlatforms] and [nativeVersionSuggest]
     * list what the table offers.
     *
     * `token` is a privilege key redeemed on first connect; it grants its
     * groups to this UID once. Empty means none.
     */
    external fun nativeCreate(
        address: String,
        identityIni: String,
        nickname: String,
        serverPassword: String?,
        channel: String?,
        channelPassword: String?,
        version: String,
        platform: String,
        token: String,
        startMuted: Boolean,
    ): Long

    /**
     * An account-only session: no address, no socket, no connection thread.
     *
     * The account side lives on the handle, so a login screen has to exist
     * before anything is joined. The three paths are where the library may
     * write: the remembered login, the sync baseline and pre-write backups.
     * Returns 0 when the account side could not be started.
     */
    external fun nativeCreateAccount(
        credentialFile: String,
        baselineFile: String,
        backupDir: String,
    ): Long

    /**
     * Connects, carrying the signed-in account's myTeamSpeak proof.
     *
     * The proof is signed per connection over the server's handshake IV, so it
     * comes from the live account handle. A `0` [accountPtr], or an account
     * without a myTS identity, connects exactly like [nativeCreate].
     */
    external fun nativeCreateWithAccount(
        accountPtr: Long,
        address: String,
        identityIni: String,
        nickname: String,
        serverPassword: String?,
        channel: String?,
        channelPassword: String?,
        version: String,
        platform: String,
        token: String,
        startMuted: Boolean,
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

    /**
     * The identity the session is using, in TS3 `counterVkey` form, or null before
     * the worker thread has built one. Read it once when the snapshot reports
     * `identityReady` (older libraries: when `ownClientId` appears), then store
     * it so the server keeps recognising this user as the same person.
     */
    external fun nativeIdentity(ptr: Long): String?

    /** Queues captured PCM16 (48 kHz mono) for transmission. */
    external fun nativeAudioWrite(ptr: Long, samples: ShortArray): Int

    /** Fills `out` with decoded PCM16. Returns the number of valid samples. */
    external fun nativeAudioRead(ptr: Long, out: ShortArray): Int

    external fun nativeAudioReset(ptr: Long)

    external fun nativeDisconnect(ptr: Long)

    /**
     * The platforms that have a signed version entry, comma-separated.
     *
     * The platform dropdown is built from this, so a choice can never name a
     * platform with no signature (which the server would refuse).
     */
    external fun nativeVersionPlatforms(): String

    /**
     * The version numbers a picker should offer for the platform, comma
     * separated, newest first.
     *
     * Release numbers only: the table also holds several hundred pre-release
     * builds and a `3.?.?` placeholder, which are real signatures but not
     * something to pick from a list. Every entry resolves, so any row the user
     * taps is a valid pair with the platform it was listed under.
     */
    external fun nativeVersionChoices(platform: String): String

    /**
     * Version numbers the platform offers, comma-separated, newest build first.
     *
     * `prefix` filters by version-number prefix; empty returns every number the
     * platform has. Each number appears once even when several builds share it.
     * This is the unfiltered table, pre-releases included; the picker uses
     * [nativeVersionChoices].
     */
    external fun nativeVersionSuggest(prefix: String, platform: String): String

    /**
     * The exact text the server will see for this choice — `platform · version
     * text` — or "" when the pair names no signed entry.
     *
     * Shown under the field so the user can see the build suffix they never
     * typed, and so an impossible choice is visible before it is saved.
     */
    external fun nativeVersionResolve(version: String, platform: String): String
}
