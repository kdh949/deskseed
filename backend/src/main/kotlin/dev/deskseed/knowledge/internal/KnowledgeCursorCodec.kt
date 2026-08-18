package dev.deskseed.knowledge.internal

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@ConfigurationProperties("deskseed.knowledge.cursor")
internal data class KnowledgeCursorProperties(
    val activeKeyId: String = "local-v1",
    val signingKeys: Map<String, String> = mapOf(
        "local-v1" to "deskseed-local-knowledge-cursor-signing-key-change-before-production",
    ),
) {
    init {
        require(KEY_ID.matches(activeKeyId) && signingKeys.containsKey(activeKeyId)) {
            "Knowledge cursor active signing key is invalid"
        }
        signingKeys.forEach { (keyId, key) ->
            require(KEY_ID.matches(keyId) && key.toByteArray(StandardCharsets.UTF_8).size >= 32) {
                "Knowledge cursor signing key is invalid"
            }
        }
    }

    private companion object {
        val KEY_ID = Regex("[A-Za-z0-9_-]{1,32}")
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KnowledgeCursorProperties::class)
internal class KnowledgeCursorConfiguration

internal data class KnowledgeCursor(val createdAt: Instant, val articleId: UUID)

/** A signed cursor is bound to one audience/query/list scope and cannot be replayed elsewhere. */
@Component
internal class KnowledgeCursorCodec(
    private val properties: KnowledgeCursorProperties,
) {
    fun encode(scope: String, cursor: KnowledgeCursor): String {
        val payload = listOf(VERSION, scopeFingerprint(scope), cursor.createdAt, cursor.articleId).joinToString(SEPARATOR)
        val encodedPayload = encodeBase64(payload.toByteArray(StandardCharsets.UTF_8))
        val keyId = properties.activeKeyId
        return listOf(keyId, encodedPayload, encodeBase64(signature(keyId, encodedPayload))).joinToString(ENVELOPE_SEPARATOR)
    }

    fun decode(scope: String, value: String?): KnowledgeCursor? {
        if (value == null) return null
        if (value.length !in 24..1024) invalid()
        val parts = value.split(ENVELOPE_SEPARATOR)
        if (parts.size != 3 || parts.any(String::isEmpty)) invalid()
        val (keyId, encodedPayload, encodedSignature) = parts
        val key = properties.signingKeys[keyId] ?: invalid()
        if (!MessageDigest.isEqual(signature(keyId, encodedPayload, key), decodeBase64(encodedSignature))) invalid()
        val values = String(decodeBase64(encodedPayload), StandardCharsets.UTF_8).split(SEPARATOR)
        if (values.size != 4 || values[0] != VERSION || values[1] != scopeFingerprint(scope)) invalid()
        return runCatching { KnowledgeCursor(Instant.parse(values[2]), UUID.fromString(values[3])) }.getOrElse { invalid() }
    }

    private fun scopeFingerprint(scope: String): String = MessageDigest.getInstance("SHA-256")
        .digest(scope.toByteArray(StandardCharsets.UTF_8))
        .take(12)
        .joinToString("") { "%02x".format(it) }

    private fun signature(keyId: String, payload: String, key: String = properties.signingKeys.getValue(keyId)): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
            doFinal("$VERSION$ENVELOPE_SEPARATOR$keyId$ENVELOPE_SEPARATOR$payload".toByteArray(StandardCharsets.UTF_8))
        }

    private fun encodeBase64(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    private fun decodeBase64(value: String): ByteArray = runCatching { Base64.getUrlDecoder().decode(value) }
        .getOrElse { invalid() }

    private fun invalid(): Nothing = throw IllegalArgumentException("Knowledge cursor is invalid")

    private companion object {
        const val VERSION = "v1"
        const val SEPARATOR = "~"
        const val ENVELOPE_SEPARATOR = "."
    }
}
