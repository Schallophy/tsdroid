package dev.tsdroid.data

import dev.tsdroid.core.ServerConfig

/**
 * Stamps the announced client version onto a connection.
 *
 * The version is a global setting, not a property of one server, but it has to
 * travel inside [ServerConfig] because it is sent once in `clientinit` along
 * with the address and nickname. Every connect path therefore has the same
 * choreography — read the setting, make sure the pair is announceable, copy it
 * in — and repeating that at each call site is how the three of them drifted
 * apart in the first place.
 *
 * The pair is repaired rather than trusted: see [Settings.announceableVersion].
 */
fun Settings.withAnnouncedVersion(config: ServerConfig): ServerConfig {
    val (version, platform) = announceableVersion()
    return config.copy(version = version, platform = platform)
}
