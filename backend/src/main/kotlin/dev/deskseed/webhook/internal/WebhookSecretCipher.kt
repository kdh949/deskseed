package dev.deskseed.webhook.internal

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@ConfigurationProperties("deskseed.webhook.secret")
internal data class WebhookSecretProperties(
    var activeKeyVersion: String = "local-v1",
    var keys: Map<String, String> = emptyMap(),
) {
    fun decodedKey(version: String): ByteArray = try {
        Base64.getDecoder().decode(keys[version] ?: throw IllegalStateException("Webhook secret key is missing"))
            .also { require(it.size == 32) { "Webhook secret key must be 32 bytes" } }
    } catch (exception: IllegalArgumentException) {
        throw IllegalStateException("Webhook secret key is invalid", exception)
    }
}

internal data class EncryptedWebhookSecret(val ciphertext: ByteArray, val nonce: ByteArray, val keyVersion: String)

/** AES-GCM encryption for recoverable delivery keys; plaintext never leaves this boundary except to an HTTP signer. */
internal class WebhookSecretCipher(
    private val properties: WebhookSecretProperties,
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    init {
        key(properties.activeKeyVersion)
    }

    fun encrypt(secret: String, secretId: UUID): EncryptedWebhookSecret {
        val nonce = ByteArray(12).also(secureRandom::nextBytes)
        val keyVersion = properties.activeKeyVersion
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(keyVersion), GCMParameterSpec(128, nonce))
        cipher.updateAAD(secretId.toString().toByteArray(StandardCharsets.UTF_8))
        return EncryptedWebhookSecret(cipher.doFinal(secret.toByteArray(StandardCharsets.UTF_8)), nonce, keyVersion)
    }

    fun decrypt(value: EncryptedWebhookSecret, secretId: UUID): String = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(value.keyVersion), GCMParameterSpec(128, value.nonce))
        cipher.updateAAD(secretId.toString().toByteArray(StandardCharsets.UTF_8))
        String(cipher.doFinal(value.ciphertext), StandardCharsets.UTF_8)
    } catch (_: GeneralSecurityException) {
        throw WebhookSecretUnreadableException()
    } catch (_: IllegalArgumentException) {
        throw WebhookSecretUnreadableException()
    }

    private fun key(version: String) = SecretKeySpec(properties.decodedKey(version), "AES")

    private companion object { const val TRANSFORMATION = "AES/GCM/NoPadding" }
}

internal class WebhookSecretUnreadableException : RuntimeException(null, null, false, false)

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WebhookSecretProperties::class)
internal class WebhookSecretConfiguration {
    @Bean
    fun webhookSecretCipher(properties: WebhookSecretProperties, environment: Environment): WebhookSecretCipher {
        if (properties.keys.isNotEmpty()) return WebhookSecretCipher(properties)
        check(!environment.matchesProfiles("production")) { "Webhook secret key is required in production" }
        val ephemeral = ByteArray(32).also(SecureRandom()::nextBytes)
        return WebhookSecretCipher(
            WebhookSecretProperties(
                activeKeyVersion = "ephemeral-dev",
                keys = mapOf("ephemeral-dev" to Base64.getEncoder().encodeToString(ephemeral)),
            ),
        )
    }
}
