package dev.tsdroid.data

import dev.tsdroid.core.ServerConfig

/**
 * Stamps the settings that are global, but travel inside a connection.
 *
 * Both fields here are the same kind of thing: a preference of this device that
 * the protocol only accepts in `clientinit`, alongside the address and nickname.
 * They are applied in one place so the three connect paths cannot disagree —
 * which is exactly how they drifted apart before: two of them read the version
 * setting themselves and the third reused a stale config.
 *
 * - The version pair is repaired rather than trusted; see
 *   [Settings.announceableVersion].
 * - The starting mute is declared here, not corrected with a later
 *   `clientupdate`: the server records it from the first packet, and a
 *   follow-up update of only that flag does not come back in our own client
 *   state (the member list kept showing an open microphone while audio was
 *   already muted).
 */
fun Settings.withAnnouncedVersion(config: ServerConfig): ServerConfig {
    val (version, platform) = announceableVersion()
    return config.copy(
        version = version,
        platform = platform,
        startMuted = startMuted,
    )
}
