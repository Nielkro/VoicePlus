package ru.penik.voice.plus.plugins

import ru.penik.voice.plus.util.VoicePlusLogger
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object SvcConnectionManager {
    private val LOGGER = VoicePlusLogger.getLogger("VoicePlus-SvcConnMgr")
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "VoicePlus-ConnectionChecker").apply { isDaemon = true }
    }

    private var lastSecretPacket: Any? = null
    private var checkFuture: java.util.concurrent.ScheduledFuture<*>? = null

    @JvmStatic
    fun onAuthenticate(secretPacket: Any) {
        lastSecretPacket = secretPacket

        VoicePlusConfig.load()
        if (!ProxyableVoicechatSocket.useSocks5 && VoicePlusConfig.socksHost.isNotBlank()) {
            // Cancel any existing checker
            checkFuture?.cancel(false)
            
            val timeout = VoicePlusConfig.bypassTimeoutMs
            
            LOGGER.info("Direct connection attempt detected. Scheduling SOCKS5 fallback check in $timeout ms...")
            checkFuture = scheduler.schedule({
                checkConnectionAndFallback()
            }, timeout, TimeUnit.MILLISECONDS)
        }
    }

    private fun checkConnectionAndFallback() {
        try {
            var connectedDirectly = false
            
            val clientManagerClass = Class.forName("de.maxhenkel.voicechat.voice.client.ClientManager")
            val getClientMethod = clientManagerClass.getMethod("getClient")
            val clientInstance = getClientMethod.invoke(null)
            if (clientInstance != null) {
                val getConnectionMethod = clientInstance.javaClass.getMethod("getConnection")
                val connectionInstance = getConnectionMethod.invoke(clientInstance)
                if (connectionInstance != null) {
                    val isInitializedMethod = connectionInstance.javaClass.getMethod("isInitialized")
                    val getAddressMethod = connectionInstance.javaClass.getMethod("getAddress")
                    val isInitialized = isInitializedMethod.invoke(connectionInstance) as Boolean
                    val address = getAddressMethod.invoke(connectionInstance) as java.net.InetAddress
                    
                    if (isInitialized) {
                        connectedDirectly = true
                    }
                }
            }
            
            if (connectedDirectly) {
                LOGGER.info("Direct SVC connection succeeded! SOCKS5 bypass not needed.")
            } else if (VoicePlusConfig.socksHost.isNotBlank()) {
                LOGGER.warn("Direct SVC connection failed/timed out. Switching to SOCKS5 and retrying...")
                fallbackToSocks5()
            }
        } catch (e: Exception) {
            LOGGER.error("Error checking connection status", e)
        }
    }

    private fun fallbackToSocks5() {
        val secretPacket = lastSecretPacket
        if (secretPacket == null) {
            LOGGER.error("Cannot fallback to SOCKS5: lastSecretPacket is null!")
            return
        }

        // Enable SOCKS5 for the custom socket
        ProxyableVoicechatSocket.useSocks5 = true

        try {
            val clientManagerClass = Class.forName("de.maxhenkel.voicechat.voice.client.ClientManager")
            val instanceMethod = clientManagerClass.getMethod("instance")
            val clientManagerInstance = instanceMethod.invoke(null)
            val authenticateMethod = clientManagerClass.getDeclaredMethod("authenticate", secretPacket.javaClass)
            authenticateMethod.isAccessible = true
            
            // Invoke authenticate on Main Thread/Minecraft Executor to avoid thread-safety issues
            net.minecraft.client.Minecraft.getInstance().execute {
                try {
                    LOGGER.info("Triggering re-authentication with SOCKS5 enabled...")
                    authenticateMethod.invoke(clientManagerInstance, secretPacket)
                } catch (e: Exception) {
                    LOGGER.error("Failed to invoke authenticate via reflection", e)
                }
            }
        } catch (e: Exception) {
            LOGGER.error("Failed to prepare SOCKS5 re-authentication", e)
        }
    }

    // Reset SOCKS5 state when leaving the server
    fun reset() {
        checkFuture?.cancel(false)
        checkFuture = null
        lastSecretPacket = null
        if (ProxyableVoicechatSocket.useSocks5) {
            LOGGER.info("Resetting SOCKS5 bypass flag to false.")
            ProxyableVoicechatSocket.useSocks5 = false
        }
    }
}
