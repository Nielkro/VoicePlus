package ru.penik.voice.plus.core

/**
 * Port for the "we pretend to be a server" side (local client faces us).
 * Receives mic audio from the local client, delivers remote audio back to it.
 */
interface InboundGateway {
    /** Register the callback invoked when the local client sends mic audio. */
    fun onMicAudio(cb: (UniversalVoicePacket) -> Unit)

    /** Deliver a remote voice packet to the local client. */
    fun deliverToClient(pkt: UniversalVoicePacket)

    /**
     * Re-assert protocol-side player states. Called periodically by [VoiceHub]:
     * SVC wipes its state map on every "server change" disconnect, so states must
     * be re-injected whenever audio keeps arriving.
     */
    fun reinjectKnownPlayers()

    fun start()
    fun stop()
}

/**
 * Port for the "we are a client" side (real remote server faces us).
 * Sends mic audio to the remote server, surfaces incoming audio from it.
 *
 * Note: there is no explicit connect() — the remote (PV) handshake drives its own
 * UDP connect internally once the server sends its connection details.
 */
interface OutboundGateway {
    /** Send mic audio to the remote server. [UniversalVoicePacket.seq] is authoritative. */
    fun sendMicAudio(pkt: UniversalVoicePacket)

    /** Register the callback invoked when audio arrives from the remote server. */
    fun onIncomingAudio(cb: (UniversalVoicePacket) -> Unit)

    fun initialize()
    fun disconnect()
}
