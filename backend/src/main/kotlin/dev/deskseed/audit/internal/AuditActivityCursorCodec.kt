package dev.deskseed.audit.internal

import dev.deskseed.audit.AuditActivityFilter
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.Duration
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@ConfigurationProperties("deskseed.audit.explorer.cursor")
internal data class AuditActivityCursorProperties(
    val activeKeyId: String,
    val signingKeys: Map<String, String>,
) {
    init {
        require(KEY_ID.matches(activeKeyId)) { "Active audit cursor key ID is invalid" }
        require(signingKeys.containsKey(activeKeyId)) { "Active audit cursor signing key is missing" }
        signingKeys.forEach { (keyId, secret) ->
            require(KEY_ID.matches(keyId)) { "Audit cursor signing key ID is invalid" }
            require(secret.toByteArray(StandardCharsets.UTF_8).size >= 32) {
                "Audit cursor signing keys must be at least 32 bytes"
            }
        }
    }

    private companion object {
        val KEY_ID = Regex("[A-Za-z0-9_-]{1,32}")
    }
}

@ConfigurationProperties("deskseed.audit.explorer.reveal")
internal data class AuditExplorerRevealProperties(
    val recentAuthentication: Duration = Duration.ofMinutes(15),
    val mfaRequired: Boolean = false,
) {
    init {
        require(!recentAuthentication.isNegative && !recentAuthentication.isZero) {
            "Audit reveal recent-authentication duration must be positive"
        }
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuditActivityCursorProperties::class, AuditExplorerRevealProperties::class)
internal class AuditActivityCursorConfiguration

internal data class AuditActivityCursor(
    val snapshotAt: Instant,
    val snapshotId: UUID,
    val lastOccurredAt: Instant,
    val lastId: UUID,
)

@Component
internal class AuditActivityCursorCodec(
    private val properties: AuditActivityCursorProperties,
) {
    fun encode(filters: AuditActivityFilter, cursor: AuditActivityCursor): String {
        val payload = listOf(
            VERSION,
            filterFingerprint(filters),
            cursor.snapshotAt.toString(),
            cursor.snapshotId.toString(),
            cursor.lastOccurredAt.toString(),
            cursor.lastId.toString(),
        ).joinToString(SEPARATOR)
        val encodedPayload = encode(payload.toByteArray(StandardCharsets.UTF_8))
        val keyId = properties.activeKeyId
        return listOf(keyId, encodedPayload, encode(sign(keyId, encodedPayload))).joinToString(ENVELOPE)
    }

    fun decode(filters: AuditActivityFilter, encodedCursor: String): AuditActivityCursor {
        val envelope = encodedCursor.split(ENVELOPE)
        if (envelope.size != 3 || envelope.any(String::isBlank)) invalid()
        val keyId = envelope[0]
        val signingKey = properties.signingKeys[keyId] ?: invalid()
        val encodedPayload = envelope[1]
        if (!MessageDigest.isEqual(sign(keyId, encodedPayload, signingKey), decode(envelope[2]))) invalid()
        val values = String(decode(encodedPayload), StandardCharsets.UTF_8).split(SEPARATOR)
        if (values.size != 6 || values[0] != VERSION || values[1] != filterFingerprint(filters)) invalid()
        return runCatching {
            AuditActivityCursor(
                snapshotAt = Instant.parse(values[2]),
                snapshotId = UUID.fromString(values[3]),
                lastOccurredAt = Instant.parse(values[4]),
                lastId = UUID.fromString(values[5]),
            )
        }.getOrElse { invalid() }
    }

    fun filterFingerprint(filters: AuditActivityFilter): String {
        val canonical = listOf(
            filters.from?.toString().orEmpty(),
            filters.to?.toString().orEmpty(),
            filters.ledger?.name.orEmpty(),
            filters.action.orEmpty(),
            filters.actorType?.name.orEmpty(),
            filters.actorId?.toString().orEmpty(),
            filters.ticketNumber?.toString().orEmpty(),
            filters.groupId?.toString().orEmpty(),
            filters.field.orEmpty(),
            filters.source.orEmpty(),
            filters.outcome?.name.orEmpty(),
            filters.requestId.orEmpty(),
            filters.correlationId.orEmpty(),
            filters.searchFingerprint.orEmpty(),
        ).joinToString("|")
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .take(16)
            .joinToString("") { "%02x".format(it) }
    }

    private fun sign(
        keyId: String,
        payload: String,
        secret: String = properties.signingKeys.getValue(keyId),
    ): ByteArray = Mac.getInstance(HMAC).run {
        init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), HMAC))
        doFinal("$VERSION.$keyId.$payload".toByteArray(StandardCharsets.UTF_8))
    }

    private fun encode(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    private fun decode(value: String): ByteArray = runCatching {
        Base64.getUrlDecoder().decode(value)
    }.getOrElse { invalid() }

    private fun invalid(): Nothing = throw IllegalArgumentException("Invalid audit activity cursor")

    private companion object {
        const val VERSION = "v1"
        const val SEPARATOR = "~"
        const val ENVELOPE = "."
        const val HMAC = "HmacSHA256"
    }
}
