package ru.penik.voice.plus.util

import io.netty.buffer.ByteBuf
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
//? if <26.1 {
import net.minecraft.resources.ResourceLocation
//?} else {
/*import net.minecraft.resources.Identifier*/
//?}

class PlasmoVoicePayload(val data: ByteArray) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<PlasmoVoicePayload> = TYPE

    companion object {
        //? if <26.1 {
        val ID: ResourceLocation = ResourceLocation.fromNamespaceAndPath("plasmo", "voice/v2")
        //?} else {
        /*val ID: Identifier = Identifier.fromNamespaceAndPath("plasmo", "voice/v2")*/
        //?}

        val TYPE: CustomPacketPayload.Type<PlasmoVoicePayload> = CustomPacketPayload.Type(ID)

        val CODEC: StreamCodec<ByteBuf, PlasmoVoicePayload> = object : StreamCodec<ByteBuf, PlasmoVoicePayload> {
            override fun encode(buf: ByteBuf, payload: PlasmoVoicePayload) {
                buf.writeBytes(payload.data)
            }

            override fun decode(buf: ByteBuf): PlasmoVoicePayload {
                val bytes = ByteArray(buf.readableBytes())
                buf.readBytes(bytes)
                return PlasmoVoicePayload(bytes)
            }
        }
    }
}

object FabricNetworkBridge {
    private val LOGGER = VoicePlusLogger.getLogger("VoicePlus-NetworkBridge")

    fun init(onPacketReceived: (ByteArray) -> Unit) {
        try {
            //? if <26.1 {
            PayloadTypeRegistry.playS2C().register(PlasmoVoicePayload.TYPE, PlasmoVoicePayload.CODEC)
            PayloadTypeRegistry.playC2S().register(PlasmoVoicePayload.TYPE, PlasmoVoicePayload.CODEC)
            PayloadTypeRegistry.configurationS2C().register(PlasmoVoicePayload.TYPE, PlasmoVoicePayload.CODEC)
            PayloadTypeRegistry.configurationC2S().register(PlasmoVoicePayload.TYPE, PlasmoVoicePayload.CODEC)
            //?} else {
            /*PayloadTypeRegistry.clientboundPlay().register(PlasmoVoicePayload.TYPE, PlasmoVoicePayload.CODEC)
            PayloadTypeRegistry.serverboundPlay().register(PlasmoVoicePayload.TYPE, PlasmoVoicePayload.CODEC)
            PayloadTypeRegistry.clientboundConfiguration().register(PlasmoVoicePayload.TYPE, PlasmoVoicePayload.CODEC)
            PayloadTypeRegistry.serverboundConfiguration().register(PlasmoVoicePayload.TYPE, PlasmoVoicePayload.CODEC)*/
            //?}

            ClientPlayNetworking.registerGlobalReceiver(PlasmoVoicePayload.TYPE) { payload, _ ->
                onPacketReceived(payload.data)
            }
            LOGGER.info("Successfully registered Plasmo Voice network payloads and receiver")
        } catch (e: Throwable) {
            LOGGER.error("Failed to initialize FabricNetworkBridge", e)
        }
    }

    fun send(bytes: ByteArray) {
        try {
            ClientPlayNetworking.send(PlasmoVoicePayload(bytes))
        } catch (e: Exception) {
            LOGGER.error("Failed to send packet via FabricNetworkBridge", e)
        }
    }
}
