//! Snapshot of what the server told us, plus the diffing that turns it into
//! semantic events.
//!
//! The UI reads this on every frame, so it must never touch the socket. The
//! connection thread refreshes it as book events arrive; JNI readers borrow it
//! under a lock.
//!
//! Translation strategy: rather than matching on ~30 `PropertyId` variants (which
//! is where the previous client implementation grew to 1454 lines), every book
//! event triggers a full snapshot refresh, and the *difference* against the
//! previous snapshot is what becomes events. A few hundred clients make that
//! cheap, and it cannot silently miss a field that a new server version adds.

use std::collections::BTreeMap;

use serde::Serialize;
use tsclientlib::data;
use tsclientlib::{ClientType, MaxClients};

use crate::events::QueuedEvent;

/// One client as the UI sees it.
#[derive(Debug, Default, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UserEntry {
    pub id: i32,
    pub uid: String,
    pub nickname: String,
    pub channel_id: i64,
    pub input_muted: bool,
    pub output_muted: bool,
    pub client_type: i32,
    pub away: bool,
    pub away_message: String,
    pub description: String,
    pub talking: bool,
}

/// One channel as the UI sees it.
#[derive(Debug, Default, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ChannelEntry {
    pub id: i64,
    pub parent_id: i64,
    pub name: String,
    pub order: i64,
    pub has_password: bool,
    pub is_default: bool,
    pub topic: String,
    /// -1 means unlimited, which is what the UI wants to render directly.
    pub max_clients: i32,
}

/// One file in a channel's file browser.
#[derive(Debug, Default, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FileEntry {
    pub channel_id: i64,
    pub path: String,
    pub name: String,
    pub size: u64,
    pub is_file: bool,
    /// Unix seconds.
    pub date_time: i64,
}

#[derive(Debug, Default, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ServerEntry {
    pub name: String,
    pub version: String,
    pub platform: String,
    pub clients_online: u32,
    pub max_clients: u32,
    pub welcome_message: String,
}

#[derive(Debug, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Snapshot {
    /// Bumped whenever anything the UI renders changed; the cached JSON is
    /// invalidated by comparing this, so an unchanged server costs a lock and an
    /// integer compare per frame.
    pub rev: u64,
    pub own_client_id: Option<i32>,
    pub own_channel_id: Option<i64>,
    pub nickname: String,
    pub input_muted: bool,
    pub output_muted: bool,
    pub away: bool,
    pub away_message: String,
    pub users: Vec<UserEntry>,
    pub channels: Vec<ChannelEntry>,
    /// Kept across `sync_from` calls: a file listing is a one-off response, not
    /// part of the book, so the next member update must not wipe it.
    pub files: Vec<FileEntry>,
    pub server: ServerEntry,
    pub connected: bool,
}

impl Snapshot {
    /// Rebuilds the snapshot from the book and returns the events implied by the
    /// difference. Call it once per book event batch.
    pub fn sync_from(&mut self, book: &data::Connection, talking: &[i32]) -> Vec<QueuedEvent> {
        let mut events = Vec::new();

        let prev_users: BTreeMap<i32, UserEntry> =
            self.users.iter().map(|u| (u.id, u.clone())).collect();
        let prev_channels: BTreeMap<i64, ChannelEntry> =
            self.channels.iter().map(|c| (c.id, c.clone())).collect();

        let own_id = book.own_client.0 as i32;
        let was_connected = self.connected;
        self.own_client_id = Some(own_id);

        // ── own state ───────────────────────────────────────────────────────
        // `own_client` is only an id; the data lives in the client map.
        if let Some(own) = book.clients.get(&book.own_client) {
            self.nickname = own.name.clone();
            self.input_muted = own.input_muted;
            self.output_muted = own.output_muted;
            self.away_message = own.away_message.clone().unwrap_or_default();
            // `away_message` is `Some` whenever the client is away, even with
            // an empty message — see the clients loop below.
            self.away = own.away_message.is_some();
        }

        // ── channels ────────────────────────────────────────────────────────
        let mut next_channels: BTreeMap<i64, ChannelEntry> = BTreeMap::new();
        for (id, channel) in &book.channels {
            next_channels.insert(
                id.0 as i64,
                ChannelEntry {
                    id: id.0 as i64,
                    parent_id: channel.parent.0 as i64,
                    name: channel.name.clone(),
                    order: channel.order.0 as i64,
                    has_password: channel.has_password.unwrap_or(false),
                    is_default: channel.is_default.unwrap_or(false),
                    topic: channel.topic.clone().unwrap_or_default(),
                    max_clients: match channel.max_clients {
                        Some(MaxClients::Limited(value)) => i32::from(value),
                        Some(MaxClients::Inherited) => -2,
                        Some(MaxClients::Unlimited) | None => -1,
                    },
                },
            );
        }
        for (id, channel) in &next_channels {
            match prev_channels.get(id) {
                None => events.push(
                    QueuedEvent::new("channel_created")
                        .long("channel_id", *id)
                        .long("parent_id", channel.parent_id)
                        .str("name", &channel.name),
                ),
                Some(old) if old.name != channel.name => events.push(
                    QueuedEvent::new("channel_renamed")
                        .long("channel_id", *id)
                        .str("name", &channel.name)
                        .str("old_name", &old.name),
                ),
                Some(_) => {}
            }
        }
        for id in prev_channels.keys() {
            if !next_channels.contains_key(id) {
                events.push(QueuedEvent::new("channel_removed").long("channel_id", *id));
            }
        }

        // ── clients ─────────────────────────────────────────────────────────
        let mut next_users: BTreeMap<i32, UserEntry> = BTreeMap::new();
        for (id, client) in &book.clients {
            let away_message = client.away_message.clone().unwrap_or_default();
            next_users.insert(
                id.0 as i32,
                UserEntry {
                    id: id.0 as i32,
                    uid: client.uid.as_ref().map(|u| u.to_string()).unwrap_or_default(),
                    nickname: client.name.clone(),
                    channel_id: client.channel.0 as i64,
                    input_muted: client.input_muted,
                    output_muted: client.output_muted,
                    client_type: if client.client_type == ClientType::Normal { 0 } else { 1 },
                    // ts-bookkeeping has no separate `client_away` flag: it folds
                    // the boolean into `away_message`, `Some` even when the
                    // message is empty. `is_empty()` would hide everyone who
                    // went away without typing a reason.
                    away: client.away_message.is_some(),
                    away_message,
                    description: client.description.clone(),
                    talking: talking.contains(&(id.0 as i32)),
                },
            );
        }
        for (id, user) in &next_users {
            match prev_users.get(id) {
                None => events.push(
                    QueuedEvent::new("user_joined")
                        .int("client_id", *id)
                        .str("nickname", &user.nickname)
                        .str("uid", &user.uid)
                        .long("channel_id", user.channel_id),
                ),
                Some(old) => {
                    if old.channel_id != user.channel_id {
                        events.push(
                            QueuedEvent::new("user_moved")
                                .int("client_id", *id)
                                .str("nickname", &user.nickname)
                                .long("channel_id", user.channel_id)
                                .long("old_channel_id", old.channel_id),
                        );
                    }
                    if old.nickname != user.nickname {
                        events.push(
                            QueuedEvent::new("user_renamed")
                                .int("client_id", *id)
                                .str("nickname", &user.nickname)
                                .str("old_nickname", &old.nickname),
                        );
                    }
                    if old.input_muted != user.input_muted {
                        events.push(
                            QueuedEvent::new("user_mute_changed")
                                .int("client_id", *id)
                                .str("nickname", &user.nickname)
                                .bool("input_muted", user.input_muted)
                                .bool("output_muted", user.output_muted)
                                .str("scope", "input"),
                        );
                    }
                    if old.output_muted != user.output_muted {
                        events.push(
                            QueuedEvent::new("user_mute_changed")
                                .int("client_id", *id)
                                .str("nickname", &user.nickname)
                                .bool("input_muted", user.input_muted)
                                .bool("output_muted", user.output_muted)
                                .str("scope", "output"),
                        );
                    }
                    if old.away != user.away || old.away_message != user.away_message {
                        events.push(
                            QueuedEvent::new("user_away_changed")
                                .int("client_id", *id)
                                .str("nickname", &user.nickname)
                                .bool("away", user.away)
                                .str("away_message", &user.away_message),
                        );
                    }
                }
            }
        }
        for (id, old) in &prev_users {
            if !next_users.contains_key(id) {
                events.push(
                    QueuedEvent::new("user_left")
                        .int("client_id", *id)
                        .str("nickname", &old.nickname),
                );
            }
        }

        // ── server ──────────────────────────────────────────────────────────
        let server = ServerEntry {
            name: book.server.name.clone(),
            version: book.server.version.clone(),
            platform: book.server.platform.clone(),
            clients_online: book.clients.len() as u32,
            max_clients: u32::from(book.server.max_clients),
            welcome_message: book.server.welcome_message.clone(),
        };
        if !was_connected && !server.name.is_empty() {
            self.connected = true;
            events.push(
                QueuedEvent::new("connected")
                    .int("client_id", own_id)
                    .str("server_name", &server.name)
                    .str("server_version", &server.version),
            );
        }

        let own_channel_id = next_users.get(&own_id).map(|u| u.channel_id);
        let changed = prev_users != next_users
            || prev_channels != next_channels
            || self.server != server
            || self.own_channel_id != own_channel_id;
        if changed || !events.is_empty() {
            self.rev = self.rev.wrapping_add(1);
        }

        self.server = server;
        self.own_channel_id = own_channel_id;
        self.users = next_users.into_values().collect();
        self.channels = next_channels.into_values().collect();

        events
    }

    /// Replaces the cached file listing of one channel and returns the event that
    /// tells the UI its spinner can stop.
    pub fn set_files(&mut self, channel_id: i64, entries: Vec<FileEntry>) -> QueuedEvent {
        self.files.retain(|file| file.channel_id != channel_id);
        let count = entries.len() as i32;
        self.files.extend(entries);
        self.rev = self.rev.wrapping_add(1);
        QueuedEvent::new("file_list")
            .long("channel_id", channel_id)
            .int("count", count)
    }

    /// Marks who is transmitting right now, bumping the revision only on change.
    pub fn set_talking(&mut self, talking: &[i32]) -> bool {
        let mut changed = false;
        for user in &mut self.users {
            let now = talking.contains(&user.id);
            if user.talking != now {
                user.talking = now;
                changed = true;
            }
        }
        if changed {
            self.rev = self.rev.wrapping_add(1);
        }
        changed
    }
}
