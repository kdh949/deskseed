package dev.deskseed.portal.internal

import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal data class CustomerRequestCursor(val updatedAt: Instant, val ticketNumber: Long)

@Component
internal class CustomerRequestCursorCodec(
    private val properties: CustomerPortalSecurityProperties,
) {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(cursor: CustomerRequestCursor): String {
        val payload = encoder.encodeToString(
            "v1:${cursor.updatedAt.toEpochMilli()}:${cursor.ticketNumber}".toByteArray(StandardCharsets.UTF_8),
        )
        return "$payload.${encoder.encodeToString(sign(payload))}"
    }

    fun decode(value: String?): CustomerRequestCursor? {
        if (value == null) return null
        if (value.length !in 16..512) throw IllegalArgumentException("Customer request cursor is invalid")
        val parts = value.split('.')
        if (parts.size != 2) throw IllegalArgumentException("Customer request cursor is invalid")
        val supplied = runCatching { decoder.decode(parts[1]) }.getOrNull()
            ?: throw IllegalArgumentException("Customer request cursor is invalid")
        if (!MessageDigest.isEqual(sign(parts[0]), supplied)) {
            throw IllegalArgumentException("Customer request cursor is invalid")
        }
        return runCatching {
            val decoded = String(decoder.decode(parts[0]), StandardCharsets.UTF_8).split(':')
            require(decoded.size == 3 && decoded[0] == "v1")
            CustomerRequestCursor(Instant.ofEpochMilli(decoded[1].toLong()), decoded[2].toLong())
        }.getOrElse { throw IllegalArgumentException("Customer request cursor is invalid") }
    }

    private fun sign(value: String): ByteArray = Mac.getInstance("HmacSHA256").run {
        val key = Base64.getDecoder().decode(properties.claimSigningKey)
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(value.toByteArray(StandardCharsets.UTF_8))
    }
}
