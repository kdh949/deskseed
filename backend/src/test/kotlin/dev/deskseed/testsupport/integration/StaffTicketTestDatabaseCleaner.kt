package dev.deskseed.testsupport.integration

import org.springframework.jdbc.core.JdbcTemplate

/**
 * Resets the mutable staff, customer, and ticket rows used by staff HTTP integration tests.
 *
 * This deliberately does not truncate every application table. Tests with additional state
 * (for example saved views or ticket configuration) remain responsible for that state.
 */
class StaffTicketTestDatabaseCleaner(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun resetMutableStaffTicketState() {
        jdbcTemplate.execute(
            """
            truncate table
                access_audit_events,
                admin_security_audit_events,
                ticket_audit_events,
                ticket_audits,
                ticket_comments,
                request_access_tokens,
                tickets,
                customers,
                group_memberships,
                support_groups,
                staff_login_throttles,
                staff_accounts
            restart identity cascade
            """.trimIndent(),
        )
    }
}
