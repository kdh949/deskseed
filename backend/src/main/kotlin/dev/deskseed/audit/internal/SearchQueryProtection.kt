package dev.deskseed.audit.internal

import dev.deskseed.audit.AccessAuditProtectionException
import dev.deskseed.audit.AccessAuditSessionFingerprint
import dev.deskseed.audit.ProtectedSearchQueryAudit
import dev.deskseed.audit.SearchQueryProtector
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.text.Normalizer
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Locale
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@ConfigurationProperties("deskseed.audit.access")
internal data class SearchQueryAuditProperties(
    val enabled: Boolean = true,
    val activeKeyVersion: String = "",
    val keys: Map<String, String> = emptyMap(),
    val ciphertextRetention: Duration = Duration.ofDays(30),
)

internal class SearchQueryConfigurationException(message: String, cause: Throwable? = null) :
    AccessAuditProtectionException(message, cause)

internal class SearchQueryProtectionException(cause: Throwable) :
    AccessAuditProtectionException("Protected search query authentication failed", cause)

internal class SearchQueryProtection(
    private val properties: SearchQueryAuditProperties,
    private val secureRandom: SecureRandom = SecureRandom(),
) : SearchQueryProtector {
    private val decodedKeys: Map<String, ByteArray> = decodeKeys(properties)

    init {
        if (properties.enabled) {
            if (properties.activeKeyVersion.isBlank()) {
                throw SearchQueryConfigurationException("Access audit active key version is required")
            }
            if (decodedKeys[properties.activeKeyVersion] == null) {
                throw SearchQueryConfigurationException("Access audit active key version is not configured")
            }
        }
        if (properties.ciphertextRetention.isZero || properties.ciphertextRetention.isNegative) {
            throw SearchQueryConfigurationException("Search query ciphertext retention must be positive")
        }
    }

    override fun protect(eventId: UUID, rawQuery: String, occurredAt: Instant): ProtectedSearchQueryAudit {
        if (!properties.enabled) {
            throw SearchQueryConfigurationException("Access audit is disabled for protected search")
        }
        require(rawQuery.isNotBlank() && rawQuery.length <= MAX_QUERY_LENGTH) {
            "Search query must contain between 1 and $MAX_QUERY_LENGTH characters"
        }
        val rootKey = decodedKeys.getValue(properties.activeKeyVersion)
        val normalized = normalize(rawQuery)
        val fingerprint = hmac(
            derive(rootKey, FINGERPRINT_KEY_PURPOSE),
            "$FINGERPRINT_MESSAGE_PURPOSE\u0000$normalized".toByteArray(StandardCharsets.UTF_8),
        )
        val nonce = ByteArray(GCM_NONCE_BYTES).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance(AEAD_TRANSFORMATION)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(derive(rootKey, ENCRYPTION_KEY_PURPOSE), "AES"),
            GCMParameterSpec(GCM_TAG_BITS, nonce),
        )
        cipher.updateAAD(associatedData(eventId))
        val encrypted = cipher.doFinal(rawQuery.toByteArray(StandardCharsets.UTF_8))

        return ProtectedSearchQueryAudit(
            queryRedacted = redact(rawQuery),
            queryFingerprint = Base64.getUrlEncoder().withoutPadding().encodeToString(fingerprint),
            keyVersion = properties.activeKeyVersion,
            queryCiphertext = ByteBuffer.allocate(nonce.size + encrypted.size)
                .put(nonce)
                .put(encrypted)
                .array(),
            expiresAt = occurredAt.plus(properties.ciphertextRetention),
        )
    }

    /** Narrow seam for a future reason-gated, self-audited single-event reveal use case. */
    fun reveal(eventId: UUID, protected: ProtectedSearchQueryAudit): String {
        val rootKey = decodedKeys[protected.keyVersion]
            ?: throw SearchQueryProtectionException(
                SearchQueryConfigurationException("Protected search query key version is unavailable"),
            )
        if (protected.queryCiphertext.size <= GCM_NONCE_BYTES) {
            throw SearchQueryProtectionException(GeneralSecurityException("Invalid ciphertext envelope"))
        }
        val nonce = protected.queryCiphertext.copyOfRange(0, GCM_NONCE_BYTES)
        val ciphertext = protected.queryCiphertext.copyOfRange(GCM_NONCE_BYTES, protected.queryCiphertext.size)
        return try {
            val cipher = Cipher.getInstance(AEAD_TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(derive(rootKey, ENCRYPTION_KEY_PURPOSE), "AES"),
                GCMParameterSpec(GCM_TAG_BITS, nonce),
            )
            cipher.updateAAD(associatedData(eventId))
            String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
        } catch (exception: AEADBadTagException) {
            throw SearchQueryProtectionException(exception)
        } catch (exception: GeneralSecurityException) {
            throw SearchQueryProtectionException(exception)
        }
    }

    fun activeKeyVersion(): String? = properties.activeKeyVersion.takeIf { properties.enabled }

    fun fingerprintSession(sessionId: String): String {
        if (!properties.enabled) {
            throw SearchQueryConfigurationException("Access audit is disabled for protected reads")
        }
        require(sessionId.isNotBlank()) { "Authenticated session is required" }
        val rootKey = decodedKeys.getValue(properties.activeKeyVersion)
        val fingerprint = hmac(
            derive(rootKey, SESSION_KEY_PURPOSE),
            "$SESSION_MESSAGE_PURPOSE\u0000$sessionId".toByteArray(StandardCharsets.UTF_8),
        )
        return "${properties.activeKeyVersion}:${Base64.getUrlEncoder().withoutPadding().encodeToString(fingerprint)}"
    }

    private fun associatedData(eventId: UUID): ByteArray =
        "$CIPHERTEXT_AAD_PURPOSE|$eventId".toByteArray(StandardCharsets.UTF_8)

    private fun derive(rootKey: ByteArray, purpose: String): ByteArray =
        hmac(rootKey, purpose.toByteArray(StandardCharsets.UTF_8))

    private fun hmac(key: ByteArray, value: ByteArray): ByteArray = Mac.getInstance(HMAC_ALGORITHM).run {
        init(SecretKeySpec(key, HMAC_ALGORITHM))
        doFinal(value)
    }

    private fun normalize(rawQuery: String): String = Normalizer
        .normalize(rawQuery, Normalizer.Form.NFKC)
        .trim()
        .lowercase(Locale.ROOT)
        .replace(WHITESPACE, " ")

    private fun redact(rawQuery: String): String {
        var redacted = Normalizer.normalize(rawQuery, Normalizer.Form.NFKC)
            .replace(WHITESPACE, " ")
            .trim()
        redacted = AUTHORIZATION.replace(redacted) { match -> "${match.groupValues[1]} [REDACTED]" }
        redacted = NAMED_SECRET.replace(redacted) { match -> "${match.groupValues[1]}=[REDACTED]" }
        redacted = EMAIL.replace(redacted) { match ->
            val local = match.groupValues[1]
            "${local.take(1)}***@${match.groupValues[2]}"
        }
        redacted = CARD_LIKE.replace(redacted, "[CARD-REDACTED]")
        return redacted.take(MAX_QUERY_LENGTH)
    }

    private fun decodeKeys(properties: SearchQueryAuditProperties): Map<String, ByteArray> =
        properties.keys.mapValues { (version, encoded) ->
            if (version.isBlank()) throw SearchQueryConfigurationException("Access audit key version is blank")
            val decoded = try {
                Base64.getDecoder().decode(encoded)
            } catch (exception: IllegalArgumentException) {
                throw SearchQueryConfigurationException("Access audit key must be base64 encoded", exception)
            }
            if (decoded.size != ROOT_KEY_BYTES) {
                throw SearchQueryConfigurationException("Access audit key must decode to $ROOT_KEY_BYTES bytes")
            }
            decoded
        }

    private companion object {
        const val MAX_QUERY_LENGTH = 500
        const val ROOT_KEY_BYTES = 32
        const val GCM_NONCE_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val AEAD_TRANSFORMATION = "AES/GCM/NoPadding"
        const val HMAC_ALGORITHM = "HmacSHA256"
        const val ENCRYPTION_KEY_PURPOSE = "deskseed:access-audit:search-query:encryption-key:v1"
        const val FINGERPRINT_KEY_PURPOSE = "deskseed:access-audit:search-query:fingerprint-key:v1"
        const val FINGERPRINT_MESSAGE_PURPOSE = "deskseed:access-audit:search-query:fingerprint:v1"
        const val CIPHERTEXT_AAD_PURPOSE = "deskseed:access-audit:search-query:ciphertext:v1"
        const val SESSION_KEY_PURPOSE = "deskseed:access-audit:staff-session:fingerprint-key:v1"
        const val SESSION_MESSAGE_PURPOSE = "deskseed:access-audit:staff-session:fingerprint:v1"
        val WHITESPACE = Regex("\\s+")
        val AUTHORIZATION = Regex("\\b(bearer|basic)\\s+[^\\s]+", RegexOption.IGNORE_CASE)
        val NAMED_SECRET = Regex(
            "\\b(password|passwd|pwd|token|api[_-]?key|secret)\\s*[:=]\\s*[^\\s]+",
            RegexOption.IGNORE_CASE,
        )
        val EMAIL = Regex("\\b([A-Z0-9._%+-]+)@([A-Z0-9.-]+\\.[A-Z]{2,})\\b", RegexOption.IGNORE_CASE)
        val CARD_LIKE = Regex("(?<!\\d)(?:\\d[ -]?){13,19}(?!\\d)")
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SearchQueryAuditProperties::class)
internal class SearchQueryProtectionConfiguration {
    @Bean
    fun searchQueryProtection(properties: SearchQueryAuditProperties) = SearchQueryProtection(properties)

    @Bean
    fun accessAuditSessionFingerprint(protection: SearchQueryProtection): AccessAuditSessionFingerprint =
        AccessAuditSessionFingerprint(protection::fingerprintSession)

    @Bean("accessAuditKeyHealthIndicator")
    fun accessAuditKeyHealthIndicator(protection: SearchQueryProtection) = HealthIndicator {
        protection.activeKeyVersion()
            ?.let { Health.up().withDetail("activeKeyVersion", it).build() }
            ?: Health.outOfService().withDetail("reason", "access audit disabled").build()
    }
}
