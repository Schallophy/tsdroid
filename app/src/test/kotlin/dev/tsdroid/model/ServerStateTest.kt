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
}
