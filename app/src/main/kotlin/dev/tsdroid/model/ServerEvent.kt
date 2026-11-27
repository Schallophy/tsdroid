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

    data class Failure(
        val message: String,
        val kind: String,
        /**
         * True when the session is over and no further command will be handled.
         *
         * Without it a shell cannot tell "the server refused this packet" from
         * "the connection is dead" — both arrive as `error` — and has to guess
         * from [kind], which is free text.
         */
        val terminal: Boolean = false,
    ) : ServerEvent {
        override val type = "error"
    }

    data class CommandFailure(
        val message: String,
        val missingPermission: String,
        /**
         * Ties this failure to the command that caused it.
         *
         * `0` when the failure was detected locally and no command was involved;
         * the same number is on the matching [CommandOk].
         */
        val commandId: Int = 0,
    ) : ServerEvent {
        override val type = "command_error"
    }

    /**
     * The server accepted a command.
     *
     * A successful command produces no other event, so this is the only signal
     * that a write actually went through. Without it a shell can only wait and
     * hope, which is why a failed delete used to look identical to one that
     * worked.
     */
    data class CommandOk(val commandId: Int = 0) : ServerEvent {
        override val type = "command_ok"
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
        val fromSelf: Boolean,
        val timestamp: Long,
        val timestampMs: Long,
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

    /**
     * One finished myTeamSpeak command. The account pane reads the same result
     * from `snapshot.account`; this event is how a shell notices completion
     * without comparing snapshots.
     */
    data class AccountStatus(
        val command: String,
        val ok: Boolean,
        val message: String,
        /**
         * Machine-readable category of a failure, empty when [ok].
         *
         * `not_logged_in` · `no_key` · `login` · `session` · `write` · `file` ·
         * `identity_ambiguous` · `identity_missing` · `account`. A shell switches
         * on it to translate the sentence or offer the next step; `account` means
         * "show it as written". Defaults to empty so an older library reads as
         * "no category", which is also what success carries.
         */
        val kind: String = "",
    ) : ServerEvent {
        override val type = "account_status"
    }

    data class UserPrioritySpeakerChanged(
        val clientId: Int = 0,
        val nickname: String = "",
        val prioritySpeaker: Boolean = false,
    ) : ServerEvent {
        override val type = "user_priority_speaker_changed"
    }

    data class UserUnreadMessagesChanged(
        val clientId: Int = 0,
        val nickname: String = "",
        val unreadMessages: Long = 0L,
    ) : ServerEvent {
        override val type = "user_unread_messages_changed"
    }

    data class UserUpdated(
        val clientId: Int = 0,
        val nickname: String = "",
        val changed: String = "",
    ) : ServerEvent {
        override val type = "user_updated"
    }

    data class UserGroupsChanged(
        val clientId: Int = 0,
        val nickname: String = "",
        val channelGroup: Long = 0L,
        val serverGroups: String = "",
    ) : ServerEvent {
        override val type = "user_groups_changed"
    }

    data class ServerQueryLoginPassword(
        val password: String = "",
    ) : ServerEvent {
        override val type = "server_query_login_password"
    }

    data class ChatComposing(
        val clientId: Int = 0,
        val uid: String = "",
    ) : ServerEvent {
        override val type = "chat_composing"
    }

    data class FileListFinished(
        val channelId: Long = 0L,
        val path: String = "",
    ) : ServerEvent {
        override val type = "file_list_finished"
    }

    data class MissingPermission(
        val permissionId: Long = 0L,
        val value: Int = 0,
    ) : ServerEvent {
        override val type = "missing_permission"
    }

    data class ChannelChanged(
        val channelId: Long = 0L,
    ) : ServerEvent {
        override val type = "channel_changed"
    }

    data class ChannelPasswordChanged(
        val channelId: Long = 0L,
    ) : ServerEvent {
        override val type = "channel_password_changed"
    }

    data class PluginCommand(
        val name: String = "",
        val `data`: String = "",
        val invokerId: Int = 0,
        val invokerName: String = "",
        val invokerUid: String = "",
    ) : ServerEvent {
        override val type = "plugin_command"
    }

    data class ChannelCreatedId(
        val channelId: Long = 0L,
    ) : ServerEvent {
        override val type = "channel_created_id"
    }

    data class ServerGroupCreated(
        val groupId: Long = 0L,
    ) : ServerEvent {
        override val type = "server_group_created"
    }

    data class GroupCreated(
        val groupKind: String = "",
        val groupId: Long = 0L,
        val name: String = "",
    ) : ServerEvent {
        override val type = "group_created"
    }

    data class GroupUpdated(
        val groupKind: String = "",
        val groupId: Long = 0L,
        val name: String = "",
        val changed: String = "",
    ) : ServerEvent {
        override val type = "group_updated"
    }

    data class GroupRemoved(
        val groupKind: String = "",
        val groupId: Long = 0L,
        val name: String = "",
    ) : ServerEvent {
        override val type = "group_removed"
    }

    data class ServerUpdated(
        val changed: String = "",
        val name: String = "",
        val welcomeMessage: String = "",
    ) : ServerEvent {
        override val type = "server_updated"
    }

    data class ChannelUpdated(
        val channelId: Long = 0L,
        val changed: String = "",
    ) : ServerEvent {
        override val type = "channel_updated"
    }

    data class IconReady(
        val iconId: Long = 0L,
        val path: String = "",
        val format: String = "",
        val size: Long = 0L,
    ) : ServerEvent {
        override val type = "icon_ready"
    }

    data class IconFailed(
        val iconId: Long = 0L,
        val message: String = "",
    ) : ServerEvent {
        override val type = "icon_failed"
    }

    data class BanListCount(
        val count: Long = 0L,
    ) : ServerEvent {
        override val type = "ban_list_count"
    }

    data class BanListed(
        val banId: Long = 0L,
        val ip: String = "",
        val name: String = "",
        val uid: String = "",
        val lastNickname: String = "",
        val created: Long = 0L,
        val duration: Long = 0L,
        val invokerName: String = "",
        val reason: String = "",
    ) : ServerEvent {
        override val type = "ban_listed"
    }

    data object BanListDone : ServerEvent {
        override val type = "ban_list_done"
    }

    data class ComplaintListed(
        val targetDbId: Long = 0L,
        val targetName: String = "",
        val fromDbId: Long = 0L,
        val fromName: String = "",
        val message: String = "",
        val timestamp: Long = 0L,
    ) : ServerEvent {
        override val type = "complaint_listed"
    }

    data object ComplaintListDone : ServerEvent {
        override val type = "complaint_list_done"
    }

    data class OfflineMessageListed(
        val messageId: Long = 0L,
        val uid: String = "",
        val subject: String = "",
        val timestamp: Long = 0L,
        val read: Boolean = false,
    ) : ServerEvent {
        override val type = "offline_message_listed"
    }

    data object OfflineMessageListDone : ServerEvent {
        override val type = "offline_message_list_done"
    }

    data class OfflineMessage(
        val messageId: Long = 0L,
        val uid: String = "",
        val subject: String = "",
        val message: String = "",
        val timestamp: Long = 0L,
    ) : ServerEvent {
        override val type = "offline_message"
    }

    data class ClientPermission(
        val clientDbId: Long = 0L,
        val name: String = "",
        val value: Int = 0,
        val negated: Boolean = false,
        val skip: Boolean = false,
    ) : ServerEvent {
        override val type = "client_permission"
    }

    data object ClientPermissionListDone : ServerEvent {
        override val type = "client_permission_list_done"
    }

    data class ChannelPermission(
        val channelId: Long = 0L,
        val name: String = "",
        val value: Int = 0,
        val negated: Boolean = false,
        val skip: Boolean = false,
    ) : ServerEvent {
        override val type = "channel_permission"
    }

    data object ChannelPermissionListDone : ServerEvent {
        override val type = "channel_permission_list_done"
    }

    data class TempPasswordListed(
        val nickname: String = "",
        val uid: String = "",
        val description: String = "",
        val password: String = "",
        val start: Long = 0L,
        val end: Long = 0L,
        val channelId: Long = 0L,
    ) : ServerEvent {
        override val type = "temp_password_listed"
    }

    data object TempPasswordListDone : ServerEvent {
        override val type = "temp_password_list_done"
    }

    data class ClientDbListCount(
        val count: Long = 0L,
    ) : ServerEvent {
        override val type = "client_db_list_count"
    }

    data class ClientDbEntry(
        val clientDbId: Long = 0L,
        val uid: String = "",
        val nickname: String = "",
        val created: Long = 0L,
        val lastConnected: Long = 0L,
        val connections: Long = 0L,
        val description: String = "",
    ) : ServerEvent {
        override val type = "client_db_entry"
    }

    data object ClientDbListDone : ServerEvent {
        override val type = "client_db_list_done"
    }

    data class ClientDbInfo(
        val clientDbId: Long = 0L,
        val uid: String = "",
        val nickname: String = "",
        val description: String = "",
        val avatarHash: String = "",
        val created: Long = 0L,
    ) : ServerEvent {
        override val type = "client_db_info"
    }

    data class ClientDbFound(
        val clientDbId: Long = 0L,
        val uid: String = "",
        val nickname: String = "",
        val lastConnected: Long = 0L,
        val connections: Long = 0L,
    ) : ServerEvent {
        override val type = "client_db_found"
    }

    data object ClientDbFindDone : ServerEvent {
        override val type = "client_db_find_done"
    }

    data class RecordingStarted(
        val path: String = "",
    ) : ServerEvent {
        override val type = "recording_started"
    }

    data class RecordingStopped(
        val path: String = "",
        val frames: Long = 0L,
        val seconds: Long = 0L,
        val failed: Boolean = false,
        val reason: String = "",
    ) : ServerEvent {
        override val type = "recording_stopped"
    }

    data class ServerGroupClient(
        val groupId: Long = 0L,
        val clientDbId: Long = 0L,
        val nickname: String = "",
        val uid: String = "",
    ) : ServerEvent {
        override val type = "server_group_client"
    }

    data object ServerGroupClientListDone : ServerEvent {
        override val type = "server_group_client_list_done"
    }

    data class GroupPermission(
        val groupKind: String = "",
        val groupId: Long = 0L,
        val name: String = "",
        val value: Int = 0,
        val negated: Boolean = false,
        val skip: Boolean = false,
    ) : ServerEvent {
        override val type = "group_permission"
    }

    data class GroupPermissionListDone(
        val groupKind: String = "",
    ) : ServerEvent {
        override val type = "group_permission_list_done"
    }

    data class ChannelGroupClient(
        val channelId: Long = 0L,
        val clientDbId: Long = 0L,
        val groupId: Long = 0L,
    ) : ServerEvent {
        override val type = "channel_group_client"
    }

    data object ChannelGroupClientListDone : ServerEvent {
        override val type = "channel_group_client_list_done"
    }

    data class ChannelClientPermission(
        val channelId: Long = 0L,
        val clientDbId: Long = 0L,
        val name: String = "",
        val value: Int = 0,
        val negated: Boolean = false,
        val skip: Boolean = false,
    ) : ServerEvent {
        override val type = "channel_client_permission"
    }

    data object ChannelClientPermissionListDone : ServerEvent {
        override val type = "channel_client_permission_list_done"
    }

    data class ClientServerGroup(
        val clientDbId: Long = 0L,
        val groupId: Long = 0L,
        val name: String = "",
    ) : ServerEvent {
        override val type = "client_server_group"
    }

    data object ClientServerGroupListDone : ServerEvent {
        override val type = "client_server_group_list_done"
    }

    data class ClientIdResolved(
        val uid: String = "",
        val clientId: Int = 0,
        val nickname: String = "",
    ) : ServerEvent {
        override val type = "client_id_resolved"
    }

    data object ClientIdResolvedDone : ServerEvent {
        override val type = "client_id_resolved_done"
    }

    data class ClientNameResolved(
        val uid: String = "",
        val clientDbId: Long = 0L,
        val nickname: String = "",
    ) : ServerEvent {
        override val type = "client_name_resolved"
    }

    data object ClientNameResolvedDone : ServerEvent {
        override val type = "client_name_resolved_done"
    }

    data class ClientUidResolved(
        val uid: String = "",
        val clientId: Int = 0,
        val nickname: String = "",
    ) : ServerEvent {
        override val type = "client_uid_resolved"
    }

    data object ClientUidResolvedDone : ServerEvent {
        override val type = "client_uid_resolved_done"
    }

    data class ClientConnectionInfo(
        val clientId: Int = 0,
        val ping: Long = 0L,
        val pingDeviation: Long = 0L,
        val connectedTime: Long = 0L,
        val ip: String = "",
        val port: Int = 0,
        val packetsSent: Long = 0L,
        val bytesSent: Long = 0L,
        val packetsReceived: Long = 0L,
        val bytesReceived: Long = 0L,
        val packetLoss: Int = 0,
    ) : ServerEvent {
        override val type = "client_connection_info"
    }

    data class ServerConnectionInfo(
        val packetsSent: Long = 0L,
        val bytesSent: Long = 0L,
        val packetsReceived: Long = 0L,
        val bytesReceived: Long = 0L,
        val filetransferBytesSent: Long = 0L,
        val filetransferBytesReceived: Long = 0L,
        val connectedTime: Long = 0L,
        val ping: Long = 0L,
        val packetLoss: Int = 0,
    ) : ServerEvent {
        override val type = "server_connection_info"
    }

    data class FileInfo(
        val channelId: Long = 0L,
        val path: String = "",
        val name: String = "",
        val size: Long = 0L,
        val dateTime: Long = 0L,
    ) : ServerEvent {
        override val type = "file_info"
    }

    data class PermFound(
        val scope: String = "",
        val id1: Long = 0L,
        val id2: Long = 0L,
        val permissionId: Long = 0L,
    ) : ServerEvent {
        override val type = "perm_found"
    }

    data object PermFindDone : ServerEvent {
        override val type = "perm_find_done"
    }

    data class PermListed(
        val groupIdEnd: Long = 0L,
        val permissionId: Long = 0L,
        val name: String = "",
        val description: String = "",
    ) : ServerEvent {
        override val type = "perm_listed"
    }

    data object PermListDone : ServerEvent {
        override val type = "perm_list_done"
    }

    data class LogLine(
        val offset: Long = 0L,
        val fileSize: Long = 0L,
        val line: String = "",
    ) : ServerEvent {
        override val type = "log_line"
    }

    data class TokenCreated(
        val token: String = "",
    ) : ServerEvent {
        override val type = "token_created"
    }

    data class TokenListed(
        val token: String = "",
        val tokenKind: String = "",
        val groupId: Long = 0L,
        val channelId: Long = 0L,
        val created: Long = 0L,
        val description: String = "",
    ) : ServerEvent {
        override val type = "token_listed"
    }

    data object TokenListDone : ServerEvent {
        override val type = "token_list_done"
    }

    data class TokenUsed(
        val token: String = "",
        val customSet: String = "",
        val groupId: String = "",
        val channelId: String = "",
        val clientId: Int = 0,
        val clientDbId: Long = 0L,
        val uid: String = "",
    ) : ServerEvent {
        override val type = "token_used"
    }

    data class ClientInfo(
        val clientDbId: Long = 0L,
        val uid: String = "",
        val nickname: String = "",
        val channelId: Long = 0L,
        val platform: String = "",
        val version: String = "",
        val versionSign: String = "",
        val loginName: String = "",
        val ip: String = "",
        val country: String = "",
        val description: String = "",
        val avatarHash: String = "",
        val iconId: Long = 0L,
        val badges: String = "",
        val idleTime: Long = 0L,
        val connectedTime: Long = 0L,
        val packetsSent: Long = 0L,
        val packetsReceived: Long = 0L,
        val bytesSent: Long = 0L,
        val bytesReceived: Long = 0L,
        val filetransferBytesSent: Long = 0L,
        val filetransferBytesReceived: Long = 0L,
        val clientType: Int = 0,
        val recording: Boolean = false,
        val away: Boolean = false,
        val awayMessage: String = "",
        val talkPower: Int = 0,
        val channelGroupId: Long = 0L,
    ) : ServerEvent {
        override val type = "client_info"
    }

    data class ChannelInfo(
        val parentId: Long = 0L,
        val name: String = "",
        val topic: String = "",
        val description: String = "",
        val hasPassword: Boolean = false,
        val codec: String = "",
        val codecQuality: Int = 0,
        val maxClients: Int = 0,
        val maxFamilyClients: Int = 0,
        val order: Long = 0L,
        val isPermanent: Boolean = false,
        val isDefault: Boolean = false,
        val neededTalkPower: Int = 0,
        val forcedSilence: Boolean = false,
        val iconId: Long = 0L,
        val deleteDelay: Long = 0L,
    ) : ServerEvent {
        override val type = "channel_info"
    }

    data class ChannelFound(
        val channelId: Long = 0L,
        val name: String = "",
    ) : ServerEvent {
        override val type = "channel_found"
    }

    data object ChannelFindDone : ServerEvent {
        override val type = "channel_find_done"
    }

    data class ClientListed(
        val clientId: Int = 0,
        val channelId: Long = 0L,
        val clientDbId: Long = 0L,
        val nickname: String = "",
        val uid: String = "",
        val clientType: Int = 0,
        val away: Boolean = false,
        val awayMessage: String = "",
        val talking: Boolean = false,
        val inputMuted: Boolean = false,
        val outputMuted: Boolean = false,
        val recording: Boolean = false,
        val talkPower: Int = 0,
        val channelGroupId: Long = 0L,
        val platform: String = "",
        val version: String = "",
        val country: String = "",
    ) : ServerEvent {
        override val type = "client_listed"
    }

    data object ClientListDone : ServerEvent {
        override val type = "client_list_done"
    }

    data class VirtualServerListed(
        val serverId: Long = 0L,
        val port: Int = 0,
        val status: String = "",
        val clientsOnline: Int = 0,
        val queryClientsOnline: Int = 0,
        val maxClients: Int = 0,
        val uptime: Long = 0L,
        val autostart: Boolean = false,
        val machineId: String = "",
        val name: String = "",
        val uid: String = "",
    ) : ServerEvent {
        override val type = "virtual_server_listed"
    }

    data object VirtualServerListDone : ServerEvent {
        override val type = "virtual_server_list_done"
    }

    data class ChannelEdited(
        val channelId: Long = 0L,
        val description: String = "",
    ) : ServerEvent {
        override val type = "channel_edited"
    }

    data class ClientDbIdResolved(
        val uid: String = "",
        val clientDbId: Long = 0L,
    ) : ServerEvent {
        override val type = "client_db_id_resolved"
    }

    data object ClientDbIdResolvedDone : ServerEvent {
        override val type = "client_db_id_resolved_done"
    }

    data class ClientFound(
        val clientId: Int = 0,
        val nickname: String = "",
    ) : ServerEvent {
        override val type = "client_found"
    }

    data object ClientFindDone : ServerEvent {
        override val type = "client_find_done"
    }

    data class PermValue(
        val permissionId: Long = 0L,
        val name: String = "",
        val value: Int = 0,
    ) : ServerEvent {
        override val type = "perm_value"
    }

    data class ServerInfo(
        val uid: String = "",
        val name: String = "",
        val welcomeMessage: String = "",
        val platform: String = "",
        val version: String = "",
        val maxClients: Int = 0,
        val clientsOnline: Int = 0,
        val channelsOnline: Long = 0L,
        val created: Long = 0L,
        val uptime: Long = 0L,
        val port: Int = 0,
        val hasPassword: Boolean = false,
        val autostart: Boolean = false,
        val weblistEnabled: Boolean = false,
        val askForPrivilegekey: Boolean = false,
        val hostmessage: String = "",
        val hostmessageMode: Int = 0,
        val hostbannerUrl: String = "",
        val hostbannerGfxUrl: String = "",
        val hostbuttonTooltip: String = "",
        val hostbuttonUrl: String = "",
        val hostbuttonGfxUrl: String = "",
        val machineId: String = "",
        val nickname: String = "",
        val phoneticName: String = "",
        val iconId: Long = 0L,
        val reservedSlots: Int = 0,
        val downloadQuota: Long = 0L,
        val uploadQuota: Long = 0L,
        val bytesDownloadedMonth: Long = 0L,
        val bytesUploadedMonth: Long = 0L,
        val bytesDownloadedTotal: Long = 0L,
        val bytesUploadedTotal: Long = 0L,
        val packetLoss: Int = 0,
        val ping: Long = 0L,
        val queryClientsOnline: Long = 0L,
        val connectionCountTotal: Long = 0L,
        val queryCountTotal: Long = 0L,
        val neededIdentitySecurityLevel: Int = 0,
        val prioritySpeakerDimmModificator: Int = 0,
        val status: String = "",
        val defaultServerGroup: Long = 0L,
        val defaultChannelGroup: Long = 0L,
        val defaultChannelAdminGroup: Long = 0L,
        val maxDownloadBandwidthTotal: Long = 0L,
        val maxUploadBandwidthTotal: Long = 0L,
        val tempChannelDefaultDeleteDelay: Long = 0L,
        val logClient: Boolean = false,
        val logQuery: Boolean = false,
        val logChannel: Boolean = false,
        val logPermissions: Boolean = false,
        val logServer: Boolean = false,
        val logFiletransfer: Boolean = false,
    ) : ServerEvent {
        override val type = "server_info"
    }

    data class ServerSnapshot(
        val formatVersion: Int = 0,
        val salt: String = "",
        val snapshot: String = "",
        val size: Long = 0L,
    ) : ServerEvent {
        override val type = "server_snapshot"
    }

    data class CustomEntry(
        val clientDbId: Long = 0L,
        val ident: String = "",
        val value: String = "",
    ) : ServerEvent {
        override val type = "custom_entry"
    }

    data object CustomInfoDone : ServerEvent {
        override val type = "custom_info_done"
    }

    data object CustomSearchDone : ServerEvent {
        override val type = "custom_search_done"
    }

    data class WhoAmI(
        val clientId: Int = 0,
        val channelId: Long = 0L,
        val nickname: String = "",
        val clientDbId: Long = 0L,
        val loginName: String = "",
        val uid: String = "",
        val serverId: Long = 0L,
        val originServerId: Long = 0L,
        val serverUid: String = "",
        val port: Int = 0,
        val serverStatus: String = "",
    ) : ServerEvent {
        override val type = "who_am_i"
    }

    data class ChatClosed(
        val clientId: Int = 0,
        val uid: String = "",
    ) : ServerEvent {
        override val type = "chat_closed"
    }

    data class Filetransfer(
        val clientId: Int = 0,
        val path: String = "",
        val name: String = "",
        val size: Long = 0L,
        val sizeDone: Long = 0L,
        val serverId: Int = 0,
        val clientIdTransfer: Int = 0,
        val sender: String = "",
        val status: Int = 0,
        val speed: Int = 0,
        val runtime: Long = 0L,
    ) : ServerEvent {
        override val type = "filetransfer"
    }

    data object FiletransferListDone : ServerEvent {
        override val type = "filetransfer_list_done"
    }

    data class Unknown(override val type: String) : ServerEvent

    /** One line for the debug log; also the quickest way to eyeball a build. */
    fun describe(): String = when (this) {
        is Connected -> "connected · ${serverName.ifEmpty { "?" }} (client $clientId)"
        is Disconnected -> "disconnected"
        is Reconnecting -> "reconnecting…"
        is Failure -> "error[$kind] $message"
        is CommandFailure -> "command $commandId failed: $message"
        is CommandOk -> "command $commandId ok"
        is UserJoined -> "+ $nickname"
        is UserLeft -> "- $nickname"
        is UserMoved -> "→ $nickname"
        is UserRenamed -> "~ $oldNickname ⇒ $nickname"
        is UserMuteChanged -> when {
            // A device that is off looks un-muted at the server, so the log line
            // has to say which of the two happened or it reads as "mic on" for a
            // client nobody can hear.
            !inputHardwareEnabled -> "mic device $nickname off"
            !outputHardwareEnabled -> "speaker device $nickname off"
            else -> "mic $nickname ${if (inputMuted) "off" else "on"}"
        }
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
        is AccountStatus -> if (ok) "account $command: ok" else "account $command: $message"
        is UserPrioritySpeakerChanged -> "user_priority_speaker_changed " + nickname
        is UserUnreadMessagesChanged -> "user_unread_messages_changed " + nickname
        is UserUpdated -> "user_updated " + nickname
        is UserGroupsChanged -> "user_groups_changed " + nickname
        is ServerQueryLoginPassword -> "server_query_login_password"
        is ChatComposing -> "chat_composing " + uid
        is FileListFinished -> "file_list_finished " + path
        is MissingPermission -> "missing_permission"
        is ChannelChanged -> "channel_changed"
        is ChannelPasswordChanged -> "channel_password_changed"
        is PluginCommand -> "plugin_command " + name
        is ChannelCreatedId -> "channel_created_id"
        is ServerGroupCreated -> "server_group_created"
        is GroupCreated -> "group_created " + name
        is GroupUpdated -> "group_updated " + name
        is GroupRemoved -> "group_removed " + name
        is ServerUpdated -> "server_updated " + name
        is ChannelUpdated -> "channel_updated"
        is IconReady -> "icon_ready " + path
        is IconFailed -> "icon_failed " + message
        is BanListCount -> "ban_list_count " + count
        is BanListed -> "ban_listed " + name
        is BanListDone -> "ban_list_done"
        is ComplaintListed -> "complaint_listed " + message
        is ComplaintListDone -> "complaint_list_done"
        is OfflineMessageListed -> "offline_message_listed " + uid
        is OfflineMessageListDone -> "offline_message_list_done"
        is OfflineMessage -> "offline_message " + message
        is ClientPermission -> "client_permission " + name
        is ClientPermissionListDone -> "client_permission_list_done"
        is ChannelPermission -> "channel_permission " + name
        is ChannelPermissionListDone -> "channel_permission_list_done"
        is TempPasswordListed -> "temp_password_listed " + nickname
        is TempPasswordListDone -> "temp_password_list_done"
        is ClientDbListCount -> "client_db_list_count " + count
        is ClientDbEntry -> "client_db_entry " + nickname
        is ClientDbListDone -> "client_db_list_done"
        is ClientDbInfo -> "client_db_info " + nickname
        is ClientDbFound -> "client_db_found " + nickname
        is ClientDbFindDone -> "client_db_find_done"
        is RecordingStarted -> "recording_started " + path
        is RecordingStopped -> "recording_stopped " + path
        is ServerGroupClient -> "server_group_client " + nickname
        is ServerGroupClientListDone -> "server_group_client_list_done"
        is GroupPermission -> "group_permission " + name
        is GroupPermissionListDone -> "group_permission_list_done"
        is ChannelGroupClient -> "channel_group_client"
        is ChannelGroupClientListDone -> "channel_group_client_list_done"
        is ChannelClientPermission -> "channel_client_permission " + name
        is ChannelClientPermissionListDone -> "channel_client_permission_list_done"
        is ClientServerGroup -> "client_server_group " + name
        is ClientServerGroupListDone -> "client_server_group_list_done"
        is ClientIdResolved -> "client_id_resolved " + nickname
        is ClientIdResolvedDone -> "client_id_resolved_done"
        is ClientNameResolved -> "client_name_resolved " + nickname
        is ClientNameResolvedDone -> "client_name_resolved_done"
        is ClientUidResolved -> "client_uid_resolved " + nickname
        is ClientUidResolvedDone -> "client_uid_resolved_done"
        is ClientConnectionInfo -> "client_connection_info"
        is ServerConnectionInfo -> "server_connection_info"
        is FileInfo -> "file_info " + name
        is PermFound -> "perm_found"
        is PermFindDone -> "perm_find_done"
        is PermListed -> "perm_listed " + name
        is PermListDone -> "perm_list_done"
        is LogLine -> "log_line " + line
        is TokenCreated -> "token_created " + token
        is TokenListed -> "token_listed " + token
        is TokenListDone -> "token_list_done"
        is TokenUsed -> "token_used " + token
        is ClientInfo -> "client_info " + nickname
        is ChannelInfo -> "channel_info " + name
        is ChannelFound -> "channel_found " + name
        is ChannelFindDone -> "channel_find_done"
        is ClientListed -> "client_listed " + nickname
        is ClientListDone -> "client_list_done"
        is VirtualServerListed -> "virtual_server_listed " + name
        is VirtualServerListDone -> "virtual_server_list_done"
        is ChannelEdited -> "channel_edited"
        is ClientDbIdResolved -> "client_db_id_resolved " + uid
        is ClientDbIdResolvedDone -> "client_db_id_resolved_done"
        is ClientFound -> "client_found " + nickname
        is ClientFindDone -> "client_find_done"
        is PermValue -> "perm_value " + name
        is ServerInfo -> "server_info " + name
        is ServerSnapshot -> "server_snapshot"
        is CustomEntry -> "custom_entry " + ident
        is CustomInfoDone -> "custom_info_done"
        is CustomSearchDone -> "custom_search_done"
        is WhoAmI -> "who_am_i " + nickname
        is ChatClosed -> "chat_closed " + uid
        is Filetransfer -> "filetransfer " + name
        is FiletransferListDone -> "filetransfer_list_done"
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
                "error" -> Failure(
                    message = str(map, "message"),
                    kind = str(map, "kind"),
                    // Absent on an older library; "not terminal" is the safe
                    // reading, because it keeps the connect button available.
                    terminal = boolOr(map, "terminal", false),
                )
                "command_error" -> CommandFailure(
                    message = str(map, "message"),
                    missingPermission = str(map, "missing_permission"),
                    // Absent for a failure detected locally, which is the same
                    // "no command to blame" case the docs describe.
                    commandId = int(map, "command_id"),
                )
                "command_ok" -> CommandOk(commandId = int(map, "command_id"))

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
                    fromSelf = bool(map, "from_self"),
                    timestamp = long(map, "timestamp"),
                    timestampMs = long(map, "timestamp_ms"),
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

                "account_status" -> AccountStatus(
                    command = str(map, "command"),
                    ok = bool(map, "ok"),
                    message = str(map, "message"),
                    kind = str(map, "kind"),
                )

                "user_priority_speaker_changed" -> UserPrioritySpeakerChanged(
                    clientId = int(map, "client_id"),
                    nickname = str(map, "nickname"),
                    prioritySpeaker = bool(map, "priority_speaker"),
                )

                "user_unread_messages_changed" -> UserUnreadMessagesChanged(
                    clientId = int(map, "client_id"),
                    nickname = str(map, "nickname"),
                    unreadMessages = long(map, "unread_messages"),
                )

                "user_updated" -> UserUpdated(
                    clientId = int(map, "client_id"),
                    nickname = str(map, "nickname"),
                    changed = str(map, "changed"),
                )

                "user_groups_changed" -> UserGroupsChanged(
                    clientId = int(map, "client_id"),
                    nickname = str(map, "nickname"),
                    channelGroup = long(map, "channel_group"),
                    serverGroups = str(map, "server_groups"),
                )

                "server_query_login_password" -> ServerQueryLoginPassword(
                    password = str(map, "password"),
                )

                "chat_composing" -> ChatComposing(
                    clientId = int(map, "client_id"),
                    uid = str(map, "uid"),
                )

                "file_list_finished" -> FileListFinished(
                    channelId = long(map, "channel_id"),
                    path = str(map, "path"),
                )

                "missing_permission" -> MissingPermission(
                    permissionId = long(map, "permission_id"),
                    value = int(map, "value"),
                )

                "channel_changed" -> ChannelChanged(
                    channelId = long(map, "channel_id"),
                )

                "channel_password_changed" -> ChannelPasswordChanged(
                    channelId = long(map, "channel_id"),
                )

                "plugin_command" -> PluginCommand(
                    name = str(map, "name"),
                    `data` = str(map, "data"),
                    invokerId = int(map, "invoker_id"),
                    invokerName = str(map, "invoker_name"),
                    invokerUid = str(map, "invoker_uid"),
                )

                "channel_created_id" -> ChannelCreatedId(
                    channelId = long(map, "channel_id"),
                )

                "server_group_created" -> ServerGroupCreated(
                    groupId = long(map, "group_id"),
                )

                "group_created" -> GroupCreated(
                    groupKind = str(map, "group_kind"),
                    groupId = long(map, "group_id"),
                    name = str(map, "name"),
                )

                "group_updated" -> GroupUpdated(
                    groupKind = str(map, "group_kind"),
                    groupId = long(map, "group_id"),
                    name = str(map, "name"),
                    changed = str(map, "changed"),
                )

                "group_removed" -> GroupRemoved(
                    groupKind = str(map, "group_kind"),
                    groupId = long(map, "group_id"),
                    name = str(map, "name"),
                )

                "server_updated" -> ServerUpdated(
                    changed = str(map, "changed"),
                    name = str(map, "name"),
                    welcomeMessage = str(map, "welcome_message"),
                )

                "channel_updated" -> ChannelUpdated(
                    channelId = long(map, "channel_id"),
                    changed = str(map, "changed"),
                )

                "icon_ready" -> IconReady(
                    iconId = long(map, "icon_id"),
                    path = str(map, "path"),
                    format = str(map, "format"),
                    size = long(map, "size"),
                )

                "icon_failed" -> IconFailed(
                    iconId = long(map, "icon_id"),
                    message = str(map, "message"),
                )

                "ban_list_count" -> BanListCount(count = long(map, "count"))

                "ban_listed" -> BanListed(
                    banId = long(map, "ban_id"),
                    ip = str(map, "ip"),
                    name = str(map, "name"),
                    uid = str(map, "uid"),
                    lastNickname = str(map, "last_nickname"),
                    created = long(map, "created"),
                    duration = long(map, "duration"),
                    invokerName = str(map, "invoker_name"),
                    reason = str(map, "reason"),
                )

                "ban_list_done" -> BanListDone

                "complaint_listed" -> ComplaintListed(
                    targetDbId = long(map, "target_db_id"),
                    targetName = str(map, "target_name"),
                    fromDbId = long(map, "from_db_id"),
                    fromName = str(map, "from_name"),
                    message = str(map, "message"),
                    timestamp = long(map, "timestamp"),
                )

                "complaint_list_done" -> ComplaintListDone

                "offline_message_listed" -> OfflineMessageListed(
                    messageId = long(map, "message_id"),
                    uid = str(map, "uid"),
                    subject = str(map, "subject"),
                    timestamp = long(map, "timestamp"),
                    read = bool(map, "read"),
                )

                "offline_message_list_done" -> OfflineMessageListDone

                "offline_message" -> OfflineMessage(
                    messageId = long(map, "message_id"),
                    uid = str(map, "uid"),
                    subject = str(map, "subject"),
                    message = str(map, "message"),
                    timestamp = long(map, "timestamp"),
                )

                "client_permission" -> ClientPermission(
                    clientDbId = long(map, "client_db_id"),
                    name = str(map, "name"),
                    value = int(map, "value"),
                    negated = bool(map, "negated"),
                    skip = bool(map, "skip"),
                )

                "client_permission_list_done" -> ClientPermissionListDone

                "channel_permission" -> ChannelPermission(
                    channelId = long(map, "channel_id"),
                    name = str(map, "name"),
                    value = int(map, "value"),
                    negated = bool(map, "negated"),
                    skip = bool(map, "skip"),
                )

                "channel_permission_list_done" -> ChannelPermissionListDone

                "temp_password_listed" -> TempPasswordListed(
                    nickname = str(map, "nickname"),
                    uid = str(map, "uid"),
                    description = str(map, "description"),
                    password = str(map, "password"),
                    start = long(map, "start"),
                    end = long(map, "end"),
                    channelId = long(map, "channel_id"),
                )

                "temp_password_list_done" -> TempPasswordListDone

                "client_db_list_count" -> ClientDbListCount(count = long(map, "count"))

                "client_db_entry" -> ClientDbEntry(
                    clientDbId = long(map, "client_db_id"),
                    uid = str(map, "uid"),
                    nickname = str(map, "nickname"),
                    created = long(map, "created"),
                    lastConnected = long(map, "last_connected"),
                    connections = long(map, "connections"),
                    description = str(map, "description"),
                )

                "client_db_list_done" -> ClientDbListDone

                "client_db_info" -> ClientDbInfo(
                    clientDbId = long(map, "client_db_id"),
                    uid = str(map, "uid"),
                    nickname = str(map, "nickname"),
                    description = str(map, "description"),
                    avatarHash = str(map, "avatar_hash"),
                    created = long(map, "created"),
                )

                "client_db_found" -> ClientDbFound(
                    clientDbId = long(map, "client_db_id"),
                    uid = str(map, "uid"),
                    nickname = str(map, "nickname"),
                    lastConnected = long(map, "last_connected"),
                    connections = long(map, "connections"),
                )

                "client_db_find_done" -> ClientDbFindDone

                "recording_started" -> RecordingStarted(
                    path = str(map, "path"),
                )

                "recording_stopped" -> RecordingStopped(
                    path = str(map, "path"),
                    frames = long(map, "frames"),
                    seconds = long(map, "seconds"),
                    failed = bool(map, "failed"),
                    reason = str(map, "reason"),
                )

                "server_group_client" -> ServerGroupClient(
                    groupId = long(map, "group_id"),
                    clientDbId = long(map, "client_db_id"),
                    nickname = str(map, "nickname"),
                    uid = str(map, "uid"),
                )

                "server_group_client_list_done" -> ServerGroupClientListDone

                "group_permission" -> GroupPermission(
                    groupKind = str(map, "group_kind"),
                    groupId = long(map, "group_id"),
                    name = str(map, "name"),
                    value = int(map, "value"),
                    negated = bool(map, "negated"),
                    skip = bool(map, "skip"),
                )

                "group_permission_list_done" -> GroupPermissionListDone(
                    groupKind = str(map, "group_kind"),
                )

                "channel_group_client" -> ChannelGroupClient(
                    channelId = long(map, "channel_id"),
                    clientDbId = long(map, "client_db_id"),
                    groupId = long(map, "group_id"),
                )

                "channel_group_client_list_done" -> ChannelGroupClientListDone

                "channel_client_permission" -> ChannelClientPermission(
                    channelId = long(map, "channel_id"),
                    clientDbId = long(map, "client_db_id"),
                    name = str(map, "name"),
                    value = int(map, "value"),
                    negated = bool(map, "negated"),
                    skip = bool(map, "skip"),
                )

                "channel_client_permission_list_done" -> ChannelClientPermissionListDone

                "client_server_group" -> ClientServerGroup(
                    clientDbId = long(map, "client_db_id"),
                    groupId = long(map, "group_id"),
                    name = str(map, "name"),
                )

                "client_server_group_list_done" -> ClientServerGroupListDone

                "client_id_resolved" -> ClientIdResolved(
                    uid = str(map, "uid"),
                    clientId = int(map, "client_id"),
                    nickname = str(map, "nickname"),
                )

                "client_id_resolved_done" -> ClientIdResolvedDone

                "client_name_resolved" -> ClientNameResolved(
                    uid = str(map, "uid"),
                    clientDbId = long(map, "client_db_id"),
                    nickname = str(map, "nickname"),
                )

                "client_name_resolved_done" -> ClientNameResolvedDone

                "client_uid_resolved" -> ClientUidResolved(
                    uid = str(map, "uid"),
                    clientId = int(map, "client_id"),
                    nickname = str(map, "nickname"),
                )

                "client_uid_resolved_done" -> ClientUidResolvedDone

                "client_connection_info" -> ClientConnectionInfo(
                    clientId = int(map, "client_id"),
                    ping = long(map, "ping"),
                    pingDeviation = long(map, "ping_deviation"),
                    connectedTime = long(map, "connected_time"),
                    ip = str(map, "ip"),
                    port = int(map, "port"),
                    packetsSent = long(map, "packets_sent"),
                    bytesSent = long(map, "bytes_sent"),
                    packetsReceived = long(map, "packets_received"),
                    bytesReceived = long(map, "bytes_received"),
                    packetLoss = int(map, "packet_loss"),
                )

                "server_connection_info" -> ServerConnectionInfo(
                    packetsSent = long(map, "packets_sent"),
                    bytesSent = long(map, "bytes_sent"),
                    packetsReceived = long(map, "packets_received"),
                    bytesReceived = long(map, "bytes_received"),
                    filetransferBytesSent = long(map, "filetransfer_bytes_sent"),
                    filetransferBytesReceived = long(map, "filetransfer_bytes_received"),
                    connectedTime = long(map, "connected_time"),
                    ping = long(map, "ping"),
                    packetLoss = int(map, "packet_loss"),
                )

                "file_info" -> FileInfo(
                    channelId = long(map, "channel_id"),
                    path = str(map, "path"),
                    name = str(map, "name"),
                    size = long(map, "size"),
                    dateTime = long(map, "date_time"),
                )

                "perm_found" -> PermFound(
                    scope = str(map, "scope"),
                    id1 = long(map, "id1"),
                    id2 = long(map, "id2"),
                    permissionId = long(map, "permission_id"),
                )

                "perm_find_done" -> PermFindDone

                "perm_listed" -> PermListed(
                    groupIdEnd = long(map, "group_id_end"),
                    permissionId = long(map, "permission_id"),
                    name = str(map, "name"),
                    description = str(map, "description"),
                )

                "perm_list_done" -> PermListDone

                "log_line" -> LogLine(
                    offset = long(map, "offset"),
                    fileSize = long(map, "file_size"),
                    line = str(map, "line"),
                )

                "token_created" -> TokenCreated(
                    token = str(map, "token"),
                )

                "token_listed" -> TokenListed(
                    token = str(map, "token"),
                    tokenKind = str(map, "token_kind"),
                    groupId = long(map, "group_id"),
                    channelId = long(map, "channel_id"),
                    created = long(map, "created"),
                    description = str(map, "description"),
                )

                "token_list_done" -> TokenListDone

                "token_used" -> TokenUsed(
                    token = str(map, "token"),
                    customSet = str(map, "custom_set"),
                    groupId = str(map, "group_id"),
                    channelId = str(map, "channel_id"),
                    clientId = int(map, "client_id"),
                    clientDbId = long(map, "client_db_id"),
                    uid = str(map, "uid"),
                )

                "client_info" -> ClientInfo(
                    clientDbId = long(map, "client_db_id"),
                    uid = str(map, "uid"),
                    nickname = str(map, "nickname"),
                    channelId = long(map, "channel_id"),
                    platform = str(map, "platform"),
                    version = str(map, "version"),
                    versionSign = str(map, "version_sign"),
                    loginName = str(map, "login_name"),
                    ip = str(map, "ip"),
                    country = str(map, "country"),
                    description = str(map, "description"),
                    avatarHash = str(map, "avatar_hash"),
                    iconId = long(map, "icon_id"),
                    badges = str(map, "badges"),
                    idleTime = long(map, "idle_time"),
                    connectedTime = long(map, "connected_time"),
                    packetsSent = long(map, "packets_sent"),
                    packetsReceived = long(map, "packets_received"),
                    bytesSent = long(map, "bytes_sent"),
                    bytesReceived = long(map, "bytes_received"),
                    filetransferBytesSent = long(map, "filetransfer_bytes_sent"),
                    filetransferBytesReceived = long(map, "filetransfer_bytes_received"),
                    clientType = int(map, "client_type"),
                    recording = bool(map, "recording"),
                    away = bool(map, "away"),
                    awayMessage = str(map, "away_message"),
                    talkPower = int(map, "talk_power"),
                    channelGroupId = long(map, "channel_group_id"),
                )

                "channel_info" -> ChannelInfo(
                    parentId = long(map, "parent_id"),
                    name = str(map, "name"),
                    topic = str(map, "topic"),
                    description = str(map, "description"),
                    hasPassword = bool(map, "has_password"),
                    codec = str(map, "codec"),
                    codecQuality = int(map, "codec_quality"),
                    maxClients = int(map, "max_clients"),
                    maxFamilyClients = int(map, "max_family_clients"),
                    order = long(map, "order"),
                    isPermanent = bool(map, "is_permanent"),
                    isDefault = bool(map, "is_default"),
                    neededTalkPower = int(map, "needed_talk_power"),
                    forcedSilence = bool(map, "forced_silence"),
                    iconId = long(map, "icon_id"),
                    deleteDelay = long(map, "delete_delay"),
                )

                "channel_found" -> ChannelFound(
                    channelId = long(map, "channel_id"),
                    name = str(map, "name"),
                )

                "channel_find_done" -> ChannelFindDone

                "client_listed" -> ClientListed(
                    clientId = int(map, "client_id"),
                    channelId = long(map, "channel_id"),
                    clientDbId = long(map, "client_db_id"),
                    nickname = str(map, "nickname"),
                    uid = str(map, "uid"),
                    clientType = int(map, "client_type"),
                    away = bool(map, "away"),
                    awayMessage = str(map, "away_message"),
                    talking = bool(map, "talking"),
                    inputMuted = bool(map, "input_muted"),
                    outputMuted = bool(map, "output_muted"),
                    recording = bool(map, "recording"),
                    talkPower = int(map, "talk_power"),
                    channelGroupId = long(map, "channel_group_id"),
                    platform = str(map, "platform"),
                    version = str(map, "version"),
                    country = str(map, "country"),
                )

                "client_list_done" -> ClientListDone

                "virtual_server_listed" -> VirtualServerListed(
                    serverId = long(map, "server_id"),
                    port = int(map, "port"),
                    status = str(map, "status"),
                    clientsOnline = int(map, "clients_online"),
                    queryClientsOnline = int(map, "query_clients_online"),
                    maxClients = int(map, "max_clients"),
                    uptime = long(map, "uptime"),
                    autostart = bool(map, "autostart"),
                    machineId = str(map, "machine_id"),
                    name = str(map, "name"),
                    uid = str(map, "uid"),
                )

                "virtual_server_list_done" -> VirtualServerListDone

                "channel_edited" -> ChannelEdited(
                    channelId = long(map, "channel_id"),
                    description = str(map, "description"),
                )

                "client_db_id_resolved" -> ClientDbIdResolved(
                    uid = str(map, "uid"),
                    clientDbId = long(map, "client_db_id"),
                )

                "client_db_id_resolved_done" -> ClientDbIdResolvedDone

                "client_found" -> ClientFound(
                    clientId = int(map, "client_id"),
                    nickname = str(map, "nickname"),
                )

                "client_find_done" -> ClientFindDone

                "perm_value" -> PermValue(
                    permissionId = long(map, "permission_id"),
                    name = str(map, "name"),
                    value = int(map, "value"),
                )

                "server_info" -> ServerInfo(
                    uid = str(map, "uid"),
                    name = str(map, "name"),
                    welcomeMessage = str(map, "welcome_message"),
                    platform = str(map, "platform"),
                    version = str(map, "version"),
                    maxClients = int(map, "max_clients"),
                    clientsOnline = int(map, "clients_online"),
                    channelsOnline = long(map, "channels_online"),
                    created = long(map, "created"),
                    uptime = long(map, "uptime"),
                    port = int(map, "port"),
                    hasPassword = bool(map, "has_password"),
                    autostart = bool(map, "autostart"),
                    weblistEnabled = bool(map, "weblist_enabled"),
                    askForPrivilegekey = bool(map, "ask_for_privilegekey"),
                    hostmessage = str(map, "hostmessage"),
                    hostmessageMode = int(map, "hostmessage_mode"),
                    hostbannerUrl = str(map, "hostbanner_url"),
                    hostbannerGfxUrl = str(map, "hostbanner_gfx_url"),
                    hostbuttonTooltip = str(map, "hostbutton_tooltip"),
                    hostbuttonUrl = str(map, "hostbutton_url"),
                    hostbuttonGfxUrl = str(map, "hostbutton_gfx_url"),
                    machineId = str(map, "machine_id"),
                    nickname = str(map, "nickname"),
                    phoneticName = str(map, "phonetic_name"),
                    iconId = long(map, "icon_id"),
                    reservedSlots = int(map, "reserved_slots"),
                    downloadQuota = long(map, "download_quota"),
                    uploadQuota = long(map, "upload_quota"),
                    bytesDownloadedMonth = long(map, "bytes_downloaded_month"),
                    bytesUploadedMonth = long(map, "bytes_uploaded_month"),
                    bytesDownloadedTotal = long(map, "bytes_downloaded_total"),
                    bytesUploadedTotal = long(map, "bytes_uploaded_total"),
                    packetLoss = int(map, "packet_loss"),
                    ping = long(map, "ping"),
                    queryClientsOnline = long(map, "query_clients_online"),
                    connectionCountTotal = long(map, "connection_count_total"),
                    queryCountTotal = long(map, "query_count_total"),
                    neededIdentitySecurityLevel = int(map, "needed_identity_security_level"),
                    prioritySpeakerDimmModificator = int(map, "priority_speaker_dimm_modificator"),
                    status = str(map, "status"),
                    defaultServerGroup = long(map, "default_server_group"),
                    defaultChannelGroup = long(map, "default_channel_group"),
                    defaultChannelAdminGroup = long(map, "default_channel_admin_group"),
                    maxDownloadBandwidthTotal = long(map, "max_download_bandwidth_total"),
                    maxUploadBandwidthTotal = long(map, "max_upload_bandwidth_total"),
                    tempChannelDefaultDeleteDelay = long(map, "temp_channel_default_delete_delay"),
                    logClient = bool(map, "log_client"),
                    logQuery = bool(map, "log_query"),
                    logChannel = bool(map, "log_channel"),
                    logPermissions = bool(map, "log_permissions"),
                    logServer = bool(map, "log_server"),
                    logFiletransfer = bool(map, "log_filetransfer"),
                )

                "server_snapshot" -> ServerSnapshot(
                    formatVersion = int(map, "format_version"),
                    salt = str(map, "salt"),
                    snapshot = str(map, "snapshot"),
                    size = long(map, "size"),
                )

                "custom_entry" -> CustomEntry(
                    clientDbId = long(map, "client_db_id"),
                    ident = str(map, "ident"),
                    value = str(map, "value"),
                )

                "custom_info_done" -> CustomInfoDone

                "custom_search_done" -> CustomSearchDone

                "who_am_i" -> WhoAmI(
                    clientId = int(map, "client_id"),
                    channelId = long(map, "channel_id"),
                    nickname = str(map, "nickname"),
                    clientDbId = long(map, "client_db_id"),
                    loginName = str(map, "login_name"),
                    uid = str(map, "uid"),
                    serverId = long(map, "server_id"),
                    originServerId = long(map, "origin_server_id"),
                    serverUid = str(map, "server_uid"),
                    port = int(map, "port"),
                    serverStatus = str(map, "server_status"),
                )

                "chat_closed" -> ChatClosed(
                    clientId = int(map, "client_id"),
                    uid = str(map, "uid"),
                )

                "filetransfer" -> Filetransfer(
                    clientId = int(map, "client_id"),
                    path = str(map, "path"),
                    name = str(map, "name"),
                    size = long(map, "size"),
                    sizeDone = long(map, "size_done"),
                    serverId = int(map, "server_id"),
                    clientIdTransfer = int(map, "client_id_transfer"),
                    sender = str(map, "sender"),
                    status = int(map, "status"),
                    speed = int(map, "speed"),
                    runtime = long(map, "runtime"),
                )

                "filetransfer_list_done" -> FiletransferListDone

                else -> Unknown(type)
            }
        }
    }
}
