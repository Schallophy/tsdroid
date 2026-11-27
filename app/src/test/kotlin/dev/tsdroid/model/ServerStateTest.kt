package dev.tsdroid.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The channel tree follows the server's linked-list ordering.
 *
 * TeamSpeak's `channel_order` is the id of the channel a channel is placed
 * after (0 = first), not a sort index. Reading it as a number looks right on a
 * server nobody has reordered and produced a different tree everywhere else.
 */
class ServerStateTest {

    private fun channel(id: Long, parentId: Long = 0, order: Long, name: String = "#$id") =
        Channel(id = id, parentId = parentId, name = name, order = order)

    @Test
    fun siblingsFollowTheLinkedOrderNotTheNumericValue() {
        val state = ServerState(
            channels = listOf(
                channel(id = 1, order = 0, name = "A"),
                channel(id = 2, order = 4, name = "B"),
                channel(id = 3, order = 2, name = "C"),
                channel(id = 4, order = 1, name = "D"),
            ),
        )

        assertEquals(listOf("A", "D", "B", "C"), state.channelTree().map { it.channel.name })
    }

    @Test
    fun nestingIsKeptAndEachLevelUsesItsOwnChain() {
        val state = ServerState(
            channels = listOf(
                channel(id = 1, order = 0, name = "A"),
                channel(id = 2, order = 1, name = "B"),
                channel(id = 10, parentId = 1, order = 0, name = "A1"),
                channel(id = 11, parentId = 1, order = 10, name = "A2"),
                channel(id = 20, parentId = 2, order = 0, name = "B1"),
            ),
        )

        val tree = state.channelTree()
        assertEquals(listOf("A", "A1", "A2", "B", "B1"), tree.map { it.channel.name })
        assertEquals(listOf(0, 1, 1, 0, 1), tree.map { it.depth })
    }

    @Test
    fun theCoreTreeIsUsedAsGiven() {
        val state = ServerState(
            channels = listOf(
                channel(id = 1, order = 0, name = "A"),
                channel(id = 2, order = 0, name = "B"),
                channel(id = 3, order = 0, name = "C"),
            ),
            tree = listOf(
                ChannelTreeEntry(id = 3, depth = 0),
                ChannelTreeEntry(id = 1, depth = 1),
                ChannelTreeEntry(id = 2, depth = 0),
            ),
        )

        val tree = state.channelTree()
        assertEquals(listOf("C", "A", "B"), tree.map { it.channel.name })
        assertEquals(listOf(0, 1, 0), tree.map { it.depth })
    }

    @Test
    fun snapshotCarriesTheCoreTreeAndIdentityFlag() {
        val json = Json { ignoreUnknownKeys = true }
        val state = json.decodeFromString<ServerState>(
            """
            {
              "rev": 3,
              "identityReady": true,
              "channels": [
                { "id": 7, "parentId": 1, "name": "nested", "order": 0,
                  "permissionHints": 192 }
              ],
              "channelTree": [ { "id": 7, "depth": 2 } ]
            }
            """.trimIndent(),
        )

        assertTrue(state.identityReady)
        assertEquals(listOf(ChannelTreeEntry(id = 7, depth = 2)), state.tree)
        assertEquals(listOf("nested"), state.channelTree().map { it.channel.name })
        assertEquals(2, state.channelTree().first().depth)
        assertEquals(192L, state.channel(7)?.permissionHints)
    }

    @Test
    fun orphansAreStillShown() {
        val state = ServerState(
            channels = listOf(
                channel(id = 1, order = 0, name = "A"),
                channel(id = 9, parentId = 8, order = 0, name = "orphan"),
            ),
        )

        assertEquals(listOf("A", "orphan"), state.channelTree().map { it.channel.name })
    }

    @Test
    fun snapshotCarriesThePrivilegeKeyRequest() {
        // The core reports `virtualserver_ask_for_privilegekey` as
        // `server.askPrivilegeKey`; the token dialog is driven by this flag, so a
        // silent rename on either side would make the dialog never appear (or
        // appear on every server). The default must be false: most servers never
        // ask.
        val json = Json { ignoreUnknownKeys = true }

        val asking = json.decodeFromString<ServerState>(
            """{ "server": { "name": "S", "askPrivilegeKey": true } }""",
        )
        assertTrue(asking.server.askPrivilegeKey)

        val quiet = json.decodeFromString<ServerState>(
            """{ "server": { "name": "S" } }""",
        )
        assertEquals(false, quiet.server.askPrivilegeKey)
    }

    /**
     * Titles are drawn channel group first, then server groups.
     *
     * This is the order the desktop client uses, and it is the only thing
     * deciding what a row looks like: the ids alone say nothing about which one
     * belongs where.
     */
    @Test
    fun titlesRunFromChannelGroupToServerGroups() {
        val state = ServerState(
            channelGroups = listOf(GroupEntry(id = 5, name = "Voice")),
            serverGroups = listOf(
                GroupEntry(id = 6, name = "Server Admin"),
                GroupEntry(id = 8, name = "Guest"),
            ),
        )
        val member = User(channelGroup = 5, serverGroups = listOf(6, 8))

        assertEquals(
            listOf("Voice", "Server Admin", "Guest"),
            state.titlesFor(member).map { it.name },
        )
    }

    /**
     * A title with no name behind it is dropped, not drawn as a number.
     *
     * The group list arrives separately from the client list and an account may
     * not be allowed to see it at all, so an id without a name is ordinary. A
     * row showing `5` would be worse than a row showing nothing.
     */
    @Test
    fun anUnresolvedTitleIsDroppedRatherThanDrawnAsAnId() {
        val state = ServerState(
            serverGroups = listOf(GroupEntry(id = 8, name = "Guest")),
        )
        val member = User(channelGroup = 5, serverGroups = listOf(6, 8))

        assertEquals(listOf("Guest"), state.titlesFor(member).map { it.name })
    }

    /**
     * A member with no groups gets no titles at all, so the row has no gap.
     *
     * `channelGroup = 0` is what the server reports for a client whose group is
     * not set; it must not resolve to a title or leave a placeholder behind.
     */
    @Test
    fun aMemberWithoutGroupsGetsNoTitles() {
        val state = ServerState(
            channelGroups = listOf(GroupEntry(id = 5, name = "Voice")),
            serverGroups = listOf(GroupEntry(id = 8, name = "Guest")),
        )

        assertEquals(emptyList<String>(), state.titlesFor(User()).map { it.name })
    }

    /**
     * A template group is never drawn, even when its id somehow matches.
     *
     * `servergrouplist` returns template groups under the same names as the
     * regular groups they are blueprints for. No client is ever a member, so a
     * match means the lookup found the wrong entry — drawing it would label a
     * member with a group that does not exist for them.
     */
    @Test
    fun aTemplateGroupIsNeverDrawn() {
        val state = ServerState(
            channelGroups = listOf(
                GroupEntry(id = 1, name = "Channel Admin", groupType = GroupEntry.TEMPLATE),
                GroupEntry(id = 5, name = "Channel Admin"),
            ),
            serverGroups = listOf(
                GroupEntry(id = 3, name = "Server Admin", groupType = GroupEntry.TEMPLATE),
                GroupEntry(id = 6, name = "Server Admin"),
            ),
        )

        // The regular ids resolve; the template ids resolve to nothing.
        assertEquals(
            listOf("Channel Admin", "Server Admin"),
            state.titlesFor(User(channelGroup = 5, serverGroups = listOf(6))).map { it.name },
        )
        assertEquals(
            emptyList<String>(),
            state.titlesFor(User(channelGroup = 1, serverGroups = listOf(3))).map { it.name },
        )
    }

    /**
     * The group icon id comes through so the row can draw the group's mark.
     *
     * A stock server sets `iconid=300` on Server Admin and `iconid=0` on Guest;
     * the row keys its bundled pictures off this value, so a dropped field would
     * silently draw every group as text.
     */
    @Test
    fun theGroupIconIdSurvivesTheSnapshot() {
        val json = Json { ignoreUnknownKeys = true }
        val state = json.decodeFromString<ServerState>(
            """
            {
              "serverGroups": [
                { "id": 6, "name": "Server Admin", "groupType": "regular", "iconId": 300 },
                { "id": 8, "name": "Guest", "groupType": "regular", "iconId": 0 }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(300L, state.serverGroups.first { it.id == 6L }.iconId)
        assertEquals(0L, state.serverGroups.first { it.id == 8L }.iconId)
    }

    /**
     * The group tables and the member's channel group arrive under the names the
     * native snapshot uses.
     *
     * Written as JSON rather than constructed directly because the failure this
     * guards against is a rename on the Rust side: a field Kotlin cannot find
     * decodes to its default, so the row would quietly lose its titles with no
     * error anywhere.
     */
    @Test
    fun theGroupTablesDecodeFromTheNativeSnapshot() {
        val json = Json { ignoreUnknownKeys = true }
        val state = json.decodeFromString<ServerState>(
            """
            {
              "users": [ { "id": 2, "nickname": "ann", "channelGroup": 5, "serverGroups": [6] } ],
              "serverGroups": [ { "id": 6, "name": "Server Admin" } ],
              "channelGroups": [ { "id": 5, "name": "Voice" } ]
            }
            """.trimIndent(),
        )

        assertEquals(5L, state.users.single().channelGroup)
        assertEquals(listOf(6L), state.users.single().serverGroups)
        assertEquals(
            listOf("Voice", "Server Admin"),
            state.titlesFor(state.users.single()).map { it.name },
        )
    }
}
