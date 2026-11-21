//! JNI bridge for TS6 Droid.
//!
//! The Kotlin side sees this library through a fixed set of entry points:
//!
//! * `nativeCreate` / `nativeDestroy` — lifecycle, returns an opaque handle.
//! * `nativePoll` — drains queued events as `HashMap[]` (plain JDK types).
//! * `nativeSnapshot` — the whole server state as one JSON string.
//! * `nativeExec` — **every** command the UI can issue, by name plus JSON args.
//! * `nativeAudioWrite` / `nativeAudioRead` / `nativeAudioReset` — the only
//!   streaming path, kept separate because 50 calls a second through JSON would
//!   be a waste of everyone's CPU.
//!
//! Three deliberate choices:
//!
//! * **No app-defined Java classes.** Events and the snapshot cross the boundary
//!   as `HashMap` / `String`, so this library never has to be rebuilt when the
//!   UI's data classes change.
//! * **Polling, not callbacks.** The UI already runs a 20 ms tick; pushing from
//!   the connection thread would need a cached JNI env per thread and would
//!   re-enter Java at unpredictable points.
//! * **One command entry point.** Adding a feature costs a match arm in
//!   `command.rs`, not a new exported symbol — so the contract in `Native.kt`
//!   stays honest by construction.
//!
//! Every exported function is wrapped in [`guard`]. Rust must not unwind into
//! Java, and before this wrapper existed a single panicking `unwrap` deep inside
//! the protocol stack killed the process with a SIGABRT that named no cause.

mod audio;
mod command;
mod conn;
mod events;
mod files;
mod handle;
mod state;

use jni::JNIEnv;
use jni::JavaVM;
use jni::objects::{JClass, JShortArray, JString};
use jni::sys::{jboolean, jint, jlong, jobjectArray, jstring};

use crate::handle::{Handle, boxed, panic_message};

#[unsafe(no_mangle)]
pub extern "system" fn JNI_OnLoad(vm: JavaVM, _class: JClass) -> jint {
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Debug)
            .with_tag("tslib"),
    );

    // The hook fires before unwinding starts, which is the only place the source
    // location is still available: the payload alone rarely says which call
    // panicked, and `panic = "abort"` used to lose it entirely.
    std::panic::set_hook(Box::new(|info| {
        let location = info
            .location()
            .map(|l| format!("{}:{}:{}", l.file(), l.line(), l.column()))
            .unwrap_or_else(|| "unknown location".to_string());
        log::error!("RUST PANIC at {location}: {}", panic_message(info.payload()));
    }));

    // Must happen before any network call. `hickory-resolver` (used by
    // tsclientlib for DNS) reads the system nameservers through `ndk-context`,
    // which panics if this was skipped — the connection thread would die one
    // line after reporting a successful connect.
    init_android_context(&vm);

    jni::sys::JNI_VERSION_1_6
}

/// Hands the JVM and the application object to `ndk-context`.
///
/// The context object has to stay alive for the life of the process, so the
/// global reference is leaked on purpose: `ndk_context` stores a raw pointer to
/// it, and dropping the `GlobalRef` here would leave a dangling handle that
/// fails the first time Android code touches it.
fn init_android_context(vm: &JavaVM) {
    let result = (|| -> jni::errors::Result<()> {
        let mut env = vm.get_env()?;
        let thread_class = env.find_class("android/app/ActivityThread")?;
        let application = env.call_static_method(
            thread_class,
            "currentApplication",
            "()Landroid/app/Application;",
            &[],
        )?;
        let application = application.l()?;
        if application.is_null() {
            log::error!("ActivityThread.currentApplication() returned null; DNS will not resolve");
            return Ok(());
        }
        let global = env.new_global_ref(application)?;
        let raw = global.as_obj().as_raw();
        unsafe {
            ndk_context::initialize_android_context(
                vm.get_java_vm_pointer() as *mut std::ffi::c_void,
                raw as *mut std::ffi::c_void,
            );
        }
        std::mem::forget(global);
        log::info!("Android context handed to ndk-context; DNS can use the system resolver");
        Ok(())
    })();

    if let Err(e) = result {
        log::error!("Failed to initialize the Android context: {e}");
    }
}

/// Runs `f`, turning a panic into `fallback` instead of letting it escape.
///
/// Unwinding across an `extern "system"` boundary is undefined behaviour, so the
/// choice is catch it here or lose the process. `fallback` is chosen per call to
/// mean "nothing happened": 0, false, or a null reference, which every caller
/// already handles.
fn guard<T>(fallback: T, f: impl FnOnce() -> T) -> T {
    match std::panic::catch_unwind(std::panic::AssertUnwindSafe(f)) {
        Ok(value) => value,
        Err(payload) => {
            log::error!("JNI call panicked: {}", panic_message(&*payload));
            fallback
        }
    }
}

/// `nativeCreate(address, identityIni, nickname, serverPassword, channel, channelPassword)`
///
/// `identityIni` may be empty (a fresh identity is generated) or the contents of
/// a TS3 `identity.ini`, which is what makes a returning user keep their UID.
/// Returns 0 on immediate failure; connection errors arrive as `error` events,
/// because connecting happens on the connection thread.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_tsdroid_core_Native_nativeCreate(
    mut env: JNIEnv,
    _class: JClass,
    address: JString,
    identity_ini: JString,
    nickname: JString,
    server_password: JString,
    channel: JString,
    channel_password: JString,
) -> jlong {
    guard(0, || {
        let Some(address) = read_string(&mut env, &address) else {
            log::error!("nativeCreate called without an address");
            return 0;
        };
        let identity_ini = read_string(&mut env, &identity_ini).unwrap_or_default();
        let nickname = read_string(&mut env, &nickname).unwrap_or_default();
        let server_password = read_string(&mut env, &server_password);
        let channel = read_string(&mut env, &channel);
        let channel_password = read_string(&mut env, &channel_password);

        match Handle::create(
            address,
            identity_ini,
            nickname,
            server_password,
            channel,
            channel_password,
        ) {
            Ok(handle) => boxed(handle),
            Err(e) => {
                log::error!("nativeCreate failed: {e}");
                0
            }
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_tsdroid_core_Native_nativeDestroy(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) {
    guard((), || {
        if ptr == 0 {
            return;
        }
        // Dropping the handle closes the command channel, which ends the
        // connection thread, which drops the socket.
        drop(unsafe { Box::from_raw(ptr as *mut Handle) });
    })
}

/// Whether the socket is up right now.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_tsdroid_core_Native_nativeIsConnected(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) -> jboolean {
    guard(false as jboolean, || {
        if ptr == 0 {
            return false as jboolean;
        }
        let handle = unsafe { &*(ptr as *const Handle) };
        handle.is_connected() as jboolean
    })
}

/// Drains queued events. The caller polls this on its 20 ms tick.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_tsdroid_core_Native_nativePoll(
    mut env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) -> jobjectArray {
    guard(std::ptr::null_mut(), || {
        if ptr == 0 {
            return std::ptr::null_mut();
        }
        let handle = unsafe { &*(ptr as *const Handle) };
        let events = handle.drain_events();
        match events::to_java_array(&mut env, events) {
            Ok(array) => array,
            Err(e) => {
                log::error!("nativePoll failed: {e}");
                std::ptr::null_mut()
            }
        }
    })
}

/// The whole state as JSON; an unchanged revision returns the same cached string.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_tsdroid_core_Native_nativeSnapshot(
    env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) -> jstring {
    guard(std::ptr::null_mut(), || {
        if ptr == 0 {
            return std::ptr::null_mut();
        }
        let handle = unsafe { &*(ptr as *const Handle) };
        match env.new_string(handle.snapshot_json()) {
            Ok(s) => s.into_raw(),
            Err(e) => {
                log::error!("nativeSnapshot failed: {e}");
                std::ptr::null_mut()
            }
        }
    })
}

/// `nativeExec(command, argsJson)` — returns "" on success, or the error text.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_tsdroid_core_Native_nativeExec(
    mut env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    name: JString,
    args: JString,
) -> jstring {
    guard(std::ptr::null_mut(), || {
        let message = match (read_string(&mut env, &name), read_string(&mut env, &args)) {
            (Some(name), args) => {
                let args = args.unwrap_or_default();
                match command::parse(&name, &args) {
                    Ok(command) => {
                        if ptr == 0 {
                            return std::ptr::null_mut();
                        }
                        let handle = unsafe { &*(ptr as *const Handle) };
                        handle.send(command);
                        String::new()
                    }
                    Err(e) => e,
                }
            }
            (None, _) => "missing command name".to_string(),
        };

        match env.new_string(message) {
            Ok(s) => s.into_raw(),
            Err(e) => {
                log::error!("nativeExec could not return its result: {e}");
                std::ptr::null_mut()
            }
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_tsdroid_core_Native_nativeDisconnect(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) {
    guard((), || {
        if ptr == 0 {
            return;
        }
        let handle = unsafe { &*(ptr as *const Handle) };
        handle.send(crate::handle::Command::Disconnect);
    })
}

/// Queues captured PCM16 for transmission. Returns how many samples were taken.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_tsdroid_core_Native_nativeAudioWrite(
    env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    samples: JShortArray,
) -> jint {
    guard(0, || {
        if ptr == 0 {
            return 0;
        }
        let length = env.get_array_length(&samples).unwrap_or(0) as usize;
        if length == 0 {
            return 0;
        }
        let mut buffer = vec![0i16; length];
        if env.get_short_array_region(&samples, 0, &mut buffer).is_err() {
            return 0;
        }
        let handle = unsafe { &*(ptr as *const Handle) };
        handle.inner().push_capture(&buffer);
        length as jint
    })
}

/// Fills the given array with decoded PCM16. Returns the sample count; the
/// caller plays `out[0 until count]`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_tsdroid_core_Native_nativeAudioRead(
    env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    out: JShortArray,
) -> jint {
    guard(0, || {
        if ptr == 0 {
            return 0;
        }
        let length = env.get_array_length(&out).unwrap_or(0) as usize;
        if length == 0 {
            return 0;
        }
        let mut buffer = vec![0i16; length];
        let handle = unsafe { &*(ptr as *const Handle) };
        let written = handle.inner().read_playback(&mut buffer);
        if written == 0 {
            return 0;
        }
        if env.set_short_array_region(&out, 0, &buffer[..written]).is_err() {
            return 0;
        }
        written as jint
    })
}

/// Drops buffered audio. Called when playback stops so the next session does not
/// start with a second of the previous one.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_tsdroid_core_Native_nativeAudioReset(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) {
    guard((), || {
        if ptr == 0 {
            return;
        }
        let handle = unsafe { &*(ptr as *const Handle) };
        handle.inner().clear_audio();
    })
}

fn read_string(env: &mut JNIEnv, value: &JString) -> Option<String> {
    if value.is_null() {
        return None;
    }
    match env.get_string(value) {
        Ok(s) => Some(s.into()),
        Err(e) => {
            log::error!("Failed to read a Java string: {e}");
            None
        }
    }
}
