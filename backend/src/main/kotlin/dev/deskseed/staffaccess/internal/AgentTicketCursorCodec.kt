package dev.deskseed.staffaccess.internal

import dev.deskseed.ticketing.DefaultStaffView
import dev.deskseed.ticketing.StaffTicketCursor
import dev.deskseed.ticketing.StaffTicketListFilter
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.InvalidKeyException
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@ConfigurationProperties("deskseed.agent-ticket-read.cursor")
internal data class AgentTicketCursorProperties(
    val activeKeyId: String,
    val signingKeys: Map<String, String>,
) {
    init {
        require(KEY_ID_PATTERN.matches(activeKeyId)) { "Active cursor key ID is invalid" }
        require(signingKeys.containsKey(activeKeyId)) { "Active cursor signing key is missing" }
        signingKeys.forEach { (keyId, secret) ->
            require(KEY_ID_PATTERN.matches(keyId)) { "Cursor signing key ID is invalid" }
            require(secret.toByteArray(StandardCharsets.UTF_8).size >= MINIMUM_SECRET_BYTES) {
                "Cursor signing keys must be at least $MINIMUM_SECRET_BYTES bytes"
            }
        }
    }

    private companion object {
        val KEY_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,32}")
        const val MINIMUM_SECRET_BYTES = 32
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgentTicketCursorProperties::class)
internal class AgentTicketCursorConfiguration

@Component
internal class AgentTicketCursorCodec(
    private val properties: AgentTicketCursorProperties,
) {
    fun encode(view: DefaultStaffView, filters: StaffTicketListFilter, cursor: StaffTicketCursor): String =
        encode(view.key, filters, cursor)

    fun encode(viewKey: String, filters: StaffTicketListFilter, cursor: StaffTicketCursor): String {
        val payload = listOf(
            VERSION,
            viewKey,
            fingerprint(filters),
            cursor.updatedAt.toString(),
            cursor.ticketNumber.toString(),
        ).joinToString(SEPARATOR)
        val encodedPayload = encodeBase64(payload.toByteArray(StandardCharsets.UTF_8))
        val keyId = properties.activeKeyId
        return listOf(keyId, encodedPayload, encodeBase64(signature(keyId, encodedPayload))).joinToString(ENVELOPE_SEPARATOR)
    }

    fun decode(view: DefaultStaffView, filters: StaffTicketListFilter, cursor: String): StaffTicketCursor =
        decode(view.key, filters, cursor)

    fun decode(viewKey: String, filters: StaffTicketListFilter, cursor: String): StaffTicketCursor {
        val parts = cursor.split(ENVELOPE_SEPARATOR)
        if (parts.size != 3 || parts.any(String::isEmpty)) throw IllegalArgumentException("Invalid ticket cursor")
        val (keyId, encodedPayload, encodedSignature) = parts
        val signingKey = properties.signingKeys[keyId] ?: throw IllegalArgumentException("Invalid ticket cursor")
        val suppliedSignature = decodeBase64(encodedSignature)
        val expectedSignature = signature(keyId, encodedPayload, signingKey)
        if (!MessageDigest.isEqual(expectedSignature, suppliedSignature)) {
            throw IllegalArgumentException("Invalid ticket cursor")
        }
        val decoded = String(decodeBase64(encodedPayload), StandardCharsets.UTF_8)
        val values = decoded.split(SEPARATOR)
        if (values.size != 5 || values[0] != VERSION || values[1] != viewKey || values[2] != fingerprint(filters)) {
            throw IllegalArgumentException("Ticket cursor does not match the selected view and filters")
        }
        return runCatching {
            StaffTicketCursor(Instant.parse(values[3]), values[4].toLong())
        }.getOrElse { throw IllegalArgumentException("Invalid ticket cursor") }
    }

    private fun fingerprint(filters: StaffTicketListFilter): String {
        val canonical = listOf(
            filters.status?.name.orEmpty(),
            filters.priority?.name.orEmpty(),
            filters.groupId?.toString().orEmpty(),
            filters.assignee.orEmpty(),
            filters.slaState?.name.orEmpty(),
        ).joinToString("|")
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(it) }
    }

    private fun signature(keyId: String, encodedPayload: String, signingKey: String = properties.signingKeys.getValue(keyId)): ByteArray = try {
        Mac.getInstance(HMAC_ALGORITHM).run {
            init(SecretKeySpec(signingKey.toByteArray(StandardCharsets.UTF_8), HMAC_ALGORITHM))
            doFinal("$VERSION$ENVELOPE_SEPARATOR$keyId$ENVELOPE_SEPARATOR$encodedPayload".toByteArray(StandardCharsets.UTF_8))
        }
    } catch (exception: InvalidKeyException) {
        throw IllegalStateException("Cursor signing key is invalid", exception)
    }

    private fun encodeBase64(value: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    private fun decodeBase64(value: String): ByteArray = runCatching {
        Base64.getUrlDecoder().decode(value)
    }.getOrElse { throw IllegalArgumentException("Invalid ticket cursor") }

    private companion object {
        const val VERSION = "v2"
        const val SEPARATOR = "~"
        const val ENVELOPE_SEPARATOR = "."
        const val HMAC_ALGORITHM = "HmacSHA256"
    }
}
