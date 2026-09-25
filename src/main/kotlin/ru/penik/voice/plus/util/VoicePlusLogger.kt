package ru.penik.voice.plus.util

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import ru.penik.voice.plus.plugins.VoicePlusConfig

class VoicePlusLogger(private val slf4j: Logger) {

    fun info(message: String) {
        if (VoicePlusConfig.debug == 1) {
            slf4j.info(message)
        }
    }

    fun info(message: String, vararg args: Any?) {
        if (VoicePlusConfig.debug == 1) {
            slf4j.info(message, *args)
        }
    }

    fun debug(message: String) {
        if (VoicePlusConfig.debug == 1) {
            slf4j.debug(message)
        }
    }

    fun debug(message: String, vararg args: Any?) {
        if (VoicePlusConfig.debug == 1) {
            slf4j.debug(message, *args)
        }
    }

    fun warn(message: String) {
        if (VoicePlusConfig.debug == 1) {
            slf4j.warn(message)
        }
    }

    fun warn(message: String, t: Throwable) {
        if (VoicePlusConfig.debug == 1) {
            slf4j.warn(message, t)
        }
    }

    fun error(message: String) {
        if (VoicePlusConfig.debug == 1) {
            slf4j.error(message)
        }
    }

    fun error(message: String, t: Throwable) {
        if (VoicePlusConfig.debug == 1) {
            slf4j.error(message, t)
        }
    }

    companion object {
        fun getLogger(name: String): VoicePlusLogger = VoicePlusLogger(LoggerFactory.getLogger(name))
        fun getLogger(clazz: Class<*>): VoicePlusLogger = VoicePlusLogger(LoggerFactory.getLogger(clazz))
    }
}
