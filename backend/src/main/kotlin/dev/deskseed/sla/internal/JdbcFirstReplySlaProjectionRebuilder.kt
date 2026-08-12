package dev.deskseed.sla.internal

import dev.deskseed.sla.BusinessScheduleProvider
import dev.deskseed.sla.FirstReplySlaProjectionRebuildResult
import dev.deskseed.sla.FirstReplySlaProjectionRebuilder
import dev.deskseed.sla.FirstReplySlaStateMachine
import dev.deskseed.sla.FirstReplySlaTargetClock
import dev.deskseed.sla.SlaTargetState
import dev.deskseed.ticketing.TicketStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@Component
internal class JdbcFirstReplySlaProjectionRebuilder(
    private val jdbc: JdbcTemplate,
    private val schedules: BusinessScheduleProvider,
) : FirstReplySlaProjectionRebuilder {
    @Transactional
    override fun rebuild(ticketId: UUID): FirstReplySlaProjectionRebuildResult {
        val ticket = lockTicket(ticketId) ?: throw IllegalArgumentException("Ticket does not exist")
        val transitions = loadStatusTransitions(ticketId)
        val initialStatus = transitions.firstOrNull()?.previousStatus ?: ticket.currentStatus

        jdbc.update("delete from ticket_state_intervals where ticket_id = ?", ticketId)
        var status = initialStatus
        var startedAt = ticket.createdAt
        var startAuditId = ticket.createdAuditId
        var intervalCount = 0
        transitions.forEach { transition ->
            jdbc.update(
                """
                insert into ticket_state_intervals
                    (id, ticket_id, status, started_at, ended_at, start_audit_id, end_audit_id)
                values (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                UUID.randomUUID(),
                ticketId,
                status.name,
                startedAt.atOffset(ZoneOffset.UTC),
                transition.occurredAt.atOffset(ZoneOffset.UTC),
                startAuditId,
                transition.auditId,
            )
            intervalCount++
            status = transition.newStatus
            startedAt = transition.occurredAt
            startAuditId = transition.auditId
        }
        jdbc.update(
            """
            insert into ticket_state_intervals
                (id, ticket_id, status, started_at, ended_at, start_audit_id, end_audit_id)
            values (?, ?, ?, ?, null, ?, null)
            """.trimIndent(),
            UUID.randomUUID(),
            ticketId,
            status.name,
            startedAt.atOffset(ZoneOffset.UTC),
            startAuditId,
        )
        intervalCount++

        val recalculated = recalculateOpenTarget(ticketId)
        return FirstReplySlaProjectionRebuildResult(ticketId, intervalCount, recalculated)
    }

    private fun lockTicket(ticketId: UUID): TicketSeed? = jdbc.query(
        """
        select t.created_at, t.status,
               (select a.id
                  from ticket_audits a
                  join ticket_audit_events e on e.audit_id = a.id
                 where a.ticket_id = t.id and e.event_type = 'TICKET_CREATED'
                 order by e.occurred_at, e.event_order, e.id
                 limit 1) as created_audit_id
          from tickets t where t.id = ? for update
        """.trimIndent(),
        { result, _ ->
            TicketSeed(
                result.getTimestamp("created_at").toInstant(),
                TicketStatus.valueOf(result.getString("status")),
                result.getObject("created_audit_id", UUID::class.java)
                    ?: error("Ticket creation audit is unavailable"),
            )
        },
        ticketId,
    ).singleOrNull()

    private fun loadStatusTransitions(ticketId: UUID): List<StatusTransition> = jdbc.query(
        """
        select a.id as audit_id, e.occurred_at,
               e.old_value_json::jsonb #>> '{}' as previous_status,
               e.new_value_json::jsonb #>> '{}' as new_status
          from ticket_audits a
          join ticket_audit_events e on e.audit_id = a.id
         where a.ticket_id = ? and e.event_type = 'STATUS_CHANGED'
         order by e.occurred_at, a.ticket_version, e.event_order, e.id
        """.trimIndent(),
        { result, _ ->
            StatusTransition(
                result.getObject("audit_id", UUID::class.java),
                result.getTimestamp("occurred_at").toInstant(),
                TicketStatus.valueOf(result.getString("previous_status")),
                TicketStatus.valueOf(result.getString("new_status")),
            )
        },
        ticketId,
    )

    private fun recalculateOpenTarget(ticketId: UUID): Boolean {
        val target = jdbc.query(
            """
            select id, schedule_id, schedule_version, target_minutes, pause_statuses, state,
                   started_at, active_segment_started_at, due_at, remaining_business_minutes,
                   achieved_at, breached_at, cancelled_at, version
              from sla_target_instances
             where ticket_id = ? and metric = 'FIRST_REPLY'
             for update
            """.trimIndent(),
            { result, _ -> openTargetRow(result) },
            ticketId,
        ).singleOrNull() ?: return false
        if (target.clock.state.isTerminal()) return false

        val intervals = jdbc.query(
            """
            select status, started_at, ended_at
              from ticket_state_intervals
             where ticket_id = ?
               and (ended_at is null or ended_at >= ?)
             order by started_at, id
            """.trimIndent(),
            { result, _ ->
                StatusInterval(
                    TicketStatus.valueOf(result.getString("status")),
                    result.getTimestamp("started_at").toInstant(),
                    result.getTimestamp("ended_at")?.toInstant(),
                )
            },
            ticketId,
            target.clock.startedAt.atOffset(ZoneOffset.UTC),
        )
        val initial = intervals.firstOrNull {
            !it.startedAt.isAfter(target.clock.startedAt) &&
                (it.endedAt == null || !it.endedAt.isBefore(target.clock.startedAt))
        } ?: error("Target start is not covered by a ticket status interval")
        val schedule = schedules.exact(target.scheduleId, target.scheduleVersion)
            ?: error("Snapshotted business schedule is unavailable")
        val machine = FirstReplySlaStateMachine(schedule.definition, target.targetMinutes, target.pauseStatuses)
        var replayed = machine.start(target.clock.startedAt, initial.status)
        var previousStatus = initial.status
        intervals.asSequence()
            .filter { it.startedAt.isAfter(target.clock.startedAt) }
            .forEach { interval ->
                replayed = machine.onStatusChanged(replayed, previousStatus, interval.status, interval.startedAt)
                previousStatus = interval.status
            }

        if (replayed == target.clock) return false
        jdbc.update(
            """
            update sla_target_instances
               set state = ?, active_segment_started_at = ?, due_at = ?,
                   remaining_business_minutes = ?, achieved_at = ?, breached_at = ?, cancelled_at = ?,
                   version = version + 1, updated_at = now()
             where id = ? and version = ?
            """.trimIndent(),
            replayed.state.name,
            replayed.activeSegmentStartedAt?.atOffset(ZoneOffset.UTC),
            replayed.dueAt?.atOffset(ZoneOffset.UTC),
            replayed.remainingBusinessMinutes,
            replayed.achievedAt?.atOffset(ZoneOffset.UTC),
            replayed.breachedAt?.atOffset(ZoneOffset.UTC),
            replayed.cancelledAt?.atOffset(ZoneOffset.UTC),
            target.id,
            target.version,
        )
        jdbc.update(
            """
            update analytics_first_reply_facts
               set outcome = ?, due_at = ?, achieved_at = ?, breached_at = ?, cancelled_at = ?,
                   projected_at = now()
             where target_id = ?
            """.trimIndent(),
            replayed.state.name,
            replayed.dueAt?.atOffset(ZoneOffset.UTC),
            replayed.achievedAt?.atOffset(ZoneOffset.UTC),
            replayed.breachedAt?.atOffset(ZoneOffset.UTC),
            replayed.cancelledAt?.atOffset(ZoneOffset.UTC),
            target.id,
        )
        return true
    }

    private fun openTargetRow(result: ResultSet): OpenTargetRow {
        val startedAt = result.getTimestamp("started_at").toInstant()
        return OpenTargetRow(
            id = result.getObject("id", UUID::class.java),
            scheduleId = result.getObject("schedule_id", UUID::class.java),
            scheduleVersion = result.getInt("schedule_version"),
            targetMinutes = result.getLong("target_minutes"),
            pauseStatuses = (result.getArray("pause_statuses").array as Array<*>)
                .map { TicketStatus.valueOf(it.toString()) }.toSet(),
            clock = FirstReplySlaTargetClock(
                state = SlaTargetState.valueOf(result.getString("state")),
                startedAt = startedAt,
                activeSegmentStartedAt = result.getTimestamp("active_segment_started_at")?.toInstant(),
                dueAt = result.getTimestamp("due_at")?.toInstant(),
                remainingBusinessMinutes = result.getLong("remaining_business_minutes"),
                achievedAt = result.getTimestamp("achieved_at")?.toInstant(),
                breachedAt = result.getTimestamp("breached_at")?.toInstant(),
                cancelledAt = result.getTimestamp("cancelled_at")?.toInstant(),
            ),
            version = result.getLong("version"),
        )
    }

    private fun SlaTargetState.isTerminal() =
        this == SlaTargetState.ACHIEVED || this == SlaTargetState.BREACHED || this == SlaTargetState.CANCELLED

    private data class TicketSeed(
        val createdAt: Instant,
        val currentStatus: TicketStatus,
        val createdAuditId: UUID,
    )

    private data class StatusTransition(
        val auditId: UUID,
        val occurredAt: Instant,
        val previousStatus: TicketStatus,
        val newStatus: TicketStatus,
    )

    private data class StatusInterval(
        val status: TicketStatus,
        val startedAt: Instant,
        val endedAt: Instant?,
    )

    private data class OpenTargetRow(
        val id: UUID,
        val scheduleId: UUID,
        val scheduleVersion: Int,
        val targetMinutes: Long,
        val pauseStatuses: Set<TicketStatus>,
        val clock: FirstReplySlaTargetClock,
        val version: Long,
    )
}
