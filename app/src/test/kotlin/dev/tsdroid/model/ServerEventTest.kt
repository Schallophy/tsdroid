package dev.tsdroid.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the event contract between tsbridge and this model.
 *
 * `ServerEvent.from` is the only place a native event map becomes a typed value,
 * and its keys are a cross-repo contract: tsbridge's `crates/core` emits them,
 * `docs/events.md` documents them, and this file consumes them. A rename on
 * either side used to fail silently in one direction only — the event still
 * arrived, every field just read as its default, and the UI showed a blank
 * nickname with no error anywhere.
 *
 * Three sets are compared, so no two of them can drift without this failing:
 *
 * 1. the `type` strings `ServerEvent.kt` declares (read from the source);
 * 2. the fixtures below, built from the *documented* field names;
 * 3. the table in tsbridge's `docs/events.md`.
 *
 * JVM-only on purpose: nothing here touches an Android API, so `gradlew test`
 * runs it without an emulator.
 */
class ServerEventTest {

    @Test
    fun everyEventParses() {
        for ((type, map) in samples) {
            val event = ServerEvent.from(map)
            assertNotNull("event `$type` did not parse", event)
            assertEquals("event `$type` parsed as the wrong type", type, event!!.type)
        }
    }

    @Test
    fun fieldsLandInTheRightProperties() {
        val joined = ServerEvent.from(
            mapOf(
                "type" to "user_joined",
                "client_id" to 7,
                "nickname" to "ann",
                "uid" to "abc=",
                "channel_id" to 12L,
            ),
        ) as ServerEvent.UserJoined
        assertEquals(7, joined.clientId)
        assertEquals("ann", joined.nickname)
        assertEquals("abc=", joined.uid)
        // The native side sends channel ids as int64. A parser reading them as
        // Int would truncate on a large server and drop the member into channel 0.
        assertEquals(12L, joined.channelId)

        val message = ServerEvent.from(
            mapOf(
                "type" to "text_message",
                "target" to "client",
                "from_id" to 3,
                "from_name" to "bob",
                "from_uid" to "bob=",
                "message" to "hi",
                "to_id" to 9,
                "from_self" to true,
                "timestamp" to 1_700_000_000L,
                "timestamp_ms" to 1_700_000_000_123L,
            ),
        ) as ServerEvent.TextMessage
        assertEquals(ChatScope.Private, message.scope)
        assertEquals(9, message.toId)
        assertEquals(true, message.fromSelf)
        assertEquals(1_700_000_000L, message.timestamp)
        assertEquals(1_700_000_000_123L, message.timestampMs)

        val transfer = ServerEvent.from(
            mapOf(
                "type" to "transfer_progress",
                "name" to "x.bin",
                "direction" to "upload",
                "done" to 5L,
                "total" to 10L,
            ),
        ) as ServerEvent.TransferProgress
        assertEquals(TransferDirection.Upload, transfer.direction)
        assertEquals(5L, transfer.done)
    }

    @Test
    fun aChannelGroupChangeLandsInTheRightProperties() {
        // The row draws the channel group to the left of the server groups, so
        // both halves of this event have to arrive intact: a dropped
        // `channel_group` would put an admin in the wrong column, and a dropped
        // `server_groups` would empty the row.
        val event = ServerEvent.from(
            mapOf(
                "type" to "user_groups_changed",
                "client_id" to 7,
                "nickname" to "ann",
                "channel_group" to 5L,
                "server_groups" to "6,8",
            ),
        ) as ServerEvent.UserGroupsChanged
        assertEquals(7, event.clientId)
        assertEquals(5L, event.channelGroup)
        assertEquals("6,8", event.serverGroups)
    }

    @Test
    fun anAccountFailureCarriesItsKind() {
        // The kind is what lets a shell translate the sentence or offer the next
        // step; without it a non-English UI prints English prose. Empty on
        // success, so the two fields cannot disagree about whether there was a
        // failure.
        val failed = ServerEvent.from(
            mapOf(
                "type" to "account_status",
                "command" to "account_login",
                "ok" to false,
                "message" to "the credential was refused",
                "kind" to "login",
            ),
        ) as ServerEvent.AccountStatus
        assertEquals(false, failed.ok)
        assertEquals("login", failed.kind)

        val ok = ServerEvent.from(
            mapOf("type" to "account_status", "command" to "sync_list", "ok" to true, "message" to ""),
        ) as ServerEvent.AccountStatus
        assertEquals("", ok.kind)
    }

    @Test
    fun hardwareFlagsLandInTheRightProperties() {
        val event = ServerEvent.from(
            mapOf(
                "type" to "user_mute_changed",
                "client_id" to 7,
                "nickname" to "ann",
                "input_muted" to false,
                "output_muted" to false,
                "input_hardware_enabled" to false,
                "output_hardware_enabled" to true,
                "scope" to "input",
            ),
        ) as ServerEvent.UserMuteChanged
        assertEquals(7, event.clientId)
        assertTrue("the server-side flag said un-muted", !event.inputMuted)
        assertTrue("the capture device flag was dropped", !event.inputHardwareEnabled)
        assertTrue("the playback device flag was dropped", event.outputHardwareEnabled)
        assertEquals("input", event.scope)
    }

    @Test
    fun hardwareFlagsDefaultToEnabled() {
        // An event from a native library that predates the hardware fields must
        // read as "device on". Defaulting to `false` would draw every member as
        // hardware-muted, which is the visible opposite of the bug being fixed.
        val event = ServerEvent.from(
            mapOf(
                "type" to "user_mute_changed",
                "client_id" to 7,
                "nickname" to "ann",
                "input_muted" to true,
                "output_muted" to false,
                "scope" to "input",
            ),
        ) as ServerEvent.UserMuteChanged
        assertTrue(event.inputHardwareEnabled)
        assertTrue(event.outputHardwareEnabled)

        // Same for the snapshot mirror: absent keys mean an older tsbridge.
        val legacy = LENIENT_JSON.decodeFromString<ServerState>("""{"rev":1,"nickname":"x"}""")
        assertTrue(legacy.inputHardwareEnabled)
        assertTrue(legacy.outputHardwareEnabled)
    }

    @Test
    fun unknownTypesSurvive() {
        // A newer library may emit an event this build has no case for. Dropping
        // it is how a shipped feature goes quiet, so it must round-trip.
        assertEquals(
            ServerEvent.Unknown("future_thing"),
            ServerEvent.from(mapOf("type" to "future_thing", "extra" to 1)),
        )
    }

    @Test
    fun malformedMapsDoNotCrash() {
        // A missing `type` is the only thing that legitimately yields null. A
        // missing or wrongly-typed *field* must default, never throw: this runs
        // inside the 20 ms poll loop, where an exception kills the session.
        assertNull(ServerEvent.from(emptyMap()))
        assertNull(ServerEvent.from(mapOf("type" to 1)))

        val joined = ServerEvent.from(mapOf("type" to "user_joined")) as ServerEvent.UserJoined
        assertEquals(0, joined.clientId)
        assertEquals("", joined.nickname)
        assertEquals(0L, joined.channelId)
    }

    @Test
    fun declaredEventsAndFixturesAgree() {
        assertEquals(
            "ServerEvent.kt declares an event with no fixture here (or the other " +
                "way round), so the contract test cannot cover it",
            declaredTypes(),
            samples.keys,
        )
    }

    @Test
    fun tsbridgeDocsAndThisModelAgree() {
        // The cross-repo half of the check. `event_contract.rs` in tsbridge proves
        // Rust and its docs agree; this proves the docs and Kotlin agree; so all
        // three are transitively pinned without duplicating the table.
        val docs = repoFile("third_party/tsbridge/docs/events.md")
        assertTrue("tsbridge submodule missing at ${docs.absolutePath}", docs.isFile)

        // `| `type` | fields |` is the table header and has the same shape as a
        // data row, so the literal name `type` has to be excluded explicitly.
        val header = "type"

        val documented = docs.readLines()
            .mapNotNull { line ->
                val cells = line.trim().split('|')
                if (cells.size < 4) return@mapNotNull null
                cells[1].trim().removeSurrounding("`").takeIf { it.matches(EVENT_NAME) }
            }
            .minus(header)
            .toSet()

        assertEquals(
            "ServerEvent and tsbridge's docs/events.md disagree about which events exist",
            declaredTypes(),
            documented,
        )
    }

    /**
     * `type` strings declared in `ServerEvent.kt`, read from the source.
     *
     * Scanning the file beats a hand-written list: a third copy is exactly what
     * would drift. `Unknown` stores its type in the constructor, so it has no
     * `override val type = "…"` and is correctly absent.
     */
    private fun declaredTypes(): Set<String> {
        val source = repoFile("app/src/main/kotlin/dev/tsdroid/model/ServerEvent.kt")
        assertTrue("ServerEvent.kt not found at ${source.absolutePath}", source.isFile)
        return LITERAL_TYPE.findAll(source.readText())
            .map { it.groupValues[1] }
            .toSet()
    }

    /**
     * Resolves a repository-relative path regardless of the test's working
     * directory: Gradle uses the module dir, but an IDE runner uses the project
     * root, and a test that only works in one of them is a trap.
     */
    private fun repoFile(relative: String): File {
        val candidates = listOf(File("."), File(".."), File("../.."))
        return candidates.map { it.resolve(relative) }.firstOrNull { it.isFile || it.isDirectory }
            ?: File(relative)
    }

    /** Every event in tsbridge's `docs/events.md`, with its documented fields. */
    private val samples: Map<String, Map<String, Any>> = mapOf(
        "connected" to mapOf(
            "type" to "connected",
            "client_id" to 1,
            "server_name" to "s",
            "server_version" to "6",
        ),
        "disconnected" to mapOf("type" to "disconnected"),
        "reconnecting" to mapOf("type" to "reconnecting"),
        "error" to mapOf("type" to "error", "message" to "m", "kind" to "connect"),
        "command_error" to mapOf(
            "type" to "command_error",
            "message" to "m",
            "missing_permission" to "",
            "command_id" to 3,
        ),
        "command_ok" to mapOf("type" to "command_ok", "command_id" to 7),
        "user_joined" to mapOf(
            "type" to "user_joined",
            "client_id" to 2,
            "nickname" to "a",
            "uid" to "u",
            "channel_id" to 4L,
        ),
        "user_left" to mapOf("type" to "user_left", "client_id" to 2, "nickname" to "a"),
        "user_moved" to mapOf(
            "type" to "user_moved",
            "client_id" to 2,
            "nickname" to "a",
            "channel_id" to 4L,
            "old_channel_id" to 5L,
        ),
        "user_renamed" to mapOf(
            "type" to "user_renamed",
            "client_id" to 2,
            "nickname" to "a",
            "old_nickname" to "b",
        ),
        "user_mute_changed" to mapOf(
            "type" to "user_mute_changed",
            "client_id" to 2,
            "nickname" to "a",
            "input_muted" to true,
            "output_muted" to false,
            "input_hardware_enabled" to true,
            "output_hardware_enabled" to false,
            "scope" to "input",
        ),
        "user_away_changed" to mapOf(
            "type" to "user_away_changed",
            "client_id" to 2,
            "nickname" to "a",
            "away" to true,
            "away_message" to "brb",
        ),
        "channel_created" to mapOf(
            "type" to "channel_created",
            "channel_id" to 4L,
            "parent_id" to 0L,
            "name" to "c",
        ),
        "channel_renamed" to mapOf(
            "type" to "channel_renamed",
            "channel_id" to 4L,
            "name" to "c",
            "old_name" to "d",
        ),
        "channel_removed" to mapOf("type" to "channel_removed", "channel_id" to 4L),
        "text_message" to mapOf(
            "type" to "text_message",
            "target" to "channel",
            "from_id" to 2,
            "from_name" to "a",
            "from_uid" to "u",
            "message" to "m",
            "to_id" to 0,
            "from_self" to false,
            "timestamp" to 1_700_000_000L,
            "timestamp_ms" to 1_700_000_000_000L,
        ),
        "poked" to mapOf(
            "type" to "poked",
            "from_id" to 2,
            "from_name" to "a",
            "message" to "m",
        ),
        "file_list" to mapOf("type" to "file_list", "channel_id" to 4L, "count" to 3),
        "transfer_started" to mapOf(
            "type" to "transfer_started",
            "name" to "f",
            "direction" to "download",
            "total" to 10L,
        ),
        "transfer_progress" to mapOf(
            "type" to "transfer_progress",
            "name" to "f",
            "direction" to "download",
            "done" to 5L,
            "total" to 10L,
        ),
        "transfer_done" to mapOf(
            "type" to "transfer_done",
            "name" to "f",
            "direction" to "download",
            "local" to "/tmp/f",
            "total" to 10L,
        ),
        "transfer_error" to mapOf("type" to "transfer_error", "name" to "f", "message" to "m"),
        "account_status" to mapOf(
            "type" to "account_status",
            "command" to "account_login",
            "ok" to true,
            "message" to "",
            "kind" to "",
        ),
        "user_priority_speaker_changed" to mapOf(
            "type" to "user_priority_speaker_changed",
            "client_id" to 1,
            "nickname" to "v",
            "priority_speaker" to true,
        ),
        "user_unread_messages_changed" to mapOf(
            "type" to "user_unread_messages_changed",
            "client_id" to 1,
            "nickname" to "v",
            "unread_messages" to 1L,
        ),
        "user_updated" to mapOf(
            "type" to "user_updated",
            "client_id" to 1,
            "nickname" to "v",
            "changed" to "v",
        ),
        "user_groups_changed" to mapOf(
            "type" to "user_groups_changed",
            "client_id" to 1,
            "nickname" to "v",
            "channel_group" to 1L,
            "server_groups" to "v",
        ),
        "server_query_login_password" to mapOf(
            "type" to "server_query_login_password",
            "password" to "v",
        ),
        "chat_composing" to mapOf(
            "type" to "chat_composing",
            "client_id" to 1,
            "uid" to "v",
        ),
        "file_list_finished" to mapOf(
            "type" to "file_list_finished",
            "channel_id" to 1L,
            "path" to "v",
        ),
        "missing_permission" to mapOf(
            "type" to "missing_permission",
            "permission_id" to 1L,
            "value" to 1,
        ),
        "channel_changed" to mapOf(
            "type" to "channel_changed",
            "channel_id" to 1L,
        ),
        "channel_password_changed" to mapOf(
            "type" to "channel_password_changed",
            "channel_id" to 1L,
        ),
        "plugin_command" to mapOf(
            "type" to "plugin_command",
            "name" to "v",
            "data" to "v",
            "invoker_id" to 1,
            "invoker_name" to "v",
            "invoker_uid" to "v",
        ),
        "channel_created_id" to mapOf(
            "type" to "channel_created_id",
            "channel_id" to 1L,
        ),
        "server_group_created" to mapOf(
            "type" to "server_group_created",
            "group_id" to 1L,
        ),
        "group_created" to mapOf(
            "type" to "group_created",
            "group_kind" to "v",
            "group_id" to 1L,
            "name" to "v",
        ),
        "group_updated" to mapOf(
            "type" to "group_updated",
            "group_kind" to "v",
            "group_id" to 1L,
            "name" to "v",
            "changed" to "v",
        ),
        "group_removed" to mapOf(
            "type" to "group_removed",
            "group_kind" to "v",
            "group_id" to 1L,
            "name" to "v",
        ),
        "server_updated" to mapOf(
            "type" to "server_updated",
            "changed" to "v",
            "name" to "v",
            "welcome_message" to "v",
        ),
        "channel_updated" to mapOf(
            "type" to "channel_updated",
            "channel_id" to 1L,
            "changed" to "v",
        ),
        "icon_ready" to mapOf(
            "type" to "icon_ready",
            "icon_id" to 1L,
            "path" to "v",
            "format" to "v",
            "size" to 1L,
        ),
        "icon_failed" to mapOf(
            "type" to "icon_failed",
            "icon_id" to 1L,
            "message" to "v",
        ),
        "ban_list_count" to mapOf("type" to "ban_list_count", "count" to 1L),
        "ban_listed" to mapOf(
            "type" to "ban_listed",
            "ban_id" to 1L,
            "ip" to "v",
            "name" to "v",
            "uid" to "v",
            "last_nickname" to "v",
            "created" to 1L,
            "duration" to 1L,
            "invoker_name" to "v",
            "reason" to "v",
        ),
        "ban_list_done" to mapOf("type" to "ban_list_done"),
        "complaint_listed" to mapOf(
            "type" to "complaint_listed",
            "target_db_id" to 1L,
            "target_name" to "v",
            "from_db_id" to 1L,
            "from_name" to "v",
            "message" to "v",
            "timestamp" to 1L,
        ),
        "complaint_list_done" to mapOf("type" to "complaint_list_done"),
        "offline_message_listed" to mapOf(
            "type" to "offline_message_listed",
            "message_id" to 1L,
            "uid" to "v",
            "subject" to "v",
            "timestamp" to 1L,
            "read" to true,
        ),
        "offline_message_list_done" to mapOf("type" to "offline_message_list_done"),
        "offline_message" to mapOf(
            "type" to "offline_message",
            "message_id" to 1L,
            "uid" to "v",
            "subject" to "v",
            "message" to "v",
            "timestamp" to 1L,
        ),
        "client_permission" to mapOf(
            "type" to "client_permission",
            "client_db_id" to 1L,
            "name" to "v",
            "value" to 1,
            "negated" to true,
            "skip" to true,
        ),
        "client_permission_list_done" to mapOf("type" to "client_permission_list_done"),
        "channel_permission" to mapOf(
            "type" to "channel_permission",
            "channel_id" to 1L,
            "name" to "v",
            "value" to 1,
            "negated" to true,
            "skip" to true,
        ),
        "channel_permission_list_done" to mapOf("type" to "channel_permission_list_done"),
        "temp_password_listed" to mapOf(
            "type" to "temp_password_listed",
            "nickname" to "v",
            "uid" to "v",
            "description" to "v",
            "password" to "v",
            "start" to 1L,
            "end" to 1L,
            "channel_id" to 1L,
        ),
        "temp_password_list_done" to mapOf("type" to "temp_password_list_done"),
        "client_db_list_count" to mapOf("type" to "client_db_list_count", "count" to 1L),
        "client_db_entry" to mapOf(
            "type" to "client_db_entry",
            "client_db_id" to 1L,
            "uid" to "v",
            "nickname" to "v",
            "created" to 1L,
            "last_connected" to 1L,
            "connections" to 1L,
            "description" to "v",
        ),
        "client_db_list_done" to mapOf("type" to "client_db_list_done"),
        "client_db_info" to mapOf(
            "type" to "client_db_info",
            "client_db_id" to 1L,
            "uid" to "v",
            "nickname" to "v",
            "client_id" to 1,
            "channel_id" to 1L,
            "description" to "v",
            "avatar_hash" to "v",
            "icon_id" to 1L,
            "created" to 1L,
        ),
        "client_db_found" to mapOf(
            "type" to "client_db_found",
            "client_db_id" to 1L,
            "uid" to "v",
            "nickname" to "v",
            "last_connected" to 1L,
            "connections" to 1L,
        ),
        "client_db_find_done" to mapOf("type" to "client_db_find_done"),
        "recording_started" to mapOf(
            "type" to "recording_started",
            "path" to "v",
        ),
        "recording_stopped" to mapOf(
            "type" to "recording_stopped",
            "path" to "v",
            "frames" to 1L,
            "seconds" to 1L,
            "failed" to true,
            "reason" to "v",
        ),
        "server_group_client" to mapOf(
            "type" to "server_group_client",
            "group_id" to 1L,
            "client_db_id" to 1L,
            "nickname" to "v",
            "uid" to "v",
        ),
        "server_group_client_list_done" to mapOf("type" to "server_group_client_list_done"),
        "group_permission" to mapOf(
            "type" to "group_permission",
            "group_kind" to "v",
            "group_id" to 1L,
            "name" to "v",
            "value" to 1,
            "negated" to true,
            "skip" to true,
        ),
        "group_permission_list_done" to mapOf(
            "type" to "group_permission_list_done",
            "group_kind" to "v",
        ),
        "channel_group_client" to mapOf(
            "type" to "channel_group_client",
            "channel_id" to 1L,
            "client_db_id" to 1L,
            "group_id" to 1L,
        ),
        "channel_group_client_list_done" to mapOf("type" to "channel_group_client_list_done"),
        "channel_client_permission" to mapOf(
            "type" to "channel_client_permission",
            "channel_id" to 1L,
            "client_db_id" to 1L,
            "name" to "v",
            "value" to 1,
            "negated" to true,
            "skip" to true,
        ),
        "channel_client_permission_list_done" to mapOf("type" to "channel_client_permission_list_done"),
        "client_server_group" to mapOf(
            "type" to "client_server_group",
            "client_db_id" to 1L,
            "group_id" to 1L,
            "name" to "v",
        ),
        "client_server_group_list_done" to mapOf("type" to "client_server_group_list_done"),
        "client_id_resolved" to mapOf(
            "type" to "client_id_resolved",
            "uid" to "v",
            "client_id" to 1,
            "nickname" to "v",
        ),
        "client_id_resolved_done" to mapOf("type" to "client_id_resolved_done"),
        "client_name_resolved" to mapOf(
            "type" to "client_name_resolved",
            "uid" to "v",
            "client_db_id" to 1L,
            "nickname" to "v",
        ),
        "client_name_resolved_done" to mapOf("type" to "client_name_resolved_done"),
        "client_uid_resolved" to mapOf(
            "type" to "client_uid_resolved",
            "uid" to "v",
            "client_id" to 1,
            "nickname" to "v",
        ),
        "client_uid_resolved_done" to mapOf("type" to "client_uid_resolved_done"),
        "client_connection_info" to mapOf(
            "type" to "client_connection_info",
            "client_id" to 1,
            "ping" to 1L,
            "ping_deviation" to 1L,
            "connected_time" to 1L,
            "ip" to "v",
            "port" to 1,
            "packets_sent" to 1L,
            "bytes_sent" to 1L,
            "packets_received" to 1L,
            "bytes_received" to 1L,
            "packet_loss" to 1,
        ),
        "server_connection_info" to mapOf(
            "type" to "server_connection_info",
            "packets_sent" to 1L,
            "bytes_sent" to 1L,
            "packets_received" to 1L,
            "bytes_received" to 1L,
            "filetransfer_bytes_sent" to 1L,
            "filetransfer_bytes_received" to 1L,
            "connected_time" to 1L,
            "ping" to 1L,
            "packet_loss" to 1,
        ),
        "file_info" to mapOf(
            "type" to "file_info",
            "channel_id" to 1L,
            "path" to "v",
            "name" to "v",
            "size" to 1L,
            "date_time" to 1L,
        ),
        "perm_found" to mapOf(
            "type" to "perm_found",
            "scope" to "v",
            "id1" to 1L,
            "id2" to 1L,
            "permission_id" to 1L,
        ),
        "perm_find_done" to mapOf("type" to "perm_find_done"),
        "perm_listed" to mapOf(
            "type" to "perm_listed",
            "group_id_end" to 1L,
            "permission_id" to 1L,
            "name" to "v",
            "description" to "v",
        ),
        "perm_list_done" to mapOf("type" to "perm_list_done"),
        "log_line" to mapOf(
            "type" to "log_line",
            "offset" to 1L,
            "file_size" to 1L,
            "line" to "v",
        ),
        "token_created" to mapOf(
            "type" to "token_created",
            "token" to "v",
        ),
        "token_listed" to mapOf(
            "type" to "token_listed",
            "token" to "v",
            "token_kind" to "v",
            "group_id" to 1L,
            "channel_id" to 1L,
            "created" to 1L,
            "description" to "v",
        ),
        "token_list_done" to mapOf("type" to "token_list_done"),
        "token_used" to mapOf(
            "type" to "token_used",
            "token" to "v",
            "custom_set" to "v",
            "group_id" to "v",
            "channel_id" to "v",
            "client_id" to 1,
            "client_db_id" to 1L,
            "uid" to "v",
        ),
        "client_info" to mapOf(
            "type" to "client_info",
            "client_db_id" to 1L,
            "uid" to "v",
            "nickname" to "v",
            "channel_id" to 1L,
            "platform" to "v",
            "version" to "v",
            "version_sign" to "v",
            "login_name" to "v",
            "ip" to "v",
            "country" to "v",
            "description" to "v",
            "avatar_hash" to "v",
            "icon_id" to 1L,
            "badges" to "v",
            "idle_time" to 1L,
            "connected_time" to 1L,
            "packets_sent" to 1L,
            "packets_received" to 1L,
            "bytes_sent" to 1L,
            "bytes_received" to 1L,
            "filetransfer_bytes_sent" to 1L,
            "filetransfer_bytes_received" to 1L,
            "client_type" to 1,
            "recording" to true,
            "away" to true,
            "away_message" to "v",
            "talk_power" to 1,
            "channel_group_id" to 1L,
        ),
        "channel_info" to mapOf(
            "type" to "channel_info",
            "parent_id" to 1L,
            "name" to "v",
            "topic" to "v",
            "description" to "v",
            "has_password" to true,
            "codec" to "v",
            "codec_quality" to 1,
            "max_clients" to 1,
            "max_family_clients" to 1,
            "order" to 1L,
            "is_permanent" to true,
            "is_default" to true,
            "needed_talk_power" to 1,
            "forced_silence" to true,
            "icon_id" to 1L,
            "delete_delay" to 1L,
        ),
        "channel_found" to mapOf(
            "type" to "channel_found",
            "channel_id" to 1L,
            "name" to "v",
        ),
        "channel_find_done" to mapOf("type" to "channel_find_done"),
        "client_listed" to mapOf(
            "type" to "client_listed",
            "client_id" to 1,
            "channel_id" to 1L,
            "client_db_id" to 1L,
            "nickname" to "v",
            "uid" to "v",
            "client_type" to 1,
            "away" to true,
            "away_message" to "v",
            "talking" to true,
            "input_muted" to true,
            "output_muted" to true,
            "recording" to true,
            "talk_power" to 1,
            "channel_group_id" to 1L,
            "platform" to "v",
            "version" to "v",
            "country" to "v",
        ),
        "client_list_done" to mapOf("type" to "client_list_done"),
        "virtual_server_listed" to mapOf(
            "type" to "virtual_server_listed",
            "server_id" to 1L,
            "port" to 1,
            "status" to "v",
            "clients_online" to 1,
            "query_clients_online" to 1,
            "max_clients" to 1,
            "uptime" to 1L,
            "autostart" to true,
            "machine_id" to "v",
            "name" to "v",
            "uid" to "v",
        ),
        "virtual_server_list_done" to mapOf("type" to "virtual_server_list_done"),
        "channel_edited" to mapOf(
            "type" to "channel_edited",
            "channel_id" to 1L,
            "description" to "v",
        ),
        "client_db_id_resolved" to mapOf(
            "type" to "client_db_id_resolved",
            "uid" to "v",
            "client_db_id" to 1L,
        ),
        "client_db_id_resolved_done" to mapOf("type" to "client_db_id_resolved_done"),
        "client_found" to mapOf(
            "type" to "client_found",
            "client_id" to 1,
            "nickname" to "v",
        ),
        "client_find_done" to mapOf("type" to "client_find_done"),
        "perm_value" to mapOf(
            "type" to "perm_value",
            "permission_id" to 1L,
            "name" to "v",
            "value" to 1,
        ),
        "server_info" to mapOf(
            "type" to "server_info",
            "uid" to "v",
            "name" to "v",
            "welcome_message" to "v",
            "platform" to "v",
            "version" to "v",
            "max_clients" to 1,
            "clients_online" to 1,
            "channels_online" to 1L,
            "created" to 1L,
            "uptime" to 1L,
            "port" to 1,
            "has_password" to true,
            "autostart" to true,
            "weblist_enabled" to true,
            "ask_for_privilegekey" to true,
            "hostmessage" to "v",
            "hostmessage_mode" to 1,
            "hostbanner_url" to "v",
            "hostbanner_gfx_url" to "v",
            "hostbutton_tooltip" to "v",
            "hostbutton_url" to "v",
            "hostbutton_gfx_url" to "v",
            "machine_id" to "v",
            "nickname" to "v",
            "phonetic_name" to "v",
            "icon_id" to 1L,
            "reserved_slots" to 1,
            "download_quota" to 1L,
            "upload_quota" to 1L,
            "bytes_downloaded_month" to 1L,
            "bytes_uploaded_month" to 1L,
            "bytes_downloaded_total" to 1L,
            "bytes_uploaded_total" to 1L,
            "packet_loss" to 1,
            "ping" to 1L,
            "query_clients_online" to 1L,
            "connection_count_total" to 1L,
            "query_count_total" to 1L,
            "needed_identity_security_level" to 1,
            "priority_speaker_dimm_modificator" to 1,
            "status" to "v",
            "default_server_group" to 1L,
            "default_channel_group" to 1L,
            "default_channel_admin_group" to 1L,
            "max_download_bandwidth_total" to 1L,
            "max_upload_bandwidth_total" to 1L,
            "temp_channel_default_delete_delay" to 1L,
            "log_client" to true,
            "log_query" to true,
            "log_channel" to true,
            "log_permissions" to true,
            "log_server" to true,
            "log_filetransfer" to true,
        ),
        "server_snapshot" to mapOf(
            "type" to "server_snapshot",
            "format_version" to 1,
            "salt" to "v",
            "snapshot" to "v",
            "size" to 1L,
        ),
        "custom_entry" to mapOf(
            "type" to "custom_entry",
            "client_db_id" to 1L,
            "ident" to "v",
            "value" to "v",
        ),
        "custom_info_done" to mapOf("type" to "custom_info_done"),
        "custom_search_done" to mapOf("type" to "custom_search_done"),
        "who_am_i" to mapOf(
            "type" to "who_am_i",
            "client_id" to 1,
            "channel_id" to 1L,
            "nickname" to "v",
            "client_db_id" to 1L,
            "login_name" to "v",
            "uid" to "v",
            "server_id" to 1L,
            "origin_server_id" to 1L,
            "server_uid" to "v",
            "port" to 1,
            "server_status" to "v",
        ),
        "chat_closed" to mapOf(
            "type" to "chat_closed",
            "client_id" to 1,
            "uid" to "v",
        ),
        "filetransfer" to mapOf(
            "type" to "filetransfer",
            "client_id" to 1,
            "path" to "v",
            "name" to "v",
            "size" to 1L,
            "size_done" to 1L,
            "server_id" to 1,
            "client_id_transfer" to 1,
            "sender" to "v",
            "status" to 1,
            "speed" to 1,
            "runtime" to 1L,
        ),
        "filetransfer_list_done" to mapOf("type" to "filetransfer_list_done"),
    )

    private companion object {
        val EVENT_NAME = Regex("[a-z][a-z0-9_]*")

        /** Matches `override val type = "user_joined"` in `ServerEvent.kt`. */
        val LITERAL_TYPE = Regex("override val type = \"([a-z][a-z0-9_]*)\"")

        /** Built once: `Json {}` instances are not free. */
        val LENIENT_JSON = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    }
}
