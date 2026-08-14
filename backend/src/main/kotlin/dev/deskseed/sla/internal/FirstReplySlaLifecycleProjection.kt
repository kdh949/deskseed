package dev.deskseed.sla.internal

import dev.deskseed.sla.BusinessScheduleProvider
import dev.deskseed.sla.FirstReplySlaPolicyMatcher
import dev.deskseed.sla.FirstReplySlaStateMachine
import dev.deskseed.sla.FirstReplySlaTargetClock
import dev.deskseed.sla.FirstReplySlaTicketSample
import dev.deskseed.sla.SlaTargetState
import dev.deskseed.ticketing.TicketKind
import dev.deskseed.ticketing.TicketSlaLifecycleChanged
import dev.deskseed.ticketing.TicketStatus
import dev.deskseed.ticketing.TicketSubmitted
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@Component
internal class FirstReplySlaLifecycleProjection(
    private val jdbc: JdbcTemplate,
    private val matcher: FirstReplySlaPolicyMatcher,
    private val schedules: BusinessScheduleProvider,
) {
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    fun onTicketSubmitted(event: TicketSubmitted) {
        jdbc.update(
            """
            insert into ticket_state_intervals
                (id, ticket_id, status, started_at, ended_at, start_audit_id, end_audit_id)
            values (?, ?, ?, ?, null, ?, null)
            on conflict (ticket_id) where ended_at is null do nothing
            """.trimIndent(),
            UUID.randomUUID(),
            event.ticketId,
            event.status.name,
            event.occurredAt.atOffset(ZoneOffset.UTC),
            event.ticketAuditId,
        )
        if (event.kind != TicketKind.CUSTOMER_REQUEST || !event.startsFirstReplySla) return
        if (projectionExists(event.ticketId)) return

        val policy = matcher.match(
            FirstReplySlaTicketSample(event.priority, event.groupId, event.channel),
        )
        if (policy == null) {
            upsertNoPolicyFact(event)
            return
        }
        val machine = FirstReplySlaStateMachine(
            policy.schedule.definition,
            policy.targetMinutes,
            policy.pauseStatuses,
        )
        val clock = machine.start(event.occurredAt, event.status)
        val targetId = UUID.randomUUID()
        val inserted = jdbc.update(
            """
            insert into sla_target_instances
                (id, ticket_id, metric, policy_id, policy_version, schedule_id, schedule_version,
                 priority_snapshot, target_minutes, pause_statuses, state, started_at,
                 active_segment_started_at, due_at, remaining_business_minutes, achieved_at,
                 breached_at, cancelled_at, calculation_version, version, updated_at)
            values (?, ?, 'FIRST_REPLY', ?, ?, ?, ?, ?, ?, ?::varchar[], ?, ?, ?, ?, ?, null, null, null,
                    ?, 0, ?)
            on conflict (ticket_id, metric) do nothing
            """.trimIndent(),
            targetId,
            event.ticketId,
            policy.policyId,
            policy.policyVersion,
            policy.schedule.id,
            policy.schedule.version,
            event.priority.name,
            policy.targetMinutes,
            policy.pauseStatuses.toPgArray(),
            clock.state.name,
            event.occurredAt.atOffset(ZoneOffset.UTC),
            clock.activeSegmentStartedAt?.atOffset(ZoneOffset.UTC),
            clock.dueAt?.atOffset(ZoneOffset.UTC),
            clock.remainingBusinessMinutes,
            CALCULATION_VERSION,
            event.occurredAt.atOffset(ZoneOffset.UTC),
        )
        if (inserted != 1) return
        appendEvent(
            targetId = targetId,
            eventType = "SLA_TARGET_STARTED",
            previousState = null,
            nextState = clock.state,
            actorType = event.actorType,
            actorId = event.actorId,
            source = event.source,
            requestId = event.requestId,
            correlationId = event.correlationId,
            ticketAuditId = event.ticketAuditId,
            occurredAt = event.occurredAt,
        )
        upsertTargetFact(event.ticketId, targetId, event.priority.name, policy.policyId, policy.policyVersion,
            policy.schedule.id, policy.schedule.version, policy.targetMinutes, clock, event.occurredAt)
    }

    private fun projectionExists(ticketId: UUID): Boolean = jdbc.queryForObject(
        """
        select exists (
            select 1 from sla_target_instances where ticket_id = ? and metric = 'FIRST_REPLY'
            union all
            select 1 from analytics_first_reply_facts where ticket_id = ?
        )
        """.trimIndent(),
        Boolean::class.java,
        ticketId,
        ticketId,
    ) == true

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    fun onTicketChanged(event: TicketSlaLifecycleChanged) {
        if (event.previousStatus != event.currentStatus) {
            jdbc.update(
                """
                update ticket_state_intervals set ended_at = ?, end_audit_id = ?
                 where ticket_id = ? and ended_at is null
                """.trimIndent(),
                event.occurredAt.atOffset(ZoneOffset.UTC),
                event.ticketAuditId,
                event.ticketId,
            )
            jdbc.update(
                """
                insert into ticket_state_intervals
                    (id, ticket_id, status, started_at, ended_at, start_audit_id, end_audit_id)
                values (?, ?, ?, ?, null, ?, null)
                """.trimIndent(),
                UUID.randomUUID(),
                event.ticketId,
                event.currentStatus.name,
                event.occurredAt.atOffset(ZoneOffset.UTC),
                event.ticketAuditId,
            )
        }

        val target = lockTarget(event.ticketId) ?: return
        if (target.clock.state.isTerminal()) return
        val schedule = schedules.exact(target.scheduleId, target.scheduleVersion)
            ?: throw IllegalStateException("Snapshotted business schedule is unavailable")
        val machine = FirstReplySlaStateMachine(
            schedule.definition,
            target.targetMinutes,
            target.pauseStatuses,
        )
        var next = target.clock
        if (event.humanStaffPublicReply) {
            next = machine.onStaffComment(next, public = true, humanStaff = true, event.occurredAt)
        }
        if (event.previousStatus != event.currentStatus) {
            next = machine.onStatusChanged(next, event.previousStatus, event.currentStatus, event.occurredAt)
        }
        if (next == target.clock) return

        jdbc.update(
            """
            update sla_target_instances
               set state = ?, active_segment_started_at = ?, due_at = ?, remaining_business_minutes = ?,
                   achieved_at = ?, breached_at = ?, cancelled_at = ?, version = version + 1, updated_at = ?
             where id = ? and version = ?
            """.trimIndent(),
            next.state.name,
            next.activeSegmentStartedAt?.atOffset(ZoneOffset.UTC),
            next.dueAt?.atOffset(ZoneOffset.UTC),
            next.remainingBusinessMinutes,
            next.achievedAt?.atOffset(ZoneOffset.UTC),
            next.breachedAt?.atOffset(ZoneOffset.UTC),
            next.cancelledAt?.atOffset(ZoneOffset.UTC),
            event.occurredAt.atOffset(ZoneOffset.UTC),
            target.id,
            target.version,
        )
        appendEvent(
            targetId = target.id,
            eventType = eventType(target.clock.state, next.state),
            previousState = target.clock.state,
            nextState = next.state,
            actorType = "STAFF",
            actorId = event.actorId,
            source = event.source,
            requestId = event.requestId,
            correlationId = event.correlationId,
            ticketAuditId = event.ticketAuditId,
            occurredAt = event.occurredAt,
        )
        upsertTargetFact(
            event.ticketId,
            target.id,
            target.priority,
            target.policyId,
            target.policyVersion,
            target.scheduleId,
            target.scheduleVersion,
            target.targetMinutes,
            next,
            event.occurredAt,
        )
    }

    private fun lockTarget(ticketId: UUID): TargetRow? = jdbc.query(
        """
        select id, policy_id, policy_version, schedule_id, schedule_version, priority_snapshot,
               target_minutes, pause_statuses, state, started_at, active_segment_started_at, due_at,
               remaining_business_minutes, achieved_at, breached_at, cancelled_at, version
          from sla_target_instances where ticket_id = ? and metric = 'FIRST_REPLY' for update
        """.trimIndent(),
        { result, _ -> targetRow(result) },
        ticketId,
    ).singleOrNull()

    private fun upsertNoPolicyFact(event: TicketSubmitted) {
        jdbc.update(
            """
            insert into analytics_first_reply_facts
                (ticket_id, target_id, outcome, priority_snapshot, policy_id, policy_version,
                 schedule_id, schedule_version, target_minutes, started_at, due_at, achieved_at,
                 breached_at, cancelled_at, calculation_version, projected_at)
            values (?, null, 'NO_POLICY', ?, null, null, null, null, null, ?, null, null, null, null, ?, ?)
            on conflict (ticket_id) do update set
                outcome = excluded.outcome, priority_snapshot = excluded.priority_snapshot,
                calculation_version = excluded.calculation_version, projected_at = excluded.projected_at
            """.trimIndent(),
            event.ticketId,
            event.priority.name,
            event.occurredAt.atOffset(ZoneOffset.UTC),
            CALCULATION_VERSION,
            event.occurredAt.atOffset(ZoneOffset.UTC),
        )
    }

    private fun upsertTargetFact(
        ticketId: UUID,
        targetId: UUID,
        priority: String,
        policyId: UUID,
        policyVersion: Int,
        scheduleId: UUID,
        scheduleVersion: Int,
        targetMinutes: Long,
        clock: FirstReplySlaTargetClock,
        projectedAt: Instant,
    ) {
        jdbc.update(
            """
            insert into analytics_first_reply_facts
                (ticket_id, target_id, outcome, priority_snapshot, policy_id, policy_version,
                 schedule_id, schedule_version, target_minutes, started_at, due_at, achieved_at,
                 breached_at, cancelled_at, calculation_version, projected_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (ticket_id) do update set
                target_id = excluded.target_id, outcome = excluded.outcome,
                priority_snapshot = excluded.priority_snapshot, policy_id = excluded.policy_id,
                policy_version = excluded.policy_version, schedule_id = excluded.schedule_id,
                schedule_version = excluded.schedule_version, target_minutes = excluded.target_minutes,
                due_at = excluded.due_at, achieved_at = excluded.achieved_at,
                breached_at = excluded.breached_at, cancelled_at = excluded.cancelled_at,
                calculation_version = excluded.calculation_version, projected_at = excluded.projected_at
            """.trimIndent(),
            ticketId,
            targetId,
            clock.state.name,
            priority,
            policyId,
            policyVersion,
            scheduleId,
            scheduleVersion,
            targetMinutes,
            clock.startedAt.atOffset(ZoneOffset.UTC),
            clock.dueAt?.atOffset(ZoneOffset.UTC),
            clock.achievedAt?.atOffset(ZoneOffset.UTC),
            clock.breachedAt?.atOffset(ZoneOffset.UTC),
            clock.cancelledAt?.atOffset(ZoneOffset.UTC),
            CALCULATION_VERSION,
            projectedAt.atOffset(ZoneOffset.UTC),
        )
    }

    private fun appendEvent(
        targetId: UUID,
        eventType: String,
        previousState: SlaTargetState?,
        nextState: SlaTargetState,
        actorType: String,
        actorId: UUID?,
        source: String,
        requestId: String,
        correlationId: String,
        ticketAuditId: UUID?,
        occurredAt: Instant,
    ) {
        jdbc.update(
            """
            insert into sla_target_events
                (id, target_id, event_type, previous_state, next_state, actor_type, actor_id,
                 source, request_id, correlation_id, ticket_audit_id, metadata_json, occurred_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '{}'::jsonb, ?)
            """.trimIndent(),
            UUID.randomUUID(),
            targetId,
            eventType,
            previousState?.name,
            nextState.name,
            actorType,
            actorId,
            source,
            requestId,
            correlationId,
            ticketAuditId,
            occurredAt.atOffset(ZoneOffset.UTC),
        )
    }

    private fun targetRow(result: ResultSet): TargetRow {
        val startedAt = result.getTimestamp("started_at").toInstant()
        return TargetRow(
            id = result.getObject("id", UUID::class.java),
            policyId = result.getObject("policy_id", UUID::class.java),
            policyVersion = result.getInt("policy_version"),
            scheduleId = result.getObject("schedule_id", UUID::class.java),
            scheduleVersion = result.getInt("schedule_version"),
            priority = result.getString("priority_snapshot"),
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

    private fun eventType(previous: SlaTargetState, next: SlaTargetState): String = when (next) {
        SlaTargetState.ACTIVE -> "SLA_TARGET_RESUMED"
        SlaTargetState.PAUSED -> "SLA_TARGET_PAUSED"
        SlaTargetState.ACHIEVED -> "SLA_TARGET_ACHIEVED"
        SlaTargetState.BREACHED -> "SLA_TARGET_BREACHED"
        SlaTargetState.CANCELLED -> "SLA_TARGET_CANCELLED"
    }

    private fun Set<TicketStatus>.toPgArray(): String =
        sortedBy(TicketStatus::ordinal).joinToString(prefix = "{", postfix = "}") { it.name }

    private fun SlaTargetState.isTerminal() =
        this == SlaTargetState.ACHIEVED || this == SlaTargetState.BREACHED || this == SlaTargetState.CANCELLED

    private data class TargetRow(
        val id: UUID,
        val policyId: UUID,
        val policyVersion: Int,
        val scheduleId: UUID,
        val scheduleVersion: Int,
        val priority: String,
        val targetMinutes: Long,
        val pauseStatuses: Set<TicketStatus>,
        val clock: FirstReplySlaTargetClock,
        val version: Long,
    )

    companion object {
        const val CALCULATION_VERSION = "FIRST_REPLY_BUSINESS_TIME_V1"
    }
}
