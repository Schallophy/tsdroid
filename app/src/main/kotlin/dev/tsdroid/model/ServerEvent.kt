package dev.tsdroid.model

/**
 * Semantic events translated by the native layer from tsclientlib's
 * property-level book events and incoming protocol messages.
 *
 * The `type` strings and the field keys below are the contract with
 * tsbridge's `crates/core/src/state.rs` and `crates/core/src/conn.rs` — both
 * sides must change together, and `docs/events.md` there is the shared table.
 * Unknown types are surfaced as [Unknown] instead of being dropped, so a newer
 * native library never silently loses information the UI could show.
 */
sealed interface ServerEvent {

    val type: String

    data class Connected(
        val clientId: Int,
        val serverName: String,
        val serverVersion: String,
    ) : ServerEvent {
        override val type = "connected"
    }

    data object Disconnected : ServerEvent {
        override val type = "disconnected"
    }

    data object Reconnecting : ServerEvent {
        override val type = "reconnecting"
    }

    data class Failure(val message: String, val kind: String) : ServerEvent {
        override val type = "error"
    }

    data class CommandFailure(
        val message: String,
        val missingPermission: String,
    ) : ServerEvent {
        override val type = "command_error"
    }

    data class UserJoined(
        val clientId: Int,
        val nickname: String,
        val uid: String,
        val channelId: Long,
    ) : ServerEvent {
        override val type = "user_joined"
    }

    data class UserLeft(val clientId: Int, val nickname: String) : ServerEvent {
        override val type = "user_left"
    }

    data class UserMoved(
        val clientId: Int,
        val nickname: String,
        val channelId: Long,
        val oldChannelId: Long,
    ) : ServerEvent {
        override val type = "user_moved"
    }

    data class UserRenamed(
        val clientId: Int,
        val nickname: String,
        val oldNickname: String,
    ) : ServerEvent {
        override val type = "user_renamed"
    }

    data class UserMuteChanged(
        val clientId: Int,
        val nickname: String,
        val inputMuted: Boolean,
        val outputMuted: Boolean,
        val inputHardwareEnabled: Boolean,
        val outputHardwareEnabled: Boolean,
        val scope: String,
    ) : ServerEvent {
        override val type = "user_mute_changed"
    }

    data class UserAwayChanged(
        val clientId: Int,
        val nickname: String,
        val away: Boolean,
        val awayMessage: String,
    ) : ServerEvent {
        override val type = "user_away_changed"
    }

    data class ChannelCreated(
        val channelId: Long,
        val parentId: Long,
        val name: String,
    ) : ServerEvent {
        override val type = "channel_created"
    }

    data class ChannelRenamed(
        val channelId: Long,
        val name: String,
        val oldName: String,
    ) : ServerEvent {
        override val type = "channel_renamed"
    }

    data class ChannelRemoved(val channelId: Long) : ServerEvent {
        override val type = "channel_removed"
    }

    /** A chat message, from anyone including ourselves (filter by [fromId]). */
    data class TextMessage(
        val scope: ChatScope,
        val fromId: Int,
        val fromName: String,
        val fromUid: String,
        val message: String,
        val toId: Int,
        val timestamp: Long,
    ) : ServerEvent {
        override val type = "text_message"
    }

    data class Poked(
        val fromId: Int,
        val fromName: String,
        val message: String,
    ) : ServerEvent {
        override val type = "poked"
    }

    data class FileListed(val channelId: Long, val count: Int) : ServerEvent {
        override val type = "file_list"
    }

    data class TransferStarted(
        val name: String,
        val direction: TransferDirection,
        val total: Long,
    ) : ServerEvent {
        override val type = "transfer_started"
    }

    data class TransferProgress(
        val name: String,
        val direction: TransferDirection,
        val done: Long,
        val total: Long,
    ) : ServerEvent {
        override val type = "transfer_progress"
    }

    data class TransferDone(
        val name: String,
        val direction: TransferDirection,
        val local: String,
        val total: Long,
    ) : ServerEvent {
        override val type = "transfer_done"
    }

    data class TransferFailed(val name: String, val message: String) : ServerEvent {
        override val type = "transfer_error"
    }

    data class Unknown(override val type: String) : ServerEvent

    /** One line for the debug log; also the quickest way to eyeball a build. */
    fun describe(): String = when (this) {
        is Connected -> "connected · ${serverName.ifEmpty { "?" }} (client $clientId)"
        is Disconnected -> "disconnected"
        is Reconnecting -> "reconnecting…"
        is Failure -> "error[$kind] $message"
        is CommandFailure -> "command failed: $message"
        is UserJoined -> "+ $nickname"
        is UserLeft -> "- $nickname"
        is UserMoved -> "→ $nickname"
        is UserRenamed -> "~ $oldNickname ⇒ $nickname"
        is UserMuteChanged -> "mic $nickname ${if (inputMuted) "off" else "on"}"
        is UserAwayChanged -> "away $nickname = $away"
        is ChannelCreated -> "+ channel $name"
        is ChannelRenamed -> "~ channel $oldName ⇒ $name"
        is ChannelRemoved -> "- channel #$channelId"
        is TextMessage -> "<$fromName> $message"
        is Poked -> "$fromName poked you: $message"
        is FileListed -> "files of #$channelId: $count"
        is TransferStarted -> "transfer started: $name"
        is TransferProgress -> "transfer $name ${done * 100 / total.coerceAtLeast(1)}%"
        is TransferDone -> "transfer done: $name"
        is TransferFailed -> "transfer failed: $name ($message)"
        is Unknown -> "? $type"
    }

    companion object {
        private fun str(map: Map<String, Any>, key: String): String =
            map[key] as? String ?: ""

        private fun int(map: Map<String, Any>, key: String): Int =
            (map[key] as? Number)?.toInt() ?: 0

        private fun long(map: Map<String, Any>, key: String): Long =
            (map[key] as? Number)?.toLong() ?: 0L

        private fun bool(map: Map<String, Any>, key: String): Boolean =
            map[key] as? Boolean ?: false

        /**
         * A boolean whose documented default is `true`: an older native library
         * omits the key entirely, and reading `false` there would flag every
         * client as hardware-muted.
         */
        private fun boolOr(map: Map<String, Any>, key: String, fallback: Boolean): Boolean =
            map[key] as? Boolean ?: fallback

        private fun direction(map: Map<String, Any>): TransferDirection =
            if (str(map, "direction") == "upload") TransferDirection.Upload else TransferDirection.Download

        /** Returns null only for a malformed map (no `type`). */
        fun from(map: Map<String, Any>): ServerEvent? {
            val type = map["type"] as? String ?: return null
            return when (type) {
                "connected" -> Connected(
                    clientId = int(map, "client_id"),
                    serverName = str(map, "server_name"),
                    serverVersion = str(map, "server_version"),
                )

                "disconnected" -> Disconnected
                "reconnecting" -> Reconnecting
                "error" -> Failure(str(map, "message"), str(map, "kind"))
                "command_error" -> CommandFailure(
                    message = str(map, "message"),
                    missingPermission = str(map, "missing_permission"),
                )

                "user_joined" -> UserJoined(
                    clientId = int(map, "client_id"),
                    nickname = str(map, "nickname"),
                    uid = str(map, "uid"),
                    channelId = long(map, "channel_id"),
                )

                "user_left" -> UserLeft(int(map, "client_id"), str(map, "nickname"))
                "user_moved" -> UserMoved(
                    clientId = int(map, "client_id"),
                    nickname = str(map, "nickname"),
                    channelId = long(map, "channel_id"),
                    oldChannelId = long(map, "old_channel_id"),
                )

                "user_renamed" -> UserRenamed(
                    clientId = int(map, "client_id"),
                    nickname = str(map, "nickname"),
                    oldNickname = str(map, "old_nickname"),
                )

                "user_mute_changed" -> UserMuteChanged(
                    clientId = int(map, "client_id"),
                    nickname = str(map, "nickname"),
                    inputMuted = bool(map, "input_muted"),
                    outputMuted = bool(map, "output_muted"),
                    // Default to enabled: an event from an older native library
                    // has no hardware keys and must read as "device on" rather
                    // than flagging everyone as hardware-muted.
                    inputHardwareEnabled = boolOr(map, "input_hardware_enabled", true),
                    outputHardwareEnabled = boolOr(map, "output_hardware_enabled", true),
                    scope = str(map, "scope"),
                )

                "user_away_changed" -> UserAwayChanged(
                    clientId = int(map, "client_id"),
                    nickname = str(map, "nickname"),
                    away = bool(map, "away"),
                    awayMessage = str(map, "away_message"),
                )

                "channel_created" -> ChannelCreated(
                    channelId = long(map, "channel_id"),
                    parentId = long(map, "parent_id"),
                    name = str(map, "name"),
                )

                "channel_renamed" -> ChannelRenamed(
                    channelId = long(map, "channel_id"),
                    name = str(map, "name"),
                    oldName = str(map, "old_name"),
                )

                "channel_removed" -> ChannelRemoved(long(map, "channel_id"))

                "text_message" -> TextMessage(
                    scope = when (str(map, "target")) {
                        "server" -> ChatScope.Server
                        "client" -> ChatScope.Private
                        else -> ChatScope.Channel
                    },
                    fromId = int(map, "from_id"),
                    fromName = str(map, "from_name"),
                    fromUid = str(map, "from_uid"),
                    message = str(map, "message"),
                    toId = int(map, "to_id"),
                    timestamp = long(map, "timestamp"),
                )

                "poked" -> Poked(
                    fromId = int(map, "from_id"),
                    fromName = str(map, "from_name"),
                    message = str(map, "message"),
                )

                "file_list" -> FileListed(long(map, "channel_id"), int(map, "count"))

                "transfer_started" -> TransferStarted(
                    name = str(map, "name"),
                    direction = direction(map),
                    total = long(map, "total"),
                )

                "transfer_progress" -> TransferProgress(
                    name = str(map, "name"),
                    direction = direction(map),
                    done = long(map, "done"),
                    total = long(map, "total"),
                )

                "transfer_done" -> TransferDone(
                    name = str(map, "name"),
                    direction = direction(map),
                    local = str(map, "local"),
                    total = long(map, "total"),
                )

                "transfer_error" -> TransferFailed(str(map, "name"), str(map, "message"))

                else -> Unknown(type)
            }
        }
    }
}
