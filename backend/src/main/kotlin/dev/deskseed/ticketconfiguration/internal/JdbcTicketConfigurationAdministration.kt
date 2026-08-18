package dev.deskseed.ticketconfiguration.internal

import dev.deskseed.audit.AdminSecurityAudit
import dev.deskseed.audit.AdminSecurityAuditWriter
import dev.deskseed.audit.AdminSecurityOutcome
import dev.deskseed.foundation.ActorType
import dev.deskseed.ticketconfiguration.TicketConfigurationAdministration
import dev.deskseed.ticketconfiguration.TicketConfigurationAdminActor
import dev.deskseed.ticketconfiguration.TicketConfigurationAuditUnavailableException
import dev.deskseed.ticketconfiguration.TicketConfigurationConflictException
import dev.deskseed.ticketconfiguration.TicketConfigurationNotFoundException
import dev.deskseed.ticketconfiguration.TicketConfigurationPreconditionFailedException
import dev.deskseed.ticketconfiguration.TicketConfigurationValidationException
import dev.deskseed.ticketconfiguration.TicketCustomFieldType
import dev.deskseed.ticketconfiguration.TicketFieldDefinitionDraft
import dev.deskseed.ticketconfiguration.TicketFieldDefinitionView
import dev.deskseed.ticketconfiguration.TicketFieldOptionDraft
import dev.deskseed.ticketconfiguration.TicketFieldOptionUpdate
import dev.deskseed.ticketconfiguration.TicketFieldOptionView
import dev.deskseed.ticketconfiguration.TicketFieldValidation
import org.springframework.dao.DataAccessException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.sql.ResultSet
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The configuration tables intentionally use JDBC: each write locks its small
 * aggregate, persists the configuration row and required admin audit in one
 * transaction, and exposes no persistence entity beyond this module boundary.
 */
@Service
internal class JdbcTicketConfigurationAdministration(
    private val jdbc: JdbcTemplate,
    private val auditWriter: AdminSecurityAuditWriter,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) : TicketConfigurationAdministration {
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    override fun listFieldDefinitions(active: Boolean?): List<TicketFieldDefinitionView> {
        val sql = buildString {
            append(FIELD_SELECT)
            if (active != null) append(" where active = ?")
            append(" order by machine_key, id")
        }
        return if (active == null) jdbc.query(sql, ::field) else jdbc.query(sql, ::field, active)
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    override fun getFieldDefinition(fieldId: UUID): TicketFieldDefinitionView = fieldById(fieldId)

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    override fun createFieldDefinition(
        draft: TicketFieldDefinitionDraft,
        actor: TicketConfigurationAdminActor,
    ): TicketFieldDefinitionView = translateStorageFailure {
        val id = UUID.randomUUID()
        val now = Instant.now(clock)
        try {
            jdbc.update(
                """
                insert into ticket_field_definitions (
                    id, machine_key, field_type, staff_label, staff_description, customer_label, customer_description,
                    customer_visible, customer_editable, agent_visible, agent_editable, searchable, analytics_eligible,
                    sensitive, validation_json, active, definition_version, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), true, 1, ?, ?)
                """.trimIndent(),
                id, draft.machineKey, draft.type.name, draft.staffLabel.trim(), normalized(draft.staffDescription),
                normalized(draft.customerLabel), normalized(draft.customerDescription), draft.customerVisible,
                draft.customerEditable, draft.agentVisible, draft.agentEditable, draft.searchable, draft.analyticsEligible,
                draft.sensitive, validationJson(draft.validation), now.atOffset(ZoneOffset.UTC), now.atOffset(ZoneOffset.UTC),
            )
        } catch (failure: DataIntegrityViolationException) {
            throw TicketConfigurationConflictException("FIELD_MACHINE_KEY_EXISTS")
        }
        audit("TICKET_FIELD_CREATED", "TICKET_FIELD", id, actor, mapOf("fieldType" to draft.type.name), now)
        fieldById(id)
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    override fun updateFieldDefinition(
        fieldId: UUID,
        expectedVersion: Long,
        draft: TicketFieldDefinitionDraft,
        actor: TicketConfigurationAdminActor,
    ): TicketFieldDefinitionView = translateStorageFailure {
        val current = lockedField(fieldId)
        requireExpected(current.version, expectedVersion)
        if (current.machineKey != draft.machineKey || current.type != draft.type) {
            throw TicketConfigurationValidationException(
                "IMMUTABLE_FIELD_IDENTITY",
                "machineKey and type cannot change after creation",
            )
        }
        val now = Instant.now(clock)
        jdbc.update(
            """
            update ticket_field_definitions set
                staff_label = ?, staff_description = ?, customer_label = ?, customer_description = ?,
                customer_visible = ?, customer_editable = ?, agent_visible = ?, agent_editable = ?,
                searchable = ?, analytics_eligible = ?, sensitive = ?, validation_json = cast(? as jsonb),
                definition_version = definition_version + 1, updated_at = ?
            where id = ?
            """.trimIndent(),
            draft.staffLabel.trim(), normalized(draft.staffDescription), normalized(draft.customerLabel),
            normalized(draft.customerDescription), draft.customerVisible, draft.customerEditable, draft.agentVisible,
            draft.agentEditable, draft.searchable, draft.analyticsEligible, draft.sensitive, validationJson(draft.validation),
            now.atOffset(ZoneOffset.UTC), fieldId,
        )
        audit("TICKET_FIELD_UPDATED", "TICKET_FIELD", fieldId, actor, mapOf("version" to (current.version + 1).toString()), now)
        fieldById(fieldId)
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    override fun setFieldDefinitionActivation(
        fieldId: UUID,
        expectedVersion: Long,
        active: Boolean,
        actor: TicketConfigurationAdminActor,
    ): TicketFieldDefinitionView = translateStorageFailure {
        val current = lockedField(fieldId)
        requireExpected(current.version, expectedVersion)
        if (current.active == active) {
            current
        } else {
            val now = Instant.now(clock)
            jdbc.update(
                """
                update ticket_field_definitions
                   set active = ?, definition_version = definition_version + 1, updated_at = ?
                 where id = ?
                """.trimIndent(),
                active, now.atOffset(ZoneOffset.UTC), fieldId,
            )
            audit(
                if (active) "TICKET_FIELD_ACTIVATED" else "TICKET_FIELD_DEACTIVATED",
                "TICKET_FIELD",
                fieldId,
                actor,
                mapOf("version" to (current.version + 1).toString()),
                now,
            )
            fieldById(fieldId)
        }
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    override fun listFieldOptions(fieldId: UUID): List<TicketFieldOptionView> {
        requireSingleSelect(fieldById(fieldId))
        return options(fieldId)
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    override fun createFieldOption(
        fieldId: UUID,
        draft: TicketFieldOptionDraft,
        actor: TicketConfigurationAdminActor,
    ): TicketFieldOptionView = translateStorageFailure {
        requireSingleSelect(lockedField(fieldId))
        val id = UUID.randomUUID()
        val now = Instant.now(clock)
        try {
            jdbc.update(
                """
                insert into ticket_field_options (
                    id, field_definition_id, machine_key, staff_label, customer_label, display_order,
                    active, option_version, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, true, 1, ?, ?)
                """.trimIndent(),
                id, fieldId, draft.machineKey, draft.staffLabel.trim(), normalized(draft.customerLabel), draft.order,
                now.atOffset(ZoneOffset.UTC), now.atOffset(ZoneOffset.UTC),
            )
        } catch (failure: DataIntegrityViolationException) {
            throw TicketConfigurationConflictException("FIELD_OPTION_KEY_OR_ORDER_EXISTS")
        }
        audit("TICKET_FIELD_OPTION_CREATED", "TICKET_FIELD_OPTION", id, actor, mapOf("fieldId" to fieldId.toString()), now)
        optionById(fieldId, id)
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    override fun updateFieldOption(
        fieldId: UUID,
        optionId: UUID,
        expectedVersion: Long,
        update: TicketFieldOptionUpdate,
        actor: TicketConfigurationAdminActor,
    ): TicketFieldOptionView = translateStorageFailure {
        requireSingleSelect(lockedField(fieldId))
        val current = lockedOption(fieldId, optionId)
        requireExpected(current.version, expectedVersion)
        val now = Instant.now(clock)
        jdbc.update(
            """
            update ticket_field_options
               set staff_label = ?, customer_label = ?, active = ?, option_version = option_version + 1, updated_at = ?
             where id = ? and field_definition_id = ?
            """.trimIndent(),
            update.staffLabel.trim(), normalized(update.customerLabel), update.active, now.atOffset(ZoneOffset.UTC), optionId, fieldId,
        )
        audit(
            if (update.active) "TICKET_FIELD_OPTION_UPDATED" else "TICKET_FIELD_OPTION_DEACTIVATED",
            "TICKET_FIELD_OPTION",
            optionId,
            actor,
            mapOf("fieldId" to fieldId.toString(), "version" to (current.version + 1).toString()),
            now,
        )
        optionById(fieldId, optionId)
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    override fun reorderFieldOptions(
        fieldId: UUID,
        ids: List<UUID>,
        actor: TicketConfigurationAdminActor,
    ): List<TicketFieldOptionView> = translateStorageFailure {
        requireSingleSelect(lockedField(fieldId))
        if (ids.isEmpty() || ids.size != ids.toSet().size) {
            throw TicketConfigurationValidationException("INVALID_OPTION_ORDER", "ids must be non-empty and unique")
        }
        val current = lockedOptions(fieldId)
        if (current.map { it.id }.toSet() != ids.toSet()) {
            throw TicketConfigurationConflictException("OPTION_ORDER_MUST_INCLUDE_EXACT_COLLECTION")
        }
        val now = Instant.now(clock)
        // The unique order constraint is deferred inside this transaction so a swap never needs an
        // arbitrary temporary range that could collide with an existing value or overflow.
        jdbc.execute("set constraints ticket_field_options_field_definition_id_display_order_key deferred")
        ids.forEachIndexed { index, id ->
            jdbc.update(
                "update ticket_field_options set display_order = ?, option_version = option_version + 1, updated_at = ? where id = ?",
                index, now.atOffset(ZoneOffset.UTC), id,
            )
        }
        audit(
            "TICKET_FIELD_OPTIONS_REORDERED",
            "TICKET_FIELD",
            fieldId,
            actor,
            mapOf("optionCount" to ids.size.toString()),
            now,
        )
        options(fieldId)
    }

    private fun fieldById(fieldId: UUID): TicketFieldDefinitionView = jdbc.query(
        "$FIELD_SELECT where id = ?",
        ::field,
        fieldId,
    ).singleOrNull() ?: throw TicketConfigurationNotFoundException()

    private fun lockedField(fieldId: UUID): TicketFieldDefinitionView = jdbc.query(
        "$FIELD_SELECT where id = ? for update",
        ::field,
        fieldId,
    ).singleOrNull() ?: throw TicketConfigurationNotFoundException()

    private fun options(fieldId: UUID): List<TicketFieldOptionView> = jdbc.query(
        "$OPTION_SELECT where field_definition_id = ? order by display_order, id",
        ::option,
        fieldId,
    )

    private fun optionById(fieldId: UUID, optionId: UUID): TicketFieldOptionView = jdbc.query(
        "$OPTION_SELECT where field_definition_id = ? and id = ?",
        ::option,
        fieldId,
        optionId,
    ).singleOrNull() ?: throw TicketConfigurationNotFoundException()

    private fun lockedOption(fieldId: UUID, optionId: UUID): TicketFieldOptionView = jdbc.query(
        "$OPTION_SELECT where field_definition_id = ? and id = ? for update",
        ::option,
        fieldId,
        optionId,
    ).singleOrNull() ?: throw TicketConfigurationNotFoundException()

    private fun lockedOptions(fieldId: UUID): List<TicketFieldOptionView> = jdbc.query(
        "$OPTION_SELECT where field_definition_id = ? order by id for update",
        ::option,
        fieldId,
    )

    private fun field(result: ResultSet, @Suppress("UNUSED_PARAMETER") row: Int): TicketFieldDefinitionView =
        TicketFieldDefinitionView(
            id = result.getObject("id", UUID::class.java),
            machineKey = result.getString("machine_key"),
            type = TicketCustomFieldType.valueOf(result.getString("field_type")),
            staffLabel = result.getString("staff_label"),
            staffDescription = result.getString("staff_description"),
            customerLabel = result.getString("customer_label"),
            customerDescription = result.getString("customer_description"),
            active = result.getBoolean("active"),
            customerVisible = result.getBoolean("customer_visible"),
            customerEditable = result.getBoolean("customer_editable"),
            agentVisible = result.getBoolean("agent_visible"),
            agentEditable = result.getBoolean("agent_editable"),
            searchable = result.getBoolean("searchable"),
            analyticsEligible = result.getBoolean("analytics_eligible"),
            sensitive = result.getBoolean("sensitive"),
            validation = objectMapper.readValue(result.getString("validation_json"), TicketFieldValidation::class.java),
            version = result.getLong("definition_version"),
            createdAt = result.getTimestamp("created_at").toInstant(),
            updatedAt = result.getTimestamp("updated_at").toInstant(),
        )

    private fun option(result: ResultSet, @Suppress("UNUSED_PARAMETER") row: Int): TicketFieldOptionView =
        TicketFieldOptionView(
            id = result.getObject("id", UUID::class.java),
            machineKey = result.getString("machine_key"),
            staffLabel = result.getString("staff_label"),
            customerLabel = result.getString("customer_label"),
            active = result.getBoolean("active"),
            order = result.getInt("display_order"),
            version = result.getLong("option_version"),
        )

    private fun requireSingleSelect(field: TicketFieldDefinitionView) {
        if (field.type != TicketCustomFieldType.SINGLE_SELECT) {
            throw TicketConfigurationValidationException("FIELD_OPTIONS_REQUIRE_SINGLE_SELECT", "Only SINGLE_SELECT fields own options")
        }
    }

    private fun requireExpected(current: Long, expected: Long) {
        if (current != expected) throw TicketConfigurationPreconditionFailedException(current)
    }

    private fun validationJson(value: TicketFieldValidation): String = objectMapper.writeValueAsString(value)

    private fun normalized(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

    private fun audit(
        eventType: String,
        targetType: String,
        targetId: UUID,
        actor: TicketConfigurationAdminActor,
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
                targetType = targetType,
                targetId = targetId,
                outcome = AdminSecurityOutcome.SUCCEEDED,
                requestId = actor.requestId,
                correlationId = actor.correlationId,
                metadata = metadata,
                occurredAt = occurredAt,
            ),
        )
    }

    private fun <T> translateStorageFailure(block: () -> T): T = try {
        block()
    } catch (failure: DataAccessException) {
        throw TicketConfigurationAuditUnavailableException(failure)
    }

    private companion object {
        const val FIELD_SELECT = """
            select id, machine_key, field_type, staff_label, staff_description, customer_label, customer_description,
                   customer_visible, customer_editable, agent_visible, agent_editable, searchable, analytics_eligible,
                   sensitive, validation_json, active, definition_version, created_at, updated_at
              from ticket_field_definitions
        """
        const val OPTION_SELECT = """
            select id, machine_key, staff_label, customer_label, active, display_order, option_version
              from ticket_field_options
        """
    }
}
