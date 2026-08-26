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
    val purpose: CustomerOneTimeTokenPurpose,
    val registrationIntentId: UUID?,
    val accountId: UUID?,
    val emailNormalized: String,
    val emailDisplay: String,
    val expiresAt: Instant,
) {
    override fun toString(): String = "[PROTECTED GENERATED CUSTOMER TOKEN]"
}

internal data class ConsumedCustomerToken(
    val id: UUID,
    val purpose: CustomerOneTimeTokenPurpose,
    val registrationIntentId: UUID?,
    val accountId: UUID?,
    val emailNormalized: String,
    val emailDisplay: String,
    val expiresAt: Instant,
) {
    override fun toString(): String = "[PROTECTED CONSUMED CUSTOMER TOKEN]"
}

internal data class ConsumableCustomerTokenTarget(
    val id: UUID,
    val accountId: UUID?,
    val emailNormalized: String,
) {
    override fun toString(): String = "[PROTECTED CONSUMABLE CUSTOMER TOKEN TARGET]"
}

internal enum class TokenFailureClass { REPLAYED, EXPIRED_OR_INVALID }

internal enum class CustomerOneTimeTokenPurpose {
    PASSWORDLESS_LOGIN,
    EMAIL_VERIFICATION,
    PASSWORD_RESET,
}

internal sealed interface CustomerOneTimeTokenTarget {
    val purpose: CustomerOneTimeTokenPurpose
    val registrationIntentId: UUID?
    val accountId: UUID?

    data object PasswordlessLogin : CustomerOneTimeTokenTarget {
        override val purpose = CustomerOneTimeTokenPurpose.PASSWORDLESS_LOGIN
        override val registrationIntentId: UUID? = null
        override val accountId: UUID? = null
    }

    data class EmailVerification(
        override val registrationIntentId: UUID,
    ) : CustomerOneTimeTokenTarget {
        override val purpose = CustomerOneTimeTokenPurpose.EMAIL_VERIFICATION
        override val accountId: UUID? = null
    }

    data class PasswordReset(
        override val accountId: UUID,
    ) : CustomerOneTimeTokenTarget {
        override val purpose = CustomerOneTimeTokenPurpose.PASSWORD_RESET
        override val registrationIntentId: UUID? = null
    }
}

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
            target = CustomerOneTimeTokenTarget.PasswordlessLogin,
            ttl = request.expiresIn,
            requestId = UUID.randomUUID().toString(),
            correlationId = UUID.randomUUID().toString(),
        )
        return DefaultOneTimeToken(generated.rawToken, generated.emailNormalized, generated.expiresAt)
    }

    fun generate(emailDisplay: String, context: CommandContext): GeneratedCustomerToken = generate(
        emailDisplay = emailDisplay,
        target = CustomerOneTimeTokenTarget.PasswordlessLogin,
        ttl = properties.magicLinkTtl,
        requestId = context.requestId,
        correlationId = context.correlationId,
    )

    fun generate(
        emailDisplay: String,
        target: CustomerOneTimeTokenTarget,
        ttl: Duration,
        context: CommandContext,
    ): GeneratedCustomerToken = generate(
        emailDisplay = emailDisplay,
        target = target,
        ttl = ttl,
        requestId = context.requestId,
        correlationId = context.correlationId,
    )

    private fun generate(
        emailDisplay: String,
        target: CustomerOneTimeTokenTarget,
        ttl: Duration,
        requestId: String,
        correlationId: String,
    ): GeneratedCustomerToken {
        val maximumTtl = if (target.purpose == CustomerOneTimeTokenPurpose.EMAIL_VERIFICATION) {
            Duration.ofHours(48)
        } else {
            Duration.ofMinutes(60)
        }
        require(ttl in Duration.ofMinutes(5)..maximumTtl) { "one-time token TTL is out of policy" }
        val normalized = emailDisplay.trim().lowercase(Locale.ROOT)
        val now = Instant.now(clock)
        val generated = GeneratedCustomerToken(
            id = UUID.randomUUID(),
            rawToken = CustomerAuthSecrets.randomBearer(),
            purpose = target.purpose,
            registrationIntentId = target.registrationIntentId,
            accountId = target.accountId,
            emailNormalized = normalized,
            emailDisplay = emailDisplay.trim(),
            expiresAt = now.plus(ttl),
        )
        jdbcTemplate.update(
            """
            insert into customer_one_time_tokens
                (id, token_digest, purpose, registration_intent_id, account_id,
                 email_normalized, email_display, request_id, correlation_id,
                 created_at, expires_at, consumed_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, null)
            """.trimIndent(),
            generated.id,
            CustomerAuthSecrets.digest(generated.rawToken),
            generated.purpose.name,
            generated.registrationIntentId,
            generated.accountId,
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

    fun consume(rawToken: String): ConsumedCustomerToken? =
        consume(rawToken, CustomerOneTimeTokenPurpose.PASSWORDLESS_LOGIN)

    fun consume(
        rawToken: String,
        expectedPurpose: CustomerOneTimeTokenPurpose,
    ): ConsumedCustomerToken? {
        val now = Instant.now(clock)
        return jdbcTemplate.query(
            """
            update customer_one_time_tokens
               set consumed_at = ?
             where token_digest = ?
               and purpose = ?
               and consumed_at is null
               and expires_at > ?
            returning id, purpose, registration_intent_id, account_id,
                      email_normalized, email_display, expires_at
            """.trimIndent(),
            { resultSet, _ ->
                ConsumedCustomerToken(
                    id = resultSet.getObject("id", UUID::class.java),
                    purpose = CustomerOneTimeTokenPurpose.valueOf(resultSet.getString("purpose")),
                    registrationIntentId = resultSet.getObject("registration_intent_id", UUID::class.java),
                    accountId = resultSet.getObject("account_id", UUID::class.java),
                    emailNormalized = resultSet.getString("email_normalized"),
                    emailDisplay = resultSet.getString("email_display"),
                    expiresAt = resultSet.getTimestamp("expires_at").toInstant(),
                )
            },
            Timestamp.from(now),
            CustomerAuthSecrets.digest(rawToken),
            expectedPurpose.name,
            Timestamp.from(now),
        ).singleOrNull()
    }

    fun findConsumableTarget(
        rawToken: String,
        expectedPurpose: CustomerOneTimeTokenPurpose,
    ): ConsumableCustomerTokenTarget? {
        val now = Instant.now(clock)
        return jdbcTemplate.query(
            """
            select id, account_id, email_normalized
              from customer_one_time_tokens
             where token_digest = ?
               and purpose = ?
               and consumed_at is null
               and expires_at > ?
            """.trimIndent(),
            { resultSet, _ ->
                ConsumableCustomerTokenTarget(
                    id = resultSet.getObject("id", UUID::class.java),
                    accountId = resultSet.getObject("account_id", UUID::class.java),
                    emailNormalized = resultSet.getString("email_normalized"),
                )
            },
            CustomerAuthSecrets.digest(rawToken),
            expectedPurpose.name,
            Timestamp.from(now),
        ).singleOrNull()
    }

    fun failureClass(rawToken: String): TokenFailureClass =
        failureClass(rawToken, CustomerOneTimeTokenPurpose.PASSWORDLESS_LOGIN)

    fun failureClass(
        rawToken: String,
        expectedPurpose: CustomerOneTimeTokenPurpose,
    ): TokenFailureClass = jdbcTemplate.query(
        """
        select consumed_at, expires_at
         from customer_one_time_tokens
         where token_digest = ?
           and purpose = ?
        """.trimIndent(),
        { resultSet, _ ->
            if (resultSet.getTimestamp("consumed_at") != null) TokenFailureClass.REPLAYED
            else TokenFailureClass.EXPIRED_OR_INVALID
        },
        CustomerAuthSecrets.digest(rawToken),
        expectedPurpose.name,
    ).singleOrNull() ?: TokenFailureClass.EXPIRED_OR_INVALID
}
