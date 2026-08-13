package ru.penik.voice.plus.core

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

/**
 * Connects one [InboundGateway] with one [OutboundGateway].
 *
 *  - mic audio from inbound  -> (assign sequence number) -> outbound
 *  - audio from outbound     -> (periodic state re-inject) -> inbound
 *
 * Owns the protocol-agnostic routing state: per-player outgoing sequence numbers
 * and the incoming-packet counter that drives periodic re-injection of player
 * states (SVC wipes them on reconnects).
 */
class VoiceHub(
    private val inbound: InboundGateway,
    private val outbound: OutboundGateway
) {
    private val LOGGER = LoggerFactory.getLogger("VoicePlus-VoiceHub")

    // Outgoing (mic) sequence numbers, per local player.
    private val sendSequenceNumbers = ConcurrentHashMap<UUID, Long>()
    private var serverPacketsReceived = 0

    fun wire() {
        // mic (SVC client -> us -> PV server)
        inbound.onMicAudio { pkt ->
            val nextSeq = sendSequenceNumbers.compute(pkt.uuid) { _, v -> (v ?: 0L) + 1 } ?: 1L
            outbound.sendMicAudio(pkt.copy(seq = nextSeq))
        }

        // audio (PV server -> us -> SVC client)
        outbound.onIncomingAudio { pkt ->
            serverPacketsReceived++
            if (serverPacketsReceived % 50 == 1) {
                // Re-assert SVC player states periodically; SVC wipes them on reconnects.
                inbound.reinjectKnownPlayers()
            }
            inbound.deliverToClient(pkt)
        }
    }
}
