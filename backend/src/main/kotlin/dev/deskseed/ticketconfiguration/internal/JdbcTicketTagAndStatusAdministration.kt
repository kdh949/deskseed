package dev.deskseed.ticketconfiguration.internal

import dev.deskseed.audit.AdminSecurityAudit
import dev.deskseed.audit.AdminSecurityAuditWriter
import dev.deskseed.audit.AdminSecurityOutcome
import dev.deskseed.foundation.ActorType
import dev.deskseed.ticketconfiguration.CustomTicketStatusDraft
import dev.deskseed.ticketconfiguration.CustomTicketStatusView
import dev.deskseed.ticketconfiguration.TicketConfigurationAdminActor
import dev.deskseed.ticketconfiguration.TicketConfigurationAuditUnavailableException
import dev.deskseed.ticketconfiguration.TicketConfigurationConflictException
import dev.deskseed.ticketconfiguration.TicketConfigurationNotFoundException
import dev.deskseed.ticketconfiguration.TicketConfigurationPreconditionFailedException
import dev.deskseed.ticketconfiguration.TicketConfigurationValidationException
import dev.deskseed.ticketconfiguration.TicketTagAndStatusAdministration
import dev.deskseed.ticketconfiguration.TicketTagDefinitionDraft
import dev.deskseed.ticketconfiguration.TicketTagDefinitionView
import dev.deskseed.ticketing.TicketStatus
import org.springframework.dao.DataAccessException
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
internal class JdbcTicketTagAndStatusAdministration(
    private val jdbc: JdbcTemplate,
    private val auditWriter: AdminSecurityAuditWriter,
    private val clock: Clock,
) : TicketTagAndStatusAdministration {
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    override fun listTags(): List<TicketTagDefinitionView> = jdbc.query(
        "$TAG_SELECT order by normalized_value, id",
        ::tag,
    )

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    override fun createTag(draft: TicketTagDefinitionDraft, actor: TicketConfigurationAdminActor): TicketTagDefinitionView = translateStorageFailure {
        val id = UUID.randomUUID()
        val now = Instant.now(clock)
        try {
            jdbc.update(
                """
                insert into ticket_tag_definitions
                    (id, normalized_value, label, active, definition_version, created_at, updated_at)
                values (?, ?, ?, ?, 1, ?, ?)
                """.trimIndent(),
                id, draft.normalizedValue, draft.label.trim(), draft.active,
                now.atOffset(ZoneOffset.UTC), now.atOffset(ZoneOffset.UTC),
            )
        } catch (failure: DataIntegrityViolationException) {
            throw TicketConfigurationConflictException("TAG_VALUE_EXISTS")
        }
        audit("TICKET_TAG_CREATED", "TICKET_TAG", id, actor, mapOf("active" to draft.active.toString()), now)
        tagById(id)
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    override fun updateTag(
        tagId: UUID,
        expectedVersion: Long,
        draft: TicketTagDefinitionDraft,
        actor: TicketConfigurationAdminActor,
    ): TicketTagDefinitionView = translateStorageFailure {
        val current = lockedTag(tagId)
        requireExpected(current.version, expectedVersion)
        if (current.value != draft.normalizedValue) {
            throw TicketConfigurationValidationException(
                "IMMUTABLE_TAG_VALUE",
                "normalized tag value cannot change after assignment identity exists",
            )
        }
        val now = Instant.now(clock)
        jdbc.update(
            """
            update ticket_tag_definitions
               set label = ?, active = ?, definition_version = definition_version + 1, updated_at = ?
             where id = ?
            """.trimIndent(),
            draft.label.trim(), draft.active, now.atOffset(ZoneOffset.UTC), tagId,
        )
        audit(
            if (draft.active) "TICKET_TAG_UPDATED" else "TICKET_TAG_DEACTIVATED",
            "TICKET_TAG",
            tagId,
            actor,
            mapOf("version" to (current.version + 1).toString()),
            now,
        )
        tagById(tagId)
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    override fun listStatuses(): List<CustomTicketStatusView> = jdbc.query(
        "$STATUS_SELECT order by display_order, machine_key, id",
        ::status,
    )

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    override fun createStatus(draft: CustomTicketStatusDraft, actor: TicketConfigurationAdminActor): CustomTicketStatusView = translateStorageFailure {
        validateAllowedForms(draft.allowedFormIds)
        val id = UUID.randomUUID()
        val now = Instant.now(clock)
        try {
            jdbc.update(
                """
                insert into custom_ticket_statuses
                    (id, machine_key, agent_label, customer_label, status_category, active, display_order,
                    default_for_category, allowed_form_ids, description, definition_version, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, cast(? as uuid[]), ?, 1, ?, ?)
                """.trimIndent(),
                id, draft.machineKey, draft.agentLabel.trim(), normalized(draft.customerLabel), draft.statusCategory.name,
                draft.active, draft.order, draft.defaultForCategory, uuidArray(draft.allowedFormIds), normalized(draft.description),
                now.atOffset(ZoneOffset.UTC), now.atOffset(ZoneOffset.UTC),
            )
        } catch (failure: DataIntegrityViolationException) {
            throw TicketConfigurationConflictException("STATUS_KEY_OR_DEFAULT_CATEGORY_EXISTS")
        }
        audit("CUSTOM_TICKET_STATUS_CREATED", "CUSTOM_TICKET_STATUS", id, actor, mapOf("category" to draft.statusCategory.name), now)
        statusById(id)
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    override fun updateStatus(
        statusId: UUID,
        expectedVersion: Long,
        draft: CustomTicketStatusDraft,
        actor: TicketConfigurationAdminActor,
    ): CustomTicketStatusView = translateStorageFailure {
        val current = lockedStatus(statusId)
        requireExpected(current.version, expectedVersion)
        if (current.machineKey != draft.machineKey || current.statusCategory != draft.statusCategory) {
            throw TicketConfigurationValidationException(
                "IMMUTABLE_STATUS_IDENTITY",
                "machineKey and statusCategory cannot change after creation",
            )
        }
        validateAllowedForms(draft.allowedFormIds)
        val now = Instant.now(clock)
        try {
            jdbc.update(
                """
                update custom_ticket_statuses set
                    agent_label = ?, customer_label = ?, active = ?, display_order = ?, default_for_category = ?,
                    allowed_form_ids = cast(? as uuid[]), description = ?, definition_version = definition_version + 1, updated_at = ?
                where id = ?
                """.trimIndent(),
                draft.agentLabel.trim(), normalized(draft.customerLabel), draft.active, draft.order, draft.defaultForCategory,
                uuidArray(draft.allowedFormIds), normalized(draft.description), now.atOffset(ZoneOffset.UTC), statusId,
            )
        } catch (failure: DataIntegrityViolationException) {
            throw TicketConfigurationConflictException("DEFAULT_STATUS_CATEGORY_EXISTS")
        }
        audit(
            if (draft.active) "CUSTOM_TICKET_STATUS_UPDATED" else "CUSTOM_TICKET_STATUS_DEACTIVATED",
            "CUSTOM_TICKET_STATUS",
            statusId,
            actor,
            mapOf("version" to (current.version + 1).toString(), "category" to current.statusCategory.name),
            now,
        )
        statusById(statusId)
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    override fun reorderStatuses(ids: List<UUID>, actor: TicketConfigurationAdminActor): List<CustomTicketStatusView> = translateStorageFailure {
        if (ids.isEmpty() || ids.size != ids.toSet().size) {
            throw TicketConfigurationValidationException("INVALID_STATUS_ORDER", "ids must be non-empty and unique")
        }
        val current = lockedStatuses()
        if (current.map { it.id }.toSet() != ids.toSet()) {
            throw TicketConfigurationConflictException("STATUS_ORDER_MUST_INCLUDE_EXACT_COLLECTION")
        }
        val now = Instant.now(clock)
        ids.forEachIndexed { index, id ->
            jdbc.update(
                """
                update custom_ticket_statuses
                   set display_order = ?, definition_version = definition_version + 1, updated_at = ?
                 where id = ?
                """.trimIndent(),
                index, now.atOffset(ZoneOffset.UTC), id,
            )
        }
        audit("CUSTOM_TICKET_STATUSES_REORDERED", "CUSTOM_TICKET_STATUS_CATALOG", CATALOG_ID, actor, mapOf("statusCount" to ids.size.toString()), now)
        listStatuses()
    }

    private fun tagById(tagId: UUID): TicketTagDefinitionView = jdbc.query(
        "$TAG_SELECT where id = ?", ::tag, tagId,
    ).singleOrNull() ?: throw TicketConfigurationNotFoundException()

    private fun lockedTag(tagId: UUID): TicketTagDefinitionView = jdbc.query(
        "$TAG_SELECT where id = ? for update", ::tag, tagId,
    ).singleOrNull() ?: throw TicketConfigurationNotFoundException()

    private fun statusById(statusId: UUID): CustomTicketStatusView = jdbc.query(
        "$STATUS_SELECT where id = ?", ::status, statusId,
    ).singleOrNull() ?: throw TicketConfigurationNotFoundException()

    private fun lockedStatus(statusId: UUID): CustomTicketStatusView = jdbc.query(
        "$STATUS_SELECT where id = ? for update", ::status, statusId,
    ).singleOrNull() ?: throw TicketConfigurationNotFoundException()

    private fun lockedStatuses(): List<CustomTicketStatusView> = jdbc.query(
        "$STATUS_SELECT order by id for update", ::status,
    )

    private fun tag(result: ResultSet, @Suppress("UNUSED_PARAMETER") row: Int): TicketTagDefinitionView {
        val id = result.getObject("id", UUID::class.java)
        return TicketTagDefinitionView(
            id, result.getString("normalized_value"), result.getString("label"), result.getBoolean("active"),
            jdbc.queryForObject("select count(*) >= 10000 from ticket_tag_assignments where tag_definition_id = ?", Boolean::class.java, id) ?: false,
            result.getLong("definition_version"),
        )
    }

    private fun status(result: ResultSet, @Suppress("UNUSED_PARAMETER") row: Int): CustomTicketStatusView =
        CustomTicketStatusView(
            result.getObject("id", UUID::class.java), result.getString("machine_key"), result.getString("agent_label"),
            result.getString("customer_label"), TicketStatus.valueOf(result.getString("status_category")), result.getBoolean("active"),
            result.getInt("display_order"), result.getBoolean("default_for_category"),
            ((result.getArray("allowed_form_ids")?.array as? Array<*>)?.map { UUID.fromString(it.toString()) } ?: emptyList()).toSet(),
            result.getString("description"), result.getLong("definition_version"),
        )

    private fun validateAllowedForms(ids: Set<UUID>) {
        if (ids.isEmpty()) return
        val found = jdbc.query(
            "select id from ticket_forms where id = any (cast(? as uuid[])) and lifecycle <> 'ARCHIVED'",
            { result, _ -> result.getObject(1, UUID::class.java) },
            uuidArray(ids),
        ).toSet()
        if (found != ids) throw TicketConfigurationValidationException("ALLOWED_FORM_NOT_FOUND", "Allowed form must exist and be active")
    }

    private fun uuidArray(ids: Set<UUID>): Array<UUID> = ids.toTypedArray()

    private fun requireExpected(current: Long, expected: Long) {
        if (current != expected) throw TicketConfigurationPreconditionFailedException(current)
    }

    private fun normalized(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

    private fun audit(eventType: String, targetType: String, targetId: UUID, actor: TicketConfigurationAdminActor, metadata: Map<String, String>, now: Instant) {
        auditWriter.append(
            AdminSecurityAudit(
                eventType, ActorType.STAFF, actor.staffId, actor.displayName, actor.source, targetType, targetId,
                AdminSecurityOutcome.SUCCEEDED, actor.requestId, actor.correlationId, metadata, now,
            ),
        )
    }

    private fun <T> translateStorageFailure(block: () -> T): T = try {
        block()
    } catch (failure: DataAccessException) {
        throw TicketConfigurationAuditUnavailableException(failure)
    }

    private companion object {
        val CATALOG_ID: UUID = UUID(0, 0)
        const val TAG_SELECT = """
            select id, normalized_value, label, active, definition_version from ticket_tag_definitions
        """
        const val STATUS_SELECT = """
            select id, machine_key, agent_label, customer_label, status_category, active, display_order,
                   default_for_category, allowed_form_ids, description, definition_version
              from custom_ticket_statuses
        """
    }
}
