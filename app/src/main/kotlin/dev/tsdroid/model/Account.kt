package dev.tsdroid.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The `account` object of a native snapshot.
 *
 * The account side runs on the handle rather than on the connection, so this
 * object is present even when nobody is signed in and no server is joined.
 * Field names are the core's `camelCase` contract (`docs/events.md` there).
 */
@Serializable
data class AccountState(
    val loggedIn: Boolean = false,
    val email: String = "",
    val username: String = "",
    val uuid: String = "",
    val unwrappedWith: String = "",
    /** Length of the unwrapped account key; the key itself never leaves the core. */
    val keyBytes: Int = 0,
    val busy: Boolean = false,
    /** The last accepted command name, e.g. `account_login`. */
    val command: String = "",
    /** Empty when that command succeeded. */
    val lastError: String = "",
    /**
     * Machine-readable category of [lastError], empty exactly when it is.
     *
     * The connection side has carried a `kind` beside its message since the
     * first version; this is the account side's, so a non-English UI can
     * translate the failure or offer the next step instead of printing English.
     * See `ServerEvent.AccountStatus.kind` for the values.
     */
    val lastErrorKind: String = "",
    val lastBackup: String = "",
    /** In-flight text of a multi-step command (`3/25 restored`), not an error. */
    val progress: String = "",
    val status: Int = 0,
    val statusName: String = "",
    val globalVersion: String = "",
    val itemCount: Int = 0,
    val items: List<AccountItem> = emptyList(),
    val hierarchy: AccountHierarchy = AccountHierarchy(),
    /** The myTeamSpeak ID in base64; empty means servers see no badge/name. */
    val mytsId: String = "",
    val mytsPublicKey: String = "",
    /**
     * The `IDENTITY` items the account stores. Their `value` is the
     * `counterVkey` a returning install must reuse, because server groups and
     * admin rights are bound to the UID it produces.
     */
    val identities: List<AccountIdentity> = emptyList(),
    /**
     * The identity `identity_pick` chose for the address it was asked about.
     * `value` is empty when the account is ambiguous, which the UI explains
     * instead of making the user choose between two keys.
     */
    val pickedIdentity: AccountIdentity? = null,
) {
    val hasMytsId: Boolean get() = mytsId.isNotEmpty()

    fun item(uuid: String): AccountItem? = items.firstOrNull { it.uuid == uuid }

    /**
     * Bookmarks in the order the account stores them, folders included.
     *
     * The core's `hierarchy` is a flat pre-order walk, which is the only way to
     * recover the order: `sortOrder` is a link to the previous sibling, not a
     * rank. A library from before the field existed falls back to name order.
     */
    fun bookmarkRows(): List<AccountRow> {
        if (hierarchy.entries.isEmpty()) {
            return items
                .filter { it.bookmark != null }
                .sortedBy { it.bookmark?.name.orEmpty().lowercase() }
                .map { AccountRow(item = it, depth = 0, folder = false) }
        }
        val byUuid = items.associateBy { it.uuid }
        return hierarchy.entries.mapNotNull { entry ->
            val item = byUuid[entry.uuid]
            when {
                entry.isFolder -> AccountRow(
                    item = item,
                    depth = entry.depth,
                    folder = true,
                    name = entry.name.ifEmpty { item?.folder?.name.orEmpty() },
                )
                item?.bookmark != null -> AccountRow(item = item, depth = entry.depth, folder = false)
                else -> null
            }
        }
    }
}

data class AccountRow(
    val item: AccountItem?,
    val depth: Int,
    val folder: Boolean,
    val name: String = "",
) {
    val uuid: String get() = item?.uuid.orEmpty()
    val bookmark: AccountBookmark? get() = item?.bookmark
}

@Serializable
data class AccountItem(
    val uuid: String = "",
    @SerialName("class") val itemClass: String = "",
    val classId: Int = 0,
    val itemVersion: String = "",
    val bytes: Int = 0,
    /** `plaintext` · `encrypted` · `locked` · `empty`. */
    val capability: String = "",
    val writable: Boolean = false,
    val deleted: Boolean = false,
    val plaintextBytes: Int = 0,
    val parent: String = "",
    val sortOrder: String = "",
    val folder: AccountFolder? = null,
    val bookmark: AccountBookmark? = null,
)

@Serializable
data class AccountFolder(
    val name: String = "",
    /** The class this folder groups, e.g. `BOOKMARK`. */
    val type: String? = null,
    val typeId: Int = 0,
)

@Serializable
data class AccountBookmark(
    val name: String = "",
    val address: String = "",
    val port: Int = 0,
    val nickname: String = "",
    val defaultChannel: String = "",
    val autoconnect: Boolean = false,
    val sendMytsid: Boolean = false,
    val hotkeyProfile: String = "",
    /**
     * The *name* of the identity this bookmark was created with, or empty.
     * Measured on a real settings.db: the official client writes `Default`
     * here, i.e. the IDENTITY item's own name, not a uuid.
     */
    val identityName: String = "",
    val hasServerPassword: Boolean = false,
    val hasChannelPassword: Boolean = false,
    /** Only present when the listing was fetched with `revealSecrets`. */
    val serverPassword: String? = null,
    val channelPassword: String? = null,
) {
    /**
     * `host:port` as the connect screen expects it, or the bare host for the
     * protocol default. A port of 0 is "unset", not "port zero".
     */
    val connectAddress: String
        get() = if (port in 1..65535 && port != DEFAULT_PORT) "$address:$port" else address

    private companion object {
        const val DEFAULT_PORT = 9987
    }
}

/**
 * The two password values of one bookmark.
 *
 * The account client reads them once after a login or refresh and keeps them
 * out of the polled snapshot; a bookmark click reads this cache synchronously.
 * They are also kept encrypted on disk (BookmarkSecretsCache) so a cold start
 * can connect from a bookmark before the first network read has landed.
 */
data class BookmarkSecrets(
    val serverPassword: String?,
    val channelPassword: String?,
)

@Serializable
data class AccountHierarchy(
    val entries: List<AccountEntry> = emptyList(),
    val unplaced: List<String> = emptyList(),
    val folders: Int = 0,
    val roots: Int = 0,
)

@Serializable
data class AccountEntry(
    val uuid: String = "",
    val parent: String = "",
    val depth: Int = 0,
    val name: String = "",
    val folderFor: String? = null,
    val isFolder: Boolean = false,
)

@Serializable
data class AccountIdentity(
    val uuid: String = "",
    val name: String = "",
    val nickname: String = "",
    val default: Boolean = false,
    /** The `counterVkey` string itself. */
    val value: String = "",
)
