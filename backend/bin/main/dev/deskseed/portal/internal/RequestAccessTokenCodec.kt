package dev.deskseed.portal.internal

import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

@Component
internal class RequestAccessTokenCodec(
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    fun issue(): IssuedAccessToken {
        val bytes = ByteArray(TOKEN_BYTES)
        secureRandom.nextBytes(bytes)
        val raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        return IssuedAccessToken(raw = raw, hash = hash(raw))
    }

    fun hash(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString(separator = "") { "%02x".format(it) }
    }

    companion object {
        private const val TOKEN_BYTES = 32
    }
}

internal data class IssuedAccessToken(
    val raw: String,
    val hash: String,
)
