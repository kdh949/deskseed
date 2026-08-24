package dev.deskseed.automation.internal

import dev.deskseed.audit.AdminSecurityAudit
import dev.deskseed.audit.AdminSecurityAuditWriter
import dev.deskseed.audit.AdminSecurityOutcome
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
