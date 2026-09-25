package ru.penik.voice.plus.util

import net.minecraft.client.Minecraft
import java.util.UUID

object ProfileUtils {
    fun getId(profile: Any?): UUID? {
        if (profile == null) return null
        return try {
            val method = profile.javaClass.methods.firstOrNull { it.name in listOf("getId", "id") && it.parameterCount == 0 }
            method?.invoke(profile) as? UUID
        } catch (e: Exception) {
            null
        }
    }

    fun getName(profile: Any?): String {
        if (profile == null) return ""
        return try {
            val method = profile.javaClass.methods.firstOrNull { it.name in listOf("getName", "name") && it.parameterCount == 0 }
            (method?.invoke(profile) as? String) ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    fun getLocalPlayerUuid(): UUID? {
        val minecraft = Minecraft.getInstance()
        minecraft.player?.uuid?.let { return it }
        val user = minecraft.user ?: return null
        return try {
            val method = user.javaClass.methods.firstOrNull { it.name in listOf("getProfileId", "profileId") && it.parameterCount == 0 }
            method?.invoke(user) as? UUID
        } catch (e: Exception) {
            null
        }
    }
}
