//! The command protocol between Kotlin and the connection thread.
//!
//! Everything the UI can *do* arrives through one JNI function (`nativeExec`)
//! as a name plus a JSON object. That is deliberately a single entry point: the
//! JNI surface stays at eight functions no matter how many features exist, and
//! adding one is a match arm here plus a caller in Kotlin — no new symbol, no
//! regenerated headers, no chance of the two sides disagreeing about arity.

use serde_json::Value;

use crate::handle::{Command, TextTarget};

/// Parses one command. Errors are user-facing strings: they end up in the log
/// right next to the button that caused them.
pub fn parse(name: &str, args: &str) -> Result<Command, String> {
    let json: Value = if args.trim().is_empty() {
        Value::Null
    } else {
        serde_json::from_str(args).map_err(|e| format!("bad arguments: {e}"))?
    };

    match name {
        "disconnect" => Ok(Command::Disconnect),

        "send_text" => Ok(Command::SendText {
            target: match str_field(&json, "target")? {
                "server" => TextTarget::Server,
                "channel" => TextTarget::Channel,
                "client" => TextTarget::Client(int_field(&json, "clientId")?),
                other => return Err(format!("unknown message target: {other}")),
            },
            message: str_field(&json, "message")?.to_string(),
        }),

        "poke" => Ok(Command::Poke {
            client_id: int_field(&json, "clientId")?,
            message: str_field(&json, "message")?.to_string(),
        }),

        "join_channel" => Ok(Command::JoinChannel {
            channel_id: long_field(&json, "channelId")?,
            password: optional_str(&json, "password"),
        }),

        "set_input_muted" => Ok(Command::SetInputMuted(bool_field(&json, "muted")?)),
        "set_output_muted" => Ok(Command::SetOutputMuted(bool_field(&json, "muted")?)),

        "set_away" => Ok(Command::SetAway {
            away: bool_field(&json, "away")?,
            message: optional_str(&json, "message"),
        }),

        "set_nickname" => Ok(Command::SetNickname(str_field(&json, "nickname")?.to_string())),

        "list_files" => Ok(Command::ListFiles {
            channel_id: long_field(&json, "channelId")?,
            path: optional_str(&json, "path").unwrap_or_else(|| "/".to_string()),
            password: optional_str(&json, "password"),
        }),

        "download" => Ok(Command::Download {
            channel_id: long_field(&json, "channelId")?,
            path: str_field(&json, "path")?.to_string(),
            local: str_field(&json, "local")?.to_string(),
            password: optional_str(&json, "password"),
        }),

        "upload" => Ok(Command::Upload {
            channel_id: long_field(&json, "channelId")?,
            path: str_field(&json, "path")?.to_string(),
            local: str_field(&json, "local")?.to_string(),
            password: optional_str(&json, "password"),
            overwrite: json.get("overwrite").and_then(Value::as_bool).unwrap_or(true),
        }),

        "audio_enabled" => Ok(Command::SetAudioEnabled(bool_field(&json, "enabled")?)),

        other => Err(format!("unknown command: {other}")),
    }
}

fn str_field<'a>(json: &'a Value, key: &str) -> Result<&'a str, String> {
    json.get(key)
        .and_then(Value::as_str)
        .ok_or_else(|| format!("missing string field `{key}`"))
}

fn optional_str(json: &Value, key: &str) -> Option<String> {
    json.get(key)
        .and_then(Value::as_str)
        .filter(|value| !value.is_empty())
        .map(str::to_string)
}

fn int_field(json: &Value, key: &str) -> Result<i32, String> {
    json.get(key)
        .and_then(Value::as_i64)
        .map(|value| value as i32)
        .ok_or_else(|| format!("missing integer field `{key}`"))
}

fn long_field(json: &Value, key: &str) -> Result<i64, String> {
    json.get(key)
        .and_then(Value::as_i64)
        .ok_or_else(|| format!("missing integer field `{key}`"))
}

fn bool_field(json: &Value, key: &str) -> Result<bool, String> {
    json.get(key)
        .and_then(Value::as_bool)
        .ok_or_else(|| format!("missing boolean field `{key}`"))
}
