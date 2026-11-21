//! The connection thread: the only place a tsclientlib `Connection` exists.
//!
//! It is created here, driven by one loop over "network events", "commands from
//! the UI" and a 20 ms audio clock, and dropped here. Everything else talks to it
//! through the command channel and reads results from the snapshot.
//!
//! Borrowing note: `Connection::events()` borrows the connection mutably, so a
//! command cannot be applied while an event stream is alive. The stream is
//! therefore rebuilt per iteration — safe because `EventStream` is a thin wrapper
//! around `poll_next` with no state of its own.

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use futures::StreamExt;
use tokio::sync::mpsc::UnboundedReceiver;
use tsclientlib::messages::c2s;
use tsclientlib::{
    ChannelId, ClientId, Connection, DisconnectOptions, Identity, InMessage, MessageTarget,
    OutCommandExt, StreamItem,
};

use crate::audio::AudioEngine;
use crate::events::QueuedEvent;
use crate::handle::{Command, Inner, TextTarget};
use crate::state::FileEntry;

/// A transfer that was requested but whose TCP stream has not arrived yet.
struct Pending {
    name: String,
    local: PathBuf,
}

struct Session {
    audio: AudioEngine,
    downloads: HashMap<u16, Pending>,
    uploads: HashMap<u16, Pending>,
    /// Which channel the outstanding `filelist` request belongs to. Needed
    /// because an empty directory produces a response with no entries at all.
    listing_channel: Option<i64>,
}

enum Step {
    Item(Option<Result<StreamItem, tsclientlib::Error>>),
    Command(Command),
    CommandsClosed,
    Tick,
}

pub async fn run(
    inner: Arc<Inner>,
    mut commands: UnboundedReceiver<Command>,
    address: String,
    identity_input: String,
    nickname: String,
    server_password: Option<String>,
    channel: Option<String>,
    channel_password: Option<String>,
) {
    let identity = if identity_input.trim().is_empty() {
        log::info!("No identity supplied; generating a new one for this session");
        Identity::create()
    } else {
        match Identity::new_from_ts_str(&identity_input) {
            Ok(identity) => identity,
            Err(e) => {
                log::error!("Rejected identity input: {e}");
                inner.push_event(
                    QueuedEvent::new("error")
                        .str("message", format!("invalid identity: {e}"))
                        .str("kind", "identity"),
                );
                return;
            }
        }
    };

    // Never log the password itself — only whether one arrived. This line is what
    // proves the Kotlin field actually reached the native side, which is the
    // first thing to check when a locked server keeps refusing.
    log::info!(
        "Connecting to {address}: server password {}, target channel {}",
        if server_password.is_some() { "provided" } else { "NOT provided" },
        channel.as_deref().unwrap_or("<default>"),
    );

    let mut options = Connection::build(address.clone())
        .identity(identity)
        .name(nickname)
        .input_muted(false)
        .output_muted(false);
    // The server password is not the channel password: one unlocks the server,
    // the other a channel inside it, and a server can require either, both or
    // neither. Keeping them separate is the whole point of this pair of fields.
    if let Some(password) = server_password {
        options = options.password(password);
    }
    if let Some(channel) = channel {
        options = options.channel(channel);
    }
    if let Some(password) = channel_password {
        options = options.channel_password(password);
    }

    let mut connection = match options.connect() {
        Ok(connection) => connection,
        Err(e) => {
            log::error!("Connect to {address} failed: {e}");
            inner.push_event(
                QueuedEvent::new("error")
                    .str("message", format!("connect failed: {e}"))
                    .str("kind", "connect"),
            );
            return;
        }
    };
    log::info!("Connected to {address}; waiting for the first book events");

    let mut session = Session {
        audio: AudioEngine::new(),
        downloads: HashMap::new(),
        uploads: HashMap::new(),
        listing_channel: None,
    };

    // 20 ms is one Opus frame: the same clock drives capture and playback, so
    // there is exactly one place where audio timing is decided.
    let mut ticker = tokio::time::interval(Duration::from_millis(20));
    ticker.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);

    // Set once the goodbye is queued. `disconnect()` only enqueues the packet,
    // and dropping the connection right after would cancel the write — the
    // server would never see `clientdisconnect` and keep the client listed
    // until its timeout. So the loop keeps running until the server closes
    // the socket, with this as the ceiling.
    let mut goodbye_deadline: Option<tokio::time::Instant> = None;

    loop {
        if let Some(deadline) = goodbye_deadline {
            if tokio::time::Instant::now() >= deadline {
                log::info!("Disconnect wait timed out; closing anyway");
                break;
            }
        }

        let step = {
            let mut stream = connection.events();
            tokio::select! {
                item = stream.next() => Step::Item(item),
                command = commands.recv() => match command {
                    Some(command) => Step::Command(command),
                    None => Step::CommandsClosed,
                },
                _ = ticker.tick() => Step::Tick,
            }
        };

        match step {
            Step::CommandsClosed => {
                log::info!("Handle dropped; closing the connection");
                break;
            }
            Step::Command(Command::Disconnect) => match connection.disconnect(DisconnectOptions::new()) {
                Ok(()) => {
                    log::info!("Goodbye sent; waiting for the server to close");
                    goodbye_deadline = Some(tokio::time::Instant::now() + Duration::from_secs(1));
                }
                Err(e) => {
                    log::warn!("Disconnect failed: {e}");
                    break;
                }
            },
            Step::Command(command) => {
                if !handle_command(&inner, &mut connection, &mut session, command) {
                    break;
                }
            }
            Step::Tick => {
                if let Some(frame) = inner.take_capture_frame() {
                    session.audio.transmit(&mut connection, &frame);
                }
                session.audio.tick(&inner);
            }
            Step::Item(None) => {
                log::info!("Event stream ended");
                break;
            }
            Step::Item(Some(Err(e))) => {
                log::error!("Event stream failed: {e}");
                let (message, kind) = describe_stream_error(&e);
                inner.push_event(
                    QueuedEvent::new("error").str("message", message).str("kind", kind),
                );
                break;
            }
            Step::Item(Some(Ok(item))) => {
                if !handle_stream_item(&inner, &mut connection, &mut session, item) {
                    break;
                }
            }
        }
    }

    inner.set_connected(false);
    inner.set_talking(Vec::new());
    inner.clear_audio();
    inner.push_event(QueuedEvent::new("disconnected"));
    log::info!("Connection thread stopped");
}

/// Returns false when the session is over.
fn handle_stream_item(
    inner: &Arc<Inner>,
    connection: &mut Connection,
    session: &mut Session,
    item: StreamItem,
) -> bool {
    match item {
        StreamItem::BookEvents(_) => {
            // The book has already been updated when these arrive, so the
            // snapshot diff sees the new state even if the event batch is split.
            let talking = inner.talking();
            let outcome = match connection.get_state() {
                Ok(book) => Some(inner.with_state(|snapshot| {
                    let events = snapshot.sync_from(book, &talking);
                    (events, snapshot.connected)
                })),
                Err(e) => {
                    log::warn!("No book state while handling events: {e}");
                    None
                }
            };
            if let Some((events, connected)) = outcome {
                // Connected-ness is owned by the snapshot (it knows whether the
                // server entries arrived); mirror it for the JNI query.
                inner.set_connected(connected);
                for event in events {
                    inner.push_event(event);
                }
            }
            true
        }

        StreamItem::MessageEvent(message) => {
            handle_message(inner, session, message);
            true
        }

        StreamItem::Audio(packet) => {
            session.audio.receive(inner, packet);
            true
        }

        StreamItem::DisconnectedTemporarily(reason) => {
            log::warn!("Temporarily disconnected: {reason:?}; tsclientlib will reconnect");
            inner.set_connected(false);
            inner.push_event(QueuedEvent::new("reconnecting"));
            true
        }

        StreamItem::MessageResult(handle, result) => {
            if let Err(e) = result {
                log::warn!("Command {} failed: {e}", handle.0);
                inner.push_event(
                    QueuedEvent::new("command_error")
                        .str("message", e.to_string())
                        .str(
                            "missing_permission",
                            e.missing_permission.map(|p| format!("{p:?}")).unwrap_or_default(),
                        )
                        .int("command_id", handle.0 as i32),
                );
            }
            true
        }

        StreamItem::FileDownload(handle, result) => {
            if let Some(pending) = session.downloads.remove(&handle.0) {
                let inner = inner.clone();
                tokio::spawn(crate::files::download(
                    inner,
                    pending.name,
                    result.size,
                    result.stream,
                    pending.local,
                ));
            }
            true
        }

        StreamItem::FileUpload(handle, result) => {
            if let Some(pending) = session.uploads.remove(&handle.0) {
                let inner = inner.clone();
                tokio::spawn(crate::files::upload(
                    inner,
                    pending.name,
                    result.seek_position,
                    result.stream,
                    pending.local,
                ));
            }
            true
        }

        StreamItem::FiletransferFailed(handle, error) => {
            transfer_failed(inner, session, handle.0, &error.to_string());
            true
        }

        _ => true,
    }
}

fn transfer_failed(inner: &Arc<Inner>, session: &mut Session, handle: u16, error: &str) {
    let name = session
        .downloads
        .remove(&handle)
        .or_else(|| session.uploads.remove(&handle))
        .map(|pending| pending.name)
        .unwrap_or_else(|| format!("transfer {handle}"));
    log::warn!("{name} failed: {error}");
    inner.push_event(
        QueuedEvent::new("transfer_error")
            .str("name", &name)
            .str("message", error),
    );
}

fn handle_message(inner: &Arc<Inner>, session: &mut Session, message: InMessage) {
    match message {
        InMessage::TextMessage(text) => {
            for part in text.iter() {
                let target = match part.target {
                    tsclientlib::TextMessageTargetMode::Server => "server",
                    tsclientlib::TextMessageTargetMode::Channel => "channel",
                    _ => "client",
                };
                inner.push_event(
                    QueuedEvent::new("text_message")
                        .str("target", target)
                        .int("from_id", part.invoker_id.0 as i32)
                        .str("from_name", &part.invoker_name)
                        .str(
                            "from_uid",
                            part.invoker_uid.as_ref().map(|u| u.to_string()).unwrap_or_default(),
                        )
                        .str("message", &part.message)
                        .int("to_id", part.target_client_id.map(|id| id.0 as i32).unwrap_or(0))
                        .long(
                            "timestamp",
                            part.timestamp.map(|t| t.unix_timestamp()).unwrap_or(0),
                        ),
                );
            }
        }

        InMessage::ClientPoke(poke) => {
            for part in poke.iter() {
                inner.push_event(
                    QueuedEvent::new("poked")
                        .int("from_id", part.invoker_id.0 as i32)
                        .str("from_name", &part.invoker_name)
                        .str("message", &part.message),
                );
            }
        }

        InMessage::FileList(list) => {
            let mut entries = Vec::new();
            let mut channel_id = session.listing_channel.unwrap_or(0);
            for part in list.iter() {
                channel_id = part.channel_id.0 as i64;
                entries.push(FileEntry {
                    channel_id,
                    path: part.path.clone(),
                    name: part.name.clone(),
                    size: part.size,
                    is_file: part.is_file,
                    date_time: part.date_time.unix_timestamp(),
                });
            }
            let event = inner.with_state(|snapshot| snapshot.set_files(channel_id, entries));
            inner.push_event(event);
        }

        _ => {}
    }
}

/// Returns false when the session is over.
fn handle_command(
    inner: &Arc<Inner>,
    connection: &mut Connection,
    session: &mut Session,
    command: Command,
) -> bool {
    match command {
        Command::Disconnect => return false,

        Command::SendText { target, message } => {
            let target = match target {
                TextTarget::Server => MessageTarget::Server,
                TextTarget::Channel => MessageTarget::Channel,
                TextTarget::Client(id) => MessageTarget::Client(ClientId(id as u16)),
            };
            let command = match connection.get_state() {
                Ok(book) => book.send_message(target, &message),
                Err(e) => {
                    push_command_error(inner, &e.to_string());
                    return true;
                }
            };
            if let Err(e) = command.send(connection) {
                push_command_error(inner, &e.to_string());
            }
        }

        Command::Poke { client_id, message } => {
            let command = match connection.get_state() {
                Ok(book) => {
                    book.send_message(MessageTarget::Poke(ClientId(client_id as u16)), &message)
                }
                Err(e) => {
                    push_command_error(inner, &e.to_string());
                    return true;
                }
            };
            if let Err(e) = command.send(connection) {
                push_command_error(inner, &e.to_string());
            }
        }

        Command::JoinChannel { channel_id, password } => {
            let command = c2s::OutClientMoveMessage::new(&mut std::iter::once(
                c2s::OutClientMovePart {
                    client_id: ClientId(own_id(connection)),
                    channel_id: ChannelId(channel_id as u64),
                    channel_password: password.map(Into::into),
                },
            ));
            if let Err(e) = command.send(connection) {
                push_command_error(inner, &e.to_string());
            }
        }

        Command::SetInputMuted(muted) => {
            send_update(inner, connection, |part| part.input_muted = Some(muted));
            // Dropping whatever was captured before the mute keeps a stale
            // syllable from being transmitted after the fact.
            if muted {
                inner.clear_capture();
            }
            session.audio.set_enabled(!muted);
        }

        Command::SetOutputMuted(muted) => {
            send_update(inner, connection, |part| part.output_muted = Some(muted));
        }

        Command::SetAway { away, message } => {
            send_update(inner, connection, |part| {
                part.is_away = Some(away);
                part.away_message = Some(message.unwrap_or_default().into());
            });
        }

        Command::SetNickname(name) => {
            send_update(inner, connection, |part| part.name = Some(name.into()));
        }

        Command::ListFiles { channel_id, path, password } => {
            session.listing_channel = Some(channel_id);
            let command = c2s::OutFileListRequestMessage::new(&mut std::iter::once(
                c2s::OutFileListRequestPart {
                    channel_id: ChannelId(channel_id as u64),
                    channel_password: password.unwrap_or_default().into(),
                    path: path.into(),
                },
            ));
            if let Err(e) = command.send(connection) {
                push_command_error(inner, &e.to_string());
            }
        }

        Command::Download { channel_id, path, local, password } => {
            match connection.download_file(
                ChannelId(channel_id as u64),
                &path,
                password.as_deref(),
                None,
            ) {
                Ok(handle) => {
                    session.downloads.insert(
                        handle.0,
                        Pending { name: file_name(&path), local: PathBuf::from(local) },
                    );
                }
                Err(e) => push_command_error(inner, &e.to_string()),
            }
        }

        Command::Upload { channel_id, path, local, password, overwrite } => {
            let local_path = PathBuf::from(&local);
            let size = match std::fs::metadata(&local_path) {
                Ok(meta) => meta.len(),
                Err(e) => {
                    push_command_error(inner, &format!("cannot read {local}: {e}"));
                    return true;
                }
            };
            match connection.upload_file(
                ChannelId(channel_id as u64),
                &path,
                password.as_deref(),
                size,
                overwrite,
                false,
            ) {
                Ok(handle) => {
                    session.uploads.insert(
                        handle.0,
                        Pending { name: file_name(&path), local: local_path },
                    );
                }
                Err(e) => push_command_error(inner, &e.to_string()),
            }
        }

        Command::SetAudioEnabled(enabled) => session.audio.set_enabled(enabled),
    }
    true
}

fn own_id(connection: &Connection) -> u16 {
    connection
        .get_state()
        .map(|book| book.own_client.0)
        .unwrap_or(0)
}

fn file_name(path: &str) -> String {
    path.rsplit('/').next().unwrap_or(path).to_string()
}

fn push_command_error(inner: &Arc<Inner>, message: &str) {
    log::warn!("Command failed locally: {message}");
    inner.push_event(QueuedEvent::new("command_error").str("message", message));
}

/// `clientupdate` with only the fields the caller cares about set.
fn send_update(
    inner: &Arc<Inner>,
    connection: &mut Connection,
    apply: impl FnOnce(&mut c2s::OutClientUpdatePart<'_>),
) {
    let mut part = c2s::OutClientUpdatePart {
        name: None,
        input_muted: None,
        output_muted: None,
        is_away: None,
        away_message: None,
        input_hardware_enabled: None,
        output_hardware_enabled: None,
        is_channel_commander: None,
        avatar_hash: None,
        phonetic_name: None,
        talk_power_request: None,
        talk_power_request_message: None,
        is_recording: None,
        badges: None,
    };
    apply(&mut part);

    let command = c2s::OutClientUpdateMessage::new(&mut std::iter::once(part));
    if let Err(e) = command.send(connection) {
        push_command_error(inner, &e.to_string());
    }
}

/// Turns a connection failure into something a person can act on.
///
/// `Error::ConnectTs` carries the server's own error code. Only the password
/// cases are worth translating — the rest are rare enough that the English enum
/// name plus the kind is more useful than a guess at Chinese wording.
fn describe_stream_error(error: &tsclientlib::Error) -> (String, &'static str) {
    use tsproto_types::errors::Error as TsError;

    if let tsclientlib::Error::ConnectTs(kind) = error {
        return match kind {
            TsError::ServerInvalidPassword => (
                "服务器需要密码，或者密码不正确。检查「服务器密码」后重试。".to_string(),
                "server_password",
            ),
            TsError::ClientInvalidPassword => (
                "客户端身份密码错误（服务器已占用这个身份）".to_string(),
                "client_password",
            ),
            TsError::ChannelInvalidPassword => (
                "频道密码不正确".to_string(),
                "channel_password",
            ),
            other => (format!("服务器拒绝连接：{other}"), "connect"),
        };
    }

    (format!("{error}"), "stream")
}
