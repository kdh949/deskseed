package dev.deskseed.customerconsent.internal

import dev.deskseed.customerconsent.CustomerConsentContext
import dev.deskseed.customerconsent.CustomerConsentPolicyContextLock
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Component
internal class JdbcCustomerConsentPolicyContextLock(
    private val jdbc: JdbcTemplate,
) : CustomerConsentPolicyContextLock {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun lock(context: CustomerConsentContext) {
        jdbc.queryForObject(
            "select pg_advisory_xact_lock(?)",
            { _, _ -> Unit },
            when (context) {
                CustomerConsentContext.REGISTRATION -> REGISTRATION_LOCK
                CustomerConsentContext.REQUEST_SUBMISSION -> REQUEST_SUBMISSION_LOCK
            },
        )
    }

    private companion object {
        const val REGISTRATION_LOCK = 1_067_539_001L
        const val REQUEST_SUBMISSION_LOCK = 1_067_539_002L
    }
}
