package ru.penik.voice.plus.util

import net.minecraft.resources.Identifier
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

object FabricNetworkBridge {
    private val LOGGER = VoicePlusLogger.getLogger("VoicePlus-NetworkBridge")

    private var payloadTypeInstance: Any? = null
    private var payloadInterface: Class<*>? = null
    private var sendMethod: Method? = null

    interface ByteHolder {
        fun getRawBytes(): ByteArray
    }

    fun init(channelId: Identifier, onPacketReceived: (ByteArray) -> Unit) {
        try {
            // 1. Locate CustomPacketPayload interface
            val payloadClass = findClass(
                "net.minecraft.network.protocol.common.custom.CustomPacketPayload",
                "net.minecraft.class_8710"
            ) ?: throw ClassNotFoundException("Could not find CustomPacketPayload class")
            payloadInterface = payloadClass

            // 2. Locate Type / Id class inside CustomPacketPayload
            val typeClass = payloadClass.declaredClasses.firstOrNull()
                ?: findClass(
                    "net.minecraft.network.protocol.common.custom.CustomPacketPayload\$Type",
                    "net.minecraft.network.protocol.common.custom.CustomPacketPayload\$Id",
                    "net.minecraft.class_8710\$class_9154"
                ) ?: throw ClassNotFoundException("Could not find CustomPacketPayload.Type class")

            // 3. Create Type instance: new CustomPacketPayload.Type<>(channelId)
            val typeConstructor = typeClass.declaredConstructors.firstOrNull { it.parameterCount == 1 }
                ?: throw NoSuchMethodException("Could not find Type constructor")
            typeConstructor.isAccessible = true
            val payloadType = typeConstructor.newInstance(channelId)
            payloadTypeInstance = payloadType

            // 4. Create dynamic StreamCodec proxy
            val streamCodecInterface = findClass(
                "net.minecraft.network.codec.StreamCodec",
                "net.minecraft.class_9139"
            ) ?: throw ClassNotFoundException("Could not find StreamCodec class")

            val streamCodecProxy = Proxy.newProxyInstance(
                streamCodecInterface.classLoader,
                arrayOf(streamCodecInterface)
            ) { _, method, args ->
                when (method.name) {
                    "encode" -> {
                        val buf = args[0]
                        val payload = args[1]
                        val bytes = if (payload is ByteHolder) {
                            payload.getRawBytes()
                        } else {
                            ByteArray(0)
                        }
                        val writeBytes = buf.javaClass.methods.firstOrNull {
                            it.name == "writeBytes" && it.parameterTypes.size == 1 && it.parameterTypes[0] == ByteArray::class.java
                        }
                        writeBytes?.invoke(buf, bytes)
                        null
                    }
                    "decode" -> {
                        val buf = args[0]
                        val readableBytesMethod = buf.javaClass.getMethod("readableBytes")
                        val len = readableBytesMethod.invoke(buf) as Int
                        val bytes = ByteArray(len)
                        val readBytesMethod = buf.javaClass.methods.firstOrNull {
                            it.name == "readBytes" && it.parameterTypes.size == 1 && it.parameterTypes[0] == ByteArray::class.java
                        }
                        readBytesMethod?.invoke(buf, bytes)
                        createPayloadProxy(bytes)
                    }
                    else -> method.defaultValue
                }
            }

            // 5. Register in PayloadTypeRegistry
            val registryClass = Class.forName("net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry")
            val s2cMethod = registryClass.methods.firstOrNull { it.name in listOf("clientboundPlay", "playS2C") && it.parameterCount == 0 }
            val c2sMethod = registryClass.methods.firstOrNull { it.name in listOf("serverboundPlay", "playC2S") && it.parameterCount == 0 }

            val s2c = s2cMethod?.invoke(null)
            val c2s = c2sMethod?.invoke(null)

            val regMethodS2c = s2c?.javaClass?.methods?.firstOrNull { it.name == "register" && it.parameterCount == 2 }
            val regMethodC2s = c2s?.javaClass?.methods?.firstOrNull { it.name == "register" && it.parameterCount == 2 }

            regMethodS2c?.invoke(s2c, payloadType, streamCodecProxy)
            regMethodC2s?.invoke(c2s, payloadType, streamCodecProxy)
            LOGGER.info("Registered network payload dynamically via ${s2cMethod?.name}/${c2sMethod?.name}")

            // 6. Register receiver in ClientPlayNetworking
            val networkingClass = Class.forName("net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking")
            val receiverMethod = networkingClass.methods.firstOrNull {
                it.name == "registerGlobalReceiver" && it.parameterCount == 2
            }
            if (receiverMethod != null) {
                val receiverParamType = receiverMethod.parameterTypes[1]
                val receiverProxy = Proxy.newProxyInstance(
                    receiverParamType.classLoader,
                    arrayOf(receiverParamType)
                ) { _, method, args ->
                    if (method.name in listOf("receive", "accept")) {
                        val payload = args[0]
                        val bytes = if (payload is ByteHolder) {
                            payload.getRawBytes()
                        } else {
                            ByteArray(0)
                        }
                        onPacketReceived(bytes)
                    }
                    null
                }
                receiverMethod.invoke(null, payloadType, receiverProxy)
                LOGGER.info("Registered ClientPlayNetworking receiver dynamically")
            }

            // 7. Find send method
            sendMethod = networkingClass.methods.firstOrNull {
                it.name == "send" && it.parameterCount == 1 && it.parameterTypes[0].isAssignableFrom(payloadClass)
            }
        } catch (e: Throwable) {
            LOGGER.error("Failed to initialize FabricNetworkBridge dynamically", e)
        }
    }

    fun send(bytes: ByteArray) {
        try {
            val payload = createPayloadProxy(bytes) ?: return
            val m = sendMethod ?: return
            m.invoke(null, payload)
        } catch (e: Exception) {
            LOGGER.error("Failed to send packet via FabricNetworkBridge", e)
        }
    }

    private fun createPayloadProxy(bytes: ByteArray): Any? {
        val iface = payloadInterface ?: return null
        val type = payloadTypeInstance ?: return null

        return Proxy.newProxyInstance(
            iface.classLoader,
            arrayOf(iface, ByteHolder::class.java),
            object : InvocationHandler {
                override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? {
                    return when (method.name) {
                        "getRawBytes" -> bytes
                        "type", "getId", "id" -> type
                        "hashCode" -> bytes.contentHashCode()
                        "equals" -> args?.getOrNull(0) === proxy
                        "toString" -> "DynamicVoicePlusPayload(bytes=${bytes.size})"
                        else -> method.defaultValue
                    }
                }
            }
        )
    }

    private fun findClass(vararg names: String): Class<*>? {
        for (name in names) {
            try {
                return Class.forName(name)
            } catch (_: Throwable) {}
        }
        return null
    }
}
