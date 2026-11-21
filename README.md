# tsdroid

A TeamSpeak 3/6 client for Android: a native protocol core in Rust and a Compose
UI in Kotlin, in one repository.

Nothing here shares code with the TeamSpeak clients it grew out of — the native
library, the JNI contract and the app are all written from scratch. `tsclientlib`
(ReSpeak) is used as the protocol stack, pinned by revision.

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

Deliberately absent: myTeamSpeak account login, whisper (密聊), per-user volume,
permission editing. Each of those needs either a private API or another protocol
layer, and shipping a half-working version is worse than not shipping it.

## Layout

```
tsdroid/
├── native/                     Rust: protocol → JNI, builds libtsdroid_core.so
│   └── src/
│       ├── lib.rs              the 11 JNI entry points
│       ├── handle.rs           session handle, command channel, audio queues
│       ├── conn.rs             connection thread: events, commands, 20 ms clock
│       ├── command.rs          name + JSON → Command (the whole command surface)
│       ├── state.rs            snapshot + diff → semantic events
│       ├── events.rs           event → java.util.HashMap[]
│       ├── audio.rs            Opus encode/decode, mixing, talking detection
│       └── files.rs            transfer workers
└── app/src/main/kotlin/dev/tsdroid/
    ├── core/Native.kt          the external functions
    ├── core/NativeClient.kt    handle ownership, 20 ms poll, command wrappers
    ├── core/AudioBridge.kt     AudioRecord/AudioTrack, AEC, SCO routing
    ├── model/                  snapshot, events, chat types (the contract)
    ├── data/                   saved servers, local settings
    ├── SessionViewModel.kt     session state machine
    └── ui/                     connect, session (3 tabs), settings
```

## The JNI contract

Eleven functions, and the count is fixed:

| function | purpose |
|---|---|
| `nativeCreate` / `nativeDestroy` | session lifecycle, opaque handle |
| `nativeWaitConnected` / `nativeIsConnected` | connection state |
| `nativePoll` | drain events as `HashMap[]` |
| `nativeSnapshot` | whole state as one JSON string |
| `nativeExec` | **every** command: name + JSON args |
| `nativeAudioWrite` / `nativeAudioRead` / `nativeAudioReset` | the 50 Hz PCM stream |
| `nativeDisconnect` | close the connection |

Events and state cross the boundary as plain JDK types and JSON, so the native
library never references an app class and does not need rebuilding when the UI's
data classes change. Adding a feature means adding a match arm in `command.rs` and
a caller in `NativeClient.kt` — never a new symbol.

Event names and field keys are the real API: `native/src/state.rs` /
`native/src/conn.rs` and `model/ServerEvent.kt` must change together.

## Building

**Native library** (arm64; needs cargo-ndk, NDK 27.2, cmake+ninja):

```bash
cd native
./build-jni.sh
cp target/jniLibs/arm64-v8a/libtsdroid_core.so ../app/src/main/jniLibs/arm64-v8a/
```

The copy is deliberately a separate step: a build that fails must never silently
leave the previous `.so` in place, looking like the new code shipped.

**App:**

```bash
./gradlew :app:assembleDebug
```

The built `.so` is committed under `app/src/main/jniLibs/`, so a fresh clone
builds an APK without a Rust toolchain.

## Known gaps

* **Identity is not exported yet.** Every connect generates a fresh identity, so
  the server sees a new UID each time. Needs one more native function
  (`Identity::to_ts_str`) and a file write; until then, do not point this at a
  server you care about.
* Release builds are signed with the debug key: no `release.keystore` in the repo.
* No foreground service, so a long call can be killed when the app is backgrounded.
