package dev.deskseed.portal.internal

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

@ConfigurationProperties("deskseed.portal.request-access-token")
internal data class RequestAccessTokenProperties(
    @param:DefaultValue("30d")
    val ttl: Duration,
) {
    init {
        require(!ttl.isZero && !ttl.isNegative) { "Request access token TTL must be positive" }
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RequestAccessTokenProperties::class)
internal class RequestAccessTokenConfiguration

@Service
internal class RequestAccessTokenStore(
    private val repository: RequestAccessTokenRepository,
    private val codec: RequestAccessTokenCodec,
    private val clock: Clock,
    private val properties: RequestAccessTokenProperties,
) {
    @Transactional
    fun issue(ticketId: UUID): String {
        val issued = codec.issue()
        val now = Instant.now(clock)
        repository.save(
            RequestAccessTokenEntity(
                ticketId = ticketId,
                tokenHash = issued.hash,
                createdAt = now,
                expiresAt = now.plus(properties.ttl),
            ),
        )
        return issued.raw
    }

    @Transactional(readOnly = true)
    fun resolveTicketId(rawToken: String): UUID? = repository
        .findActiveByHash(
            tokenHash = codec.hash(rawToken),
            now = Instant.now(clock),
        )
        ?.ticketId

    @Transactional
    fun lockActiveTicketId(rawToken: String): UUID? = repository
        .lockActiveByHash(codec.hash(rawToken), Instant.now(clock))
        ?.ticketId

    /** Retained for the existing explicit-claim flow; anonymous follow-ups use [lockActiveTicketId]. */
    @Transactional
    fun lockTicketIdForClaim(rawToken: String): UUID? = lockActiveTicketId(rawToken)

    @Transactional
    fun revokeAll(ticketId: UUID) {
        repository.revokeAllForTicket(ticketId, Instant.now(clock))
    }
}
