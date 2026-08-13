package ru.penik.voice.plus.adapters.outbound

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.util.UUID
import org.slf4j.LoggerFactory
import com.google.common.io.ByteStreams
import io.netty.buffer.Unpooled
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.minecraft.client.Minecraft
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import ru.penik.voice.plus.core.OutboundGateway
import ru.penik.voice.plus.core.PlayerRegistry
import ru.penik.voice.plus.core.UniversalVoicePacket
import ru.penik.voice.plus.crypto.SvcEncryption

/**
 * Outbound side: we act as a Plasmo Voice CLIENT of the real server.
 *
 * Merges the former PlasmoVoiceTcpClient (RSA-2048 handshake, ConfigPacket AES-key
 * decode, ConnectionPacket -> UDP connect, source/player discovery) and
 * PlasmoVoiceUdpClient (DatagramSocket, ping loop, listen loop, SourceAudio decode).
 *
 * PV-specific state (proximity activation id, AES key) lives here — it is
 * control-plane, not part of [UniversalVoicePacket].
 */
class PvOutboundAdapter : OutboundGateway {
    private val LOGGER = LoggerFactory.getLogger("VoicePlus-PvOutbound")

    // --- callbacks bridging PV control-plane into the SVC (inbound) side ---
    /** Fired when the PV handshake yields a secret; inbound uses it to auth the local SVC client. */
    var onConnectionEstablished: ((playerUuid: UUID, svcSecretBytes: ByteArray) -> Unit)? = null
    /** Fired when a PV player is discovered; inbound injects an SVC PlayerState for it. */
    var onPlayerDiscovered: ((uuid: UUID, nick: String) -> Unit)? = null

    // --- PV-specific control-plane state (was in SvcPvTranslationBridge) ---
    private var proximityActivationId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000000")
    private var pvEncryptionKey: ByteArray? = null

    private var incomingCb: ((UniversalVoicePacket) -> Unit)? = null
    private var micPacketsSent = 0
    private var serverPacketsReceived = 0

    override fun onIncomingAudio(cb: (UniversalVoicePacket) -> Unit) { incomingCb = cb }

    // ============================ TCP client ============================
    private var keyPair: KeyPair? = null

    val PAYLOAD_TYPE = CustomPacketPayload.Type<VoicePlusPayload>(Identifier.fromNamespaceAndPath("plasmo", "voice/v2"))

    val STREAM_CODEC = object : StreamCodec<RegistryFriendlyByteBuf, VoicePlusPayload> {
        override fun encode(buf: RegistryFriendlyByteBuf, value: VoicePlusPayload) {
            buf.writeBytes(value.bytes)
        }
        override fun decode(buf: RegistryFriendlyByteBuf): VoicePlusPayload {
            val bytes = ByteArray(buf.readableBytes())
            buf.readBytes(bytes)
            return VoicePlusPayload(bytes)
        }
    }

    override fun initialize() {
        // Register payload type in Fabric 1.20.5+ / 1.21 Registry
        PayloadTypeRegistry.playS2C().register(PAYLOAD_TYPE, STREAM_CODEC)
        PayloadTypeRegistry.playC2S().register(PAYLOAD_TYPE, STREAM_CODEC)

        // Generate RSA KeyPair for Plasmo Voice authentication
        try {
            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(2048)
            keyPair = generator.generateKeyPair()
            LOGGER.info("Generated RSA KeyPair for Plasmo Voice authentication")
        } catch (e: Exception) {
            LOGGER.error("Failed to generate RSA KeyPair", e)
        }

        ClientPlayNetworking.registerGlobalReceiver(PAYLOAD_TYPE) { payload, context ->
            handleServerPacket(payload.bytes)
        }
    }
    private var currentSecret: UUID? = null

    private fun isLocalPlayer(uuid: UUID): Boolean {
        val minecraft = Minecraft.getInstance()
        // Prefer the in-world local player entity; fall back to the session profile id.
        val localUuid = minecraft.player?.uuid ?: minecraft.user?.profileId
        return localUuid != null && localUuid == uuid
    }

    private fun handleServerPacket(bytes: ByteArray) {
        val input = com.google.common.io.ByteStreams.newDataInput(bytes)
        try {
            val packetOpt = su.plo.voice.proto.packets.tcp.PacketTcpCodec.decode<su.plo.voice.proto.packets.tcp.clientbound.ClientPacketTcpHandler>(input)
            if (!packetOpt.isPresent) return

            val packet = packetOpt.get()
            LOGGER.info("Received Plasmo Voice TCP packet: ${packet::class.java.simpleName}")

            when (packet) {
                is su.plo.voice.proto.packets.tcp.clientbound.PlayerInfoRequestPacket -> {
                    // Send PlayerInfoPacket containing our public key
                    val kp = keyPair ?: return
                    val minecraftVersion = Minecraft.getInstance().launchedVersion
                    val pvVersion = "2.1.10" // Emulate Plasmo Voice version

                    val response = su.plo.voice.proto.packets.tcp.serverbound.PlayerInfoPacket(
                        minecraftVersion,
                        pvVersion,
                        kp.public.encoded,
                        false,
                        false
                    )
                    sendPacket(response)
                }

                is su.plo.voice.proto.packets.tcp.clientbound.ConnectionPacket -> {
                    // The server sends the UDP socket details
                    this.currentSecret = packet.secret
                    var ip = packet.ip
                    if (ip == "0.0.0.0" || ip == "") {
                        ip = Minecraft.getInstance().connection?.connection?.remoteAddress?.let {
                            if (it is java.net.InetSocketAddress) it.address.hostAddress else "127.0.0.1"
                        } ?: "127.0.0.1"
                    }
                    val port = packet.port

                    LOGGER.info("Connecting to Plasmo Voice UDP server: $ip:$port with secret UUID: $currentSecret")
                    udpConnect(ip, port, packet.secret)

                    // Also feed the secret to virtual SVC server so that the local SVC client can authorize
                    val localSvcSecret = SvcEncryption.uuidToBytes(packet.secret) // Use PV secret as SVC secret
                    val session = Minecraft.getInstance().user
                    val playerUuid = session?.profileId
                    if (playerUuid != null) {
                        onConnectionEstablished?.invoke(playerUuid, localSvcSecret)
                    } else {
                        LOGGER.error("Could not obtain player UUID from Minecraft session!")
                    }
                }

                is su.plo.voice.proto.packets.tcp.clientbound.ConfigPacket -> {
                    LOGGER.info("Received ConfigPacket from Plasmo Voice server")

                    val encryptionInfo = packet.encryption
                    if (encryptionInfo != null) {
                        LOGGER.info("Found encryption key in ConfigPacket. Algorithm: ${encryptionInfo.algorithm}, key length: ${encryptionInfo.data.size}")
                        try {
                            val kp = keyPair
                            if (kp != null) {
                                val decryptCipher = javax.crypto.Cipher.getInstance("RSA")
                                decryptCipher.init(javax.crypto.Cipher.DECRYPT_MODE, kp.private)
                                val decryptedKey = decryptCipher.doFinal(encryptionInfo.data)
                                pvEncryptionKey = decryptedKey
                                LOGGER.info("Successfully decrypted AES key via RSA private key. Decrypted key size: ${decryptedKey.size}")
                            } else {
                                LOGGER.error("RSA KeyPair is null, cannot decrypt AES key!")
                            }
                        } catch (e: Exception) {
                            LOGGER.error("Failed to decrypt AES key via RSA", e)
                        }
                    } else {
                        LOGGER.warn("No encryption info found in ConfigPacket!")
                    }

                    // Track activations for proximity activation ID.
                    // Proximity activation id comes from `activations` (NOT sourceLines).
                    packet.activations.forEach { activation ->
                        LOGGER.info("Registered PV activation: ${activation.id} for name: ${activation.name}")
                        if (activation.name == "proximity") {
                            proximityActivationId = activation.id
                            LOGGER.info("Updated proximity activation ID to ${activation.id}")
                        }
                    }
                }

                is su.plo.voice.proto.packets.tcp.clientbound.SourceInfoPacket -> {
                    val info = packet.sourceInfo
                    if (info is su.plo.voice.proto.data.audio.source.PlayerSourceInfo) {
                        val playerUuid = info.playerInfo.playerId
                        if (playerUuid != null) {
                            PlayerRegistry.registerSourceMapping(info.id, playerUuid, info.isStereo)
                        }
                    }
                }

                is su.plo.voice.proto.packets.tcp.clientbound.PlayerListPacket -> {
                    packet.players.forEach { playerInfo ->
                        LOGGER.info("Received player info from player list: ${playerInfo.playerNick} with UUID: ${playerInfo.playerId}")
                        // Skip self so the local player is not injected into SVC volume settings.
                        if (isLocalPlayer(playerInfo.playerId)) return@forEach
                        // Safety mapping player UUID -> player UUID; PlayerSourceInfo refines it later.
                        PlayerRegistry.registerSourceMapping(playerInfo.playerId, playerInfo.playerId, false)
                        onPlayerDiscovered?.invoke(playerInfo.playerId, playerInfo.playerNick)
                    }
                }

                is su.plo.voice.proto.packets.tcp.clientbound.PlayerInfoUpdatePacket -> {
                    val playerInfo = packet.playerInfo
                    LOGGER.info("Player info updated: ${playerInfo.playerNick} with UUID: ${playerInfo.playerId}")
                    // Skip self so the local player is not injected into SVC volume settings.
                    if (!isLocalPlayer(playerInfo.playerId)) {
                        PlayerRegistry.registerSourceMapping(playerInfo.playerId, playerInfo.playerId, false)
                        onPlayerDiscovered?.invoke(playerInfo.playerId, playerInfo.playerNick)
                    }
                }
            }
        } catch (e: Exception) {
            LOGGER.error("Error handling Plasmo Voice TCP packet", e)
        }
    }

    fun sendPacket(packet: su.plo.voice.proto.packets.Packet<*>) {
        val encoded = su.plo.voice.proto.packets.tcp.PacketTcpCodec.encode(packet) ?: return
        ClientPlayNetworking.send(VoicePlusPayload(encoded))
        LOGGER.info("Sent Plasmo Voice TCP packet: ${packet::class.java.simpleName}")
    }

    // --- mic path: SVC mic (universal) -> PV PlayerAudioPacket ---
    override fun sendMicAudio(pkt: UniversalVoicePacket) {
        try {
            val key = pvEncryptionKey
            val encryptedOpus = if (key != null) SvcEncryption.encryptPv(pkt.opus, key) else pkt.opus
            val pvPacket = su.plo.voice.proto.packets.udp.serverbound.PlayerAudioPacket(
                pkt.seq,
                encryptedOpus,
                proximityActivationId,
                if (pkt.whisper) 3.toShort() else 16.toShort(),
                false
            )
            udpSendPacket(pvPacket)

            micPacketsSent++
            if (micPacketsSent % 50 == 1) {
                LOGGER.info("Forwarding microphone audio to Plasmo Voice. Packet count: $micPacketsSent, bytes: ${encryptedOpus.size}, activationId: $proximityActivationId")
            }
        } catch (e: Exception) {
            LOGGER.error("Failed to forward mic audio to Plasmo Voice", e)
        }
    }

    // ============================ UDP client ============================
    private var udpSocket: DatagramSocket? = null
    private var udpRunning = false
    private var udpThread: Thread? = null
    private var serverAddress: InetAddress? = null
    private var serverPort: Int = 0
    private var udpSecret: UUID? = null

    private fun udpConnect(ip: String, port: Int, secretUuid: UUID) {
        udpStop()

        this.serverAddress = InetAddress.getByName(ip)
        this.serverPort = port
        this.udpSecret = secretUuid
        this.udpRunning = true

        try {
            udpSocket = DatagramSocket()
            LOGGER.info("Plasmo Voice UDP Client initialized on local port ${udpSocket?.localPort} -> connecting to $ip:$port")
        } catch (e: Exception) {
            LOGGER.error("Failed to initialize DatagramSocket for Plasmo Voice client", e)
            return
        }

        udpThread = Thread({ listenLoop() }, "VoicePlus-PlasmoVoiceUdp-Listener").apply {
            isDaemon = true
            start()
        }

        startPingLoop()
    }

    override fun disconnect() = udpStop()

    private fun udpStop() {
        udpRunning = false
        udpSocket?.close()
        udpSocket = null
        udpThread?.interrupt()
        udpThread = null
        serverAddress = null
        serverPort = 0
        udpSecret = null
        LOGGER.info("Plasmo Voice UDP Client stopped")
    }

    /** Sends a packet to the real server using Plasmo Voice packet structure. */
    private fun udpSendPacket(packet: su.plo.voice.proto.packets.Packet<*>) {
        val socket = udpSocket ?: return
        val addr = serverAddress ?: return
        val sec = udpSecret ?: return

        try {
            val encoded = su.plo.voice.proto.packets.udp.PacketUdpCodec.encode(packet, sec) ?: return
            val datagram = DatagramPacket(encoded, encoded.size, addr, serverPort)
            socket.send(datagram)
        } catch (e: Exception) {
            LOGGER.error("Failed to send UDP packet to Plasmo Voice server", e)
        }
    }

    private fun startPingLoop() {
        Thread({
            while (udpRunning) {
                val addr = serverAddress ?: break
                udpSecret ?: break
                try {
                    val pingPacket = su.plo.voice.proto.packets.udp.bothbound.PingPacket(addr.hostAddress, serverPort)
                    udpSendPacket(pingPacket)
                } catch (e: Exception) {
                    LOGGER.error("Error in Plasmo Voice UDP Ping Loop", e)
                }
                try {
                    Thread.sleep(1000)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }, "VoicePlus-PlasmoVoiceUdp-Ping").apply {
            isDaemon = true
            start()
        }
    }

    private fun listenLoop() {
        val buffer = ByteArray(4096)
        while (udpRunning) {
            val socket = udpSocket ?: break
            try {
                val datagram = DatagramPacket(buffer, buffer.size)
                socket.receive(datagram)

                val data = ByteArray(datagram.length)
                System.arraycopy(buffer, 0, data, 0, datagram.length)

                val input = ByteStreams.newDataInput(data)
                val decodedOpt = su.plo.voice.proto.packets.udp.PacketUdpCodec.decode(input, su.plo.voice.proto.packets.PacketDirection.CLIENT)
                if (!decodedOpt.isPresent) continue

                val packetUdp = decodedOpt.get()
                val packet = packetUdp.getPacketUntyped()

                when (packet) {
                    is su.plo.voice.proto.packets.udp.bothbound.PingPacket -> {
                        LOGGER.info("Plasmo Voice UDP Ping roundtrip successful. Time: ${packet.time}")
                    }
                    is su.plo.voice.proto.packets.udp.clientbound.SourceAudioPacket -> {
                        handleAudioFromServer(packet.sourceId, packet.sequenceNumber, packet.sourceState, packet.distance, packet.data)
                    }
                }
            } catch (e: SocketException) {
                break
            } catch (e: Exception) {
                LOGGER.error("Error receiving Plasmo Voice UDP packet", e)
            }
        }
    }

    // --- audio path: PV SourceAudio -> universal (decrypted opus + metadata) ---
    private fun handleAudioFromServer(sourceId: UUID, sequenceNumber: Long, sourceState: Byte, distance: Short, audioData: ByteArray) {
        // Unknown source: ask the server who it is (once) so we can map it to a player.
        if (!PlayerRegistry.isKnownSource(sourceId) && PlayerRegistry.markRequested(sourceId)) {
            LOGGER.info("Unknown sourceId $sourceId received in audio, sending SourceInfoRequestPacket...")
            sendPacket(su.plo.voice.proto.packets.tcp.serverbound.SourceInfoRequestPacket(sourceId))
        }

        serverPacketsReceived++
        val key = pvEncryptionKey
        val decryptedAudio = if (key != null) SvcEncryption.decryptPv(audioData, key) else audioData

        if (serverPacketsReceived % 50 == 1) {
            val toc = if (decryptedAudio.isNotEmpty()) decryptedAudio[0].toInt() and 0xFF else -1
            LOGGER.info("Received sound packet from Plasmo Voice server. Packet count: $serverPacketsReceived, sourceId: $sourceId, rawBytes: ${audioData.size}, decryptedBytes: ${decryptedAudio.size}")
            LOGGER.info("PV decrypted opus: toc=0x${toc.toString(16)}, stereoBit=${(toc shr 2) and 1}, frameCount=${toc and 3}, sourceState=$sourceState")
        }

        val playerUuid = PlayerRegistry.resolvePlayer(sourceId)
        incomingCb?.invoke(UniversalVoicePacket(playerUuid, decryptedAudio, distance, false, sequenceNumber))
    }

    inner class VoicePlusPayload(val bytes: ByteArray) : CustomPacketPayload {
        override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = PAYLOAD_TYPE
    }
}
