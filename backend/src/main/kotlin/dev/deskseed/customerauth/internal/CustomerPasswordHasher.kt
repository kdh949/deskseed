package dev.deskseed.customerauth.internal

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.stereotype.Component
import java.util.Base64

internal class CustomerPasswordHash private constructor(
    val encoded: String,
) {
    override fun equals(other: Any?): Boolean = other is CustomerPasswordHash && encoded == other.encoded

    override fun hashCode(): Int = encoded.hashCode()

    override fun toString(): String = "[PROTECTED CUSTOMER PASSWORD HASH]"

    fun isUsableArgon2idEncoding(): Boolean {
        if (!encoded.startsWith(CustomerArgon2Policy.ENCODED_PREFIX)) return false
        val binaryParts = encoded.removePrefix(CustomerArgon2Policy.ENCODED_PREFIX).split('$')
        if (binaryParts.size != 2 || binaryParts.any { !it.matches(CustomerArgon2Policy.BASE64_CHUNK) }) return false
        val salt = runCatching { Base64.getDecoder().decode(binaryParts[0]) }.getOrNull() ?: return false
        val hash = runCatching { Base64.getDecoder().decode(binaryParts[1]) }.getOrNull() ?: return false
        return salt.size == CustomerArgon2Policy.SALT_LENGTH && hash.size == CustomerArgon2Policy.HASH_LENGTH
    }

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
        CustomerArgon2Policy.SALT_LENGTH,
        CustomerArgon2Policy.HASH_LENGTH,
        CustomerArgon2Policy.PARALLELISM,
        CustomerArgon2Policy.MEMORY_KIB,
        CustomerArgon2Policy.ITERATIONS,
    )
    private val dummyHash = CustomerPasswordHash.fromEncoded(
        requireNotNull(encoder.encode(DUMMY_PASSWORD)),
    )

    fun encode(rawPassword: String): CustomerPasswordHash {
        validate(rawPassword)
        return CustomerPasswordHash.fromEncoded(requireNotNull(encoder.encode(rawPassword)))
    }

    fun matches(rawPassword: String, encoded: CustomerPasswordHash): Boolean =
        encoder.matches(rawPassword, encoded.encoded)

    /** Executes one adaptive comparison even when no usable customer credential exists. */
    fun matchesOrDummy(rawPassword: String, encoded: CustomerPasswordHash?): Boolean {
        val comparisonHash = encoded?.takeIf(CustomerPasswordHash::isUsableArgon2idEncoding) ?: dummyHash
        return encoder.matches(rawPassword, comparisonHash.encoded)
    }

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
        private const val MIN_LENGTH = 12
        private const val MAX_LENGTH = 128
        private const val DUMMY_PASSWORD = "deskseed customer dummy credential"
    }
}

private object CustomerArgon2Policy {
    const val SALT_LENGTH = 16
    const val HASH_LENGTH = 32
    const val PARALLELISM = 1
    const val MEMORY_KIB = 19 * 1024
    const val ITERATIONS = 2
    const val ENCODED_PREFIX = "${'$'}argon2id${'$'}v=19${'$'}m=19456,t=2,p=1${'$'}"
    val BASE64_CHUNK = Regex("^[A-Za-z0-9+/]+${'$'}")
}
