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
                "timestamp" to 1_700_000_000L,
            ),
        ) as ServerEvent.TextMessage
        assertEquals(ChatScope.Private, message.scope)
        assertEquals(9, message.toId)
        assertEquals(1_700_000_000L, message.timestamp)

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
            "timestamp" to 1_700_000_000L,
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
    )

    private companion object {
        val EVENT_NAME = Regex("[a-z][a-z0-9_]*")

        /** Matches `override val type = "user_joined"` in `ServerEvent.kt`. */
        val LITERAL_TYPE = Regex("override val type = \"([a-z][a-z0-9_]*)\"")
    }
}
