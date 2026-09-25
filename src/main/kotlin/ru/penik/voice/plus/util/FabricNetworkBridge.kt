package ru.penik.voice.plus.util

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

    fun init(namespace: String, path: String, onPacketReceived: (ByteArray) -> Unit) {
        try {
            val channelId = createIdentifier(namespace, path)

            // 1. Locate CustomPacketPayload interface
            val payloadClass = findClass(
                "net.minecraft.network.protocol.common.custom.CustomPacketPayload",
                "net.minecraft.class_8710"
            ) ?: throw ClassNotFoundException("Could not find CustomPacketPayload class")
            payloadInterface = payloadClass

            // 2. Locate Type / Id class inside CustomPacketPayload (specifically Type or Id, not anonymous class)
            val typeClass = payloadClass.declaredClasses.firstOrNull { it.simpleName in listOf("Type", "Id") }
                ?: findClass(
                    "net.minecraft.network.protocol.common.custom.CustomPacketPayload\$Type",
                    "net.minecraft.network.protocol.common.custom.CustomPacketPayload\$Id",
                    "net.minecraft.network.packet.CustomPayload\$Id",
                    "net.minecraft.class_8710\$class_9154"
                ) ?: throw ClassNotFoundException("Could not find CustomPacketPayload.Type/Id class")

            LOGGER.info("Using CustomPacketPayload Type class: ${typeClass.name}")

            // 3. Create Type instance: new CustomPacketPayload.Type<>(channelId)
            val typeConstructor = typeClass.declaredConstructors.firstOrNull {
                it.parameterCount == 1 && it.parameterTypes[0].isAssignableFrom(channelId.javaClass)
            } ?: typeClass.declaredConstructors.firstOrNull { it.parameterCount == 1 }
              ?: throw NoSuchMethodException("Could not find Type constructor with 1 param")
            typeConstructor.isAccessible = true
            val payloadType = typeConstructor.newInstance(channelId)
            payloadTypeInstance = payloadType
            LOGGER.info("Created payloadType instance: $payloadType")

            // 4. Create dynamic StreamCodec proxy
            val streamCodecInterface = findClass(
                "net.minecraft.network.codec.StreamCodec",
                "net.minecraft.class_9139"
            ) ?: throw ClassNotFoundException("Could not find StreamCodec class")

            val streamCodecProxy = Proxy.newProxyInstance(
                streamCodecInterface.classLoader,
                arrayOf(streamCodecInterface)
            ) { proxy, method, args ->
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
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> args?.getOrNull(0) === proxy
                    "toString" -> "DynamicStreamCodec"
                    else -> null
                }
            }

            // 5. Register in PayloadTypeRegistry (register across play and config phases)
            val registryClass = Class.forName("net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry")
            val regMethod = registryClass.methods.firstOrNull { it.name == "register" && it.parameterCount == 2 }
                ?: throw NoSuchMethodException("Could not find register method on PayloadTypeRegistry")
            regMethod.isAccessible = true

            val s2cMethods = listOf("playS2C", "clientboundPlay", "configurationS2C", "clientboundConfiguration")
            for (mName in s2cMethods) {
                val m = registryClass.methods.firstOrNull { it.name == mName && it.parameterCount == 0 }
                if (m != null) {
                    val reg = m.invoke(null)
                    if (reg != null) {
                        regMethod.invoke(reg, payloadType, streamCodecProxy)
                        LOGGER.info("Registered network payload on PayloadTypeRegistry.$mName")
                    }
                }
            }

            val c2sMethods = listOf("playC2S", "serverboundPlay", "configurationC2S", "serverboundConfiguration")
            for (mName in c2sMethods) {
                val m = registryClass.methods.firstOrNull { it.name == mName && it.parameterCount == 0 }
                if (m != null) {
                    val reg = m.invoke(null)
                    if (reg != null) {
                        regMethod.invoke(reg, payloadType, streamCodecProxy)
                        LOGGER.info("Registered network payload on PayloadTypeRegistry.$mName")
                    }
                }
            }

            // 6. Register receiver in ClientPlayNetworking
            val networkingClass = Class.forName("net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking")
            val receiverMethod = networkingClass.methods.firstOrNull {
                it.name == "registerGlobalReceiver" && it.parameterCount == 2
            } ?: networkingClass.methods.firstOrNull {
                it.name == "registerReceiver" && it.parameterCount == 2
            }
            if (receiverMethod != null) {
                receiverMethod.isAccessible = true
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
                LOGGER.info("Registered ClientPlayNetworking receiver dynamically using ${receiverMethod.name}")
            } else {
                LOGGER.error("Could not find register receiver method on ClientPlayNetworking")
            }

            // 7. Find send method
            sendMethod = networkingClass.methods.firstOrNull {
                it.name == "send" && it.parameterCount == 1 && it.parameterTypes[0].isAssignableFrom(payloadClass)
            } ?: networkingClass.methods.firstOrNull {
                it.name == "send" && it.parameterCount == 1
            }?.apply { isAccessible = true }
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

    private fun createIdentifier(namespace: String, path: String): Any {
        val idClass = findClass(
            "net.minecraft.resources.ResourceLocation",
            "net.minecraft.resources.Identifier",
            "net.minecraft.util.Identifier",
            "net.minecraft.class_2960"
        ) ?: throw ClassNotFoundException("Could not find Identifier/ResourceLocation class")

        val fromNsMethod = idClass.methods.firstOrNull {
            it.name in listOf("fromNamespaceAndPath", "of", "tryBuild", "tryCreate") && it.parameterCount == 2
        }
        if (fromNsMethod != null) {
            return fromNsMethod.invoke(null, namespace, path)
        }

        val ctor2 = idClass.declaredConstructors.firstOrNull {
            it.parameterCount == 2 && it.parameterTypes[0] == String::class.java && it.parameterTypes[1] == String::class.java
        }
        if (ctor2 != null) {
            ctor2.isAccessible = true
            return ctor2.newInstance(namespace, path)
        }

        val ctor1 = idClass.declaredConstructors.firstOrNull {
            it.parameterCount == 1 && it.parameterTypes[0] == String::class.java
        }
        if (ctor1 != null) {
            ctor1.isAccessible = true
            return ctor1.newInstance("$namespace:$path")
        }

        error("Could not construct Identifier for $namespace:$path")
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
