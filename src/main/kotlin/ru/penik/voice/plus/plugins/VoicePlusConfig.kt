package ru.penik.voice.plus.plugins

import java.io.File
import java.util.Properties

object VoicePlusConfig {
    var socksHost: String = ""
    var socksPort: Int = 1080
    var socksUser: String = ""
    var socksPassword: String = ""
    var bypassTimeoutMs: Long = 5000
    var debug: Int = 0

    init {
        load()
    }

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
                socksUser = props.getProperty("socksUser", "")
                socksPassword = props.getProperty("socksPassword", "")
                bypassTimeoutMs = props.getProperty("bypassTimeoutMs", "5000").toLongOrNull() ?: 5000
                debug = props.getProperty("debug", "0").toIntOrNull() ?: if (props.getProperty("debug", "false").toBoolean()) 1 else 0
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            try {
                val props = Properties()
                props.setProperty("socksHost", socksHost)
                props.setProperty("socksPort", socksPort.toString())
                props.setProperty("socksUser", socksUser)
                props.setProperty("socksPassword", socksPassword)
                props.setProperty("bypassTimeoutMs", bypassTimeoutMs.toString())
                props.setProperty("debug", debug.toString())
                configFile.outputStream().use { props.store(it, "VoicePlus config") }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
