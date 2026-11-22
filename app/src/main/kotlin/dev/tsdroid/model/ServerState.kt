package dev.tsdroid.model

import kotlinx.serialization.SerialName
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
    /**
     * `false` when this client switched its capture/playback device off in its
     * own settings. That is a different flag from [inputMuted]: a hardware-muted
     * microphone sends no audio while `inputMuted` stays `false`, which is why
     * the member list used to draw such a person as open-mic. Both default to
     * `true` so an older snapshot without them reads as "device on".
     */
    val inputHardwareEnabled: Boolean = true,
    val outputHardwareEnabled: Boolean = true,
    val away: Boolean = false,
    val awayMessage: String = "",
    /** Computed by the core from the capture path; covers this client only. */
    val ownTalking: Boolean = false,
    val users: List<User> = emptyList(),
    val channels: List<Channel> = emptyList(),
    /**
     * The display order computed by the native core. Empty only with a library
     * from before the field existed; [channelTree] then falls back to sorting
     * here.
     */
    @SerialName("channelTree") val tree: List<ChannelTreeEntry> = emptyList(),
    val files: List<FileEntry> = emptyList(),
    val server: Server = Server(),
    val connected: Boolean = false,
    /**
     * True once `nativeIdentity` returns a value. The key itself is not in the
     * snapshot (it ends up in logs); this flag is the signal to read it once.
     */
    val identityReady: Boolean = false,
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
     * Channels in tree order: roots first, each followed by its descendants.
     *
     * The core already produces the display order ([tree]); it is used as given.
     * The fallback below walks the server's own arrangement, which is a linked
     * list, not a sort index: `order` is the id of the channel this one is
     * placed after, and 0 means "first". It exists only for a native library
     * from before the snapshot carried the tree.
     */
    fun channelTree(): List<ChannelNode> {
        if (tree.isNotEmpty()) {
            val byId = channels.associateBy { it.id }
            return tree.mapNotNull { entry -> byId[entry.id]?.let { ChannelNode(it, entry.depth) } }
        }
        val byParent = channels.groupBy { it.parentId }

        fun ordered(siblings: List<Channel>): List<Channel> {
            if (siblings.size <= 1) return siblings
            val after = HashMap<Long, Channel>(siblings.size)
            for (channel in siblings) after[channel.order] = channel

            val result = ArrayList<Channel>(siblings.size)
            val seen = HashSet<Long>(siblings.size)
            var current = after[0L]
            while (current != null && seen.add(current.id)) {
                result += current
                current = after[current.id]
            }
            // Duplicate or dangling order values leave channels outside the
            // chain; showing them at the end beats hiding them.
            for (channel in siblings) if (channel.id !in seen) result += channel
            return result
        }

        fun descendants(parent: Channel, depth: Int): List<ChannelNode> =
            ordered(byParent[parent.id].orEmpty()).flatMap { child ->
                listOf(ChannelNode(child, depth + 1)) + descendants(child, depth + 1)
            }

        val roots = ordered(byParent[0L].orEmpty())
        // Orphans (a parent we never received) would otherwise vanish entirely.
        // Checking `byParent` is not enough: the missing parent's children are
        // themselves a group under that key.
        val known = channels.mapTo(HashSet(channels.size)) { it.id }
        val orphaned = channels.filter { it.parentId != 0L && it.parentId !in known }

        return (roots + ordered(orphaned)).flatMap { root ->
            listOf(ChannelNode(root, 0)) + descendants(root, 0)
        }
    }
}

data class ChannelNode(val channel: Channel, val depth: Int)

/** One snapshot `channelTree` entry: a channel id plus its nesting depth. */
@Serializable
data class ChannelTreeEntry(val id: Long = 0, val depth: Int = 0)

@Serializable
data class User(
    val id: Int = 0,
    val uid: String = "",
    val nickname: String = "",
    val channelId: Long = 0,
    val inputMuted: Boolean = false,
    val outputMuted: Boolean = false,
    /** See [ServerState.inputHardwareEnabled]. Defaults to `true`. */
    val inputHardwareEnabled: Boolean = true,
    val outputHardwareEnabled: Boolean = true,
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
