package dev.tsdroid.data

import android.content.Context
import dev.tsdroid.model.BookmarkSecrets
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The passwords of cloud bookmarks, kept on the device so a bookmark works
 * before the account's first read has landed.
 *
 * The account client reads these from the account once per login, over the
 * network. That is fine after a minute's use, but a cold start draws the
 * bookmark rows from the local cache at the first frame — and a tap in that
 * window used to arrive before the passwords had been read, so a password-
 * protected bookmark failed with an auth error.
 *
 * Ciphertext, like [ServerStore]'s passwords: the same Keystore key, and the
 * file is dropped on logout so the next person to use the phone cannot read the
 * previous account's server passwords.
 */
class BookmarkSecretsCache(context: Context) {

    private val prefs = context.getSharedPreferences("bookmark-secrets", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Secret(
        val server: String? = null,
        val channel: String? = null,
    )

    @Serializable
    private data class Snapshot(val entries: Map<String, Secret> = emptyMap())

    /** Empty when nothing was stored, or when the ciphertext no longer opens. */
    fun load(): Map<String, BookmarkSecrets> {
        val blob = prefs.getString(KEY, null) ?: return emptyMap()
        val plain = SecureStore.decrypt(blob) ?: run {
            // A restored backup carries the blob but not the hardware key.
            clear()
            null
        } ?: return emptyMap()
        return runCatching {
            json.decodeFromString(Snapshot.serializer(), plain)
        }.getOrDefault(Snapshot()).entries.mapValues { (_, secret) ->
            BookmarkSecrets(serverPassword = secret.server, channelPassword = secret.channel)
        }
    }

    /**
     * Replaces the whole map with what the account just reported.
     *
     * A whole write rather than per-item patches: the account's list is the
     * truth, so a bookmark deleted on another device drops its password here
     * too. Nothing to store means the file is removed, not left holding an empty
     * object.
     */
    fun save(secrets: Map<String, BookmarkSecrets>) {
        if (secrets.isEmpty()) {
            clear()
            return
        }
        val encoded = secrets.mapValues { (_, secret) ->
            Secret(server = secret.serverPassword, channel = secret.channelPassword)
        }
        runCatching {
            val plain = json.encodeToString(Snapshot.serializer(), Snapshot(encoded))
            val blob = SecureStore.encrypt(plain)
            if (blob == null) {
                // Without the keystore the values cannot be stored protected;
                // leaving an older blob behind would mean the file no longer
                // matches what the account reported.
                clear()
            } else {
                prefs.edit().putString(KEY, blob).apply()
            }
        }
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY = "secrets"
    }
}
