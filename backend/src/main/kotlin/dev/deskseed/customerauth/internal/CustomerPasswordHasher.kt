package dev.deskseed.customerauth.internal

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.stereotype.Component

internal class CustomerPasswordHash private constructor(
    val encoded: String,
) {
    override fun equals(other: Any?): Boolean = other is CustomerPasswordHash && encoded == other.encoded

    override fun hashCode(): Int = encoded.hashCode()

    override fun toString(): String = "[PROTECTED CUSTOMER PASSWORD HASH]"

    companion object {
        fun fromEncoded(encoded: String): CustomerPasswordHash {
            require(encoded.startsWith("${'$'}argon2id${'$'}")) { "customer password hash must use Argon2id" }
            require(encoded.length in 20..255 && encoded.none(Char::isISOControl)) {
                "customer password hash is invalid"
            }
            return CustomerPasswordHash(encoded)
        }
    }
}

/** Dedicated customer credential hasher. The staff authentication BCrypt bean remains unchanged. */
@Component
internal class CustomerPasswordHasher {
    private val encoder = Argon2PasswordEncoder(
        SALT_LENGTH,
        HASH_LENGTH,
        PARALLELISM,
        MEMORY_KIB,
        ITERATIONS,
    )

    fun encode(rawPassword: String): CustomerPasswordHash {
        validate(rawPassword)
        return CustomerPasswordHash.fromEncoded(requireNotNull(encoder.encode(rawPassword)))
    }

    fun matches(rawPassword: String, encoded: CustomerPasswordHash): Boolean =
        encoder.matches(rawPassword, encoded.encoded)

    private fun validate(rawPassword: String) {
        val codePointCount = rawPassword.codePointCount(0, rawPassword.length)
        require(codePointCount in MIN_LENGTH..MAX_LENGTH) {
            "customer password must contain 12 to 128 characters"
        }
        require(rawPassword.codePoints().noneMatch(Character::isISOControl)) {
            "customer password must not contain control characters"
        }
    }

    private companion object {
        private const val SALT_LENGTH = 16
        private const val HASH_LENGTH = 32
        private const val PARALLELISM = 1
        private const val MEMORY_KIB = 19 * 1024
        private const val ITERATIONS = 2
        private const val MIN_LENGTH = 12
        private const val MAX_LENGTH = 128
    }
}
