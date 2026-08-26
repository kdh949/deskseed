package dev.deskseed.customerconsent.internal

import dev.deskseed.customerconsent.CustomerConsentAcceptanceWriter
import dev.deskseed.customerconsent.RecordCustomerRegistrationConsents
import dev.deskseed.foundation.RequestSource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
internal class JdbcCustomerConsentAcceptanceWriter(
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
) : CustomerConsentAcceptanceWriter {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun appendRegistration(command: RecordCustomerRegistrationConsents) {
        require(command.context.source == RequestSource.CUSTOMER_PORTAL) {
            "customer registration consent source is invalid"
        }
        require(command.selections.size in 1..20) { "registration consent selection count is invalid" }
        require(command.selections.distinctBy { it.policyId }.size == command.selections.size) {
            "registration consent selections must be unique"
        }
        val acceptedAt = Instant.now(clock)
        command.selections.forEach { selection ->
            jdbc.update(
                """
                insert into customer_consent_acceptances
                    (id, customer_id, account_id, ticket_id, policy_id, policy_version,
                     context, accepted_at, source, request_id, correlation_id)
                values (?, ?, ?, null, ?, ?, 'REGISTRATION', ?, 'CUSTOMER_PORTAL', ?, ?)
                """.trimIndent(),
                UUID.randomUUID(),
                command.customerId,
                command.accountId,
                selection.policyId,
                selection.policyVersion,
                Timestamp.from(acceptedAt),
                command.context.requestId,
                command.context.correlationId,
            )
        }
    }
}
