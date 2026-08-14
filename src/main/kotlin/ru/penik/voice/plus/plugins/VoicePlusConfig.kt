package ru.penik.voice.plus.plugins

import java.io.File
import java.util.Properties

object VoicePlusConfig {
    var socksHost: String = ""
    var socksPort: Int = 1080
    var bypassTimeoutMs: Long = 5000

    fun load() {
        val configDir = File("config")
        if (!configDir.exists()) {
            configDir.mkdirs()
        }
        val configFile = File(configDir, "voiceplus.properties")
        if (configFile.exists()) {
            try {
                val props = Properties()
                configFile.inputStream().use { props.load(it) }
                socksHost = props.getProperty("socksHost", "")
                socksPort = props.getProperty("socksPort", "1080").toIntOrNull() ?: 1080
                bypassTimeoutMs = props.getProperty("bypassTimeoutMs", "5000").toLongOrNull() ?: 5000
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            try {
                val props = Properties()
                props.setProperty("socksHost", socksHost)
                props.setProperty("socksPort", socksPort.toString())
                props.setProperty("bypassTimeoutMs", bypassTimeoutMs.toString())
                configFile.outputStream().use { props.store(it, "VoicePlus SOCKS5 bypass config") }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
