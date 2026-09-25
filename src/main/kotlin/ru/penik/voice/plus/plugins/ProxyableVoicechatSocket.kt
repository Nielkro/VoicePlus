package ru.penik.voice.plus.plugins

import de.maxhenkel.voicechat.api.ClientVoicechatSocket
import de.maxhenkel.voicechat.api.RawUdpPacket
import ru.penik.voice.plus.util.VoicePlusLogger
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.*
import java.nio.ByteBuffer

class ProxyableVoicechatSocket : ClientVoicechatSocket {
    private val LOGGER = VoicePlusLogger.getLogger("VoicePlus-ProxySocket")

    private var socket: DatagramSocket? = null
    
    // SOCKS5 state
    private var tcpSocket: Socket? = null
    private var relayAddress: SocketAddress? = null
    
    companion object {
        @Volatile
        var useSocks5: Boolean = false
    }

    override fun open() {
        VoicePlusConfig.load()
        if (useSocks5 && VoicePlusConfig.socksHost.isNotBlank()) {
            LOGGER.info("Opening socket in SOCKS5 mode via ${VoicePlusConfig.socksHost}:${VoicePlusConfig.socksPort}...")
            try {
                // 1. Establish SOCKS5 TCP connection
                val tcp = Socket()
                tcp.connect(InetSocketAddress(VoicePlusConfig.socksHost, VoicePlusConfig.socksPort), 5000)
                tcpSocket = tcp

                val outStream = DataOutputStream(tcp.getOutputStream())
                val inStream = DataInputStream(tcp.getInputStream())

                // 2. Handshake: Version 5, 1 auth method (no auth)
                outStream.write(byteArrayOf(0x05, 0x01, 0x00))
                outStream.flush()

                val version = inStream.readByte().toInt()
                val method = inStream.readByte().toInt()
                if (version != 5 || method != 0) {
                    throw IOException("SOCKS5 server rejected connection or requires authentication (version=$version, method=$method)")
                }

                // 3. Request UDP ASSOCIATE: Version 5, Command 3 (UDP ASSOCIATE), Reserved 0, Address Type 1 (IPv4), IP 0.0.0.0, Port 0
                outStream.write(byteArrayOf(0x05, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                outStream.flush()

                val respVersion = inStream.readByte().toInt()
                val respStatus = inStream.readByte().toInt()
                inStream.readByte() // Reserved byte
                val addrType = inStream.readByte().toInt()

                if (respVersion != 5 || respStatus != 0) {
                    throw IOException("SOCKS5 UDP Associate request failed: status=$respStatus")
                }

                val relayHostAddress: InetAddress
                val relayPort: Int

                when (addrType) {
                    1 -> { // IPv4
                        val ipBytes = ByteArray(4)
                        inStream.readFully(ipBytes)
                        relayHostAddress = InetAddress.getByAddress(ipBytes)
                        relayPort = inStream.readUnsignedShort()
                    }
                    3 -> { // Domain name
                        val len = inStream.readByte().toInt() and 0xFF
                        val domainBytes = ByteArray(len)
                        inStream.readFully(domainBytes)
                        val domain = String(domainBytes)
                        relayHostAddress = InetAddress.getByName(domain)
                        relayPort = inStream.readUnsignedShort()
                    }
                    4 -> { // IPv6
                        val ipBytes = ByteArray(16)
                        inStream.readFully(ipBytes)
                        relayHostAddress = InetAddress.getByAddress(ipBytes)
                        relayPort = inStream.readUnsignedShort()
                    }
                    else -> throw IOException("Unsupported SOCKS5 address type: $addrType")
                }

                relayAddress = InetSocketAddress(relayHostAddress, relayPort)
                LOGGER.info("SOCKS5 UDP Associate success! Relay address: $relayAddress")

                // 4. Bind local DatagramSocket
                socket = DatagramSocket()
            } catch (e: Exception) {
                LOGGER.error("Failed to connect via SOCKS5 proxy, falling back to direct connection", e)
                close()
                // Fallback to direct
                socket = DatagramSocket()
                relayAddress = null
            }
        } else {
            LOGGER.info("Opening socket in Direct mode...")
            socket = DatagramSocket()
            relayAddress = null
        }
    }

    override fun read(): RawUdpPacket {
        val s = socket ?: throw IllegalStateException("Socket not opened yet")
        val buffer = ByteArray(4096)
        val packet = DatagramPacket(buffer, buffer.size)
        s.receive(packet)

        val timestamp = System.currentTimeMillis()
        val data: ByteArray
        val sourceAddress: SocketAddress

        if (relayAddress != null) {
            // SOCKS5 UDP encapsulation header:
            // RSV (2 bytes) = 0x0000
            // FRAG (1 byte) = 0x00
            // ATYP (1 byte) = Address type (1 = IPv4, 3 = Domain, 4 = IPv6)
            // Destination IP (4, domain-length + name, or 16 bytes)
            // Destination Port (2 bytes)
            val headerStream = DataInputStream(java.io.ByteArrayInputStream(packet.data, packet.offset, packet.length))
            val rsv = headerStream.readUnsignedShort()
            val frag = headerStream.readByte().toInt()
            val atyp = headerStream.readByte().toInt()
            
            val remoteAddress: InetAddress
            val remotePort: Int

            when (atyp) {
                1 -> { // IPv4
                    val ipBytes = ByteArray(4)
                    headerStream.readFully(ipBytes)
                    remoteAddress = InetAddress.getByAddress(ipBytes)
                    remotePort = headerStream.readUnsignedShort()
                }
                3 -> { // Domain name
                    val len = headerStream.readByte().toInt() and 0xFF
                    val domainBytes = ByteArray(len)
                    headerStream.readFully(domainBytes)
                    val domain = String(domainBytes)
                    remoteAddress = InetAddress.getByName(domain)
                    remotePort = headerStream.readUnsignedShort()
                }
                4 -> { // IPv6
                    val ipBytes = ByteArray(16)
                    headerStream.readFully(ipBytes)
                    remoteAddress = InetAddress.getByAddress(ipBytes)
                    remotePort = headerStream.readUnsignedShort()
                }
                else -> throw IOException("Invalid atyp in SOCKS5 UDP header: $atyp")
            }

            sourceAddress = InetSocketAddress(remoteAddress, remotePort)
            
            // Extract the actual payload
            val headerSize = when (atyp) {
                1 -> 10
                3 -> 6 + (packet.data[packet.offset + 4].toInt() and 0xFF)
                4 -> 22
                else -> 10
            }
            val payloadSize = packet.length - headerSize
            data = ByteArray(payloadSize)
            System.arraycopy(packet.data, packet.offset + headerSize, data, 0, payloadSize)
        } else {
            sourceAddress = packet.socketAddress
            data = ByteArray(packet.length)
            System.arraycopy(packet.data, packet.offset, data, 0, packet.length)
        }

        return ProxyUdpPacket(data, sourceAddress, timestamp)
    }

    override fun send(data: ByteArray, address: SocketAddress) {
        val s = socket ?: return
        val relay = relayAddress
        if (relay != null && address is InetSocketAddress) {
            // SOCKS5 UDP Encapsulation:
            // RSV (2 bytes) = 0x00 0x00
            // FRAG (1 byte) = 0x00
            // ATYP (1 byte) = 0x01 (IPv4) or 0x04 (IPv6)
            // IP (4 or 16 bytes)
            // Port (2 bytes)
            // Payload (data)
            val ipBytes = address.address.address
            val atyp = if (ipBytes.size == 4) 0x01 else 0x04
            val headerSize = 4 + ipBytes.size + 2
            val totalSize = headerSize + data.size
            val buf = ByteBuffer.allocate(totalSize)
            buf.putShort(0) // RSV
            buf.put(0.toByte()) // FRAG
            buf.put(atyp.toByte()) // ATYP
            buf.put(ipBytes) // IP
            buf.putShort(address.port.toShort()) // Port
            buf.put(data) // Payload
            
            val payload = buf.array()
            s.send(DatagramPacket(payload, payload.size, relay))
        } else {
            s.send(DatagramPacket(data, data.size, address))
        }
    }

    override fun close() {
        socket?.close()
        socket = null
        try {
            tcpSocket?.close()
        } catch (ignored: Exception) {}
        tcpSocket = null
        relayAddress = null
    }

    override fun isClosed(): Boolean = socket == null
}

class ProxyUdpPacket(
    private val data: ByteArray,
    private val address: SocketAddress,
    private val timestamp: Long
) : RawUdpPacket {
    override fun getData(): ByteArray = data
    override fun getTimestamp(): Long = timestamp
    override fun getSocketAddress(): SocketAddress = address
}
