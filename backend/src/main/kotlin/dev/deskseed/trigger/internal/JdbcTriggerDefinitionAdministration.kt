package dev.deskseed.trigger.internal

import dev.deskseed.audit.AdminSecurityAudit
import dev.deskseed.audit.AdminSecurityAuditWriter
import dev.deskseed.audit.AdminSecurityOutcome
import dev.deskseed.foundation.ActorType
import dev.deskseed.organization.StaffAuthorityCatalog
import dev.deskseed.trigger.TriggerHistory
import dev.deskseed.trigger.TriggerVersionSummary
import dev.deskseed.trigger.TriggerActivationSummary
import dev.deskseed.trigger.TriggerExecutionSummary
import dev.deskseed.trigger.TriggerJobSummary
import dev.deskseed.trigger.TriggerActionDefinition
import dev.deskseed.trigger.TriggerActionType
import dev.deskseed.trigger.TriggerAuditUnavailableException
import dev.deskseed.trigger.TriggerConditionDefinition
import dev.deskseed.trigger.TriggerConditionField
import dev.deskseed.trigger.TriggerConditionGroup
import dev.deskseed.trigger.TriggerConditionOperator
import dev.deskseed.trigger.TriggerConflictException
import dev.deskseed.trigger.TriggerDefinitionActor
import dev.deskseed.trigger.TriggerDefinitionAdministration
import dev.deskseed.trigger.TriggerDefinitionDraft
import dev.deskseed.trigger.TriggerDefinitionView
import dev.deskseed.trigger.TriggerDryRunResult
import dev.deskseed.trigger.TriggerEventType
import dev.deskseed.trigger.TriggerNotFoundException
import dev.deskseed.trigger.TriggerPreconditionFailedException
import dev.deskseed.trigger.TriggerSetGroupAction
import dev.deskseed.trigger.TriggerSetAssigneeAction
import dev.deskseed.trigger.TriggerWebhookAction
import org.springframework.dao.DataAccessException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
internal class JdbcTriggerDefinitionAdministration(
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val auditWriter: AdminSecurityAuditWriter,
    private val clock: Clock,
    private val evaluation: TriggerTicketEvaluation,
) : TriggerDefinitionAdministration {
    @Transactional(readOnly = true)
    override fun list(actor: TriggerDefinitionActor): List<TriggerDefinitionView> {
        requireAccess(actor)
        return jdbc.query("$SELECT order by definition.position, definition.id limit 200", ::state)
            .map { view(it, it.currentVersion) }
    }

    @Transactional(readOnly = true)
    override fun version(id: UUID, version: Int, actor: TriggerDefinitionActor): TriggerDefinitionView {
        requireAccess(actor)
        require(version > 0)
        if (!versionExists(id, version)) throw TriggerNotFoundException()
        return view(stateById(id), version)
    }

    @Transactional(readOnly = true)
    override fun history(id: UUID, actor: TriggerDefinitionActor): TriggerHistory {
        requireAccess(actor)
        stateById(id)
        val versions = jdbc.query("select version,name,created_by_display,created_at from trigger_versions where trigger_id = ? order by version desc limit 20", { rs, _ -> TriggerVersionSummary(rs.getInt(1),rs.getString(2),rs.getString(3),rs.getTimestamp(4).toInstant()) }, id)
        val activations = jdbc.query("select trigger_version,activation_state,actor_display,occurred_at from trigger_activations where trigger_id = ? order by occurred_at desc,id desc limit 50", { rs, _ -> TriggerActivationSummary(rs.getInt(1),rs.getString(2),rs.getString(3),rs.getTimestamp(4).toInstant()) }, id)
        val executions = jdbc.query("""select execution.id, execution.trigger_version, job.ticket_number, execution.outcome, execution.ticket_audit_id, execution.error_code, execution.completed_at
            from trigger_executions execution join trigger_evaluation_jobs job on job.id = execution.job_id
            where execution.trigger_id = ? order by execution.completed_at desc, execution.id desc limit 50""", { rs, _ -> TriggerExecutionSummary(rs.getObject(1,UUID::class.java),rs.getInt(2),rs.getLong(3),rs.getString(4),rs.getObject(5,UUID::class.java),rs.getString(6),rs.getTimestamp(7).toInstant()) }, id)
        val jobs = jdbc.query("""select id,ticket_number,event_type,status,attempt_count,last_error_code,created_at from trigger_evaluation_jobs
            where trigger_versions_json @> cast(? as jsonb) order by created_at desc,id desc limit 50""", { rs, _ -> TriggerJobSummary(rs.getObject(1,UUID::class.java),rs.getLong(2),TriggerEventType.valueOf(rs.getString(3)),rs.getString(4),rs.getInt(5),rs.getString(6),rs.getTimestamp(7).toInstant()) }, objectMapper.writeValueAsString(listOf(mapOf("triggerId" to id.toString()))))
        return TriggerHistory(versions, activations, executions, jobs)
    }

    @Transactional
    override fun create(position: Int, draft: TriggerDefinitionDraft, actor: TriggerDefinitionActor): TriggerDefinitionView =
        translateStorageFailure {
            requireAccess(actor)
            require(position in 1..10_000) { "Trigger position is invalid" }
            val id = UUID.randomUUID()
            val now = Instant.now(clock)
            try {
                jdbc.update(
                    """
                    insert into trigger_definitions (
                        id, normalized_name, name, position, current_version, active_version,
                        aggregate_version, created_at, updated_at
                    ) values (?, ?, ?, ?, 1, null, 1, ?, ?)
                    """.trimIndent(),
                    id, draft.normalizedName, draft.name.trim(), position, Timestamp.from(now), Timestamp.from(now),
                )
                insertVersion(id, 1, draft, actor, now)
            } catch (_: DataIntegrityViolationException) {
                throw TriggerConflictException("TRIGGER_NAME_OR_POSITION_EXISTS")
            }
            audit("TRIGGER_CREATED", id, actor, now, mapOf("triggerVersion" to "1", "position" to position.toString()))
            view(stateById(id), 1)
        }

    @Transactional
    override fun createVersion(
        triggerId: UUID,
        expectedAggregateVersion: Long,
        draft: TriggerDefinitionDraft,
        actor: TriggerDefinitionActor,
    ): TriggerDefinitionView = translateStorageFailure {
        requireAccess(actor)
        val current = lockedState(triggerId)
        requireExpected(current, expectedAggregateVersion)
        val nextVersion = current.currentVersion + 1
        val now = Instant.now(clock)
        try {
            insertVersion(triggerId, nextVersion, draft, actor, now)
            jdbc.update(
                """
                update trigger_definitions
                   set normalized_name = ?, name = ?, current_version = ?, aggregate_version = aggregate_version + 1,
                       updated_at = ?
                 where id = ?
                """.trimIndent(),
                draft.normalizedName, draft.name.trim(), nextVersion, Timestamp.from(now), triggerId,
            )
        } catch (_: DataIntegrityViolationException) {
            throw TriggerConflictException("TRIGGER_NAME_EXISTS")
        }
        audit("TRIGGER_VERSION_CREATED", triggerId, actor, now, mapOf("triggerVersion" to nextVersion.toString()))
        view(stateById(triggerId), nextVersion)
    }

    @Transactional
    override fun activate(
        triggerId: UUID,
        triggerVersion: Int,
        expectedAggregateVersion: Long,
        actor: TriggerDefinitionActor,
    ): TriggerDefinitionView = translateStorageFailure {
        requireAccess(actor)
        val current = lockedState(triggerId)
        requireExpected(current, expectedAggregateVersion)
        if (!versionExists(triggerId, triggerVersion)) throw TriggerNotFoundException()
        validateDefinitionTargets(triggerId, triggerVersion)
        if (current.activeVersion == null) {
            lockActiveTriggerSet()
            if (activeTriggerCount() >= MAX_ACTIVE_TRIGGER_COUNT) {
                throw TriggerConflictException("ACTIVE_TRIGGER_LIMIT_REACHED")
            }
        }
        val now = Instant.now(clock)
        jdbc.update(
            "update trigger_definitions set active_version = ?, aggregate_version = aggregate_version + 1, updated_at = ? where id = ?",
            triggerVersion, Timestamp.from(now), triggerId,
        )
        insertActivation(triggerId, triggerVersion, "ACTIVE", actor, now)
        audit("TRIGGER_ACTIVATED", triggerId, actor, now, mapOf("triggerVersion" to triggerVersion.toString()))
        view(stateById(triggerId), triggerVersion)
    }

    @Transactional
    override fun deactivate(
        triggerId: UUID,
        expectedAggregateVersion: Long,
        actor: TriggerDefinitionActor,
    ): TriggerDefinitionView = translateStorageFailure {
        requireAccess(actor)
        val current = lockedState(triggerId)
        requireExpected(current, expectedAggregateVersion)
        val activeVersion = current.activeVersion ?: throw TriggerConflictException("TRIGGER_NOT_ACTIVE")
        lockActiveTriggerSet()
        val now = Instant.now(clock)
        jdbc.update(
            "update trigger_definitions set active_version = null, aggregate_version = aggregate_version + 1, updated_at = ? where id = ?",
            Timestamp.from(now), triggerId,
        )
        insertActivation(triggerId, activeVersion, "INACTIVE", actor, now)
        audit("TRIGGER_DEACTIVATED", triggerId, actor, now, mapOf("triggerVersion" to activeVersion.toString()))
        view(stateById(triggerId), current.currentVersion)
    }

    @Transactional
    override fun reposition(
        triggerId: UUID,
        position: Int,
        expectedAggregateVersion: Long,
        actor: TriggerDefinitionActor,
    ): TriggerDefinitionView = translateStorageFailure {
        requireAccess(actor)
        require(position in 1..10_000) { "Trigger position is invalid" }
        val current = lockedState(triggerId)
        requireExpected(current, expectedAggregateVersion)
        val displaced = stateAtPosition(position, triggerId)
        val now = Instant.now(clock)
        jdbc.execute("set constraints trigger_definitions_position_key deferred")
        jdbc.update(
            "update trigger_definitions set position = ?, aggregate_version = aggregate_version + 1, updated_at = ? where position = ? and id <> ?",
            current.position, Timestamp.from(now), position, triggerId,
        )
        jdbc.update(
            "update trigger_definitions set position = ?, aggregate_version = aggregate_version + 1, updated_at = ? where id = ?",
            position, Timestamp.from(now), triggerId,
        )
        val targetMetadata = mutableMapOf(
            "previousPosition" to current.position.toString(),
            "position" to position.toString(),
        )
        displaced?.let { counterpart ->
            targetMetadata += mapOf(
                "counterpartTriggerId" to counterpart.id.toString(),
                "counterpartPreviousPosition" to counterpart.position.toString(),
                "counterpartPosition" to current.position.toString(),
            )
        }
        audit("TRIGGER_REPOSITIONED", triggerId, actor, now, targetMetadata)
        displaced?.let { counterpart ->
            audit(
                "TRIGGER_REPOSITIONED",
                counterpart.id,
                actor,
                now,
                mapOf(
                    "previousPosition" to counterpart.position.toString(),
                    "position" to current.position.toString(),
                    "counterpartTriggerId" to current.id.toString(),
                    "counterpartPreviousPosition" to current.position.toString(),
                    "counterpartPosition" to position.toString(),
                ),
            )
        }
        view(stateById(triggerId), current.currentVersion)
    }

    @Transactional(readOnly = true)
    override fun dryRun(
        triggerId: UUID,
        triggerVersion: Int,
        ticketNumber: Long,
        eventType: TriggerEventType,
        actor: TriggerDefinitionActor,
    ): TriggerDryRunResult {
        requireAccess(actor)
        val current = stateById(triggerId)
        if (!versionExists(triggerId, triggerVersion)) throw TriggerNotFoundException()
        val definition = view(current, triggerVersion)
        val ticket = evaluation.load(ticketNumber) ?: throw TriggerNotFoundException()
        val outcomes = evaluation.outcomes(definition.conditions, eventType, ticket)
        val matched = evaluation.matched(definition.conditions, outcomes)
        val failures = evaluation.failures(definition.actions, ticket)
        return TriggerDryRunResult(
            ticketNumber, triggerId, triggerVersion, matched,
            outcomes.indices.filter(outcomes::get), outcomes.indices.filterNot(outcomes::get),
            definition.actions.map(TriggerActionDefinition::type), failures,
        )
    }

    private fun insertVersion(
        triggerId: UUID,
        version: Int,
        draft: TriggerDefinitionDraft,
        actor: TriggerDefinitionActor,
        now: Instant,
    ) {
        jdbc.update(
            "insert into trigger_versions (trigger_id, version, name, created_by_staff_id, created_by_display, created_at) values (?, ?, ?, ?, ?, ?)",
            triggerId, version, draft.name.trim(), actor.staffId, actor.displayName.trim(), Timestamp.from(now),
        )
        draft.conditions.forEachIndexed { ordinal, condition ->
            jdbc.update(
                "insert into trigger_conditions (trigger_id, trigger_version, ordinal, condition_group, field_name, operator, value_text) values (?, ?, ?, ?, ?, ?, ?)",
                triggerId, version, ordinal, condition.group.name, condition.field.name, condition.operator.name, condition.value,
            )
        }
        draft.actions.forEachIndexed { ordinal, action ->
            jdbc.update(
                "insert into trigger_actions (trigger_id, trigger_version, ordinal, action_type, configuration_json) values (?, ?, ?, ?, cast(? as jsonb))",
                triggerId, version, ordinal, action.type.name, objectMapper.writeValueAsString(triggerActionConfiguration(action)),
            )
        }
    }

    private fun insertActivation(triggerId: UUID, version: Int, state: String, actor: TriggerDefinitionActor, now: Instant) {
        jdbc.update(
            """
            insert into trigger_activations (
                id, trigger_id, trigger_version, activation_state, actor_staff_id, actor_display,
                source, request_id, correlation_id, occurred_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            UUID.randomUUID(), triggerId, version, state, actor.staffId, actor.displayName.trim(),
            actor.source.name, actor.requestId, actor.correlationId, Timestamp.from(now),
        )
    }

    private fun view(current: TriggerState, version: Int): TriggerDefinitionView {
        val name = jdbc.queryForObject(
            "select name from trigger_versions where trigger_id = ? and version = ?",
            String::class.java, current.id, version,
        ) ?: throw TriggerNotFoundException()
        val conditions = jdbc.query(
            "select condition_group, field_name, operator, value_text from trigger_conditions where trigger_id = ? and trigger_version = ? order by ordinal",
            { result, _ -> TriggerConditionDefinition(
                TriggerConditionGroup.valueOf(result.getString(1)),
                TriggerConditionField.valueOf(result.getString(2)),
                TriggerConditionOperator.valueOf(result.getString(3)),
                result.getString(4),
            ) },
            current.id, version,
        )
        val actions = jdbc.query(
            "select action_type, configuration_json::text from trigger_actions where trigger_id = ? and trigger_version = ? order by ordinal",
            { result, _ -> triggerAction(objectMapper, result.getString(1), result.getString(2)) },
            current.id, version,
        )
        return TriggerDefinitionView(
            current.id, name, current.position, current.currentVersion, current.activeVersion,
            current.aggregateVersion, conditions, actions, current.createdAt, current.updatedAt,
        )
    }

    private fun validateDefinitionTargets(triggerId: UUID, version: Int) {
        val definition = view(stateById(triggerId), version)
        val group = definition.actions.filterIsInstance<TriggerSetGroupAction>().singleOrNull()?.groupId
        if (group != null && !evaluation.activeGroup(group)) throw TriggerConflictException("TRIGGER_TARGET_GROUP_INACTIVE")
        definition.actions.filterIsInstance<TriggerSetAssigneeAction>().singleOrNull()?.assigneeId?.let { staff ->
            if (!evaluation.activeStaff(staff) || (group != null && !evaluation.activeMember(group, staff))) throw TriggerConflictException("TRIGGER_TARGET_ASSIGNEE_INVALID")
        }
    }

    private fun activeTriggerCount(): Long = jdbc.queryForObject(
        "select count(*) from trigger_definitions where active_version is not null",
        Long::class.java,
    ) ?: 0

    private fun lockActiveTriggerSet() {
        jdbc.execute("select pg_advisory_xact_lock($ACTIVE_TRIGGER_SET_LOCK)")
    }

    private fun stateById(id: UUID): TriggerState = jdbc.query("$SELECT where definition.id = ?", ::state, id)
        .singleOrNull() ?: throw TriggerNotFoundException()

    private fun stateAtPosition(position: Int, excludingId: UUID): TriggerState? = jdbc.query(
        "$SELECT where definition.position = ? and definition.id <> ?",
        ::state,
        position,
        excludingId,
    ).singleOrNull()

    private fun lockedState(id: UUID): TriggerState = jdbc.query("$SELECT where definition.id = ? for update", ::state, id)
        .singleOrNull() ?: throw TriggerNotFoundException()

    private fun state(result: ResultSet, row: Int) = TriggerState(
        result.getObject("id", UUID::class.java), result.getInt("position"), result.getInt("current_version"),
        (result.getObject("active_version") as? Number)?.toInt(), result.getLong("aggregate_version"),
        result.getTimestamp("created_at").toInstant(), result.getTimestamp("updated_at").toInstant(),
    )

    private fun versionExists(id: UUID, version: Int): Boolean = jdbc.queryForObject(
        "select exists(select 1 from trigger_versions where trigger_id = ? and version = ?)",
        Boolean::class.java, id, version,
    ) == true

    private fun requireExpected(state: TriggerState, expected: Long) {
        if (state.aggregateVersion != expected) throw TriggerPreconditionFailedException(state.aggregateVersion)
    }

    private fun requireAccess(actor: TriggerDefinitionActor) {
        if (!actor.isAdmin || StaffAuthorityCatalog.TRIGGER_MANAGE !in actor.authorities || actor.source.name != "ADMIN_UI") {
            throw AccessDeniedException("Trigger management requires its explicit admin capability")
        }
    }

    private fun audit(
        eventType: String,
        triggerId: UUID,
        actor: TriggerDefinitionActor,
        now: Instant,
        metadata: Map<String, String>,
    ) {
        auditWriter.append(AdminSecurityAudit(
            eventType, ActorType.STAFF, actor.staffId, actor.displayName, actor.source,
            "TRIGGER", triggerId, AdminSecurityOutcome.SUCCEEDED, actor.requestId, actor.correlationId, metadata, now,
        ))
    }

    private fun <T> translateStorageFailure(block: () -> T): T = try {
        block()
    } catch (failure: TriggerConflictException) {
        throw failure
    } catch (failure: DataAccessException) {
        throw TriggerAuditUnavailableException(failure)
    }

    private data class TriggerState(
        val id: UUID,
        val position: Int,
        val currentVersion: Int,
        val activeVersion: Int?,
        val aggregateVersion: Long,
        val createdAt: Instant,
        val updatedAt: Instant,
    )


    private companion object {
        const val MAX_ACTIVE_TRIGGER_COUNT = 100
        const val ACTIVE_TRIGGER_SET_LOCK = 3_470_555_019_348_854_226L
        const val SELECT = """
            select definition.id, definition.position, definition.current_version, definition.active_version,
                   definition.aggregate_version, definition.created_at, definition.updated_at
              from trigger_definitions definition
        """
    }
}
