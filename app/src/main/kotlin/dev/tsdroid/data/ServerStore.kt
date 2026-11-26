package dev.tsdroid.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class SavedServer(
    val address: String,
    val nickname: String,
    val lastUsed: Long = 0L,
)

/**
 * Local persistence: the server list, the identity file, and the passwords for
 * known servers (encrypted — see [SecureStore]).
 *
 * Plain SharedPreferences rather than DataStore — a few short values read at
 * connect time do not justify a flow-based store.
 */
class ServerStore(context: Context) {

    private val prefs = context.getSharedPreferences("servers", Context.MODE_PRIVATE)

    /**
     * Passwords live in their own file, separate from the server list, because
     * the two have different exposure: the list is readable JSON, this one is
     * keyed ciphertext. Keeping them apart means the list can be inspected or
     * exported later without dragging secrets along.
     */
    private val credentials = context.getSharedPreferences("credentials", Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(SavedServer.serializer())
    private val identityFile = File(context.filesDir, "identity.ini")

    fun load(): List<SavedServer> {
        val raw = prefs.getString(KEY_SERVERS, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString(listSerializer, raw)
        }.getOrDefault(emptyList()).sortedByDescending { it.lastUsed }
    }

    fun upsert(entry: SavedServer) {
        val next = load().filterNot { it.address == entry.address } + entry
        write(next.sortedByDescending { it.lastUsed }.take(MAX_SERVERS))
    }

    fun remove(address: String) {
        write(load().filterNot { it.address == address })
        // Forgetting the server must forget its password too, otherwise a
        // secret stays on disk for a host the user believes they removed.
        credentials.edit().remove(address).apply()
    }

    private fun write(entries: List<SavedServer>) {
        prefs.edit().putString(KEY_SERVERS, json.encodeToString(listSerializer, entries)).apply()
    }

    /**
     * The identity is what makes a returning user keep their UID, so it is read
     * on every connect. Empty means "let the native side generate one".
     *
     * Trimmed because the file is written by hand as often as by the app, and a
     * trailing newline makes the identity string invalid — which shows up as
     * "invalid identity" on connect instead of a stale UID.
     */
    fun readIdentity(): String =
        runCatching { if (identityFile.exists()) identityFile.readText() else "" }
            .getOrDefault("")
            .trim()

    /**
     * Remembers the identity the native core actually used.
     *
     * Without this write nothing ever creates `identity.ini`, so every launch
     * generates a fresh key and the server sees a new UID — the user loses their
     * permissions, their contacts' view of them, and their server-side history.
     * Written only when the value changed, to keep it off the 20 ms path.
     */
    fun writeIdentity(identity: String) {
        if (identity.isBlank()) return
        runCatching {
            if (identityFile.exists() && identityFile.readText() == identity) return
            identityFile.parentFile?.mkdirs()
            identityFile.writeText(identity)
        }
    }

    /** Ciphertext that no longer decrypts is dropped rather than left to rot. */
    fun passwordFor(address: String): String? {
        val blob = credentials.getString(address, null) ?: return null
        val plain = SecureStore.decrypt(blob)
        if (plain == null) credentials.edit().remove(address).apply()
        return plain
    }

    /** An empty [password] forgets the entry, so clearing the field really clears it. */
    fun setPassword(address: String, password: String) {
        if (password.isEmpty()) {
            credentials.edit().remove(address).apply()
            return
        }
        SecureStore.encrypt(password)?.let { credentials.edit().putString(address, it).apply() }
    }

    /**
     * One field of the temporary-connect form's secret, encrypted like any other.
     *
     * The scratch form is not a saved server, so it does not get an address key;
     * a reserved prefix keeps its secrets apart from the per-address ones and
     * means clearing a server never touches the draft. Still ciphertext at rest —
     * the point of persisting it is convenience, not a weaker store.
     */
    fun draftSecret(name: String): String? = passwordFor(DRAFT_PREFIX + name)

    fun setDraftSecret(name: String, value: String) {
        setPassword(DRAFT_PREFIX + name, value)
    }

    private companion object {
        const val KEY_SERVERS = "list"
        const val MAX_SERVERS = 20
        const val DRAFT_PREFIX = "__draft__:"
    }
}
