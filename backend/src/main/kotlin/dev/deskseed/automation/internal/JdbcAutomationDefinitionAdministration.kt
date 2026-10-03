package dev.deskseed.automation.internal

import dev.deskseed.audit.AdminSecurityAudit
import dev.deskseed.audit.AdminSecurityAuditWriter
import dev.deskseed.audit.AdminSecurityOutcome
import dev.deskseed.automation.AutomationHistory
import dev.deskseed.automation.AutomationVersionSummary
import dev.deskseed.automation.AutomationActivationSummary
import dev.deskseed.automation.AutomationExecutionSummary
import dev.deskseed.automation.AutomationCandidateSummary
import dev.deskseed.automation.AutomationActionType
import dev.deskseed.automation.AutomationAuditUnavailableException
import dev.deskseed.automation.AutomationConflictException
import dev.deskseed.automation.AutomationDefinitionActor
import dev.deskseed.automation.AutomationDefinitionAdministration
import dev.deskseed.automation.AutomationDefinitionDraft
import dev.deskseed.automation.AutomationDefinitionView
import dev.deskseed.automation.AutomationDryRunResult
import dev.deskseed.automation.AutomationNotFoundException
import dev.deskseed.automation.AutomationPreconditionFailedException
import dev.deskseed.foundation.ActorType
import dev.deskseed.organization.StaffAuthorityCatalog
import org.springframework.dao.DataAccessException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
internal class JdbcAutomationDefinitionAdministration(
    private val jdbc: JdbcTemplate,
    private val auditWriter: AdminSecurityAuditWriter,
    private val clock: Clock,
) : AutomationDefinitionAdministration {
    @Transactional(readOnly = true)
    override fun list(actor: AutomationDefinitionActor): List<AutomationDefinitionView> {
        requireAccess(actor)
        return jdbc.query("$SELECT order by definition.position, definition.id limit 200", ::state)
            .map { view(it, it.currentVersion) }
    }

    @Transactional(readOnly = true)
    override fun version(id: UUID, version: Int, actor: AutomationDefinitionActor): AutomationDefinitionView {
        requireAccess(actor)
        require(version > 0)
        return view(stateById(id), version)
    }

    @Transactional(readOnly = true)
    override fun history(id: UUID, actor: AutomationDefinitionActor): AutomationHistory {
        requireAccess(actor)
        stateById(id)
        val versions = jdbc.query(
            "select version, name, solved_age_minutes, created_by_display, created_at from automation_versions where automation_id = ? order by version desc limit 20",
            { rs, _ -> AutomationVersionSummary(rs.getInt(1), rs.getString(2), rs.getInt(3), rs.getString(4), rs.getTimestamp(5).toInstant()) }, id,
        )
        val activations = jdbc.query(
            "select automation_version, activation_state, actor_display, occurred_at from automation_activations where automation_id = ? order by occurred_at desc, id desc limit 50",
            { rs, _ -> AutomationActivationSummary(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant()) }, id,
        )
        val executions = jdbc.query(
            """select execution.id, execution.automation_version, candidate.ticket_number, execution.outcome, execution.ticket_audit_id, execution.error_code, execution.completed_at
                from automation_executions execution join automation_candidates candidate on candidate.id = execution.candidate_id
                where execution.automation_id = ? order by execution.completed_at desc, execution.id desc limit 50""",
            { rs, _ -> AutomationExecutionSummary(rs.getObject(1, UUID::class.java), rs.getInt(2), rs.getLong(3), rs.getString(4), rs.getObject(5, UUID::class.java), rs.getString(6), rs.getTimestamp(7).toInstant()) }, id,
        )
        val candidates = jdbc.query(
            """select id, automation_version, ticket_number, status, attempt_count, last_error_code, eligible_at, discovered_at
                from automation_candidates where automation_id = ? order by discovered_at desc, id desc limit 50""",
            { rs, _ -> AutomationCandidateSummary(rs.getObject(1, UUID::class.java), rs.getInt(2), rs.getLong(3), rs.getString(4), rs.getInt(5), rs.getString(6), rs.getTimestamp(7).toInstant(), rs.getTimestamp(8).toInstant()) }, id,
        )
        return AutomationHistory(versions, activations, executions, candidates)
    }

    @Transactional
    override fun create(position: Int, draft: AutomationDefinitionDraft, actor: AutomationDefinitionActor) = storage {
        requireAccess(actor)
        require(position in 1..10_000)
        val id = UUID.randomUUID()
        val now = Instant.now(clock)
        try {
            jdbc.update(
                """
                insert into automation_definitions (
                    id, normalized_name, name, position, current_version, active_version,
                    aggregate_version, created_at, updated_at
                ) values (?, ?, ?, ?, 1, null, 1, ?, ?)
                """.trimIndent(),
                id, draft.normalizedName, draft.name.trim(), position, Timestamp.from(now), Timestamp.from(now),
            )
            insertVersion(id, 1, draft, actor, now)
        } catch (_: DataIntegrityViolationException) {
            throw AutomationConflictException("AUTOMATION_NAME_OR_POSITION_EXISTS")
        }
        audit("AUTOMATION_CREATED", id, actor, now, mapOf("automationVersion" to "1"))
        view(stateById(id), 1)
    }

    @Transactional
    override fun createVersion(
        id: UUID,
        expectedAggregateVersion: Long,
        draft: AutomationDefinitionDraft,
        actor: AutomationDefinitionActor,
    ) = storage {
        requireAccess(actor)
        val current = lockedState(id)
        expected(current, expectedAggregateVersion)
        val version = current.currentVersion + 1
        val now = Instant.now(clock)
        try {
            insertVersion(id, version, draft, actor, now)
            jdbc.update(
                "update automation_definitions set normalized_name = ?, name = ?, current_version = ?, aggregate_version = aggregate_version + 1, updated_at = ? where id = ?",
                draft.normalizedName, draft.name.trim(), version, Timestamp.from(now), id,
            )
        } catch (_: DataIntegrityViolationException) {
            throw AutomationConflictException("AUTOMATION_NAME_EXISTS")
        }
        audit("AUTOMATION_VERSION_CREATED", id, actor, now, mapOf("automationVersion" to version.toString()))
        view(stateById(id), version)
    }

    @Transactional
    override fun activate(id: UUID, version: Int, expectedAggregateVersion: Long, actor: AutomationDefinitionActor) = storage {
        requireAccess(actor)
        val current = lockedState(id)
        expected(current, expectedAggregateVersion)
        if (!versionExists(id, version)) throw AutomationNotFoundException()
        val now = Instant.now(clock)
        jdbc.update(
            "update automation_definitions set active_version = ?, aggregate_version = aggregate_version + 1, updated_at = ? where id = ?",
            version, Timestamp.from(now), id,
        )
        activation(id, version, "ACTIVE", actor, now)
        audit("AUTOMATION_ACTIVATED", id, actor, now, mapOf("automationVersion" to version.toString()))
        view(stateById(id), version)
    }

    @Transactional
    override fun deactivate(id: UUID, expectedAggregateVersion: Long, actor: AutomationDefinitionActor) = storage {
        requireAccess(actor)
        val current = lockedState(id)
        expected(current, expectedAggregateVersion)
        val version = current.activeVersion ?: throw AutomationConflictException("AUTOMATION_NOT_ACTIVE")
        val now = Instant.now(clock)
        jdbc.update(
            "update automation_definitions set active_version = null, aggregate_version = aggregate_version + 1, updated_at = ? where id = ?",
            Timestamp.from(now), id,
        )
        activation(id, version, "INACTIVE", actor, now)
        audit("AUTOMATION_DEACTIVATED", id, actor, now, mapOf("automationVersion" to version.toString()))
        view(stateById(id), current.currentVersion)
    }

    @Transactional(readOnly = true)
    override fun dryRun(id: UUID, version: Int, ticketNumber: Long, actor: AutomationDefinitionActor): AutomationDryRunResult {
        requireAccess(actor)
        val definition = view(stateById(id), version)
        val ticket = jdbc.query(
            "select status, solved_at from tickets where ticket_number = ?",
            { result, _ -> result.getString("status") to result.getTimestamp("solved_at")?.toInstant() },
            ticketNumber,
        ).singleOrNull() ?: throw AutomationNotFoundException()
        val eligibleAt = ticket.second?.plus(definition.solvedAgeMinutes.toLong(), ChronoUnit.MINUTES)
        return AutomationDryRunResult(
            ticketNumber, id, version, ticket.first, ticket.second, eligibleAt,
            ticket.first == "SOLVED" && eligibleAt != null && !eligibleAt.isAfter(Instant.now(clock)),
            definition.actionType,
        )
    }

    private fun insertVersion(id: UUID, version: Int, draft: AutomationDefinitionDraft, actor: AutomationDefinitionActor, now: Instant) {
        jdbc.update(
            """
            insert into automation_versions (
                automation_id, version, name, solved_age_minutes, action_type,
                created_by_staff_id, created_by_display, created_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            id, version, draft.name.trim(), draft.solvedAgeMinutes, draft.actionType.name,
            actor.staffId, actor.displayName.trim(), Timestamp.from(now),
        )
    }

    private fun activation(id: UUID, version: Int, state: String, actor: AutomationDefinitionActor, now: Instant) {
        jdbc.update(
            """
            insert into automation_activations (
                id, automation_id, automation_version, activation_state, actor_staff_id,
                actor_display, source, request_id, correlation_id, occurred_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            UUID.randomUUID(), id, version, state, actor.staffId, actor.displayName.trim(),
            actor.source.name, actor.requestId, actor.correlationId, Timestamp.from(now),
        )
    }

    private fun view(state: State, version: Int): AutomationDefinitionView = jdbc.query(
        "select name, solved_age_minutes, action_type from automation_versions where automation_id = ? and version = ?",
        { result, _ -> AutomationDefinitionView(
            state.id, result.getString("name"), state.position, state.currentVersion, state.activeVersion,
            state.aggregateVersion, result.getInt("solved_age_minutes"),
            AutomationActionType.valueOf(result.getString("action_type")), state.createdAt, state.updatedAt,
        ) },
        state.id, version,
    ).singleOrNull() ?: throw AutomationNotFoundException()

    private fun stateById(id: UUID) = jdbc.query("$SELECT where definition.id = ?", ::state, id)
        .singleOrNull() ?: throw AutomationNotFoundException()
    private fun lockedState(id: UUID) = jdbc.query("$SELECT where definition.id = ? for update", ::state, id)
        .singleOrNull() ?: throw AutomationNotFoundException()
    private fun state(result: ResultSet, row: Int) = State(
        result.getObject("id", UUID::class.java), result.getInt("position"), result.getInt("current_version"),
        (result.getObject("active_version") as? Number)?.toInt(), result.getLong("aggregate_version"),
        result.getTimestamp("created_at").toInstant(), result.getTimestamp("updated_at").toInstant(),
    )
    private fun versionExists(id: UUID, version: Int) = jdbc.queryForObject(
        "select exists(select 1 from automation_versions where automation_id = ? and version = ?)",
        Boolean::class.java, id, version,
    ) == true
    private fun expected(state: State, expected: Long) {
        if (state.aggregateVersion != expected) throw AutomationPreconditionFailedException(state.aggregateVersion)
    }
    private fun requireAccess(actor: AutomationDefinitionActor) {
        if (!actor.isAdmin || StaffAuthorityCatalog.AUTOMATION_MANAGE !in actor.authorities || actor.source.name != "ADMIN_UI") {
            throw AccessDeniedException("Automation management requires its explicit admin capability")
        }
    }
    private fun audit(type: String, id: UUID, actor: AutomationDefinitionActor, now: Instant, metadata: Map<String, String>) {
        auditWriter.append(AdminSecurityAudit(
            type, ActorType.STAFF, actor.staffId, actor.displayName, actor.source, "AUTOMATION", id,
            AdminSecurityOutcome.SUCCEEDED, actor.requestId, actor.correlationId, metadata, now,
        ))
    }
    private fun <T> storage(block: () -> T): T = try { block() } catch (failure: AutomationConflictException) {
        throw failure
    } catch (failure: DataAccessException) {
        throw AutomationAuditUnavailableException(failure)
    }

    private data class State(
        val id: UUID, val position: Int, val currentVersion: Int, val activeVersion: Int?,
        val aggregateVersion: Long, val createdAt: Instant, val updatedAt: Instant,
    )
    private companion object {
        const val SELECT = """
            select definition.id, definition.position, definition.current_version, definition.active_version,
                   definition.aggregate_version, definition.created_at, definition.updated_at
              from automation_definitions definition
        """
    }
}
