package dev.deskseed.staffaccess.internal

import dev.deskseed.ticketing.StaffTicketSearchCursor
import java.nio.charset.StandardCharsets
import java.security.InvalidKeyException
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.springframework.stereotype.Component

/**
 * A search cursor binds a normalized-query digest, never the query itself.  This keeps
 * a protected search term out of the URL, response and browser history while still
 * rejecting a cursor replayed with different query/filter/sort input.
 */
@Component
internal class AgentTicketSearchCursorCodec(
    private val properties: AgentTicketCursorProperties,
) {
    fun encode(
        query: String,
        filters: AgentTicketSearchFilter,
        sort: String,
        cursor: StaffTicketSearchCursor,
    ): String {
        val payload = listOf(
            VERSION,
            queryDigest(query),
            filterFingerprint(filters),
            sort,
            cursor.snapshotAt.toString(),
            cursor.lastScore?.toString().orEmpty(),
            cursor.lastUpdatedAt?.toString().orEmpty(),
            cursor.lastTicketNumber.toString(),
        ).joinToString(SEPARATOR)
        val encodedPayload = encodeBase64(payload.toByteArray(StandardCharsets.UTF_8))
        val keyId = properties.activeKeyId
        return listOf(keyId, encodedPayload, encodeBase64(signature(keyId, encodedPayload))).joinToString(ENVELOPE_SEPARATOR)
    }

    fun decode(
        query: String,
        filters: AgentTicketSearchFilter,
        sort: String,
        encodedCursor: String,
    ): StaffTicketSearchCursor {
        val parts = encodedCursor.split(ENVELOPE_SEPARATOR)
        if (parts.size != 3 || parts.any(String::isEmpty)) throw IllegalArgumentException("Invalid ticket search cursor")
        val (keyId, encodedPayload, encodedSignature) = parts
        val signingKey = properties.signingKeys[keyId] ?: throw IllegalArgumentException("Invalid ticket search cursor")
        val suppliedSignature = decodeBase64(encodedSignature)
        val expectedSignature = signature(keyId, encodedPayload, signingKey)
        if (!MessageDigest.isEqual(expectedSignature, suppliedSignature)) {
            throw IllegalArgumentException("Invalid ticket search cursor")
        }
        val values = String(decodeBase64(encodedPayload), StandardCharsets.UTF_8).split(SEPARATOR)
        if (
            values.size != 8 || values[0] != VERSION || values[1] != queryDigest(query) ||
            values[2] != filterFingerprint(filters) || values[3] != sort
        ) {
            throw IllegalArgumentException("Ticket search cursor does not match the request")
        }
        return runCatching {
            StaffTicketSearchCursor(
                snapshotAt = Instant.parse(values[4]),
                lastScore = values[5].takeIf(String::isNotEmpty)?.toInt(),
                lastUpdatedAt = values[6].takeIf(String::isNotEmpty)?.let(Instant::parse),
                lastTicketNumber = values[7].toLong(),
            )
        }.getOrElse { throw IllegalArgumentException("Invalid ticket search cursor") }
    }

    private fun queryDigest(query: String): String = sha256(query.trim())

    private fun filterFingerprint(filters: AgentTicketSearchFilter): String = sha256(
        listOf(
            filters.status?.name.orEmpty(),
            filters.priority?.name.orEmpty(),
            filters.groupId?.toString().orEmpty(),
            filters.assigneeId.orEmpty(),
            filters.slaState?.name.orEmpty(),
        ).joinToString("|"),
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun signature(
        keyId: String,
        encodedPayload: String,
        signingKey: String = properties.signingKeys.getValue(keyId),
    ): ByteArray = try {
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
    }.getOrElse { throw IllegalArgumentException("Invalid ticket search cursor") }

    private companion object {
        const val VERSION = "v1"
        const val SEPARATOR = "~"
        const val ENVELOPE_SEPARATOR = "."
        const val HMAC_ALGORITHM = "HmacSHA256"
    }
}
