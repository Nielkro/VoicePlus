package ru.penik.voice.plus.util

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import ru.penik.voice.plus.plugins.VoicePlusConfig

object MetricsReporter {
    private val LOGGER = VoicePlusLogger.getLogger("VoicePlus-Metrics")
    private const val MOD_VERSION = "1.1.0"

    private val HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build()

    private var sessionStartTime: Long = 0L

    private val osType: String by lazy {
        val os = System.getProperty("os.name", "").lowercase()
        when {
            os.contains("win") -> "windows"
            os.contains("mac") -> "macos"
            os.contains("linux") || os.contains("nix") || os.contains("nux") || os.contains("aix") -> "linux"
            else -> "other"
        }
    }

    private val javaMajorVersion: String by lazy {
        val version = System.getProperty("java.version", "unknown")
        version.split(".").firstOrNull() ?: version
    }

    private fun getFullModVersion(mcVersion: String): String {
        return try {
            net.fabricmc.loader.api.FabricLoader.getInstance()
                .getModContainer("voice-plus-pv2svc")
                .map { it.metadata.version.friendlyString }
                .orElse("1.1.0-$mcVersion")
        } catch (_: Throwable) {
            "1.1.0-$mcVersion"
        }
    }

    fun reportLaunch(mcVersion: String) {
        if (!VoicePlusConfig.enableMetrics) return

        val url = VoicePlusConfig.metricsUrl
        if (url.isBlank() || url.contains("example.com")) return

        val fullVer = getFullModVersion(mcVersion)
        sendAsync(
            url,
            """
            {
                "event": "launch",
                "mod_version": "$fullVer",
                "mc_version": "$mcVersion",
                "java_version": "$javaMajorVersion",
                "os": "$osType",
                "uses_socks": ${VoicePlusConfig.socksHost.isNotBlank()}
            }
            """.trimIndent()
        )
    }

    fun startSession() {
        sessionStartTime = System.currentTimeMillis()
    }

    fun endSession(mcVersion: String) {
        if (!VoicePlusConfig.enableMetrics || sessionStartTime == 0L) return

        val durationSec = (System.currentTimeMillis() - sessionStartTime) / 1000
        sessionStartTime = 0L

        // Ignore quick reconnects (< 10 seconds)
        if (durationSec < 10) return

        val bucket = when {
            durationSec < 300 -> "under_5m"
            durationSec < 900 -> "5m_15m"
            durationSec < 3600 -> "15m_1h"
            durationSec < 10800 -> "1h_3h"
            else -> "over_3h"
        }

        val url = VoicePlusConfig.metricsUrl
        if (url.isBlank() || url.contains("example.com")) return

        val fullVer = getFullModVersion(mcVersion)
        sendAsync(
            url,
            """
            {
                "event": "session_ended",
                "mod_version": "$fullVer",
                "mc_version": "$mcVersion",
                "java_version": "$javaMajorVersion",
                "os": "$osType",
                "duration_bucket": "$bucket",
                "duration_seconds": $durationSec,
                "uses_socks": ${VoicePlusConfig.socksHost.isNotBlank()}
            }
            """.trimIndent()
        )
    }

    private fun sendAsync(url: String, json: String) {
        Thread({
            try {
                val request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "VoicePlus/$MOD_VERSION")
                    .timeout(Duration.ofSeconds(4))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build()

                HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.discarding())
            } catch (e: Exception) {
                if (VoicePlusConfig.debug == 1) {
                    LOGGER.warn("Metrics send error (ignored): ${e.message}")
                }
            }
        }, "VoicePlus-MetricsSender").apply {
            isDaemon = true
            start()
        }
    }
}
