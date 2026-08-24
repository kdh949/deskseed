package dev.deskseed.automation.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@dev.deskseed.testsupport.integration.DeskseedSpringIntegrationTest
@dev.deskseed.testsupport.category.IntegrationTest
class AutomationCandidateScannerIntegrationTest {
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var scanner: AutomationCandidateScanner

    @BeforeEach
    fun clearData() {
        jdbc.execute(
            """
            truncate table
                automation_executions,
                automation_candidates,
                automation_activations,
                automation_versions,
                automation_definitions,
                ticket_audit_events,
                ticket_audits,
                ticket_comments,
                tickets,
                customers,
                staff_login_throttles,
                staff_accounts
            restart identity cascade
            """.trimIndent(),
        )
    }

    @Test
    fun `scanner discovers stable solved interval candidates in bounded batches without duplicates`() {
        val policy = activePolicy(60)
        val now = Instant.parse("2026-08-24T09:00:00Z")
        val ticketIds = (1..101).map { index -> solvedTicket(50_000L + index, now.minusSeconds(7_200L + index)) }

        assertThat(scanner.scanOnce(now)).isEqualTo(100)
        assertThat(scanner.scanOnce(now)).isEqualTo(1)
        assertThat(scanner.scanOnce(now)).isZero()
        assertThat(jdbc.queryForObject("select count(*) from automation_candidates", Long::class.java)).isEqualTo(101)
        assertThat(jdbc.queryForObject(
            "select count(*) from automation_candidates where automation_id = ? and automation_version = 1 and status = 'PENDING'",
            Long::class.java, policy,
        )).isEqualTo(101)

        val reopened = ticketIds.first()
        jdbc.update("update tickets set status = 'OPEN', solved_at = null, version = version + 1 where id = ?", reopened)
        assertThat(scanner.scanOnce(now.plusSeconds(60))).isZero()
        val newSolvedAt = now.minusSeconds(3_700)
        jdbc.update(
            "update tickets set status = 'SOLVED', solved_at = ?, version = version + 1 where id = ?",
            Timestamp.from(newSolvedAt), reopened,
        )
        assertThat(scanner.scanOnce(now.plusSeconds(60))).isEqualTo(1)
        assertThat(jdbc.queryForObject(
            "select count(*) from automation_candidates where ticket_id = ?",
            Long::class.java, reopened,
        )).isEqualTo(2)
    }

    private fun activePolicy(ageMinutes: Int): UUID {
        val staffId = UUID.randomUUID()
        val policyId = UUID.randomUUID()
        jdbc.update(
            """
            insert into staff_accounts (
                id, email_normalized, email_display, display_name, role, status,
                password_hash, created_at, updated_at, version
            ) values (?, ?, ?, 'Scanner Admin', 'ADMIN', 'ACTIVE', 'hash', now(), now(), 0)
            """.trimIndent(),
            staffId, "scanner-$staffId@example.com", "scanner-$staffId@example.com",
        )
        jdbc.update(
            """
            insert into automation_definitions (
                id, normalized_name, name, position, current_version, active_version,
                aggregate_version, created_at, updated_at
            ) values (?, ?, ?, 10, 1, null, 1, now(), now())
            """.trimIndent(),
            policyId, "scanner policy $policyId", "Scanner Policy $policyId",
        )
        jdbc.update(
            """
            insert into automation_versions (
                automation_id, version, name, solved_age_minutes, action_type,
                created_by_staff_id, created_by_display, created_at
            ) values (?, 1, 'Scanner Policy', ?, 'CLOSE_TICKET', ?, 'Scanner Admin', now())
            """.trimIndent(),
            policyId, ageMinutes, staffId,
        )
        jdbc.update("update automation_definitions set active_version = 1 where id = ?", policyId)
        return policyId
    }

    private fun solvedTicket(number: Long, solvedAt: Instant): UUID = UUID.randomUUID().also { id ->
        jdbc.update(
            """
            insert into tickets (
                id, ticket_number, requester_id, kind, subject, status, priority,
                group_id, assignee_id, channel, version, created_at, updated_at, solved_at
            ) values (?, ?, null, 'INTERNAL_WORK_ITEM', ?, 'SOLVED', 'NORMAL', null, null, 'API', 0, ?, ?, ?)
            """.trimIndent(),
            id, number, "Solved $number", Timestamp.from(solvedAt.minusSeconds(60)), Timestamp.from(solvedAt), Timestamp.from(solvedAt),
        )
    }
}
