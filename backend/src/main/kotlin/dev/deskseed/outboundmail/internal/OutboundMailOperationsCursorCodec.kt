package dev.deskseed.outboundmail.internal

import dev.deskseed.outboundmail.OutboundMailIntentListQuery
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

@ConfigurationProperties("deskseed.mail.operations.cursor")
internal data class OutboundMailOperationsCursorProperties(
    val activeKeyId: String = "local-v1",
    val signingKeys: Map<String, String> = mapOf(
        "local-v1" to "deskseed-local-outbound-mail-operations-cursor-signing-key-change-before-production",
    ),
) {
    init {
        require(KEY_ID.matches(activeKeyId)) { "Active outbound mail cursor key ID is invalid" }
        require(signingKeys.containsKey(activeKeyId)) { "Active outbound mail cursor signing key is missing" }
        signingKeys.forEach { (keyId, secret) ->
            require(KEY_ID.matches(keyId)) { "Outbound mail cursor signing key ID is invalid" }
            require(secret.toByteArray(StandardCharsets.UTF_8).size >= MINIMUM_SECRET_BYTES) {
                "Outbound mail cursor signing keys must be at least $MINIMUM_SECRET_BYTES bytes"
            }
        }
    }

    private companion object {
        val KEY_ID = Regex("[A-Za-z0-9_-]{1,32}")
        const val MINIMUM_SECRET_BYTES = 32
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OutboundMailOperationsCursorProperties::class)
internal class OutboundMailOperationsCursorConfiguration

internal data class OutboundMailOperationsCursor(
    val queuedAt: Instant,
    val intentId: UUID,
)

@Component
internal class OutboundMailOperationsCursorCodec(
    private val properties: OutboundMailOperationsCursorProperties,
) {
    fun encode(query: OutboundMailIntentListQuery, cursor: OutboundMailOperationsCursor): String {
        val payload = listOf(
            VERSION,
            query.status?.name ?: ALL_STATUSES,
            cursor.queuedAt.toString(),
            cursor.intentId.toString(),
        ).joinToString(SEPARATOR)
        val encodedPayload = encodeBase64(payload.toByteArray(StandardCharsets.UTF_8))
        val keyId = properties.activeKeyId
        return listOf(keyId, encodedPayload, encodeBase64(signature(keyId, encodedPayload))).joinToString(ENVELOPE_SEPARATOR)
    }

    fun decode(query: OutboundMailIntentListQuery, cursor: String): OutboundMailOperationsCursor {
        val parts = cursor.split(ENVELOPE_SEPARATOR)
        if (parts.size != 3 || parts.any(String::isEmpty)) invalid()
        val (keyId, encodedPayload, encodedSignature) = parts
        val signingKey = properties.signingKeys[keyId] ?: invalid()
        val suppliedSignature = decodeBase64(encodedSignature)
        if (!MessageDigest.isEqual(signature(keyId, encodedPayload, signingKey), suppliedSignature)) invalid()
        val values = String(decodeBase64(encodedPayload), StandardCharsets.UTF_8).split(SEPARATOR)
        if (
            values.size != 4 ||
            values[0] != VERSION ||
            values[1] != (query.status?.name ?: ALL_STATUSES)
        ) {
            invalid()
        }
        return runCatching {
            OutboundMailOperationsCursor(Instant.parse(values[2]), UUID.fromString(values[3]))
        }.getOrElse { invalid() }
    }

    private fun signature(
        keyId: String,
        encodedPayload: String,
        secret: String = properties.signingKeys.getValue(keyId),
    ): ByteArray = Mac.getInstance(HMAC_ALGORITHM).run {
        init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), HMAC_ALGORITHM))
        doFinal("$VERSION$ENVELOPE_SEPARATOR$keyId$ENVELOPE_SEPARATOR$encodedPayload".toByteArray(StandardCharsets.UTF_8))
    }

    private fun encodeBase64(value: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    private fun decodeBase64(value: String): ByteArray = runCatching {
        Base64.getUrlDecoder().decode(value)
    }.getOrElse { invalid() }

    private fun invalid(): Nothing = throw IllegalArgumentException("Invalid outbound mail intent cursor")

    private companion object {
        const val VERSION = "v1"
        const val ALL_STATUSES = "ALL"
        const val SEPARATOR = "~"
        const val ENVELOPE_SEPARATOR = "."
        const val HMAC_ALGORITHM = "HmacSHA256"
    }
}
