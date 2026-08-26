package dev.deskseed.customerauth.internal

import dev.deskseed.foundation.CommandContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.ott.DefaultOneTimeToken
import org.springframework.security.authentication.ott.GenerateOneTimeTokenRequest
import org.springframework.security.authentication.ott.OneTimeToken
import org.springframework.security.authentication.ott.OneTimeTokenAuthenticationToken
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.stereotype.Service
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.UUID

internal data class GeneratedCustomerToken(
    val id: UUID,
    val rawToken: String,
    val emailNormalized: String,
    val emailDisplay: String,
    val expiresAt: Instant,
)

internal data class ConsumedCustomerToken(
    val id: UUID,
    val emailNormalized: String,
    val emailDisplay: String,
    val expiresAt: Instant,
)

internal enum class TokenFailureClass { REPLAYED, EXPIRED_OR_INVALID }

/**
 * PostgreSQL-backed Spring Security OTT adapter. Only a SHA-256 digest is stored and consumption is one atomic UPDATE.
 * Framework contract: https://docs.spring.io/spring-security/reference/servlet/authentication/onetimetoken.html
 */
@Service
internal class JdbcDigestOneTimeTokenService(
    private val jdbcTemplate: JdbcTemplate,
    private val properties: CustomerAuthProperties,
    private val clock: Clock,
) : OneTimeTokenService {
    init {
        properties.validate()
    }

    override fun generate(request: GenerateOneTimeTokenRequest): OneTimeToken {
        val generated = generate(
            emailDisplay = request.username,
            ttl = request.expiresIn,
            requestId = UUID.randomUUID().toString(),
            correlationId = UUID.randomUUID().toString(),
        )
        return DefaultOneTimeToken(generated.rawToken, generated.emailNormalized, generated.expiresAt)
    }

    fun generate(emailDisplay: String, context: CommandContext): GeneratedCustomerToken = generate(
        emailDisplay = emailDisplay,
        ttl = properties.magicLinkTtl,
        requestId = context.requestId,
        correlationId = context.correlationId,
    )

    private fun generate(
        emailDisplay: String,
        ttl: Duration,
        requestId: String,
        correlationId: String,
    ): GeneratedCustomerToken {
        require(ttl in Duration.ofMinutes(5)..Duration.ofMinutes(60)) { "one-time token TTL is out of policy" }
        val normalized = emailDisplay.trim().lowercase(Locale.ROOT)
        val now = Instant.now(clock)
        val generated = GeneratedCustomerToken(
            id = UUID.randomUUID(),
            rawToken = CustomerAuthSecrets.randomBearer(),
            emailNormalized = normalized,
            emailDisplay = emailDisplay.trim(),
            expiresAt = now.plus(ttl),
        )
        jdbcTemplate.update(
            """
            insert into customer_one_time_tokens
                (id, token_digest, purpose, email_normalized, email_display, request_id, correlation_id,
                 created_at, expires_at, consumed_at)
            values (?, ?, 'PASSWORDLESS_LOGIN', ?, ?, ?, ?, ?, ?, null)
            """.trimIndent(),
            generated.id,
            CustomerAuthSecrets.digest(generated.rawToken),
            generated.emailNormalized,
            generated.emailDisplay,
            requestId,
            correlationId,
            Timestamp.from(now),
            Timestamp.from(generated.expiresAt),
        )
        return generated
    }

    override fun consume(authenticationToken: OneTimeTokenAuthenticationToken): OneTimeToken? =
        requireNotNull(authenticationToken.tokenValue).let { rawToken ->
            consume(rawToken)?.let {
                DefaultOneTimeToken(rawToken, it.emailNormalized, it.expiresAt)
            }
        }

    fun consume(rawToken: String): ConsumedCustomerToken? {
        val now = Instant.now(clock)
        return jdbcTemplate.query(
            """
            update customer_one_time_tokens
               set consumed_at = ?
             where token_digest = ?
               and purpose = 'PASSWORDLESS_LOGIN'
               and consumed_at is null
               and expires_at > ?
            returning id, email_normalized, email_display, expires_at
            """.trimIndent(),
            { resultSet, _ ->
                ConsumedCustomerToken(
                    id = resultSet.getObject("id", UUID::class.java),
                    emailNormalized = resultSet.getString("email_normalized"),
                    emailDisplay = resultSet.getString("email_display"),
                    expiresAt = resultSet.getTimestamp("expires_at").toInstant(),
                )
            },
            Timestamp.from(now),
            CustomerAuthSecrets.digest(rawToken),
            Timestamp.from(now),
        ).singleOrNull()
    }

    fun failureClass(rawToken: String): TokenFailureClass = jdbcTemplate.query(
        """
        select consumed_at, expires_at
          from customer_one_time_tokens
         where token_digest = ?
           and purpose = 'PASSWORDLESS_LOGIN'
        """.trimIndent(),
        { resultSet, _ ->
            if (resultSet.getTimestamp("consumed_at") != null) TokenFailureClass.REPLAYED
            else TokenFailureClass.EXPIRED_OR_INVALID
        },
        CustomerAuthSecrets.digest(rawToken),
    ).singleOrNull() ?: TokenFailureClass.EXPIRED_OR_INVALID
}
