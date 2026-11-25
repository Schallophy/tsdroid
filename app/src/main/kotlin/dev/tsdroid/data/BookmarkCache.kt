package dev.tsdroid.data

import android.content.Context
import dev.tsdroid.model.AccountHierarchy
import dev.tsdroid.model.AccountItem
import dev.tsdroid.model.AccountState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The last account state the UI was shown, kept so the next launch can draw the
 * bookmark list before the network answers.
 *
 * `account_resume` restores the account without a network call, but it restores
 * the *account* — session, key, identity. The bookmark list is server data and
 * only arrives on the first `sync_list`, which is why a launch showed an empty
 * list for a moment. This file is that list, so the rows are on screen at the
 * first frame and the background read simply replaces them.
 *
 * What is stored is what the UI renders: the items, the hierarchy that orders
 * them, and the account identity fields. No passwords: those live in
 * `ServerStore`, encrypted per address, and the cache is written from a snapshot
 * that never carries them.
 */
class BookmarkCache(context: Context) {

    private val file = java.io.File(context.filesDir, "bookmarks.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    private data class Snapshot(
        val loggedIn: Boolean = false,
        val email: String = "",
        val username: String = "",
        val uuid: String = "",
        val keyBytes: Int = 0,
        val mytsId: String = "",
        val mytsPublicKey: String = "",
        val items: List<AccountItem> = emptyList(),
        val hierarchy: AccountHierarchy = AccountHierarchy(),
        val identities: List<dev.tsdroid.model.AccountIdentity> = emptyList(),
    )

    /**
     * The cached state, or null when nothing usable was stored.
     *
     * A cache that no longer parses is dropped rather than kept: a schema change
     * should cost one launch's list, not every launch's.
     */
    fun load(): AccountState? {
        if (!file.isFile) return null
        val snapshot = runCatching {
            json.decodeFromString(Snapshot.serializer(), file.readText())
        }.getOrElse {
            runCatching { file.delete() }
            return null
        }
        if (!snapshot.loggedIn || snapshot.items.isEmpty()) return null
        return AccountState(
            loggedIn = true,
            email = snapshot.email,
            username = snapshot.username,
            uuid = snapshot.uuid,
            keyBytes = snapshot.keyBytes,
            mytsId = snapshot.mytsId,
            mytsPublicKey = snapshot.mytsPublicKey,
            items = snapshot.items,
            hierarchy = snapshot.hierarchy,
            identities = snapshot.identities,
            itemCount = snapshot.items.size,
        )
    }

    /**
     * Records the state to draw next launch.
     *
     * Only a signed-in state with items is worth keeping; writing an empty one
     * would make the next launch flash an empty list over a good cache.
     */
    fun save(state: AccountState) {
        if (!state.loggedIn || state.items.isEmpty()) return
        val snapshot = Snapshot(
            loggedIn = true,
            email = state.email,
            username = state.username,
            uuid = state.uuid,
            keyBytes = state.keyBytes,
            mytsId = state.mytsId,
            mytsPublicKey = state.mytsPublicKey,
            items = state.items,
            hierarchy = state.hierarchy,
            identities = state.identities,
        )
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(Snapshot.serializer(), snapshot))
        }
    }

    /** A logout must not leave the previous account's bookmarks on disk. */
    fun clear() {
        runCatching { file.delete() }
    }
}
