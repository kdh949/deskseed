package dev.deskseed.automation.internal

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant

@Service
internal class AutomationCandidateScanner(
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
) {
    @Transactional
    fun scanOnce(now: Instant = Instant.now(clock), batchSize: Int = DEFAULT_BATCH_SIZE): Int {
        require(batchSize in 1..DEFAULT_BATCH_SIZE) { "Automation scan batch must be between 1 and 100" }
        val acquired = jdbc.queryForObject(
            "select pg_try_advisory_xact_lock(hashtextextended('deskseed:automation-scan', 0))",
            Boolean::class.java,
        ) == true
        if (!acquired) return 0
        return jdbc.queryForObject(
            """
            with eligible as (
                select definition.id as automation_id,
                       definition.active_version as automation_version,
                       ticket.id as ticket_id,
                       ticket.ticket_number,
                       ticket.solved_at,
                       ticket.solved_at + make_interval(mins => version.solved_age_minutes) as eligible_at
                  from automation_definitions definition
                  join automation_versions version
                    on version.automation_id = definition.id and version.version = definition.active_version
                  join tickets ticket
                    on ticket.status = 'SOLVED' and ticket.solved_at is not null
                   and ticket.solved_at + make_interval(mins => version.solved_age_minutes) <= ?
                 where definition.active_version is not null
                   and not exists (
                       select 1 from automation_candidates candidate
                        where candidate.automation_id = definition.id
                          and candidate.automation_version = definition.active_version
                          and candidate.ticket_id = ticket.id
                          and candidate.solved_at = ticket.solved_at
                   )
                 order by eligible_at, definition.position, definition.id, ticket.id
                 limit ?
            ), inserted as (
                insert into automation_candidates (
                    id, automation_id, automation_version, ticket_id, ticket_number, solved_at,
                    eligible_at, status, attempt_count, available_at, lease_owner, lease_expires_at,
                    last_error_code, discovered_at, updated_at, completed_at
                )
                select gen_random_uuid(), automation_id, automation_version, ticket_id, ticket_number, solved_at,
                       eligible_at, 'PENDING', 0, ?, null, null, null, ?, ?, null
                  from eligible
                on conflict (automation_id, automation_version, ticket_id, solved_at) do nothing
                returning id
            )
            select count(*) from inserted
            """.trimIndent(),
            Long::class.java,
            Timestamp.from(now), batchSize, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now),
        )?.toInt() ?: 0
    }

    @Scheduled(fixedDelayString = "\${deskseed.automation.scan-delay-ms:60000}")
    fun scanDueTickets() {
        scanOnce()
    }

    private companion object { const val DEFAULT_BATCH_SIZE = 100 }
}
