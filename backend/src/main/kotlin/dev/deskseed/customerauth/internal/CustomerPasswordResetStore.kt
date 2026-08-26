package dev.deskseed.customerauth.internal

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Component
internal class CustomerPasswordResetStore(
    private val jdbcTemplate: JdbcTemplate,
    private val accountSessionStore: CustomerAccountSessionStore,
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
}
