package dev.deskseed.outboundmail.internal

import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal data class ProtectedMailContent(
    val ciphertext: ByteArray,
    val nonce: ByteArray,
    val keyVersion: String,
)

internal class ProtectedMailContentUnreadableException : RuntimeException(null, null, false, false)

/**
 * Keeps bearer links out of plaintext outbox storage. Key rotation is versioned so queued mail remains deliverable.
 */
internal class ProtectedMailContentCipher(
    private val properties: ProtectedMailContentProperties,
    private val validateActiveKeyAtStartup: Boolean,
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    init {
        if (validateActiveKeyAtStartup) key(properties.activeKeyVersion)
    }

    fun encrypt(plaintext: String, intentId: UUID): ProtectedMailContent {
        val keyVersion = properties.activeKeyVersion
        val key = key(keyVersion)
        val nonce = ByteArray(12).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD(intentId.toString().toByteArray(StandardCharsets.UTF_8))
        return ProtectedMailContent(
            ciphertext = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8)),
            nonce = nonce,
            keyVersion = keyVersion,
        )
    }

    fun decrypt(content: ProtectedMailContent, intentId: UUID): String {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(content.keyVersion), GCMParameterSpec(128, content.nonce))
            cipher.updateAAD(intentId.toString().toByteArray(StandardCharsets.UTF_8))
            return String(cipher.doFinal(content.ciphertext), StandardCharsets.UTF_8)
        } catch (_: GeneralSecurityException) {
            throw ProtectedMailContentUnreadableException()
        } catch (_: IllegalArgumentException) {
            throw ProtectedMailContentUnreadableException()
        } catch (_: IllegalStateException) {
            throw ProtectedMailContentUnreadableException()
        }
    }

    private fun key(version: String): SecretKeySpec {
        return SecretKeySpec(properties.decodedKey(version), "AES")
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
