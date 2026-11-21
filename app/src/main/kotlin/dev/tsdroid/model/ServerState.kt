package dev.tsdroid.model

import kotlinx.serialization.Serializable

/**
 * Mirror of the JSON that `nativeSnapshot` returns.
 *
 * The Rust side serializes with `#[serde(rename_all = "camelCase")]`, so these
 * names must match one-for-one. `rev` is bumped only when something the UI
 * renders changed, which is what lets the poll loop skip a parse per frame.
 */
@Serializable
data class ServerState(
    val rev: Long = 0,
    val ownClientId: Int? = null,
    val ownChannelId: Long? = null,
    val nickname: String = "",
    val inputMuted: Boolean = false,
    val outputMuted: Boolean = false,
    val away: Boolean = false,
    val awayMessage: String = "",
    val users: List<User> = emptyList(),
    val channels: List<Channel> = emptyList(),
    val files: List<FileEntry> = emptyList(),
    val server: Server = Server(),
    val connected: Boolean = false,
) {
    /** Voice clients only: server query connections are not people. */
    fun usersIn(channelId: Long): List<User> =
        users.filter { it.channelId == channelId && it.clientType == 0 }

    fun channel(channelId: Long?): Channel? = channels.firstOrNull { it.id == channelId }

    fun user(clientId: Int?): User? = users.firstOrNull { it.id == clientId }

    fun filesIn(channelId: Long): List<FileEntry> =
        files.filter { it.channelId == channelId }.sortedWith(
            compareByDescending<FileEntry> { !it.isFile }.thenBy { it.name.lowercase() },
        )

    /**
     * Channels in tree order: roots first, each followed by its descendants,
     * siblings sorted by the order the server assigned.
     */
    fun channelTree(): List<ChannelNode> {
        val byParent = channels.groupBy { it.parentId }

        fun descendants(parent: Channel, depth: Int): List<ChannelNode> =
            byParent[parent.id].orEmpty().sortedBy { it.order }.flatMap { child ->
                listOf(ChannelNode(child, depth + 1)) + descendants(child, depth + 1)
            }

        val roots = byParent[0L].orEmpty().sortedBy { it.order }
        // Orphans (a parent we never received) would otherwise vanish entirely.
        val orphaned = channels.filter { it.parentId != 0L && byParent[it.parentId] == null }
            .sortedBy { it.order }

        return (roots + orphaned).flatMap { root ->
            listOf(ChannelNode(root, 0)) + descendants(root, 0)
        }
    }
}

data class ChannelNode(val channel: Channel, val depth: Int)

@Serializable
data class User(
    val id: Int = 0,
    val uid: String = "",
    val nickname: String = "",
    val channelId: Long = 0,
    val inputMuted: Boolean = false,
    val outputMuted: Boolean = false,
    val clientType: Int = 0,
    val away: Boolean = false,
    val awayMessage: String = "",
    val description: String = "",
    val talking: Boolean = false,
)

@Serializable
data class Channel(
    val id: Long = 0,
    val parentId: Long = 0,
    val name: String = "",
    val order: Long = 0,
    val hasPassword: Boolean = false,
    val isDefault: Boolean = false,
    val topic: String = "",
    /** -1 unlimited, -2 inherited, >= 0 a limit. */
    val maxClients: Int = -1,
)

@Serializable
data class FileEntry(
    val channelId: Long = 0,
    val path: String = "",
    val name: String = "",
    val size: Long = 0,
    val isFile: Boolean = true,
    val dateTime: Long = 0,
)

@Serializable
data class Server(
    val name: String = "",
    val version: String = "",
    val platform: String = "",
    val clientsOnline: Int = 0,
    val maxClients: Int = 0,
    val welcomeMessage: String = "",
)
