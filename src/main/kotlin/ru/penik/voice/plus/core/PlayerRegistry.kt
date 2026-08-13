package ru.penik.voice.plus.core

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

/**
 * Shared, protocol-agnostic mapping state.
 *
 * Holds source<->player<->nick relationships and the cache of known players.
 * Protocol-specific ACTIONS (sending SourceInfoRequest for unknown sources,
 * reflecting SVC PlayerState into the client) stay in the adapters — this class
 * only owns the data and the lookups.
 */
object PlayerRegistry {
    private val LOGGER = LoggerFactory.getLogger("VoicePlus-PlayerRegistry")

    // PV source ID -> sender player UUID, and whether that source is stereo.
    private val sourceToPlayerMap = ConcurrentHashMap<UUID, UUID>()
    private val sourceToStereoMap = ConcurrentHashMap<UUID, Boolean>()

    // Sources we already asked the server about (dedup for SourceInfoRequest).
    private val requestedSources = ConcurrentHashMap.newKeySet<UUID>()

    // Known players (uuid -> nick). Used to re-inject SVC states after reconnects.
    val knownPlayers = ConcurrentHashMap<UUID, String>()

    fun registerSourceMapping(sourceId: UUID, playerUuid: UUID, stereo: Boolean) {
        LOGGER.info("Mapping PV sourceId $sourceId -> player UUID $playerUuid (stereo: $stereo)")
        sourceToPlayerMap[sourceId] = playerUuid
        sourceToStereoMap[sourceId] = stereo
    }

    /** Resolve a source to a player UUID; falls back to the source id itself. */
    fun resolvePlayer(sourceId: UUID): UUID = sourceToPlayerMap[sourceId] ?: sourceId

    fun isKnownSource(sourceId: UUID): Boolean = sourceToPlayerMap.containsKey(sourceId)

    /** Returns true the first time a given source is seen (for SourceInfoRequest dedup). */
    fun markRequested(sourceId: UUID): Boolean = requestedSources.add(sourceId)

    fun registerPlayer(uuid: UUID, nick: String) {
        knownPlayers[uuid] = nick
    }

    fun isKnownPlayer(uuid: UUID): Boolean = knownPlayers.containsKey(uuid)

    fun clear() {
        sourceToPlayerMap.clear()
        sourceToStereoMap.clear()
        requestedSources.clear()
        knownPlayers.clear()
    }
}
