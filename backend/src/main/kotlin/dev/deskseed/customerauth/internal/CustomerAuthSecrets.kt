package dev.deskseed.customerauth.internal

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal object CustomerAuthSecrets {
    private val secureRandom = SecureRandom()

    fun randomBearer(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
        ByteArray(32).also(secureRandom::nextBytes),
    )

    fun digest(value: String): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)),
    )

    fun fingerprint(keyBase64: String, value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(decodeKey(keyBase64), "HmacSHA256"))
        return HexFormat.of().formatHex(mac.doFinal(value.toByteArray(StandardCharsets.UTF_8)))
    }

    fun csrf(keyBase64: String, rawSession: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(decodeKey(keyBase64), "HmacSHA256"))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            mac.doFinal(("csrf:$rawSession").toByteArray(StandardCharsets.UTF_8)),
        )
    }

    fun constantTimeEquals(left: String, right: String): Boolean = MessageDigest.isEqual(
        left.toByteArray(StandardCharsets.US_ASCII),
        right.toByteArray(StandardCharsets.US_ASCII),
    )

    private fun decodeKey(value: String): ByteArray = Base64.getDecoder().decode(value).also {
        require(it.size >= 32) { "customer authentication keys must contain at least 32 bytes" }
    }
}
