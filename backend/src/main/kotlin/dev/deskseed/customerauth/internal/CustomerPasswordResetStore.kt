package dev.deskseed.customerauth.internal

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

internal data class ResetCustomerPasswordAccount(
    val accountId: UUID,
    val customerId: UUID,
)

@Component
internal class CustomerPasswordResetStore(
    private val jdbcTemplate: JdbcTemplate,
    private val accountSessionStore: CustomerAccountSessionStore,
    private val clock: Clock,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun lockEligibleAccount(emailNormalized: String): UUID? {
        accountSessionStore.lockAccountEmail(emailNormalized)
        return jdbcTemplate.query(
            """
            select id
              from customer_accounts
             where email_normalized = ?
               and status = 'ACTIVE'
               and password_hash is not null
             for update
            """.trimIndent(),
            { resultSet, _ -> resultSet.getObject("id", UUID::class.java) },
            emailNormalized,
        ).singleOrNull()
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun lockAccountEmail(emailNormalized: String) {
        accountSessionStore.lockAccountEmail(emailNormalized)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun replacePassword(
        token: ConsumedCustomerToken,
        passwordHash: CustomerPasswordHash,
    ): ResetCustomerPasswordAccount? {
        val accountId = token.accountId ?: return null
        val account = jdbcTemplate.query(
            """
            select account.id as account_id, account.customer_id
              from customer_accounts account
             where account.id = ?
               and account.email_normalized = ?
               and account.status = 'ACTIVE'
               and account.password_hash is not null
             for update
            """.trimIndent(),
            { resultSet, _ ->
                ResetCustomerPasswordAccount(
                    accountId = resultSet.getObject("account_id", UUID::class.java),
                    customerId = resultSet.getObject("customer_id", UUID::class.java),
                )
            },
            accountId,
            token.emailNormalized,
        ).singleOrNull() ?: return null
        val now = Instant.now(clock)
        check(jdbcTemplate.update(
            """
            update customer_accounts
               set password_hash = ?, password_changed_at = ?, credential_version = credential_version + 1,
                   updated_at = ?, version = version + 1
             where id = ?
            """.trimIndent(),
            passwordHash.encoded,
            Timestamp.from(now),
            Timestamp.from(now),
            account.accountId,
        ) == 1) { "customer password reset account update failed" }
        jdbcTemplate.update(
            "update customer_sessions set revoked_at = ? where account_id = ? and revoked_at is null",
            Timestamp.from(now),
            account.accountId,
        )
        jdbcTemplate.update(
            """
            update customer_one_time_tokens
               set consumed_at = ?
             where account_id = ?
               and purpose = 'PASSWORD_RESET'
               and consumed_at is null
            """.trimIndent(),
            Timestamp.from(now),
            account.accountId,
        )
        return account
    }
}
