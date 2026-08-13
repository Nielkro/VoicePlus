package ru.penik.voice.plus.core

import java.util.UUID

/**
 * Protocol-agnostic voice payload that crosses the port between an inbound and an
 * outbound gateway. Carries ONLY audio + metadata — never control-plane data
 * (handshake, secrets, source-mapping). Crypto is protocol-specific and lives
 * inside the adapters, so [opus] is always plaintext opus at this layer.
 *
 * - mic path (inbound -> outbound): [uuid] = local player, [seq] assigned by [VoiceHub],
 *   [distance] unused (0), [whisper] = whispering flag.
 * - audio path (outbound -> inbound): [uuid] = resolved source player, [seq] from PV,
 *   [distance] from PV, [whisper] = false.
 */
data class UniversalVoicePacket(
    val uuid: UUID,
    val opus: ByteArray,
    val distance: Short,
    val whisper: Boolean,
    val seq: Long
)
