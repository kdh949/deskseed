package dev.deskseed.organization.internal

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Serializes administrative organization mutations inside PostgreSQL transactions.
 *
 * The protected set spans active administrators, groups, staff, and memberships;
 * row-level optimistic versions alone cannot protect invariants that cross rows.
 */
@Component
internal class OrganizationMutationLock(
    private val jdbcTemplate: JdbcTemplate,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun acquire() {
        jdbcTemplate.queryForObject<Boolean>(
            "select pg_advisory_xact_lock(?, ?)",
            { _, _ -> true },
            LOCK_NAMESPACE,
            LOCK_RESOURCE,
        )
    }

    private companion object {
        const val LOCK_NAMESPACE = 1_146_309_957
        const val LOCK_RESOURCE = 1_330_797_127
    }
}
