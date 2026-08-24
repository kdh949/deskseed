package dev.deskseed.automation.internal

import dev.deskseed.foundation.CommandContext
import dev.deskseed.foundation.RequestSource
import dev.deskseed.ticketing.ApplyAutomationTicketCommand
import dev.deskseed.ticketing.AutomationTicketCommandService
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

internal data class ClaimedAutomationCandidate(
    val id: UUID,
    val automationId: UUID,
    val automationVersion: Int,
    val ticketId: UUID,
    val ticketNumber: Long,
    val solvedAt: Instant,
    val eligibleAt: Instant,
    val attemptCount: Int,
    val leaseOwner: String,
)

@Service
internal class AutomationCandidateStore(
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun claim(workerId: String): ClaimedAutomationCandidate? {
        require(workerId.matches(Regex("[A-Za-z0-9._:-]{1,100}")))
        val now = Instant.now(clock)
        return jdbc.query(
            """
            with candidate as (
                select id from automation_candidates
                 where status in ('PENDING', 'RETRY_SCHEDULED') and available_at <= ?
                 order by available_at, discovered_at, id
                 for update skip locked limit 1
            )
            update automation_candidates item
               set status = 'LEASED', attempt_count = attempt_count + 1, lease_owner = ?,
                   lease_expires_at = ?, updated_at = ?
              from candidate where item.id = candidate.id
            returning item.*
            """.trimIndent(),
            { result, _ -> ClaimedAutomationCandidate(
                result.getObject("id", UUID::class.java), result.getObject("automation_id", UUID::class.java),
                result.getInt("automation_version"), result.getObject("ticket_id", UUID::class.java),
                result.getLong("ticket_number"), result.getTimestamp("solved_at").toInstant(),
                result.getTimestamp("eligible_at").toInstant(), result.getInt("attempt_count"), result.getString("lease_owner"),
            ) },
            Timestamp.from(now), workerId, Timestamp.from(now.plusSeconds(60)), Timestamp.from(now),
        ).singleOrNull()
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun fail(candidate: ClaimedAutomationCandidate, failure: Throwable) {
        val now = Instant.now(clock)
        val terminal = candidate.attemptCount >= MAX_ATTEMPTS
        val status = if (terminal) "DEAD_LETTERED" else "RETRY_SCHEDULED"
        val completion = if (terminal) ", completed_at = ?" else ""
        val parameters = mutableListOf<Any>(
            status,
            Timestamp.from(if (terminal) now else now.plusSeconds(1L shl candidate.attemptCount.coerceAtMost(6))),
            failure.javaClass.simpleName.uppercase().replace(Regex("[^A-Z0-9_]"), "_").take(80),
            Timestamp.from(now),
        )
        if (terminal) parameters += Timestamp.from(now)
        parameters.addAll(listOf(candidate.id, candidate.leaseOwner, candidate.attemptCount))
        jdbc.update(
            """
            update automation_candidates
               set status = ?, available_at = ?, lease_owner = null, lease_expires_at = null,
                   last_error_code = ?, updated_at = ?$completion
             where id = ? and status = 'LEASED' and lease_owner = ? and attempt_count = ?
            """.trimIndent(),
            *parameters.toTypedArray(),
        )
    }

    @Transactional
    fun recoverExpired(): Int {
        val now = Instant.now(clock)
        return jdbc.update(
            """
            update automation_candidates
               set status = 'RETRY_SCHEDULED', available_at = ?, lease_owner = null, lease_expires_at = null,
                   last_error_code = 'LEASE_EXPIRED', updated_at = ?
             where status = 'LEASED' and lease_expires_at < ?
            """.trimIndent(),
            Timestamp.from(now), Timestamp.from(now), Timestamp.from(now),
        )
    }

    private companion object { const val MAX_ATTEMPTS = 5 }
}

@Service
internal class AutomationCandidateExecutor(
    private val jdbc: JdbcTemplate,
    private val commands: AutomationTicketCommandService,
    private val clock: Clock,
) {
    @Transactional
    fun execute(candidate: ClaimedAutomationCandidate) {
        if (executionExists(candidate.id)) {
            complete(candidate, "SUCCEEDED")
            return
        }
        val startedAt = Instant.now(clock)
        val ticket = jdbc.query(
            "select version, status, solved_at from tickets where id = ? for update",
            { result, _ -> TicketState(
                result.getLong("version"), result.getString("status"), result.getTimestamp("solved_at")?.toInstant(),
            ) },
            candidate.ticketId,
        ).singleOrNull()
        val stale = ticket == null || ticket.status != "SOLVED" || ticket.solvedAt != candidate.solvedAt ||
            candidate.eligibleAt.isAfter(Instant.now(clock))
        if (stale) {
            insertExecution(candidate, "SKIPPED_STATE_CHANGED", null, "CURRENT_INTERVAL_MISMATCH", startedAt)
            complete(candidate, "SKIPPED")
            return
        }
        val result = commands.closeSolvedTicket(ApplyAutomationTicketCommand(
            candidate.ticketNumber,
            ticket.version,
            candidate.automationId,
            candidate.automationVersion,
            candidate.id,
            candidate.solvedAt,
            candidate.eligibleAt,
            CommandContext(
                RequestSource.AUTOMATION,
                "automation-candidate-${candidate.id}",
                candidate.id.toString(),
                candidate.id.toString(),
            ),
        ))
        insertExecution(candidate, "CLOSED", result.auditId, null, startedAt)
        complete(candidate, "SUCCEEDED")
    }

    private fun insertExecution(
        candidate: ClaimedAutomationCandidate,
        outcome: String,
        auditId: UUID?,
        errorCode: String?,
        startedAt: Instant,
    ) {
        jdbc.update(
            """
            insert into automation_executions (
                id, candidate_id, automation_id, automation_version, ticket_id, solved_at,
                outcome, ticket_audit_id, error_code, started_at, completed_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            UUID.randomUUID(), candidate.id, candidate.automationId, candidate.automationVersion, candidate.ticketId,
            Timestamp.from(candidate.solvedAt), outcome, auditId, errorCode,
            Timestamp.from(startedAt), Timestamp.from(Instant.now(clock)),
        )
    }

    private fun complete(candidate: ClaimedAutomationCandidate, status: String) {
        val now = Instant.now(clock)
        val updated = jdbc.update(
            """
            update automation_candidates
               set status = ?, lease_owner = null, lease_expires_at = null, last_error_code = null,
                   updated_at = ?, completed_at = ?
             where id = ? and status = 'LEASED' and lease_owner = ? and attempt_count = ?
            """.trimIndent(),
            status, Timestamp.from(now), Timestamp.from(now), candidate.id, candidate.leaseOwner, candidate.attemptCount,
        )
        check(updated == 1) { "Automation candidate lease was lost" }
    }

    private fun executionExists(candidateId: UUID) = jdbc.queryForObject(
        "select exists(select 1 from automation_executions where candidate_id = ?)",
        Boolean::class.java, candidateId,
    ) == true

    private data class TicketState(val version: Long, val status: String, val solvedAt: Instant?)
}

@Component
internal class AutomationExecutionWorker(
    private val store: AutomationCandidateStore,
    private val executor: AutomationCandidateExecutor,
) {
    fun runOnce(workerId: String = "automation-worker"): Boolean {
        val candidate = store.claim(workerId) ?: return false
        try {
            executor.execute(candidate)
        } catch (failure: RuntimeException) {
            store.fail(candidate, failure)
        }
        return true
    }

    @Scheduled(fixedDelayString = "\${deskseed.automation.worker-delay-ms:1000}")
    fun executeDueCandidates() {
        repeat(100) { if (!runOnce()) return }
    }

    @Scheduled(fixedDelayString = "\${deskseed.automation.lease-recovery-delay-ms:30000}")
    fun recoverExpiredLeases() {
        store.recoverExpired()
    }
}
