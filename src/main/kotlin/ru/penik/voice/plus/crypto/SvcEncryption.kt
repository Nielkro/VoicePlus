package ru.penik.voice.plus.crypto

import java.nio.ByteBuffer
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object SvcEncryption {
    private const val CIPHER = "AES/GCM/NoPadding"
    private const val IV_SIZE = 12
    private const val TAG_LEN_BITS = 128

    fun encrypt(data: ByteArray, secret: ByteArray): ByteArray {
        val keySpec = SecretKeySpec(secret, "AES")
        // Generate random IV
        val iv = ByteArray(IV_SIZE)
        java.security.SecureRandom().nextBytes(iv)

        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(TAG_LEN_BITS, iv))
        val encrypted = cipher.doFinal(data)

        // Prepend IV: [IV (12 bytes)] + [Encrypted Content]
        val payload = ByteArray(IV_SIZE + encrypted.size)
        System.arraycopy(iv, 0, payload, 0, IV_SIZE)
        System.arraycopy(encrypted, 0, payload, IV_SIZE, encrypted.size)
        return payload
    }

    fun decrypt(payload: ByteArray, secret: ByteArray): ByteArray {
        if (payload.size < IV_SIZE) return ByteArray(0)
        try {
            val keySpec = SecretKeySpec(secret, "AES")

            val iv = ByteArray(IV_SIZE)
            System.arraycopy(payload, 0, iv, 0, IV_SIZE)

            val encryptedData = ByteArray(payload.size - IV_SIZE)
            System.arraycopy(payload, IV_SIZE, encryptedData, 0, encryptedData.size)

            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(TAG_LEN_BITS, iv))
            return cipher.doFinal(encryptedData)
        } catch (e: Exception) {
            // Ignore decryption failure due to wrong secret or tag mismatch
            return ByteArray(0)
        }
    }

    fun bytesToUuid(bytes: ByteArray): UUID {
        val buffer = ByteBuffer.wrap(bytes)
        return UUID(buffer.long, buffer.long)
    }

    fun uuidToBytes(uuid: UUID): ByteArray {
        val buffer = ByteBuffer.allocate(16)
        buffer.putLong(uuid.mostSignificantBits)
        buffer.putLong(uuid.leastSignificantBits)
        return buffer.array()
    }

    // Plasmo Voice AES/CBC/PKCS5Padding encryption helpers
    fun encryptPv(data: ByteArray, keyBytes: ByteArray): ByteArray {
        try {
            val keySpec = SecretKeySpec(keyBytes, "AES")
            val ivBytes = ByteArray(16)
            java.security.SecureRandom().nextBytes(ivBytes)
            val ivSpec = javax.crypto.spec.IvParameterSpec(ivBytes)

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
            val encrypted = cipher.doFinal(data)

            val result = ByteArray(16 + encrypted.size)
            System.arraycopy(ivBytes, 0, result, 0, 16)
            System.arraycopy(encrypted, 0, result, 16, encrypted.size)
            return result
        } catch (e: Exception) {
            return data
        }
    }

    fun decryptPv(encrypted: ByteArray, keyBytes: ByteArray): ByteArray {
        if (encrypted.size < 16) return encrypted
        try {
            val keySpec = SecretKeySpec(keyBytes, "AES")
            
            val ivBytes = ByteArray(16)
            System.arraycopy(encrypted, 0, ivBytes, 0, 16)
            val ivSpec = javax.crypto.spec.IvParameterSpec(ivBytes)

            val actualEncrypted = ByteArray(encrypted.size - 16)
            System.arraycopy(encrypted, 16, actualEncrypted, 0, actualEncrypted.size)

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            return cipher.doFinal(actualEncrypted)
        } catch (e: Exception) {
            return encrypted
        }
    }
}
