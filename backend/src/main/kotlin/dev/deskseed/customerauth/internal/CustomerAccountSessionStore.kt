package dev.deskseed.customerauth.internal

import dev.deskseed.customer.CustomerDirectory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

internal data class CustomerPrincipal(
    val accountId: UUID,
    val customerId: UUID,
    val email: String,
    val displayName: String,
    val verifiedAt: Instant,
)

internal data class CustomerAccountIdentity(
    val accountId: UUID,
    val principal: CustomerPrincipal,
)

internal data class NewCustomerSession(
    val rawToken: String,
    val principal: CustomerPrincipal,
)

@Component
internal class CustomerAccountSessionStore(
    private val jdbcTemplate: JdbcTemplate,
    private val customerDirectory: CustomerDirectory,
    private val properties: CustomerAuthProperties,
    private val clock: Clock,
) {
    fun resolveOrCreateAccount(emailNormalized: String, emailDisplay: String): CustomerAccountIdentity {
        jdbcTemplate.queryForObject(
            "select pg_advisory_xact_lock(hashtextextended(?, 0))",
            { _, _ -> Unit },
            "customer-account:$emailNormalized",
        )
        findAccount(emailNormalized)?.let { return it }
        val now = Instant.now(clock)
        // Authentication proves control of the address, but does not prove ownership of old anonymous tickets.
        val customer = customerDirectory.findVerifiedByNormalizedEmail(emailNormalized)
            ?: customerDirectory.createVerified("고객", emailDisplay, now)
        val accountId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            insert into customer_accounts
                (id, customer_id, email_normalized, status, verified_at, last_login_at,
                 created_at, updated_at, version)
            values (?, ?, ?, 'ACTIVE', ?, ?, ?, ?, 0)
            """.trimIndent(),
            accountId,
            customer.id,
            emailNormalized,
            Timestamp.from(requireNotNull(customer.verifiedAt)),
            Timestamp.from(now),
            Timestamp.from(now),
            Timestamp.from(now),
        )
        return CustomerAccountIdentity(
            accountId,
            CustomerPrincipal(accountId, customer.id, emailNormalized, customer.name, requireNotNull(customer.verifiedAt)),
        )
    }

    fun createSession(account: CustomerAccountIdentity, previousRawSession: String?): NewCustomerSession {
        val now = Instant.now(clock)
        if (!previousRawSession.isNullOrBlank()) {
            jdbcTemplate.update(
                "update customer_sessions set revoked_at = ? where session_token_digest = ? and revoked_at is null",
                Timestamp.from(now),
                CustomerAuthSecrets.digest(previousRawSession),
            )
        }
        val raw = CustomerAuthSecrets.randomBearer()
        jdbcTemplate.update(
            """
            insert into customer_sessions
                (id, account_id, session_token_digest, created_at, last_activity_at,
                 expires_at, absolute_expires_at, revoked_at)
            values (?, ?, ?, ?, ?, ?, ?, null)
            """.trimIndent(),
            UUID.randomUUID(),
            account.accountId,
            CustomerAuthSecrets.digest(raw),
            Timestamp.from(now),
            Timestamp.from(now),
            Timestamp.from(now.plus(properties.sessionIdle)),
            Timestamp.from(now.plus(properties.sessionAbsolute)),
        )
        return NewCustomerSession(raw, account.principal)
    }

    fun resolveSession(rawToken: String): CustomerPrincipal? {
        val now = Instant.now(clock)
        return jdbcTemplate.query(
            """
            update customer_sessions session
               set last_activity_at = ?,
                   expires_at = least(?, session.absolute_expires_at)
              from customer_accounts account, customers customer
             where session.session_token_digest = ?
               and session.revoked_at is null
               and session.expires_at > ?
               and session.absolute_expires_at > ?
               and account.id = session.account_id
               and account.status = 'ACTIVE'
               and customer.id = account.customer_id
            returning account.id as account_id, customer.id as customer_id,
                      account.email_normalized, customer.name, account.verified_at
            """.trimIndent(),
            { resultSet, _ ->
                CustomerPrincipal(
                    accountId = resultSet.getObject("account_id", UUID::class.java),
                    customerId = resultSet.getObject("customer_id", UUID::class.java),
                    email = resultSet.getString("email_normalized"),
                    displayName = resultSet.getString("name"),
                    verifiedAt = resultSet.getTimestamp("verified_at").toInstant(),
                )
            },
            Timestamp.from(now),
            Timestamp.from(now.plus(properties.sessionIdle)),
            CustomerAuthSecrets.digest(rawToken),
            Timestamp.from(now),
            Timestamp.from(now),
        ).singleOrNull()
    }

    fun revoke(rawToken: String): CustomerPrincipal? {
        val principal = resolveSession(rawToken) ?: return null
        jdbcTemplate.update(
            "update customer_sessions set revoked_at = ? where session_token_digest = ? and revoked_at is null",
            Timestamp.from(Instant.now(clock)),
            CustomerAuthSecrets.digest(rawToken),
        )
        return principal
    }

    private fun findAccount(emailNormalized: String): CustomerAccountIdentity? = jdbcTemplate.query(
        """
        select account.id as account_id, account.customer_id, account.email_normalized,
               account.status, customer.name, account.verified_at
          from customer_accounts account
          join customers customer on customer.id = account.customer_id
         where account.email_normalized = ?
        """.trimIndent(),
        { resultSet, _ ->
            check(resultSet.getString("status") == "ACTIVE") { "customer account is disabled" }
            val accountId = resultSet.getObject("account_id", UUID::class.java)
            CustomerAccountIdentity(
                accountId,
                CustomerPrincipal(
                    accountId = accountId,
                    customerId = resultSet.getObject("customer_id", UUID::class.java),
                    email = resultSet.getString("email_normalized"),
                    displayName = resultSet.getString("name"),
                    verifiedAt = resultSet.getTimestamp("verified_at").toInstant(),
                ),
            )
        },
        emailNormalized,
    ).singleOrNull()
}
