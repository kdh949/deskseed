package dev.deskseed.portal.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@ConfigurationProperties("deskseed.customer-portal")
internal data class CustomerPortalSecurityProperties(
    var claimGrantTtl: Duration = Duration.ofMinutes(15),
    var claimSigningKey: String = "BAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQ=",
    var claimFingerprintKey: String = "BQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQU=",
) {
    fun validate() {
        require(claimGrantTtl in Duration.ofMinutes(5)..Duration.ofMinutes(60))
        require(Base64.getDecoder().decode(claimSigningKey).size >= 32)
        require(Base64.getDecoder().decode(claimFingerprintKey).size >= 32)
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CustomerPortalSecurityProperties::class)
internal class CustomerPortalSecurityConfiguration

@Entity
@Table(name = "customer_request_claim_grants")
internal class CustomerRequestClaimGrantEntity(
    @Id val id: UUID,
    @Column(name = "ticket_id", nullable = false) val ticketId: UUID,
    @Column(name = "token_digest", nullable = false, unique = true, length = 64) val tokenDigest: String,
    @Column(name = "email_fingerprint", nullable = false, length = 64) val emailFingerprint: String,
    @Column(name = "created_at", nullable = false) val createdAt: Instant,
    @Column(name = "expires_at", nullable = false) val expiresAt: Instant,
    @Column(name = "consumed_at") var consumedAt: Instant? = null,
)

internal interface CustomerRequestClaimGrantRepository : JpaRepository<CustomerRequestClaimGrantEntity, UUID> {
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select grant from CustomerRequestClaimGrantEntity grant where grant.tokenDigest = :digest")
    fun lockByDigest(@Param("digest") digest: String): CustomerRequestClaimGrantEntity?
}

internal data class IssuedClaimGrant(val token: String, val expiresAt: Instant)
internal data class ConsumedClaimGrant(val ticketId: UUID?, val emailMismatch: Boolean = false)

@Service
internal class CustomerClaimGrantStore(
    private val repository: CustomerRequestClaimGrantRepository,
    private val properties: CustomerPortalSecurityProperties,
    private val clock: Clock,
) {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    @Transactional
    fun issue(ticketId: UUID, ticketNumber: Long, requesterEmail: String): IssuedClaimGrant {
        properties.validate()
        val now = Instant.now(clock)
        val expiresAt = now.plus(properties.claimGrantTtl)
        val id = UUID.randomUUID()
        val emailFingerprint = fingerprint(requesterEmail)
        val payload = "v1:$id:$ticketNumber:${expiresAt.epochSecond}:$emailFingerprint"
        val encodedPayload = encoder.encodeToString(payload.toByteArray(StandardCharsets.UTF_8))
        val token = "$encodedPayload.${encoder.encodeToString(hmac(signingKey(), encodedPayload))}"
        repository.saveAndFlush(
            CustomerRequestClaimGrantEntity(
                id = id,
                ticketId = ticketId,
                tokenDigest = sha256(token),
                emailFingerprint = emailFingerprint,
                createdAt = now,
                expiresAt = expiresAt,
            ),
        )
        return IssuedClaimGrant(token, expiresAt)
    }

    @Transactional
    fun consume(rawToken: String, ticketNumber: Long, accountEmail: String): ConsumedClaimGrant? {
        val parsed = parseAndVerify(rawToken) ?: return null
        if (parsed.ticketNumber != ticketNumber) return null
        if (parsed.emailFingerprint != fingerprint(accountEmail)) return ConsumedClaimGrant(null, emailMismatch = true)
        val now = Instant.now(clock)
        val entity = repository.lockByDigest(sha256(rawToken))
            ?.takeIf {
                it.id == parsed.id && it.emailFingerprint == parsed.emailFingerprint &&
                    it.consumedAt == null && it.expiresAt > now &&
                    it.expiresAt.epochSecond == parsed.expiresAtEpochSecond
            } ?: return null
        entity.consumedAt = now
        repository.saveAndFlush(entity)
        return ConsumedClaimGrant(entity.ticketId)
    }

    fun fingerprint(email: String): String = hex(hmac(fingerprintKey(), email.trim().lowercase()))

    private fun parseAndVerify(token: String): ParsedClaimGrant? {
        if (token.length !in 32..1000) return null
        val parts = token.split('.')
        if (parts.size != 2) return null
        val expected = hmac(signingKey(), parts[0])
        val supplied = runCatching { decoder.decode(parts[1]) }.getOrNull() ?: return null
        if (!MessageDigest.isEqual(expected, supplied)) return null
        val values = runCatching {
            String(decoder.decode(parts[0]), StandardCharsets.UTF_8).split(':')
        }.getOrNull() ?: return null
        if (values.size != 5 || values[0] != "v1") return null
        return runCatching {
            ParsedClaimGrant(
                id = UUID.fromString(values[1]),
                ticketNumber = values[2].toLong(),
                expiresAtEpochSecond = values[3].toLong(),
                emailFingerprint = values[4].takeIf { it.matches(Regex("[0-9a-f]{64}")) }!!,
            )
        }.getOrNull()
    }

    private fun signingKey() = Base64.getDecoder().decode(properties.claimSigningKey)
    private fun fingerprintKey() = Base64.getDecoder().decode(properties.claimFingerprintKey)
    private fun hmac(key: ByteArray, value: String): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(value.toByteArray(StandardCharsets.UTF_8))
    }
    private fun sha256(value: String) = hex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))
    private fun hex(value: ByteArray) = java.util.HexFormat.of().formatHex(value)

    private data class ParsedClaimGrant(
        val id: UUID,
        val ticketNumber: Long,
        val expiresAtEpochSecond: Long,
        val emailFingerprint: String,
    )
}
