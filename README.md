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
└── third_party/tsbridge/       the core, a git submodule (pinned commit)
```

The native library is not in this repository. `app/buildTsbridgeJni` compiles
`third_party/tsbridge` with `cargo ndk` into `app/build/generated/tsbridgeJniLibs`
and Android packages it from there, so the `.so` always matches the submodule
commit — there is no separate binary that could be from another checkout.

Because of that, a fresh clone needs `git clone --recursive` (or
`git submodule update --init`) and a Rust toolchain with `cargo-ndk`, the NDK
and CMake before it can build an APK.

The Rust sources are no longer here. `native/` has been removed; the equivalent
code lives in the `third_party/tsbridge` submodule: `crates/core`
(platform-neutral) and `crates/jni` (the Android shim).

## The native contract

Twelve functions, and the count is fixed. The JNI symbol names are
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
| `nativeAbiVersion` | checked once at load; a stale `.so` fails with a message |
| `nativeDisconnect` | close the connection |

Events and state cross the boundary as plain JDK types and JSON, so the native
library never references an app class and does not need rebuilding when the UI's
data classes change. Adding a feature means adding a match arm in tsbridge's
`command.rs` and a caller in `NativeClient.kt` — never a new symbol.

Event names and field keys are the real API: tsbridge's `docs/events.md` and
`model/ServerEvent.kt` must change together. `tsbridge` has a test that fails when
an emitted event is not in that document.

## Building

**Prerequisites** (once): JDK 17, Android SDK with platform 35 + build-tools 36,
Rust, `cargo install cargo-ndk`, the NDK (27.x), CMake and Ninja — the last two
because Opus is compiled from C source. On Windows `cmake` must be on `PATH`; the
build task prepends `C:\Program Files\CMake\bin` if it is not.

```bash
git clone --recursive https://github.com/Schallophy/tsdroid.git
./gradlew :app:assembleDebug
```

`assembleDebug` runs `:app:buildTsbridgeJni`, which compiles the submodule with
`cargo ndk` and drops `libtsbridge_jni.so` into `app/build/generated/tsbridgeJniLibs`.
Nothing under `app/src/main/jniLibs` is used or tracked.

**Native library alone**, for working on the core without Gradle:

```powershell
cd third_party/tsbridge
./tools/build-android.ps1      # -> out/android/arm64-v8a/libtsbridge_jni.so
```

There is no separate "install into the app" step any more: Gradle compiles the
submodule itself, so a hand-built `.so` is only an artifact to inspect.

Both paths share the same cargo cache (`third_party/tsbridge/target`), so the
first build pays the cold cost (~3 min) and later ones are incremental (~15 s if
nothing under the submodule changed — the task is `UP-TO-DATE`).

License texts for the statically linked third-party code (tsclientlib, Opus,
aws-lc) are copied from tsbridge's `NOTICE` into `app/src/main/assets/licenses/`
and ship inside the APK — the Apache-2.0 notice obligation applies to the
binary, not just the source tree. Refresh them whenever tsbridge's `NOTICE`
changes.

## Updating the core

```bash
cd third_party/tsbridge
git fetch && git checkout <commit>          # or: git pull
cd ../..
git add third_party/tsbridge && git commit -m "tsbridge: bump core"
```

The pin is the gitlink line in `git status`. Bumping it is a separate, reviewable
commit — that is the whole reason for a submodule rather than a hand-copied
binary.

## Tests

`./gradlew :app:testDebugUnitTest` runs on the JVM — no emulator, no device, no
native library. Two suites, both pinning cross-repo contracts that used to fail
silently:

| suite | what it pins |
|---|---|
| `core/NativeAbiTest` | `Native.EXPECTED_ABI` against `TSBRIDGE_ABI_VERSION` in the submodule's header |
| `model/ServerEventTest` | `ServerEvent.kt`'s 21 `type` strings and field keys against `docs/events.md` |

Neither can pass on one side alone: tsbridge's `event_contract.rs` proves the
Rust and the docs agree, and `ServerEventTest` proves the docs and Kotlin agree,
so a rename in the core fails this build without the table being copied twice.

## Known gaps

* Release builds are signed with the debug key: no `release.keystore` in the repo.
* No foreground service, so a long call can be killed when the app is backgrounded.
* Only `arm64-v8a` is shipped.
* The unit tests read the submodule from disk (docs and header), so they need
  `git submodule update --init` — `assembleDebug` needs it anyway.
