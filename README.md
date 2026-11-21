# tsdroid

A TeamSpeak 3/6 client for Android: a Compose UI in Kotlin over the native
protocol core from [tsbridge](https://github.com/Schallophy/tsbridge).

The protocol, connection, state, voice codec and file transfers live in the
standalone `tsbridge` repository, which builds one self-contained shared library
per platform. This repository is the Android consumer: `libtsbridge_jni.so` plus
the Compose UI on top. The same core also builds for macOS, Windows and Linux, so
nothing here has to be re-derived to support another desktop.

`tsclientlib` (ReSpeak) is the protocol stack, pinned by revision inside
`tsbridge`.

## What it does

| | |
|---|---|
| **Connection** | connect by address, optional channel + password, saved server list |
| **Channels** | full tree with nesting, lock icon, member counts, join (with password) |
| **Members** | live list per channel, mute state, away state, speaking indicator |
| **Chat** | channel / server / private messages, unread badges, own messages right-aligned |
| **Pokes** | send and receive, shown inline as an amber system line |
| **Status** | away + away message, mic mute, speaker mute — all server-visible |
| **Voice** | Opus 24 kbps mono both ways, 20 ms frames, jitter buffer + PLC |
| **Files** | browse a channel, download, upload, live transfer progress |
| **Audio** | noise suppression, echo cancellation, mic gain, optional Bluetooth mic |
| **Identity** | the session's key is written to `identity.ini`, so the UID survives a restart |

Deliberately absent: myTeamSpeak account login, whisper (密聊), per-user volume,
permission editing. Each of those needs either a private API or another protocol
layer, and shipping a half-working version is worse than not shipping it.

## Layout

```
tsdroid/
├── app/src/main/kotlin/dev/tsdroid/
│   ├── core/Native.kt          the external functions
│   ├── core/NativeClient.kt    handle ownership, 20 ms poll, command wrappers
│   ├── core/AudioBridge.kt     AudioRecord/AudioTrack, AEC, SCO routing
│   ├── model/                  snapshot, events, chat types (the contract)
│   ├── data/                   saved servers, identity file, local settings
│   ├── SessionViewModel.kt     session state machine
│   └── ui/                     connect, session (3 tabs), settings
└── app/src/main/jniLibs/arm64-v8a/libtsbridge_jni.so   prebuilt, from tsbridge
```

The Rust sources are no longer here. `native/` has been removed; the equivalent
code lives in tsbridge's `crates/core` (platform-neutral) and `crates/jni` (the
Android shim).

## The native contract

Eleven functions, and the count is fixed. The JNI symbol names are
`Java_dev_tsdroid_core_Native_<name>`, so this package path must not be renamed
casually — `crates/jni` in tsbridge hard-codes it:

| function | purpose |
|---|---|
| `nativeCreate` / `nativeDestroy` | session lifecycle, opaque handle |
| `nativeIsConnected` | connection state |
| `nativePoll` | drain events as `HashMap[]` |
| `nativeSnapshot` | whole state as one JSON string |
| `nativeExec` | **every** command: name + JSON args |
| `nativeIdentity` | the identity to persist, so the UID is stable |
| `nativeAudioWrite` / `nativeAudioRead` / `nativeAudioReset` | the 50 Hz PCM stream |
| `nativeDisconnect` | close the connection |

Events and state cross the boundary as plain JDK types and JSON, so the native
library never references an app class and does not need rebuilding when the UI's
data classes change. Adding a feature means adding a match arm in tsbridge's
`command.rs` and a caller in `NativeClient.kt` — never a new symbol.

Event names and field keys are the real API: tsbridge's `docs/events.md` and
`model/ServerEvent.kt` must change together. `tsbridge` has a test that fails when
an emitted event is not in that document.

## Building

**Native library** (arm64; needs cargo-ndk, NDK 27.2, cmake+ninja). It is built in
the tsbridge checkout, not here:

```powershell
cd ../tsbridge
./tools/build-android.ps1            # build only
./tools/build-android.ps1 -Sync      # build, then copy into ../tsdroid
```

The copy is deliberately a separate step: a build that fails must never silently
leave the previous `.so` in place, looking like the new code shipped.

**App:** (JDK 17, Android SDK with platform 35 + build-tools 36)

```bash
./gradlew :app:assembleDebug
```

The built `.so` is committed under `app/src/main/jniLibs/`, so a fresh clone
builds an APK without a Rust toolchain. License texts for the statically linked
third-party code (tsclientlib, Opus, aws-lc) are copied from tsbridge's `NOTICE`
into `app/src/main/assets/licenses/` and ship inside the APK — the Apache-2.0
notice obligation applies to the binary, not just the source tree. Refresh them
whenever tsbridge's `NOTICE` changes.

## Known gaps

* Release builds are signed with the debug key: no `release.keystore` in the repo.
* No foreground service, so a long call can be killed when the app is backgrounded.
* Only `arm64-v8a` is shipped.
