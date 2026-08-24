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
class AutomationExecutionIntegrationTest {
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var scanner: AutomationCandidateScanner
    @Autowired private lateinit var worker: AutomationExecutionWorker

    @BeforeEach
    fun clearData() {
        jdbc.execute(
            """
            truncate table
                domain_event_outbox,
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
    fun `eligible solved interval closes once through an automation actor ticket command`() {
        val policy = activePolicy(60)
        val now = Instant.now().minusSeconds(5)
        val solvedAt = now.minusSeconds(3_700)
        val ticketId = solvedTicket(60_001, solvedAt)
        assertThat(scanner.scanOnce(now)).isEqualTo(1)

        assertThat(worker.runOnce("automation-test-worker")).isTrue()

        assertThat(jdbc.queryForMap("select status, version, solved_at from tickets where id = ?", ticketId))
            .containsEntry("status", "CLOSED")
            .containsEntry("version", 1L)
        val audit = jdbc.queryForMap(
            "select id, actor_type, actor_id, source from ticket_audits where ticket_id = ? and actor_type = 'AUTOMATION'",
            ticketId,
        )
        assertThat(audit).containsEntry("actor_type", "AUTOMATION").containsEntry("actor_id", policy).containsEntry("source", "AUTOMATION")
        assertThat(jdbc.queryForList(
            "select event_type from ticket_audit_events where audit_id = ? order by event_order",
            String::class.java, audit["id"],
        )).containsExactly("AUTOMATION_APPLIED", "STATUS_CHANGED")
        assertThat(jdbc.queryForMap(
            "select candidate.status, candidate.attempt_count, execution.outcome, execution.ticket_audit_id " +
                "from automation_candidates candidate join automation_executions execution on execution.candidate_id = candidate.id",
        )).containsEntry("status", "SUCCEEDED").containsEntry("attempt_count", 1)
            .containsEntry("outcome", "CLOSED").containsEntry("ticket_audit_id", audit["id"])
        assertThat(worker.runOnce("automation-test-worker")).isFalse()
        assertThat(jdbc.queryForObject(
            "select count(*) from ticket_audits where ticket_id = ? and actor_type = 'AUTOMATION'",
            Long::class.java, ticketId,
        )).isEqualTo(1)
    }

    @Test
    fun `reopened ticket after discovery is skipped from stale solved interval`() {
        activePolicy(60)
        val now = Instant.now().minusSeconds(5)
        val ticketId = solvedTicket(60_002, now.minusSeconds(3_700))
        assertThat(scanner.scanOnce(now)).isEqualTo(1)
        jdbc.update("update tickets set status = 'OPEN', solved_at = null, version = version + 1 where id = ?", ticketId)

        assertThat(worker.runOnce("automation-stale-worker")).isTrue()

        assertThat(jdbc.queryForMap("select status, version from tickets where id = ?", ticketId))
            .containsEntry("status", "OPEN").containsEntry("version", 1L)
        assertThat(jdbc.queryForMap(
            "select candidate.status, execution.outcome, execution.error_code " +
                "from automation_candidates candidate join automation_executions execution on execution.candidate_id = candidate.id",
        )).containsEntry("status", "SKIPPED")
            .containsEntry("outcome", "SKIPPED_STATE_CHANGED")
            .containsEntry("error_code", "CURRENT_INTERVAL_MISMATCH")
        assertThat(jdbc.queryForObject("select count(*) from ticket_audits where actor_type = 'AUTOMATION'", Long::class.java)).isZero()
    }

    @Test
    fun `required ticket audit failure retries then dead letters without closing`() {
        activePolicy(60)
        val now = Instant.now().minusSeconds(5)
        val ticketId = solvedTicket(60_003, now.minusSeconds(3_700))
        assertThat(scanner.scanOnce(now)).isEqualTo(1)
        jdbc.execute(
            """
            create or replace function fail_automation_ticket_audit() returns trigger language plpgsql as ${'$'}${'$'}
            begin
                if new.actor_type = 'AUTOMATION' then raise exception 'forced automation audit failure'; end if;
                return new;
            end;
            ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbc.execute(
            "create trigger fail_automation_ticket_audit before insert on ticket_audits for each row execute function fail_automation_ticket_audit()",
        )
        try {
            repeat(5) { attempt ->
                if (attempt > 0) jdbc.update(
                    "update automation_candidates set available_at = now() where ticket_id = ? and status = 'RETRY_SCHEDULED'",
                    ticketId,
                )
                assertThat(worker.runOnce("automation-failure-worker")).isTrue()
            }
        } finally {
            jdbc.execute("drop trigger if exists fail_automation_ticket_audit on ticket_audits")
            jdbc.execute("drop function if exists fail_automation_ticket_audit()")
        }

        assertThat(jdbc.queryForMap("select status, version from tickets where id = ?", ticketId))
            .containsEntry("status", "SOLVED").containsEntry("version", 0L)
        assertThat(jdbc.queryForMap("select status, attempt_count from automation_candidates where ticket_id = ?", ticketId))
            .containsEntry("status", "DEAD_LETTERED").containsEntry("attempt_count", 5)
        assertThat(jdbc.queryForObject("select count(*) from automation_executions", Long::class.java)).isZero()
        assertThat(jdbc.queryForObject("select count(*) from ticket_audits where actor_type = 'AUTOMATION'", Long::class.java)).isZero()
    }

    private fun activePolicy(ageMinutes: Int): UUID {
        val staffId = UUID.randomUUID()
        val policyId = UUID.randomUUID()
        jdbc.update(
            """
            insert into staff_accounts (
                id, email_normalized, email_display, display_name, role, status,
                password_hash, created_at, updated_at, version
            ) values (?, ?, ?, 'Automation Admin', 'ADMIN', 'ACTIVE', 'hash', now(), now(), 0)
            """.trimIndent(),
            staffId, "automation-$staffId@example.com", "automation-$staffId@example.com",
        )
        jdbc.update(
            """
            insert into automation_definitions (
                id, normalized_name, name, position, current_version, active_version,
                aggregate_version, created_at, updated_at
            ) values (?, ?, ?, 10, 1, null, 1, now(), now())
            """.trimIndent(),
            policyId, "automation policy $policyId", "Automation Policy $policyId",
        )
        jdbc.update(
            """
            insert into automation_versions (
                automation_id, version, name, solved_age_minutes, action_type,
                created_by_staff_id, created_by_display, created_at
            ) values (?, 1, 'Automation Policy', ?, 'CLOSE_TICKET', ?, 'Automation Admin', now())
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
