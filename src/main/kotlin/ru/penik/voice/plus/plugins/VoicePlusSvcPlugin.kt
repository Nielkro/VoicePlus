package ru.penik.voice.plus.plugins

import de.maxhenkel.voicechat.api.VoicechatPlugin
import de.maxhenkel.voicechat.api.events.EventRegistration
import de.maxhenkel.voicechat.api.events.ClientVoicechatInitializationEvent

class VoicePlusSvcPlugin : VoicechatPlugin {
    override fun getPluginId(): String = "voice-plus-socks5"

    override fun registerEvents(registration: EventRegistration) {
        registration.registerEvent(ClientVoicechatInitializationEvent::class.java) { event ->
            event.setSocketImplementation(ProxyableVoicechatSocket())
        }
    }
}
