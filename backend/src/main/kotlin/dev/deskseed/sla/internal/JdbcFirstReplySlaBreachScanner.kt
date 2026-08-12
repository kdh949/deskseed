package dev.deskseed.sla.internal

import dev.deskseed.sla.FirstReplySlaBreachScanner
import dev.deskseed.sla.FirstReplySlaScanResult
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.net.InetAddress
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@Component
internal class JdbcFirstReplySlaBreachScanner(
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
    @Value("\${deskseed.sla.breach-scanner.batch-size:100}") private val scheduledBatchSize: Int,
) : FirstReplySlaBreachScanner {
    @Scheduled(
        fixedDelayString = "\${deskseed.sla.breach-scanner.interval:30s}",
        initialDelayString = "\${deskseed.sla.breach-scanner.initial-delay:30s}",
    )
    fun scheduledScan() {
        val processSuffix = "-${ProcessHandle.current().pid()}"
        val hostName = InetAddress.getLocalHost().hostName
            .take(MAX_OWNER_LENGTH - processSuffix.length)
        scan("$hostName$processSuffix", scheduledBatchSize)
    }

    @Transactional
    override fun scan(owner: String, batchSize: Int): FirstReplySlaScanResult {
        require(owner.isNotBlank() && owner.length <= MAX_OWNER_LENGTH)
        require(batchSize in 1..1000)
        val now = Instant.now(clock)
        val lease = jdbc.queryForObject(
            "select lease_owner, lease_until from sla_breach_scan_state where id = 1 for update",
            { result, _ -> result.getString("lease_owner") to result.getTimestamp("lease_until")?.toInstant() },
        )
        if (lease.second?.isAfter(now) == true && lease.first != owner) {
            return FirstReplySlaScanResult(0, 0, null, null)
        }
        jdbc.update(
            """
            update sla_breach_scan_state
               set lease_owner = ?, lease_until = ?, last_started_at = ?
             where id = 1
            """.trimIndent(),
            owner,
            now.plus(LEASE_DURATION).atOffset(ZoneOffset.UTC),
            now.atOffset(ZoneOffset.UTC),
        )
        val targets = jdbc.query(
            """
            select id, due_at from sla_target_instances
             where state = 'ACTIVE' and due_at <= ?
             order by due_at, id
             for update skip locked
             limit ?
            """.trimIndent(),
            { result, _ ->
                DueTarget(
                    result.getObject("id", UUID::class.java),
                    result.getTimestamp("due_at").toInstant(),
                )
            },
            now.atOffset(ZoneOffset.UTC),
            batchSize,
        )
        var breached = 0
        targets.forEach { target ->
            val changed = jdbc.update(
                """
                update sla_target_instances
                   set state = 'BREACHED', active_segment_started_at = null,
                       breached_at = ?, version = version + 1, updated_at = ?
                 where id = ? and state = 'ACTIVE'
                """.trimIndent(),
                now.atOffset(ZoneOffset.UTC),
                now.atOffset(ZoneOffset.UTC),
                target.id,
            )
            if (changed == 1) {
                breached++
                jdbc.update(
                    """
                    insert into sla_target_events
                        (id, target_id, event_type, previous_state, next_state, actor_type, actor_id,
                         source, request_id, correlation_id, ticket_audit_id, metadata_json, occurred_at)
                    values (?, ?, 'SLA_TARGET_BREACHED', 'ACTIVE', 'BREACHED', 'SYSTEM', null,
                            'SYSTEM_JOB', ?, ?, null, '{}'::jsonb, ?)
                    """.trimIndent(),
                    UUID.randomUUID(),
                    target.id,
                    "sla-scan-${target.id}",
                    "sla-scan-$owner",
                    now.atOffset(ZoneOffset.UTC),
                )
                jdbc.update(
                    """
                    update analytics_first_reply_facts
                       set outcome = 'BREACHED', breached_at = ?, projected_at = ?
                     where target_id = ?
                    """.trimIndent(),
                    now.atOffset(ZoneOffset.UTC),
                    now.atOffset(ZoneOffset.UTC),
                    target.id,
                )
            }
        }
        val checkpoint = targets.lastOrNull()
        jdbc.update(
            """
            update sla_breach_scan_state
               set lease_owner = null, lease_until = null, last_completed_at = ?,
                   last_target_due_at = ?, last_target_id = ?,
                   last_claimed_count = ?, last_breached_count = ?
             where id = 1
            """.trimIndent(),
            now.atOffset(ZoneOffset.UTC),
            checkpoint?.dueAt?.atOffset(ZoneOffset.UTC),
            checkpoint?.id,
            targets.size,
            breached,
        )
        return FirstReplySlaScanResult(targets.size, breached, checkpoint?.dueAt, checkpoint?.id)
    }

    private data class DueTarget(val id: UUID, val dueAt: Instant)

    private companion object {
        val LEASE_DURATION: Duration = Duration.ofMinutes(2)
        const val MAX_OWNER_LENGTH = 91
    }
}
