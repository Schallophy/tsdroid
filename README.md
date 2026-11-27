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
| **Members** | live list per channel, mute state (button and switched-off device), away state, speaking indicator |
| **Chat** | channel / server / private messages, unread badges, own messages right-aligned |
| **Pokes** | send and receive, shown inline as an amber system line |
| **Status** | away + away message, mic mute, speaker mute — all server-visible |
| **Voice** | Opus 24 kbps mono both ways, 20 ms frames, jitter buffer + PLC |
| **Files** | browse a channel, download, upload, live transfer progress |
| **Audio** | noise suppression, echo cancellation, mic gain, speaker boost, optional Bluetooth mic |
| **Volume** | per-user playback volume, 0–200%, keyed by UID so it survives a reconnect |
| **Whisper** | member, channel and group targets from the header picker (groups use the all-channels form) |
| **Settings** | channel editor (codec, quality, limits, flags) on long-press; server editor from the header gear |
| **Permissions** | channel, server-group, channel-group, client and channel-client permission editors |
| **Identity** | the session's key is written to `identity.ini`, so the UID survives a restart |
| **Account** | myTeamSpeak login (password + optional OTP), remembered login, cloud bookmarks that fill the connect form (passwords read on demand), backup, logout; the myTS ID is announced on connect |

The channel codec list is Opus Voice and Opus Music only. TeamSpeak Server
3.11.0 migrated channels using the deprecated Speex/CELT codecs to Opus and
removed them from channel creation/editing; 3.13.9 rejects them. Carrying those
codecs would be a setting the official server no longer accepts.

The account side runs on its own native handle, so signing in, reading bookmarks
and backing them up work before any server is joined. The same handle is handed
to the connection, where the core signs the per-connection myTeamSpeak proof
(`clientinit`). The remembered login and sync baseline live in the app's private
`files/account/` directory; logging out deletes the credential, and that whole
directory is excluded from Android auto-backup and device transfer.

Bookmark passwords are presence-only in the normal polled snapshot. After a
login or refresh the account client reads the list once with `revealSecrets`,
keeps the values in its own memory cache, and immediately re-lists without the
flag; a bookmark click is then a synchronous lookup, and the secret never stays
in the state the UI polls.

## Layout

```
tsdroid/
├── app/src/main/kotlin/dev/tsdroid/
│   ├── core/Native.kt          the external functions
│   ├── core/NativeClient.kt    handle ownership, 20 ms poll, command wrappers
│   ├── core/AccountClient.kt   the account-only handle, its poll and commands
│   ├── core/AudioBridge.kt     AudioRecord/AudioTrack, AEC, SCO routing, playback gain
│   ├── model/                  snapshot, events, chat and account types (the contract)
│   ├── data/                   saved servers, identity file, local settings
│   ├── SessionViewModel.kt     session state machine, owns both handles
│   └── ui/                     connect, session (3 tabs), settings, account
├── app/libs/                   the Java half of rustls-platform-verifier (see below)
└── third_party/tsbridge/       the core, a git submodule (pinned commit)
```

`app/libs/rustls-platform-verifier-<version>.aar` is the Android TrustManager
component the account client verifies TLS through. Its version is read from the core's
`Cargo.lock` at build time, so it cannot drift from the Rust half linked into
`libtsbridge_jni.so`; `JNI_OnLoad` initializes it before the account's first
HTTPS request. The AAR is vendored rather than fetched so a build does not
depend on GitHub's raw Maven branch.

The native library is not in this repository. `app/buildTsbridgeJniDebug` and
`app/buildTsbridgeJniRelease` compile `third_party/tsbridge` with `cargo ndk`
into `app/build/generated/tsbridgeJniLibs/{debug,release}` and Android packages
the matching one from there, so the `.so` always matches the submodule commit —
there is no separate binary that could be from another checkout. The debug
variant uses the core's `android-dev` profile (no LTO), so a core edit does not
pay the release link; the release variant keeps the shipping profile.

Because of that, a fresh clone needs `git clone --recursive` (or
`git submodule update --init`) and a Rust toolchain with `cargo-ndk`, the NDK
and CMake before it can build an APK.

The Rust sources are no longer here. `native/` has been removed; the equivalent
code lives in the `third_party/tsbridge` submodule: `crates/core`
(platform-neutral) and `crates/jni` (the Android shim).

### The icons

Every icon under `app/src/main/res/drawable/` comes from the TeamSpeak dark icon
pack, [randomhost/teamspeak-dark], pinned at commit `66d732f2`. The whole pack
ships, not just the marks in use: a client that shows a channel, a group or a
permission mark should draw the same picture the desktop client draws, and
having the file already in the tree is what makes that a layout decision rather
than a porting job.

[randomhost/teamspeak-dark]: https://github.com/randomhost/teamspeak-dark

`tools/svg2vector.py` is the converter, and it is checked in because a
converted icon nobody can regenerate is a dead end. It refuses what it cannot
express exactly — a radial gradient, a rounded rect, a clip smaller than the
viewBox — rather than shipping an approximation. Two files needed more than the
common case: `moderated`'s `<text>` is outlined with fontTools, and `ban_list`'s
rotated `<rect>` is mapped corner by corner.

Every resource is named `icon_` plus the pack's own name. The prefix is uniform
so a drawable in the tree is recognisably from the pack, and it is what makes
the pack's name survive Android's rules without being rewritten: `default` is a
Java keyword, `3d_sound` starts with a digit, `addon-collection` has a hyphen.
Two characters change and nothing else — a hyphen becomes an underscore, since
that is all a resource name allows. `res/raw/iconpack_names.json` maps each
resource name back to the pack's spelling for code that needs the original.

The fills are baked into the paths (`#a9aaac` grey, `#7289da` blue, `#c90709`
red), so `Icon(tint = ...)`-style recolouring does nothing useful — the
microphone, the speaker and the connect header all put them on one fixed dark
plate (`IconPlate`) instead. That is also why the bottom navigation still uses
Material icons: it signals the selected tab by tinting, which these cannot do.

Which status mark applies is decided once, in `components/Common.kt`
(`inputStatusIcon` / `outputStatusIcon`): switched-off device first, mute
button second. Every screen uses those two functions, so the member row, the
user sheet and the self-view cannot disagree about the same person.

## The native contract

Fourteen functions, and the count is fixed. The JNI symbol names are
`Java_dev_tsdroid_core_Native_<name>`, so this package path must not be renamed
casually — `crates/jni` in tsbridge hard-codes it:

| function | purpose |
|---|---|
| `nativeCreate` / `nativeDestroy` | session lifecycle, opaque handle |
| `nativeCreateAccount` | an account-only handle: no address, no socket |
| `nativeCreateWithAccount` | connect while carrying the account's myTS proof |
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
`command.rs` (or `account/mod.rs`) and a caller in the Kotlin client — only a new
*kind* of handle needs a new symbol, which is why the account side added two.

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

`assembleDebug` runs `:app:buildTsbridgeJniDebug`, which compiles the submodule
with `cargo ndk` (profile `android-dev`) and drops `libtsbridge_jni.so` into
`app/build/generated/tsbridgeJniLibs/debug`. `assembleRelease` uses
`buildTsbridgeJniRelease` and `app/build/generated/tsbridgeJniLibs/release`.
Nothing under `app/src/main/jniLibs` is used or tracked.

**Native library alone**, for working on the core without Gradle:

```powershell
cd third_party/tsbridge
./tools/build-android.ps1      # -> out/android/arm64-v8a/libtsbridge_jni.so
```

There is no separate "install into the app" step any more: Gradle compiles the
submodule itself, so a hand-built `.so` is only an artifact to inspect.

Both paths share the same cargo cache (`third_party/tsbridge/target`), so the
first build pays the cold cost and later ones are incremental — and when
nothing under the submodule changed, the variant's Gradle task is a cache hit
and cargo does not run at all. `buildTsbridgeJniDebug` and
`buildTsbridgeJniRelease` have separate keys because they build different
profiles; switching between `assembleDebug` and `assembleRelease` is not one
cache entry overwriting the other.

License texts for the statically linked third-party code (tsclientlib, Opus,
ureq, rustls/ring) are copied from tsbridge's `NOTICE` into `app/src/main/assets/licenses/`
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
native library. Four suites, all pinning contracts that used to fail silently:

| suite | what it pins |
|---|---|
| `core/NativeAbiTest` | `Native.EXPECTED_ABI` against `TSBRIDGE_ABI_VERSION` in the submodule's header |
| `model/ServerEventTest` | `ServerEvent.kt`'s 22 `type` strings and field keys against `docs/events.md` |
| `model/AccountStateTest` | the `snapshot.account` shape: bookmark fields, folder nesting, `host:port` |
| `ui/components/StatusIconTest` | the icon precedence in `inputStatusIcon` / `outputStatusIcon`: switched-off device first, mute button second |

The first two cannot pass on one side alone: tsbridge's `event_contract.rs`
proves the Rust and the docs agree, and `ServerEventTest` proves the docs and
Kotlin agree, so a rename in the core fails this build without the table being
copied twice. `StatusIconTest` is the third leg of the same idea — one precedence,
three screens, so a change in `components/Common.kt` cannot silently desync the
member list from the self-view.

## CI

Two jobs, both `ubuntu-latest`:

| job | what it proves |
|---|---|
| `tests` | `:app:testDebugUnitTest` on a bare JVM, plus a byte-for-byte check that `assets/licenses/*` matches the submodule. No `.so` exists on that runner, so a test that reached into the native library would fail instead of passing on a leftover binary. |
| `apk` | `assembleDebug` with `cargo ndk` and NDK r27c, then compares the `.so` packaged in the APK against the stripped artifact Gradle derived from `buildTsbridgeJniDebug` |

Both jobs cache what they can. Gradle User Home — the distribution, the
dependencies and the local build cache — is restored by setup-gradle, and each
`buildTsbridgeJni*` variant is a `@CacheableTask`, so a push that does not move
the submodule restores the compiled `.so` instead of rebuilding it. A cold run
is what happens when the submodule or a lockfile changes.

There is no emulator job: there are no instrumentation tests to run, and a
booted emulator would cost minutes per run to prove nothing the JVM suite does
not already pin. `build.gradle.kts` prefers `ANDROID_NDK_HOME` over "newest NDK
in the SDK", so CI and a local build never compile against different sysroots.

## Known gaps

* Release builds are signed with the debug key: no `release.keystore` in the repo.
* No foreground service, so a long call can be killed when the app is backgrounded.
* Only `arm64-v8a` is shipped.
* The unit tests read the submodule from disk (docs and header), so they need
  `git submodule update --init` — `assembleDebug` needs it anyway.
