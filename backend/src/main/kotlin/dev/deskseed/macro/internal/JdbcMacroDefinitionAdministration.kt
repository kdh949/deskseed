package dev.deskseed.macro.internal

import dev.deskseed.audit.AdminSecurityAudit
import dev.deskseed.audit.AdminSecurityAuditWriter
import dev.deskseed.audit.AdminSecurityOutcome
import dev.deskseed.foundation.ActorType
import dev.deskseed.macro.MacroActionDefinition
import dev.deskseed.macro.MacroActionType
import dev.deskseed.macro.MacroAddTagAction
import dev.deskseed.macro.MacroAssigneeAction
import dev.deskseed.macro.MacroAuditUnavailableException
import dev.deskseed.macro.MacroCommentAction
import dev.deskseed.macro.MacroConflictException
import dev.deskseed.macro.MacroCustomFieldAction
import dev.deskseed.macro.MacroCustomStatusAction
import dev.deskseed.macro.MacroDefinitionActor
import dev.deskseed.macro.MacroDefinitionAdministration
import dev.deskseed.macro.MacroDefinitionDraft
import dev.deskseed.macro.MacroDefinitionView
import dev.deskseed.macro.MacroGroupAction
import dev.deskseed.macro.MacroNotFoundException
import dev.deskseed.macro.MacroPreconditionFailedException
import dev.deskseed.macro.MacroPriorityAction
import dev.deskseed.macro.MacroRemoveTagAction
import dev.deskseed.macro.MacroScope
import dev.deskseed.macro.MacroStatusAction
import dev.deskseed.organization.StaffAuthorityCatalog
import dev.deskseed.ticketing.CommentVisibility
import dev.deskseed.ticketing.TicketConfigurationFieldValue
import dev.deskseed.ticketing.TicketPriority
import dev.deskseed.ticketing.TicketStatus
import org.springframework.dao.DataAccessException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
internal class JdbcMacroDefinitionAdministration(
    private val jdbc: JdbcTemplate,
    private val auditWriter: AdminSecurityAuditWriter,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) : MacroDefinitionAdministration {
    @Transactional(readOnly = true)
    override fun listAccessible(actor: MacroDefinitionActor): List<MacroDefinitionView> = jdbc.query(
        """
        $DEFINITION_SELECT
        where definition.active_version is not null
          and (definition.scope = 'SHARED' or definition.owner_staff_id = ?)
        order by lower(definition.name), definition.id
        limit 200
        """.trimIndent(),
        ::state,
        actor.staffId,
    ).map { current -> view(current, checkNotNull(current.activeVersion)) }

    @Transactional(readOnly = true)
    override fun getActive(macroId: UUID, actor: MacroDefinitionActor): MacroDefinitionView {
        val current = stateById(macroId)
        if (current.scope == MacroScope.PERSONAL && current.ownerStaffId != actor.staffId) throw MacroNotFoundException()
        val activeVersion = current.activeVersion ?: throw MacroNotFoundException()
        return view(current, activeVersion)
    }

    @Transactional(readOnly = true)
    override fun getVersion(macroId: UUID, macroVersion: Int, actor: MacroDefinitionActor): MacroDefinitionView {
        val current = stateById(macroId)
        if (current.scope == MacroScope.PERSONAL && current.ownerStaffId != actor.staffId) throw MacroNotFoundException()
        if (!versionExists(macroId, macroVersion)) throw MacroNotFoundException()
        return view(current, macroVersion)
    }

    @Transactional(readOnly = true)
    override fun listManaged(scope: MacroScope, actor: MacroDefinitionActor): List<MacroDefinitionView> {
        requireScopeAccess(scope, actor)
        val parameters = if (scope == MacroScope.PERSONAL) arrayOf<Any>(scope.name, actor.staffId) else arrayOf(scope.name)
        val ownerClause = if (scope == MacroScope.PERSONAL) "and definition.owner_staff_id = ?" else ""
        return jdbc.query(
            """
            $DEFINITION_SELECT
            where definition.scope = ? $ownerClause
            order by lower(definition.name), definition.id
            limit 200
            """.trimIndent(),
            ::state,
            *parameters,
        ).map { current -> view(current, current.currentVersion) }
    }

    @Transactional
    override fun create(
        scope: MacroScope,
        draft: MacroDefinitionDraft,
        actor: MacroDefinitionActor,
    ): MacroDefinitionView = translateStorageFailure {
        requireScopeAccess(scope, actor)
        val id = UUID.randomUUID()
        val now = Instant.now(clock)
        try {
            jdbc.update(
                """
                insert into macro_definitions (
                    id, normalized_name, name, scope, owner_staff_id, current_version, active_version,
                    aggregate_version, created_at, updated_at
                ) values (?, ?, ?, ?, ?, 1, null, 1, ?, ?)
                """.trimIndent(),
                id,
                draft.normalizedName,
                draft.name.trim(),
                scope.name,
                if (scope == MacroScope.PERSONAL) actor.staffId else null,
                Timestamp.from(now),
                Timestamp.from(now),
            )
            insertVersion(id, 1, draft, actor, now)
        } catch (failure: DataIntegrityViolationException) {
            throw MacroConflictException("MACRO_NAME_EXISTS")
        }
        audit("MACRO_CREATED", id, actor, mapOf("scope" to scope.name, "macroVersion" to "1"), now)
        view(stateById(id), 1)
    }

    @Transactional
    override fun createVersion(
        macroId: UUID,
        expectedAggregateVersion: Long,
        draft: MacroDefinitionDraft,
        actor: MacroDefinitionActor,
    ): MacroDefinitionView = translateStorageFailure {
        val current = lockedState(macroId)
        requireManaged(current, actor)
        requireExpected(current, expectedAggregateVersion)
        val nextVersion = current.currentVersion + 1
        val now = Instant.now(clock)
        try {
            jdbc.update(
                """
                update macro_definitions
                   set normalized_name = ?, name = ?, current_version = ?,
                       aggregate_version = aggregate_version + 1, updated_at = ?
                 where id = ?
                """.trimIndent(),
                draft.normalizedName,
                draft.name.trim(),
                nextVersion,
                Timestamp.from(now),
                macroId,
            )
            insertVersion(macroId, nextVersion, draft, actor, now)
        } catch (failure: DataIntegrityViolationException) {
            throw MacroConflictException("MACRO_NAME_EXISTS")
        }
        audit(
            "MACRO_VERSION_CREATED",
            macroId,
            actor,
            mapOf("scope" to current.scope.name, "macroVersion" to nextVersion.toString()),
            now,
        )
        view(stateById(macroId), nextVersion)
    }

    @Transactional
    override fun activate(
        macroId: UUID,
        macroVersion: Int,
        expectedAggregateVersion: Long,
        actor: MacroDefinitionActor,
    ): MacroDefinitionView = translateStorageFailure {
        val current = lockedState(macroId)
        requireManaged(current, actor)
        requireExpected(current, expectedAggregateVersion)
        if (!versionExists(macroId, macroVersion)) throw MacroNotFoundException()
        if (current.activeVersion == macroVersion) return@translateStorageFailure view(current, current.currentVersion)
        val now = Instant.now(clock)
        jdbc.update(
            """
            update macro_definitions
               set active_version = ?, aggregate_version = aggregate_version + 1, updated_at = ?
             where id = ?
            """.trimIndent(),
            macroVersion,
            Timestamp.from(now),
            macroId,
        )
        activation(macroId, macroVersion, "ACTIVE", actor, now)
        audit(
            "MACRO_ACTIVATED",
            macroId,
            actor,
            mapOf("scope" to current.scope.name, "macroVersion" to macroVersion.toString()),
            now,
        )
        view(stateById(macroId), current.currentVersion)
    }

    @Transactional
    override fun deactivate(
        macroId: UUID,
        expectedAggregateVersion: Long,
        actor: MacroDefinitionActor,
    ): MacroDefinitionView = translateStorageFailure {
        val current = lockedState(macroId)
        requireManaged(current, actor)
        requireExpected(current, expectedAggregateVersion)
        val activeVersion = current.activeVersion ?: return@translateStorageFailure view(current, current.currentVersion)
        val now = Instant.now(clock)
        jdbc.update(
            """
            update macro_definitions
               set active_version = null, aggregate_version = aggregate_version + 1, updated_at = ?
             where id = ?
            """.trimIndent(),
            Timestamp.from(now),
            macroId,
        )
        activation(macroId, activeVersion, "INACTIVE", actor, now)
        audit(
            "MACRO_DEACTIVATED",
            macroId,
            actor,
            mapOf("scope" to current.scope.name, "macroVersion" to activeVersion.toString()),
            now,
        )
        view(stateById(macroId), current.currentVersion)
    }

    private fun insertVersion(
        macroId: UUID,
        version: Int,
        draft: MacroDefinitionDraft,
        actor: MacroDefinitionActor,
        now: Instant,
    ) {
        jdbc.update(
            """
            insert into macro_versions (
                macro_id, version, name, created_by_staff_id, created_by_display, created_at
            ) values (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            macroId,
            version,
            draft.name.trim(),
            actor.staffId,
            actor.displayName,
            Timestamp.from(now),
        )
        draft.actions.forEachIndexed { ordinal, action ->
            jdbc.update(
                """
                insert into macro_actions (macro_id, macro_version, ordinal, action_type, configuration_json)
                values (?, ?, ?, ?, cast(? as jsonb))
                """.trimIndent(),
                macroId,
                version,
                ordinal,
                action.type.name,
                objectMapper.writeValueAsString(configuration(action)),
            )
        }
    }

    private fun activation(
        macroId: UUID,
        macroVersion: Int,
        state: String,
        actor: MacroDefinitionActor,
        now: Instant,
    ) {
        jdbc.update(
            """
            insert into macro_activations (
                id, macro_id, macro_version, activation_state, actor_staff_id, actor_display,
                source, request_id, correlation_id, occurred_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            UUID.randomUUID(),
            macroId,
            macroVersion,
            state,
            actor.staffId,
            actor.displayName,
            actor.source.name,
            actor.requestId,
            actor.correlationId,
            Timestamp.from(now),
        )
    }

    private fun configuration(action: MacroActionDefinition): Map<String, Any?> = when (action) {
        is MacroStatusAction -> mapOf("status" to action.status.name)
        is MacroPriorityAction -> mapOf("priority" to action.priority.name)
        is MacroGroupAction -> mapOf("groupId" to action.groupId.toString())
        is MacroAssigneeAction -> mapOf("assigneeId" to action.assigneeId?.toString())
        is MacroAddTagAction -> mapOf("tagId" to action.tagId.toString())
        is MacroRemoveTagAction -> mapOf("tagId" to action.tagId.toString())
        is MacroCustomFieldAction -> mapOf(
            "fieldKey" to action.fieldKey,
            "booleanValue" to action.value.booleanValue,
            "numberValue" to action.value.numberValue,
            "optionId" to action.value.optionId?.toString(),
            "shortTextValue" to action.value.shortTextValue,
            "longTextValue" to action.value.longTextValue,
        )
        is MacroCustomStatusAction -> mapOf("customStatusId" to action.customStatusId.toString())
        is MacroCommentAction -> mapOf("visibility" to action.visibility.name, "template" to action.template)
    }

    private fun action(type: String, configurationJson: String): MacroActionDefinition {
        val node = objectMapper.readTree(configurationJson)
        return when (MacroActionType.valueOf(type)) {
            MacroActionType.STATUS -> MacroStatusAction(TicketStatus.valueOf(node.requiredText("status")))
            MacroActionType.PRIORITY -> MacroPriorityAction(TicketPriority.valueOf(node.requiredText("priority")))
            MacroActionType.GROUP -> MacroGroupAction(UUID.fromString(node.requiredText("groupId")))
            MacroActionType.ASSIGNEE -> MacroAssigneeAction(node.optionalText("assigneeId")?.let(UUID::fromString))
            MacroActionType.ADD_TAG -> MacroAddTagAction(UUID.fromString(node.requiredText("tagId")))
            MacroActionType.REMOVE_TAG -> MacroRemoveTagAction(UUID.fromString(node.requiredText("tagId")))
            MacroActionType.CUSTOM_FIELD -> MacroCustomFieldAction(
                fieldKey = node.requiredText("fieldKey"),
                value = TicketConfigurationFieldValue(
                    booleanValue = node.optionalBoolean("booleanValue"),
                    numberValue = node.optionalText("numberValue"),
                    optionId = node.optionalText("optionId")?.let(UUID::fromString),
                    shortTextValue = node.optionalText("shortTextValue"),
                    longTextValue = node.optionalText("longTextValue"),
                ),
            )
            MacroActionType.CUSTOM_STATUS -> MacroCustomStatusAction(UUID.fromString(node.requiredText("customStatusId")))
            MacroActionType.COMMENT -> MacroCommentAction(
                CommentVisibility.valueOf(node.requiredText("visibility")),
                node.requiredText("template"),
            )
        }
    }

    private fun view(current: MacroState, version: Int): MacroDefinitionView {
        val versionName = jdbc.queryForObject(
            "select name from macro_versions where macro_id = ? and version = ?",
            String::class.java,
            current.id,
            version,
        ) ?: throw MacroNotFoundException()
        val actions = jdbc.query(
            """
            select action_type, configuration_json::text
              from macro_actions
             where macro_id = ? and macro_version = ?
             order by ordinal
            """.trimIndent(),
            { result, _ -> action(result.getString(1), result.getString(2)) },
            current.id,
            version,
        )
        return MacroDefinitionView(
            id = current.id,
            name = versionName,
            scope = current.scope,
            ownerStaffId = current.ownerStaffId,
            currentVersion = current.currentVersion,
            activeVersion = current.activeVersion,
            aggregateVersion = current.aggregateVersion,
            actions = actions,
            createdAt = current.createdAt,
            updatedAt = current.updatedAt,
        )
    }

    private fun stateById(id: UUID): MacroState = jdbc.query(
        "$DEFINITION_SELECT where definition.id = ?",
        ::state,
        id,
    ).singleOrNull() ?: throw MacroNotFoundException()

    private fun lockedState(id: UUID): MacroState = jdbc.query(
        "$DEFINITION_SELECT where definition.id = ? for update",
        ::state,
        id,
    ).singleOrNull() ?: throw MacroNotFoundException()

    private fun state(result: ResultSet, rowIndex: Int) = MacroState(
        id = result.getObject("id", UUID::class.java),
        scope = MacroScope.valueOf(result.getString("scope")),
        ownerStaffId = result.getObject("owner_staff_id", UUID::class.java),
        currentVersion = result.getInt("current_version"),
        activeVersion = (result.getObject("active_version") as? Number)?.toInt(),
        aggregateVersion = result.getLong("aggregate_version"),
        createdAt = result.getTimestamp("created_at").toInstant(),
        updatedAt = result.getTimestamp("updated_at").toInstant(),
    )

    private fun versionExists(macroId: UUID, macroVersion: Int): Boolean = jdbc.queryForObject(
        "select exists(select 1 from macro_versions where macro_id = ? and version = ?)",
        Boolean::class.java,
        macroId,
        macroVersion,
    ) == true

    private fun requireScopeAccess(scope: MacroScope, actor: MacroDefinitionActor) {
        if (scope == MacroScope.SHARED && (!actor.isAdmin || StaffAuthorityCatalog.MACRO_SHARED_MANAGE !in actor.authorities)) {
            throw AccessDeniedException("Shared macro management requires its explicit capability")
        }
    }

    private fun requireManaged(current: MacroState, actor: MacroDefinitionActor) {
        when (current.scope) {
            MacroScope.PERSONAL -> if (current.ownerStaffId != actor.staffId) throw MacroNotFoundException()
            MacroScope.SHARED -> requireScopeAccess(MacroScope.SHARED, actor)
        }
    }

    private fun requireExpected(current: MacroState, expected: Long) {
        if (current.aggregateVersion != expected) throw MacroPreconditionFailedException(current.aggregateVersion)
    }

    private fun audit(
        eventType: String,
        macroId: UUID,
        actor: MacroDefinitionActor,
        metadata: Map<String, String>,
        now: Instant,
    ) {
        auditWriter.append(
            AdminSecurityAudit(
                eventType = eventType,
                actorType = ActorType.STAFF,
                actorId = actor.staffId,
                actorDisplaySnapshot = actor.displayName,
                source = actor.source,
                targetType = "MACRO",
                targetId = macroId,
                outcome = AdminSecurityOutcome.SUCCEEDED,
                requestId = actor.requestId,
                correlationId = actor.correlationId,
                metadata = metadata,
                occurredAt = now,
            ),
        )
    }

    private fun <T> translateStorageFailure(block: () -> T): T = try {
        block()
    } catch (failure: DataAccessException) {
        throw MacroAuditUnavailableException(failure)
    }

    private fun JsonNode.requiredText(name: String): String = get(name)
        ?.takeIf(JsonNode::isString)
        ?.asString()
        ?: throw IllegalStateException("Persisted macro action $name is invalid")

    private fun JsonNode.optionalText(name: String): String? = get(name)
        ?.takeUnless(JsonNode::isNull)
        ?.takeIf(JsonNode::isString)
        ?.asString()

    private fun JsonNode.optionalBoolean(name: String): Boolean? = get(name)
        ?.takeUnless(JsonNode::isNull)
        ?.takeIf(JsonNode::isBoolean)
        ?.asBoolean()

    private data class MacroState(
        val id: UUID,
        val scope: MacroScope,
        val ownerStaffId: UUID?,
        val currentVersion: Int,
        val activeVersion: Int?,
        val aggregateVersion: Long,
        val createdAt: Instant,
        val updatedAt: Instant,
    )

    private companion object {
        const val DEFINITION_SELECT = """
            select definition.id, definition.scope, definition.owner_staff_id, definition.current_version,
                   definition.active_version, definition.aggregate_version, definition.created_at, definition.updated_at
              from macro_definitions definition
        """
    }
}
