package ru.penik.voice.plus.adapters.inbound

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory
import io.netty.buffer.Unpooled
import net.minecraft.client.Minecraft
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import ru.penik.voice.plus.core.InboundGateway
import ru.penik.voice.plus.core.PlayerRegistry
import ru.penik.voice.plus.core.UniversalVoicePacket
import ru.penik.voice.plus.crypto.SvcEncryption

/**
 * Inbound side: we PRETEND to be a Simple Voice Chat server for the local client.
 *
 * Merges the former LocalSvcProxyServer (UDP proxy on 127.0.0.1:24454, keepalive,
 * AES/GCM crypto, control-plane replies) and SvcPluginMessageBridge (SVC secret
 * handshake + PlayerState injection, both via reflection into SVC internals).
 *
 * SVC-specific reflection lives here on purpose — the known-player LIST is held in
 * [PlayerRegistry], but the ACT of injecting it into SVC is protocol-specific.
 */
class SvcInboundAdapter : InboundGateway {
    private val LOGGER = LoggerFactory.getLogger("VoicePlus-SvcInbound")

    private var micCb: ((UniversalVoicePacket) -> Unit)? = null
    override fun onMicAudio(cb: (UniversalVoicePacket) -> Unit) { micCb = cb }

    // ===================== Local UDP proxy server =====================
    private var socket: DatagramSocket? = null
    private var running = false
    private var thread: Thread? = null
    private var keepAliveThread: Thread? = null

    // Simple Voice Chat default port
    var localPort: Int = 24454
        private set

    // Player UUID -> SVC Secret (16 bytes)
    private val playerSecrets = ConcurrentHashMap<UUID, ByteArray>()
    // Player UUID -> Local Client's UDP Socket Address
    private val clientAddresses = ConcurrentHashMap<UUID, java.net.SocketAddress>()

    override fun start() {
        if (running) return
        running = true
        var attempts = 0
        var bound = false
        while (!bound && attempts < 10) {
            try {
                socket = DatagramSocket(localPort, InetAddress.getByName("127.0.0.1"))
                bound = true
                LOGGER.info("Local SVC virtual server started on 127.0.0.1:$localPort")
            } catch (e: SocketException) {
                LOGGER.warn("Port $localPort busy, trying next one...")
                localPort++
                attempts++
            }
        }
        if (!bound) {
            LOGGER.error("Could not bind SvcInboundAdapter to any port!")
            return
        }

        thread = Thread({ listenLoop() }, "VoicePlus-LocalSvcProxy-Listener").apply {
            isDaemon = true
            start()
        }

        // KeepAlive thread: periodically send KeepAlive packets (type 0x08) to clients
        keepAliveThread = Thread({
            while (running) {
                try {
                    Thread.sleep(1000)
                    for (playerUuid in clientAddresses.keys) {
                        sendToLocalClient(playerUuid, 0x08.toByte(), ByteArray(0))
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    LOGGER.error("Error in proxy KeepAlive loop", e)
                }
            }
        }, "VoicePlus-LocalSvcProxy-KeepAlive").apply {
            isDaemon = true
            start()
        }
    }

    override fun stop() {
        running = false
        socket?.close()
        socket = null
        thread?.interrupt()
        thread = null
        keepAliveThread?.interrupt()
        keepAliveThread = null
        playerSecrets.clear()
        clientAddresses.clear()
        LOGGER.info("Local SVC virtual server stopped")
    }
    // PLACEHOLDER_PROXY

    fun registerPlayerSecret(playerUuid: UUID, secretBytes: ByteArray) {
        playerSecrets[playerUuid] = secretBytes
        LOGGER.info("Registered local SVC secret for player $playerUuid")
    }

    /** Encrypts and sends a packet from the virtual server to the local SVC client. */
    fun sendToLocalClient(playerUuid: UUID, packetType: Byte, payload: ByteArray) {
        // There is only one real local client connected to this proxy. Audio for OTHER
        // players won't have their own address, so forward ALL outgoing packets to the
        // single connected local client (the player running the game).
        val clientAddress = clientAddresses.values.firstOrNull() ?: return
        val localPlayerUuid = clientAddresses.keys.firstOrNull() ?: return
        val secretBytes = playerSecrets[localPlayerUuid] ?: return
        val socket = socket ?: return

        try {
            // 1. Raw inner packet: [packetType (1 byte)] + [payload]
            val inner = ByteArray(1 + payload.size)
            inner[0] = packetType
            System.arraycopy(payload, 0, inner, 1, payload.size)

            // 2. Encrypt inner packet using AES/GCM/NoPadding (Simple Voice Chat standard)
            val encrypted = SvcEncryption.encrypt(inner, secretBytes)

            // 3. Outer UDP message: [MAGIC_BYTE (1 byte)] + [Encrypted Payload as MC Byte Array]
            val buf = Unpooled.buffer()
            buf.writeByte(0b11111111.toInt()) // MAGIC_BYTE
            val friendlyBuf = FriendlyByteBuf(buf)
            friendlyBuf.writeByteArray(encrypted)

            val outData = ByteArray(friendlyBuf.readableBytes())
            friendlyBuf.readBytes(outData)

            val datagram = DatagramPacket(outData, outData.size, clientAddress)
            socket.send(datagram)
        } catch (e: Exception) {
            LOGGER.error("Failed to send packet to local client $playerUuid", e)
        }
    }

    private fun listenLoop() {
        val buffer = ByteArray(4096)
        while (running) {
            val socket = socket ?: break
            try {
                val datagram = DatagramPacket(buffer, buffer.size)
                socket.receive(datagram)

                val length = datagram.length
                if (length < 18) continue // Magic byte (1) + UUID (16) + min payload

                val rawBuf = Unpooled.wrappedBuffer(buffer, 0, length)
                val friendlyBuf = FriendlyByteBuf(rawBuf)

                val magicByte = friendlyBuf.readByte()
                if (magicByte != 0b11111111.toByte()) {
                    LOGGER.warn("Invalid magic byte: $magicByte")
                    continue
                }

                val playerUuid = friendlyBuf.readUUID()
                // Save/update client address to forward packets back
                clientAddresses[playerUuid] = datagram.socketAddress

                var secretBytes = playerSecrets[playerUuid]
                if (secretBytes == null) {
                    // Fallback to the first registered secret since we are on the client proxy
                    secretBytes = playerSecrets.values.firstOrNull()
                }
                if (secretBytes == null) continue

                val encryptedPayload = friendlyBuf.readByteArray()
                val decrypted = SvcEncryption.decrypt(encryptedPayload, secretBytes)
                if (decrypted.isEmpty()) continue

                val packetType = decrypted[0]
                val innerPayload = ByteArray(decrypted.size - 1)
                System.arraycopy(decrypted, 1, innerPayload, 0, innerPayload.size)

                handlePacketFromSvc(playerUuid, packetType, innerPayload)
            } catch (e: SocketException) {
                break
            } catch (e: Exception) {
                LOGGER.error("Error in local proxy listener loop", e)
            }
        }
    }

    private fun handlePacketFromSvc(playerUuid: UUID, packetType: Byte, payload: ByteArray) {
        if (packetType == 0x05.toByte()) { // AuthenticatePacket
            LOGGER.info("Received AuthenticatePacket from local SVC client, sending AuthenticateAckPacket")
            sendToLocalClient(playerUuid, 0x06.toByte(), ByteArray(0))
        } else if (packetType == 0x09.toByte()) { // ConnectionCheckPacket
            LOGGER.info("Received ConnectionCheckPacket from local SVC client, sending ConnectionCheckAckPacket")
            sendToLocalClient(playerUuid, 0x0a.toByte(), ByteArray(0))
            // Also send a KeepAlive packet immediately to establish keepAlive timer on client
            sendToLocalClient(playerUuid, 0x08.toByte(), ByteArray(0))
        } else if (packetType == 0x08.toByte()) { // KeepAlivePacket (0x08 in registry)
            // Reply with KeepAlive packet to local client to reset their timeout timer
            sendToLocalClient(playerUuid, 0x08.toByte(), ByteArray(0))
        } else if (packetType == 0x07.toByte()) { // PingPacket
            // Extract ping ID (long) and reply with PongPacket (type 0x08)
            try {
                val input = com.google.common.io.ByteStreams.newDataInput(payload)
                val pingId = input.readLong()
                val out = com.google.common.io.ByteStreams.newDataOutput()
                out.writeLong(pingId)
                sendToLocalClient(playerUuid, 0x08.toByte(), out.toByteArray())
            } catch (e: Exception) {}
        } else if (packetType == 0x01.toByte()) { // MicPacket (microphone audio from local client)
            try {
                val input = com.google.common.io.ByteStreams.newDataInput(payload)
                val dataLength = readVarInt(input)
                val opusBytes = ByteArray(dataLength)
                input.readFully(opusBytes)

                val sequenceNumber = input.readLong()
                val whispering = input.readBoolean()

                // SVC sends a final empty-audio packet to signal end-of-speech — skip it.
                // Sequence number is discarded here; VoiceHub assigns the outgoing seq.
                if (opusBytes.isNotEmpty()) {
                    micCb?.invoke(UniversalVoicePacket(playerUuid, opusBytes, 0, whispering, 0L))
                }
            } catch (e: Exception) {
                LOGGER.error("Failed to translate packet from SVC", e)
            }
        }
    }

    /** Deliver a remote voice packet to the local client as an SVC PlayerSoundPacket (0x02). */
    override fun deliverToClient(pkt: UniversalVoicePacket) {
        // FriendlyByteBuf format for PlayerSoundPacket.toBytes:
        // 1. channelId (UUID) 2. sender (UUID) 3. data (ByteArray)
        // 4. sequenceNumber (Long) 5. distance (Float) 6. flags (Byte)
        val buf = Unpooled.buffer()
        val friendlyBuf = FriendlyByteBuf(buf)

        friendlyBuf.writeUUID(pkt.uuid)
        friendlyBuf.writeUUID(pkt.uuid)
        friendlyBuf.writeByteArray(pkt.opus)
        friendlyBuf.writeLong(pkt.seq)
        friendlyBuf.writeFloat(pkt.distance.toFloat())
        friendlyBuf.writeByte(0) // flags: no whispering, no category

        val outputBytes = ByteArray(friendlyBuf.readableBytes())
        friendlyBuf.readBytes(outputBytes)

        sendToLocalClient(pkt.uuid, 0x02.toByte(), outputBytes)
    }

    // Varint helper (SVC MicPacket uses a varint-prefixed opus blob)
    private fun readVarInt(inStream: com.google.common.io.ByteArrayDataInput): Int {
        var value = 0
        var position = 0
        var currentByte: Byte
        while (true) {
            currentByte = inStream.readByte()
            value = value or ((currentByte.toInt() and 127) shl (position * 7))
            if ((currentByte.toInt() and 128) == 0) break
            position++
            if (position >= 5) throw RuntimeException("VarInt is too big")
        }
        return value
    }

    // ===================== SVC handshake + state injection =====================
    // Simple Voice Chat plugin channels (kept for reference; SVC registers the payload
    // types itself, so we do not register them here).
    val REQUEST_SECRET_TYPE = CustomPacketPayload.Type<RequestSecretPayload>(Identifier.fromNamespaceAndPath("voicechat", "request_secret"))
    val SECRET_TYPE = CustomPacketPayload.Type<SecretPayload>(Identifier.fromNamespaceAndPath("voicechat", "secret"))

    val REQUEST_SECRET_STREAM_CODEC = object : StreamCodec<RegistryFriendlyByteBuf, RequestSecretPayload> {
        override fun encode(buf: RegistryFriendlyByteBuf, value: RequestSecretPayload) { buf.writeBytes(value.bytes) }
        override fun decode(buf: RegistryFriendlyByteBuf): RequestSecretPayload {
            val bytes = ByteArray(buf.readableBytes()); buf.readBytes(bytes); return RequestSecretPayload(bytes)
        }
    }
    val SECRET_STREAM_CODEC = object : StreamCodec<RegistryFriendlyByteBuf, SecretPayload> {
        override fun encode(buf: RegistryFriendlyByteBuf, value: SecretPayload) { buf.writeBytes(value.bytes) }
        override fun decode(buf: RegistryFriendlyByteBuf): SecretPayload {
            val bytes = ByteArray(buf.readableBytes()); buf.readBytes(bytes); return SecretPayload(bytes)
        }
    }

    /**
     * Called once the PV side established a connection: register the SVC secret and
     * hand it to the local SVC client so it can authenticate against this proxy.
     */
    fun provideSecret(playerUuid: UUID, secretBytes: ByteArray) {
        registerPlayerSecret(playerUuid, secretBytes)
        sendSecretToLocalClient(playerUuid, secretBytes, localPort)
    }

    /** Sends "voicechat:secret" data to the local SVC client so it can authenticate. */
    private fun sendSecretToLocalClient(playerUuid: UUID, secretBytes: ByteArray, localServerPort: Int) {
        try {
            val friendlyBuf = FriendlyByteBuf(Unpooled.buffer())
            friendlyBuf.writeBytes(secretBytes)          // 1. secret bytes (16 bytes)
            friendlyBuf.writeInt(localServerPort)         // 2. port (virtual server UDP port)
            friendlyBuf.writeUUID(playerUuid)             // 3. player UUID
            friendlyBuf.writeByte(0)                      // 4. Codec (OPUS ordinal = 0)
            friendlyBuf.writeInt(1024)                    // 5. MTU size (1024 bytes)
            friendlyBuf.writeDouble(16.0)                 // 6. voice distance (16.0)
            friendlyBuf.writeInt(10000)                   // 7. keep alive (10000ms)
            friendlyBuf.writeBoolean(true)                // 8. groupsEnabled (true)
            // 9. voiceHost — force local proxy so SVC connects to 127.0.0.1 regardless of
            //    the real MC server address (empty = SVC falls back to the MC server IP,
            //    which breaks on any non-local server).
            friendlyBuf.writeUtf("127.0.0.1")
            friendlyBuf.writeBoolean(false)               // 10. allowRecording (false)

            // Reflection to bypass compile-time dependency on SVC internals
            val secretPacketClass = Class.forName("de.maxhenkel.voicechat.net.SecretPacket")
            val secretPacketInstance = secretPacketClass.getDeclaredConstructor().newInstance()
            val fromBytesMethod = secretPacketClass.getMethod("fromBytes", FriendlyByteBuf::class.java)
            friendlyBuf.readerIndex(0)
            fromBytesMethod.invoke(secretPacketInstance, friendlyBuf)

            val clientManagerClass = Class.forName("de.maxhenkel.voicechat.voice.client.ClientManager")
            val instanceMethod = clientManagerClass.getMethod("instance")
            val clientManagerInstance = instanceMethod.invoke(null)
            val authenticateMethod = clientManagerClass.getDeclaredMethod("authenticate", secretPacketClass)
            authenticateMethod.isAccessible = true
            authenticateMethod.invoke(clientManagerInstance, secretPacketInstance)

            LOGGER.info("Directly authenticated local SVC ClientManager (via reflection) with port $localServerPort")
        } catch (e: Exception) {
            LOGGER.error("Failed to send secret plugin message to local client via reflection", e)
            e.printStackTrace()
        }
    }

    /** Records a player in [PlayerRegistry] and injects an SVC PlayerState immediately. */
    fun registerAndInjectPlayer(uuid: UUID, name: String, disabled: Boolean = false) {
        PlayerRegistry.registerPlayer(uuid, name)
        injectState(uuid, name, disabled)
    }

    /** Re-inject every known player (SVC clears its states map on reconnect). */
    override fun reinjectKnownPlayers() {
        syncPlayerStates()
    }

    /**
     * Reconciles the SVC PlayerState list against the real MC tab list.
     *
     *  - in the PV player list (has the PV voice mod)  -> normal player (disabled = false)
     *  - on the MC server but NOT in the PV list       -> no voice mod -> "no mod" icon (disabled = true)
     */
    fun syncPlayerStates() {
        val connection = Minecraft.getInstance().connection ?: return
        val localUuid = Minecraft.getInstance().player?.uuid
        connection.onlinePlayers.forEach { info ->
            val uuid = info.profile.id
            if (uuid == localUuid) return@forEach
            val hasPvMod = PlayerRegistry.isKnownPlayer(uuid)
            injectState(uuid, info.profile.name, disabled = !hasPvMod)
        }
    }

    /**
     * Injects a PlayerState into the local SVC client's ClientPlayerStateManager so
     * remote Plasmo Voice players show up in the "Adjust volumes" screen and get the
     * speaking icon rendered. Safe to call repeatedly.
     */
    private fun injectState(uuid: UUID, name: String, disabled: Boolean) {
        try {
            val cmClass = Class.forName("de.maxhenkel.voicechat.voice.client.ClientManager")
            val psm = cmClass.getMethod("getPlayerStateManager").invoke(null) ?: return

            val statesField = psm.javaClass.getDeclaredField("states")
            statesField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val states = statesField.get(psm) as MutableMap<UUID, Any>

            val psClass = Class.forName("de.maxhenkel.voicechat.voice.common.PlayerState")
            val state = psClass.getConstructor(
                UUID::class.java, String::class.java,
                Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType
            ).newInstance(uuid, name, disabled, false)

            states[uuid] = state
            LOGGER.info("Injected SVC PlayerState: $name ($uuid); states now = ${states.size}")

            // Best-effort UI refresh if the volume screen is open
            try {
                Class.forName("de.maxhenkel.voicechat.gui.volume.AdjustVolumeList")
                    .getMethod("update").invoke(null)
            } catch (ignored: Exception) {}
        } catch (e: Exception) {
            LOGGER.error("Failed to inject SVC PlayerState for $name", e)
        }
    }

    inner class RequestSecretPayload(val bytes: ByteArray) : CustomPacketPayload {
        override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = REQUEST_SECRET_TYPE
    }
    inner class SecretPayload(val bytes: ByteArray) : CustomPacketPayload {
        override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = SECRET_TYPE
    }
}
