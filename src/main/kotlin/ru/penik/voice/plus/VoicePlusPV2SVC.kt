package ru.penik.voice.plus

import net.fabricmc.api.ClientModInitializer
import ru.penik.voice.plus.util.VoicePlusLogger
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import ru.penik.voice.plus.adapters.inbound.SvcInboundAdapter
import ru.penik.voice.plus.adapters.outbound.PvOutboundAdapter
import ru.penik.voice.plus.core.VoiceHub

object VoicePlusPV2SVC : ClientModInitializer {
	const val MOD_ID: String = "voice-plus-pv2svc"

	private val LOGGER = VoicePlusLogger.getLogger(MOD_ID)

	// SVC->PV bridge composition: SVC inbound adapter <-> VoiceHub <-> PV outbound adapter.
	private val inbound = SvcInboundAdapter()
	private val outbound = PvOutboundAdapter()
	private val hub = VoiceHub(inbound, outbound)

	override fun onInitializeClient() {
		LOGGER.info("Initializing Voice Plus (PV to SVC Client Bridge)...")

		try {
			val mcVer = net.minecraft.client.Minecraft.getInstance()?.launchedVersion ?: "unknown"
			ru.penik.voice.plus.util.MetricsReporter.reportLaunch(mcVer)
		} catch (_: Throwable) {}

		// Bridge PV control-plane into the SVC side:
		outbound.onConnectionEstablished = { playerUuid, secretBytes ->
			inbound.provideSecret(playerUuid, secretBytes)
			inbound.syncPlayerStates()
			ru.penik.voice.plus.util.MetricsReporter.startSession()
		}
		outbound.onPlayerDiscovered = { uuid, nick ->
			inbound.registerAndInjectPlayer(uuid, nick)
		}

		// Wire routing (mic in->out, audio out->in) and register PV TCP receiver + RSA keys.
		hub.wire()
		outbound.initialize()

		// Control the local proxy server lifecycle on Join/Disconnect
		ClientPlayConnectionEvents.JOIN.register { handler, sender, client ->
			// Local proxy server is now started lazily when PV connection is established
		}

		ClientPlayConnectionEvents.DISCONNECT.register { handler, client ->
			LOGGER.info("Disconnected from server, stopping UDP proxy clients and virtual servers...")
			inbound.stop()
			outbound.disconnect()
			ru.penik.voice.plus.plugins.SvcConnectionManager.reset()
			val mcVer = client?.launchedVersion ?: "unknown"
			ru.penik.voice.plus.util.MetricsReporter.endSession(mcVer)
		}
	}
}
