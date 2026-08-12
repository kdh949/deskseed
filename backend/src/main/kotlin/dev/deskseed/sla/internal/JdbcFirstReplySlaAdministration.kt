package dev.deskseed.sla.internal

import dev.deskseed.audit.AdminSecurityAudit
import dev.deskseed.audit.AdminSecurityAuditWriter
import dev.deskseed.audit.AdminSecurityOutcome
import dev.deskseed.foundation.ActorType
import dev.deskseed.sla.AppliedFirstReplySlaPolicy
import dev.deskseed.sla.BusinessScheduleProvider
import dev.deskseed.sla.DeterministicBusinessTimeCalculator
import dev.deskseed.sla.FirstReplyPolicyConditions
import dev.deskseed.sla.FirstReplySlaAdministration
import dev.deskseed.sla.FirstReplySlaPolicyConflictException
import dev.deskseed.sla.FirstReplySlaPolicyDefinition
import dev.deskseed.sla.FirstReplySlaPolicyMatcher
import dev.deskseed.sla.FirstReplySlaPolicyNotFoundException
import dev.deskseed.sla.FirstReplySlaPolicyPreconditionFailedException
import dev.deskseed.sla.FirstReplySlaPolicyValidationException
import dev.deskseed.sla.FirstReplySlaPolicyView
import dev.deskseed.sla.FirstReplySlaPreview
import dev.deskseed.sla.FirstReplySlaTicketSample
import dev.deskseed.sla.ScheduleVersionActorView
import dev.deskseed.sla.SlaAdminActor
import dev.deskseed.ticketing.TicketChannel
import dev.deskseed.ticketing.TicketPriority
import dev.deskseed.ticketing.TicketStatus
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@Service
internal class JdbcFirstReplySlaAdministration(
    private val jdbc: JdbcTemplate,
    private val schedules: BusinessScheduleProvider,
    private val auditWriter: AdminSecurityAuditWriter,
    private val clock: Clock,
) : FirstReplySlaAdministration, FirstReplySlaPolicyMatcher {
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    override fun list(): List<FirstReplySlaPolicyView> = jdbc.query(
        "select id from sla_policies order by created_at, id",
    ) { result, _ -> result.getObject("id", UUID::class.java) }.map(::latestView)

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    override fun get(policyId: UUID): FirstReplySlaPolicyView = latestView(policyId)

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    override fun listVersions(policyId: UUID): List<FirstReplySlaPolicyView> {
        val root = root(policyId)
        return jdbc.query(
            "select version from sla_policy_versions where policy_id = ? order by version desc",
            { result, _ -> result.getInt(1) },
            policyId,
        ).map { version -> versionView(root, version) }
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    override fun create(
        definition: FirstReplySlaPolicyDefinition,
        actor: SlaAdminActor,
    ): FirstReplySlaPolicyView {
        val schedule = activeSchedule(definition.scheduleId)
        val policyId = UUID.randomUUID()
        val now = Instant.now(clock)
        jdbc.update(
            """
            insert into sla_policies
                (id, current_version, active_version, aggregate_version, created_at, updated_at)
            values (?, 1, null, 0, ?, ?)
            """.trimIndent(),
            policyId,
            now.atOffset(ZoneOffset.UTC),
            now.atOffset(ZoneOffset.UTC),
        )
        insertVersion(policyId, 1, definition, schedule.version, actor, now)
        audit("SLA_POLICY_CREATED", policyId, actor, mapOf("version" to "1"), now)
        return versionView(root(policyId), 1)
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    override fun createVersion(
        policyId: UUID,
        expectedAggregateVersion: Long,
        definition: FirstReplySlaPolicyDefinition,
        actor: SlaAdminActor,
    ): FirstReplySlaPolicyView {
        val root = lockedRoot(policyId)
        requireExpected(root, expectedAggregateVersion)
        val schedule = activeSchedule(definition.scheduleId)
        val version = root.currentVersion + 1
        val aggregateVersion = root.aggregateVersion + 1
        val now = Instant.now(clock)
        jdbc.update(
            """
            update sla_policies set current_version = ?, aggregate_version = ?, updated_at = ? where id = ?
            """.trimIndent(),
            version,
            aggregateVersion,
            now.atOffset(ZoneOffset.UTC),
            policyId,
        )
        insertVersion(policyId, version, definition, schedule.version, actor, now)
        audit(
            "SLA_POLICY_VERSION_CREATED",
            policyId,
            actor,
            mapOf("previousVersion" to root.currentVersion.toString(), "version" to version.toString()),
            now,
        )
        return versionView(root(policyId), version)
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    override fun activate(
        policyId: UUID,
        policyVersion: Int,
        expectedAggregateVersion: Long,
        actor: SlaAdminActor,
    ): FirstReplySlaPolicyView {
        val root = lockedRoot(policyId)
        requireExpected(root, expectedAggregateVersion)
        val definition = versionView(root, policyVersion)
        if (definition.targets.isEmpty()) {
            throw FirstReplySlaPolicyValidationException(
                "targets",
                "TARGET_REQUIRED_FOR_ACTIVATION",
                "At least one priority target is required before activation",
            )
        }
        val schedule = schedules.exact(definition.scheduleId, definition.scheduleVersion)
            ?: throw FirstReplySlaPolicyValidationException(
                "scheduleId",
                "SCHEDULE_VERSION_NOT_FOUND",
                "The snapshotted business schedule version is unavailable",
            )
        if (!schedule.definition.hasRecurringCapacity()) {
            throw FirstReplySlaPolicyValidationException(
                "scheduleId",
                "RECURRING_SCHEDULE_CAPACITY_REQUIRED",
                "An active SLA policy requires recurring weekly business hours",
            )
        }
        if (root.activeVersion == policyVersion) return definition
        val now = Instant.now(clock)
        jdbc.update(
            "update sla_policies set active_version = ?, aggregate_version = ?, updated_at = ? where id = ?",
            policyVersion,
            root.aggregateVersion + 1,
            now.atOffset(ZoneOffset.UTC),
            policyId,
        )
        jdbc.update(
            """
            insert into sla_policy_activations
                (id, policy_id, policy_version, actor_id, actor_display_snapshot,
                 request_id, correlation_id, activated_at)
            values (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            UUID.randomUUID(),
            policyId,
            policyVersion,
            actor.staffId,
            actor.displayName.take(100),
            actor.requestId,
            actor.correlationId,
            now.atOffset(ZoneOffset.UTC),
        )
        audit(
            "SLA_POLICY_ACTIVATED",
            policyId,
            actor,
            mapOf(
                "previousVersion" to (root.activeVersion?.toString() ?: "none"),
                "version" to policyVersion.toString(),
            ),
            now,
        )
        return versionView(root(policyId), policyVersion)
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    override fun preview(
        candidatePolicyId: UUID?,
        candidate: FirstReplySlaPolicyDefinition?,
        ticket: FirstReplySlaTicketSample,
        startAt: Instant,
    ): FirstReplySlaPreview {
        require(candidate != null || candidatePolicyId == null) {
            "candidatePolicyId requires a candidate definition"
        }
        val persisted = matchingRows(ticket)
            .filterNot { it.policyId == candidatePolicyId }
            .firstOrNull()
        val candidateTarget = candidate
            ?.takeIf { matches(it.conditions, ticket) }
            ?.targets
            ?.get(ticket.priority)
        val candidateWins = candidate != null && candidateTarget != null && (
            persisted == null ||
                candidate.position < persisted.position ||
                candidate.position == persisted.position &&
                candidatePolicyId != null && candidatePolicyId.toString() < persisted.policyId.toString()
            )
        if (candidateWins) {
            val schedule = activeSchedule(candidate.scheduleId)
            return FirstReplySlaPreview(
                matched = true,
                dueAt = DeterministicBusinessTimeCalculator(schedule.definition)
                    .addBusinessMinutes(startAt, checkNotNull(candidateTarget)),
                targetMinutes = candidateTarget,
                policyId = null,
                policyVersion = null,
                scheduleId = schedule.id,
                scheduleVersion = schedule.version,
            )
        }
        val matched = persisted?.let(::appliedPolicy)
            ?: return FirstReplySlaPreview(false, null, null, null, null, null, null)
        return FirstReplySlaPreview(
            matched = true,
            dueAt = DeterministicBusinessTimeCalculator(matched.schedule.definition)
                .addBusinessMinutes(startAt, matched.targetMinutes),
            targetMinutes = matched.targetMinutes,
            policyId = matched.policyId,
            policyVersion = matched.policyVersion,
            scheduleId = matched.schedule.id,
            scheduleVersion = matched.schedule.version,
        )
    }

    @Transactional(readOnly = true)
    override fun match(ticket: FirstReplySlaTicketSample): AppliedFirstReplySlaPolicy? {
        val row = matchingRows(ticket).firstOrNull() ?: return null
        return appliedPolicy(row)
    }

    private fun matchingRows(ticket: FirstReplySlaTicketSample): List<MatchRow> = jdbc.query(
        """
            select v.policy_id, v.version, v.position, v.schedule_id, v.schedule_version,
                   v.condition_group_id, v.condition_channel, target.target_minutes
              from sla_policies root
              join sla_policy_versions v
                on v.policy_id = root.id and v.version = root.active_version
              join sla_policy_priority_targets target
                on target.policy_id = v.policy_id and target.policy_version = v.version
               and target.priority = ?
             where (v.condition_group_id is null or v.condition_group_id = ?)
               and (v.condition_channel is null or v.condition_channel = ?)
             order by v.position, v.policy_id
        """.trimIndent(),
        { result, _ -> matchRow(result) },
        ticket.priority.name,
        ticket.groupId,
        ticket.channel.name,
    )

    private fun appliedPolicy(row: MatchRow): AppliedFirstReplySlaPolicy {
        val schedule = schedules.exact(row.scheduleId, row.scheduleVersion)
            ?: throw IllegalStateException("Snapshotted business schedule is unavailable")
        val pauses = jdbc.query(
            """
            select status from sla_policy_pause_statuses
             where policy_id = ? and policy_version = ? order by status
            """.trimIndent(),
            { result, _ -> TicketStatus.valueOf(result.getString(1)) },
            row.policyId,
            row.policyVersion,
        ).toSet()
        return AppliedFirstReplySlaPolicy(
            row.policyId,
            row.policyVersion,
            schedule,
            row.targetMinutes,
            pauses,
        )
    }

    private fun latestView(policyId: UUID): FirstReplySlaPolicyView {
        val root = root(policyId)
        return versionView(root, root.currentVersion)
    }

    private fun root(policyId: UUID) = roots(
        "select id, current_version, active_version, aggregate_version from sla_policies where id = ?",
        policyId,
    ).singleOrNull() ?: throw FirstReplySlaPolicyNotFoundException()

    private fun lockedRoot(policyId: UUID) = roots(
        "select id, current_version, active_version, aggregate_version from sla_policies where id = ? for update",
        policyId,
    ).singleOrNull() ?: throw FirstReplySlaPolicyNotFoundException()

    private fun roots(sql: String, policyId: UUID): List<PolicyRoot> = jdbc.query(
        sql,
        { result, _ ->
            PolicyRoot(
                result.getObject("id", UUID::class.java),
                result.getInt("current_version"),
                result.getInt("active_version").takeUnless { result.wasNull() },
                result.getLong("aggregate_version"),
            )
        },
        policyId,
    )

    private fun versionView(root: PolicyRoot, version: Int): FirstReplySlaPolicyView {
        val metadata = jdbc.query(
            """
            select name, position, schedule_id, schedule_version, condition_group_id, condition_channel,
                   created_by_staff_id, created_by_display, created_at
              from sla_policy_versions where policy_id = ? and version = ?
            """.trimIndent(),
            { result, _ -> versionRow(result) },
            root.id,
            version,
        ).singleOrNull() ?: throw FirstReplySlaPolicyNotFoundException()
        val targets = jdbc.query(
            "select priority, target_minutes from sla_policy_priority_targets where policy_id = ? and policy_version = ?",
            { result, _ -> TicketPriority.valueOf(result.getString(1)) to result.getLong(2) },
            root.id,
            version,
        ).toMap()
        val pauses = jdbc.query(
            "select status from sla_policy_pause_statuses where policy_id = ? and policy_version = ?",
            { result, _ -> TicketStatus.valueOf(result.getString(1)) },
            root.id,
            version,
        ).toSet()
        return FirstReplySlaPolicyView(
            id = root.id,
            name = metadata.name,
            position = metadata.position,
            scheduleId = metadata.scheduleId,
            scheduleVersion = metadata.scheduleVersion,
            conditions = FirstReplyPolicyConditions(metadata.groupId, metadata.channel),
            targets = targets,
            pauseStatuses = pauses,
            version = version,
            activeVersion = root.activeVersion,
            aggregateVersion = root.aggregateVersion,
            active = root.activeVersion == version,
            createdAt = metadata.createdAt,
            createdBy = ScheduleVersionActorView(
                ActorType.STAFF,
                metadata.createdById,
                metadata.createdByDisplay,
            ),
        )
    }

    private fun insertVersion(
        policyId: UUID,
        version: Int,
        definition: FirstReplySlaPolicyDefinition,
        scheduleVersion: Int,
        actor: SlaAdminActor,
        now: Instant,
    ) {
        try {
            jdbc.update(
                """
                insert into sla_policy_versions
                    (policy_id, version, name, position, schedule_id, schedule_version,
                     condition_group_id, condition_channel, created_by_staff_id, created_by_display, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                policyId,
                version,
                definition.name.trim(),
                definition.position,
                definition.scheduleId,
                scheduleVersion,
                definition.conditions.groupId,
                definition.conditions.channel?.name,
                actor.staffId,
                actor.displayName.take(100),
                now.atOffset(ZoneOffset.UTC),
            )
        } catch (failure: DataIntegrityViolationException) {
            throw FirstReplySlaPolicyConflictException("INVALID_POLICY_REFERENCE")
        }
        definition.targets.toSortedMap(compareBy(TicketPriority::ordinal)).forEach { (priority, minutes) ->
            jdbc.update(
                """
                insert into sla_policy_priority_targets
                    (policy_id, policy_version, priority, target_minutes) values (?, ?, ?, ?)
                """.trimIndent(),
                policyId,
                version,
                priority.name,
                minutes,
            )
        }
        definition.pauseStatuses.sortedBy(TicketStatus::ordinal).forEach { status ->
            jdbc.update(
                "insert into sla_policy_pause_statuses (policy_id, policy_version, status) values (?, ?, ?)",
                policyId,
                version,
                status.name,
            )
        }
    }

    private fun activeSchedule(scheduleId: UUID) = schedules.active(scheduleId)
        ?: throw FirstReplySlaPolicyValidationException(
            "scheduleId",
            "ACTIVE_SCHEDULE_REQUIRED",
            "The policy must reference a business schedule with an active version",
        )

    private fun requireExpected(root: PolicyRoot, expected: Long) {
        if (root.aggregateVersion != expected) {
            throw FirstReplySlaPolicyPreconditionFailedException(root.aggregateVersion)
        }
    }

    private fun matches(conditions: FirstReplyPolicyConditions, ticket: FirstReplySlaTicketSample) =
        (conditions.groupId == null || conditions.groupId == ticket.groupId) &&
            (conditions.channel == null || conditions.channel == ticket.channel)

    private fun dev.deskseed.sla.BusinessScheduleDefinition.hasRecurringCapacity(): Boolean =
        weekdays.values.any { it.enabled && it.intervals.isNotEmpty() }

    private fun audit(
        eventType: String,
        policyId: UUID,
        actor: SlaAdminActor,
        metadata: Map<String, String>,
        occurredAt: Instant,
    ) {
        auditWriter.append(
            AdminSecurityAudit(
                eventType = eventType,
                actorType = ActorType.STAFF,
                actorId = actor.staffId,
                actorDisplaySnapshot = actor.displayName,
                source = actor.source,
                targetType = "SLA_POLICY",
                targetId = policyId,
                outcome = AdminSecurityOutcome.SUCCEEDED,
                requestId = actor.requestId,
                correlationId = actor.correlationId,
                metadata = metadata,
                occurredAt = occurredAt,
            ),
        )
    }

    private fun versionRow(result: ResultSet) = PolicyVersionRow(
        name = result.getString("name"),
        position = result.getInt("position"),
        scheduleId = result.getObject("schedule_id", UUID::class.java),
        scheduleVersion = result.getInt("schedule_version"),
        groupId = result.getObject("condition_group_id", UUID::class.java),
        channel = result.getString("condition_channel")?.let(TicketChannel::valueOf),
        createdById = result.getObject("created_by_staff_id", UUID::class.java),
        createdByDisplay = result.getString("created_by_display"),
        createdAt = result.getTimestamp("created_at").toInstant(),
    )

    private fun matchRow(result: ResultSet) = MatchRow(
        result.getObject("policy_id", UUID::class.java),
        result.getInt("version"),
        result.getInt("position"),
        result.getObject("schedule_id", UUID::class.java),
        result.getInt("schedule_version"),
        result.getLong("target_minutes"),
    )

    private data class PolicyRoot(
        val id: UUID,
        val currentVersion: Int,
        val activeVersion: Int?,
        val aggregateVersion: Long,
    )

    private data class PolicyVersionRow(
        val name: String,
        val position: Int,
        val scheduleId: UUID,
        val scheduleVersion: Int,
        val groupId: UUID?,
        val channel: TicketChannel?,
        val createdById: UUID,
        val createdByDisplay: String,
        val createdAt: Instant,
    )

    private data class MatchRow(
        val policyId: UUID,
        val policyVersion: Int,
        val position: Int,
        val scheduleId: UUID,
        val scheduleVersion: Int,
        val targetMinutes: Long,
    )
}
