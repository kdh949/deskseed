package dev.deskseed.ticketconfiguration.internal

import dev.deskseed.audit.AdminSecurityAudit
import dev.deskseed.audit.AdminSecurityAuditWriter
import dev.deskseed.audit.AdminSecurityOutcome
import dev.deskseed.foundation.ActorType
import dev.deskseed.ticketconfiguration.ProjectedTicketFormField
import dev.deskseed.ticketconfiguration.TicketConfigurationAdminActor
import dev.deskseed.ticketconfiguration.TicketConfigurationAuditUnavailableException
import dev.deskseed.ticketconfiguration.TicketConfigurationConflictException
import dev.deskseed.ticketconfiguration.TicketConfigurationNotFoundException
import dev.deskseed.ticketconfiguration.TicketConfigurationPreconditionFailedException
import dev.deskseed.ticketconfiguration.TicketConfigurationValidationException
import dev.deskseed.ticketconfiguration.TicketCustomFieldType
import dev.deskseed.ticketconfiguration.TicketFieldDefinitionView
import dev.deskseed.ticketconfiguration.TicketFieldOptionView
import dev.deskseed.ticketconfiguration.TicketFieldValidation
import dev.deskseed.ticketconfiguration.TicketFormActorKind
import dev.deskseed.ticketconfiguration.TicketFormActorPolicy
import dev.deskseed.ticketconfiguration.TicketFormAdministration
import dev.deskseed.ticketconfiguration.TicketFormConditionalRule
import dev.deskseed.ticketconfiguration.TicketFormDraft
import dev.deskseed.ticketconfiguration.TicketFormFieldBehavior
import dev.deskseed.ticketconfiguration.TicketFormFieldEffect
import dev.deskseed.ticketconfiguration.TicketFormFieldPlacement
import dev.deskseed.ticketconfiguration.TicketFormLifecycle
import dev.deskseed.ticketconfiguration.TicketFormPreviewContext
import dev.deskseed.ticketconfiguration.TicketFormProjection
import dev.deskseed.ticketconfiguration.TicketFormValidationIssue
import dev.deskseed.ticketconfiguration.TicketFormValidationResult
import dev.deskseed.ticketconfiguration.TicketFormView
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

@Service
internal class JdbcTicketFormAdministration(
    private val jdbc: JdbcTemplate,
    private val auditWriter: AdminSecurityAuditWriter,
    private val objectMapper: ObjectMapper,
    private val conditions: TicketFormConditionEngine,
    private val clock: Clock,
) : TicketFormAdministration {
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    override fun listForms(): List<TicketFormView> = jdbc.query(
        "$FORM_SELECT order by name, id",
        ::form,
    )

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    override fun getForm(formId: UUID): TicketFormView = formById(formId)

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    override fun createForm(draft: TicketFormDraft, actor: TicketConfigurationAdminActor): TicketFormView = translateStorageFailure {
        val id = UUID.randomUUID()
        val now = Instant.now(clock)
        jdbc.update(
            """
            insert into ticket_forms
                (id, name, description, lifecycle, default_for_customer, default_for_agent,
                 draft_definition_json, current_version, published_version, aggregate_version, created_at, updated_at)
            values (?, ?, ?, 'DRAFT', ?, ?, cast(? as jsonb), 1, null, 1, ?, ?)
            """.trimIndent(),
            id, draft.name.trim(), normalized(draft.description), draft.defaultForCustomer, draft.defaultForAgent,
            draftJson(draft), now.atOffset(ZoneOffset.UTC), now.atOffset(ZoneOffset.UTC),
        )
        audit("TICKET_FORM_DRAFT_CREATED", id, actor, mapOf("formVersion" to "1"), now)
        formById(id)
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    override fun updateForm(
        formId: UUID,
        expectedVersion: Long,
        draft: TicketFormDraft,
        actor: TicketConfigurationAdminActor,
    ): TicketFormView = translateStorageFailure {
        val row = lockedForm(formId)
        requireExpected(row.aggregateVersion, expectedVersion)
        if (row.lifecycle == TicketFormLifecycle.ARCHIVED) {
            throw TicketConfigurationValidationException("ARCHIVED_FORM_READ_ONLY", "Archived forms cannot be edited")
        }
        val now = Instant.now(clock)
        jdbc.update(
            """
            update ticket_forms set
                name = ?, description = ?, default_for_customer = ?, default_for_agent = ?,
                draft_definition_json = cast(? as jsonb), current_version = current_version + 1,
                aggregate_version = aggregate_version + 1, updated_at = ?
            where id = ?
            """.trimIndent(),
            draft.name.trim(), normalized(draft.description), draft.defaultForCustomer, draft.defaultForAgent,
            draftJson(draft), now.atOffset(ZoneOffset.UTC), formId,
        )
        audit("TICKET_FORM_DRAFT_UPDATED", formId, actor, mapOf("formVersion" to (row.currentVersion + 1).toString()), now)
        formById(formId)
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    override fun publishForm(
        formId: UUID,
        expectedVersion: Long,
        actor: TicketConfigurationAdminActor,
    ): TicketFormView = translateStorageFailure {
        val row = lockedForm(formId)
        requireExpected(row.aggregateVersion, expectedVersion)
        if (row.lifecycle == TicketFormLifecycle.ARCHIVED) {
            throw TicketConfigurationValidationException("ARCHIVED_FORM_CANNOT_PUBLISH", "Archived forms cannot be published")
        }
        if (row.publishedVersion == row.currentVersion) return@translateStorageFailure row.toView()
        val draft = readDraft(row.definitionJson)
        val validation = validateForPublish(draft)
        if (!validation.valid) {
            throw TicketConfigurationValidationException("FORM_PUBLISH_INVALID", "Form has invalid conditional rules or field references")
        }
        val now = Instant.now(clock)
        jdbc.update(
            """
            insert into ticket_form_versions
                (form_id, version, definition_json, published_by_staff_id, published_by_display, published_at)
            values (?, ?, cast(? as jsonb), ?, ?, ?)
            """.trimIndent(),
            formId, row.currentVersion, row.definitionJson, actor.staffId, actor.displayName.take(100), now.atOffset(ZoneOffset.UTC),
        )
        try {
            jdbc.update(
                """
                update ticket_forms
                   set lifecycle = 'PUBLISHED', published_version = ?, aggregate_version = aggregate_version + 1, updated_at = ?
                 where id = ?
                """.trimIndent(),
                row.currentVersion, now.atOffset(ZoneOffset.UTC), formId,
            )
        } catch (failure: DataIntegrityViolationException) {
            throw TicketConfigurationConflictException("PUBLISHED_DEFAULT_FORM_EXISTS")
        }
        audit("TICKET_FORM_PUBLISHED", formId, actor, mapOf("formVersion" to row.currentVersion.toString()), now)
        formById(formId)
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    override fun archiveForm(
        formId: UUID,
        expectedVersion: Long,
        actor: TicketConfigurationAdminActor,
    ): TicketFormView = translateStorageFailure {
        val row = lockedForm(formId)
        requireExpected(row.aggregateVersion, expectedVersion)
        if (row.lifecycle == TicketFormLifecycle.ARCHIVED) return@translateStorageFailure row.toView()
        if (row.publishedVersion == null) {
            throw TicketConfigurationValidationException("UNPUBLISHED_FORM_CANNOT_ARCHIVE", "Publish a form before archiving it")
        }
        val now = Instant.now(clock)
        jdbc.update(
            """
            update ticket_forms set lifecycle = 'ARCHIVED', aggregate_version = aggregate_version + 1, updated_at = ? where id = ?
            """.trimIndent(),
            now.atOffset(ZoneOffset.UTC), formId,
        )
        audit("TICKET_FORM_ARCHIVED", formId, actor, mapOf("publishedVersion" to row.publishedVersion.toString()), now)
        formById(formId)
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    override fun validateForm(draft: TicketFormDraft): TicketFormValidationResult = validateForPublish(draft)

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    override fun previewForm(formId: UUID, context: TicketFormPreviewContext): TicketFormProjection {
        val form = formById(formId)
        if (form.lifecycle == TicketFormLifecycle.ARCHIVED) {
            throw TicketConfigurationValidationException("ARCHIVED_FORM_UNAVAILABLE", "Archived form cannot be previewed")
        }
        val fields = form.placements.associate { it.fieldId to fieldById(it.fieldId) }
        val states = form.placements.associate { placement ->
            placement.fieldId to FieldState.from(
                if (context.actorKind == TicketFormActorKind.CUSTOMER) placement.customer else placement.agent,
            )
        }.toMutableMap()
        val facts = context.facts.toMutableMap().apply {
            put("actorKind", context.actorKind.name)
            put("formId", form.id.toString())
            put("formVersion", form.version.toString())
            fields.values.forEach { field ->
                get("field.${field.machineKey}")?.let { put("field.${field.id}", it) }
            }
        }
        form.conditionalRules.sortedWith(compareBy<TicketFormConditionalRule> { it.priority }.thenBy { it.id }).forEach { rule ->
            if (conditions.evaluate(rule.condition, facts) == dev.deskseed.workflow.ConditionTruth.TRUE) {
                rule.effects.forEach { effect -> states[effect.fieldId]?.apply(effect.behavior) }
            }
        }
        return TicketFormProjection(
            formId = form.id,
            formVersion = form.version,
            fields = form.placements.sortedBy { it.order }.map { placement ->
                val state = checkNotNull(states[placement.fieldId]).normalized()
                val field = checkNotNull(fields[placement.fieldId])
                ProjectedTicketFormField(
                    field = field,
                    visible = state.visible,
                    editable = state.editable,
                    required = state.required,
                    options = if (field.type == TicketCustomFieldType.SINGLE_SELECT) activeOptions(field.id) else emptyList(),
                )
            },
        )
    }

    private fun validateForPublish(draft: TicketFormDraft): TicketFormValidationResult {
        val issues = mutableListOf<TicketFormValidationIssue>()
        val fields = draft.placements.associateWith { placement -> findField(placement.fieldId) }
        fields.forEach { (placement, field) ->
            if (field == null) issues += TicketFormValidationIssue("FIELD_NOT_FOUND", "placements[${placement.fieldId}]", "Placed field does not exist")
            else if (!field.active) issues += TicketFormValidationIssue("FIELD_INACTIVE", "placements[${placement.fieldId}]", "Inactive field cannot be published")
        }
        val placementIds = draft.placements.map { it.fieldId }.toSet()
        val effectsByPriority = mutableMapOf<Pair<Int, UUID>, MutableSet<TicketFormFieldBehavior>>()
        val graph = mutableMapOf<UUID, MutableSet<UUID>>()
        draft.conditionalRules.forEachIndexed { index, rule ->
            val path = "conditionalRules[$index].condition"
            issues += conditions.validate(rule.condition, path)
            val dependencies = conditions.referencedFieldIds(rule.condition)
            dependencies.forEach { dependency ->
                if (dependency !in placementIds) issues += TicketFormValidationIssue(
                    "CONDITION_FIELD_NOT_PLACED", path, "Condition field is not placed on this form",
                )
                if (fields.entries.firstOrNull { it.key.fieldId == dependency }?.value?.sensitive == true) {
                    issues += TicketFormValidationIssue("SENSITIVE_FIELD_CONDITION_FORBIDDEN", path, "Sensitive field cannot drive form visibility")
                }
            }
            rule.effects.forEachIndexed { effectIndex, effect ->
                val effectPath = "conditionalRules[$index].effects[$effectIndex]"
                if (effect.fieldId !in placementIds) {
                    issues += TicketFormValidationIssue("EFFECT_FIELD_NOT_PLACED", effectPath, "Effect field is not placed on this form")
                }
                val behaviors = effectsByPriority.getOrPut(rule.priority to effect.fieldId) { linkedSetOf() }
                if (behaviors.any { it.contradicts(effect.behavior) }) {
                    issues += TicketFormValidationIssue("CONTRADICTORY_EFFECT", effectPath, "Same-priority rules cannot produce contradictory field effects")
                }
                behaviors += effect.behavior
                dependencies.forEach { dependency -> graph.getOrPut(dependency) { linkedSetOf() } += effect.fieldId }
            }
        }
        if (hasCycle(graph)) issues += TicketFormValidationIssue(
            "CONDITIONAL_FIELD_CYCLE", "conditionalRules", "Conditional field dependencies contain a cycle",
        )
        return TicketFormValidationResult(issues.isEmpty(), issues)
    }

    private fun hasCycle(graph: Map<UUID, Set<UUID>>): Boolean {
        val visiting = mutableSetOf<UUID>()
        val visited = mutableSetOf<UUID>()
        fun visit(node: UUID): Boolean {
            if (node in visiting) return true
            if (!visited.add(node)) return false
            visiting += node
            val cyclic = graph[node].orEmpty().any(::visit)
            visiting -= node
            return cyclic
        }
        return graph.keys.any(::visit)
    }

    private fun formById(formId: UUID): TicketFormView = jdbc.query(
        "$FORM_SELECT where id = ?",
        ::form,
        formId,
    ).singleOrNull() ?: throw TicketConfigurationNotFoundException()

    private fun lockedForm(formId: UUID): FormRow = jdbc.query(
        "$FORM_ROW_SELECT where id = ? for update",
        ::formRow,
        formId,
    ).singleOrNull() ?: throw TicketConfigurationNotFoundException()

    private fun form(result: ResultSet, @Suppress("UNUSED_PARAMETER") row: Int): TicketFormView = formRow(result, row).toView()

    private fun formRow(result: ResultSet, @Suppress("UNUSED_PARAMETER") row: Int): FormRow = FormRow(
        id = result.getObject("id", UUID::class.java),
        name = result.getString("name"),
        description = result.getString("description"),
        lifecycle = TicketFormLifecycle.valueOf(result.getString("lifecycle")),
        defaultForCustomer = result.getBoolean("default_for_customer"),
        defaultForAgent = result.getBoolean("default_for_agent"),
        definitionJson = result.getString("draft_definition_json"),
        currentVersion = result.getInt("current_version"),
        publishedVersion = result.getInt("published_version").takeUnless { result.wasNull() },
        aggregateVersion = result.getLong("aggregate_version"),
        createdAt = result.getTimestamp("created_at").toInstant(),
        updatedAt = result.getTimestamp("updated_at").toInstant(),
    )

    private fun fieldById(fieldId: UUID): TicketFieldDefinitionView = findField(fieldId) ?: throw TicketConfigurationNotFoundException()

    private fun findField(fieldId: UUID): TicketFieldDefinitionView? = jdbc.query(
        "$FIELD_SELECT where id = ?",
        ::field,
        fieldId,
    ).singleOrNull()

    private fun field(result: ResultSet, @Suppress("UNUSED_PARAMETER") row: Int): TicketFieldDefinitionView =
        TicketFieldDefinitionView(
            result.getObject("id", UUID::class.java), result.getString("machine_key"),
            TicketCustomFieldType.valueOf(result.getString("field_type")), result.getString("staff_label"),
            result.getString("staff_description"), result.getString("customer_label"), result.getString("customer_description"),
            result.getBoolean("active"), result.getBoolean("customer_visible"), result.getBoolean("customer_editable"),
            result.getBoolean("agent_visible"), result.getBoolean("agent_editable"), result.getBoolean("searchable"),
            result.getBoolean("analytics_eligible"), result.getBoolean("sensitive"),
            objectMapper.readValue(result.getString("validation_json"), TicketFieldValidation::class.java),
            result.getLong("definition_version"), result.getTimestamp("created_at").toInstant(), result.getTimestamp("updated_at").toInstant(),
        )

    private fun activeOptions(fieldId: UUID): List<TicketFieldOptionView> = jdbc.query(
        """
        select id, machine_key, staff_label, customer_label, active, display_order, option_version
          from ticket_field_options where field_definition_id = ? and active = true order by display_order, id
        """.trimIndent(),
        { result, _ ->
            TicketFieldOptionView(
                result.getObject("id", UUID::class.java), result.getString("machine_key"), result.getString("staff_label"),
                result.getString("customer_label"), result.getBoolean("active"), result.getInt("display_order"), result.getLong("option_version"),
            )
        },
        fieldId,
    )

    private fun draftJson(draft: TicketFormDraft): String = objectMapper.writeValueAsString(FormDefinition(draft.placements, draft.conditionalRules, draft.allowedCustomStatusIds))

    private fun readDraft(json: String): TicketFormDraft {
        val definition = objectMapper.readValue(json, FormDefinition::class.java)
        return TicketFormDraft("snapshot", placements = definition.placements, conditionalRules = definition.conditionalRules, allowedCustomStatusIds = definition.allowedCustomStatusIds)
    }

    private fun FormRow.toView(): TicketFormView {
        val definition = objectMapper.readValue(definitionJson, FormDefinition::class.java)
        return TicketFormView(
            id, name, description, lifecycle, defaultForCustomer, defaultForAgent, aggregateVersion,
            definition.placements, definition.conditionalRules, definition.allowedCustomStatusIds, createdAt, updatedAt,
        )
    }

    private fun requireExpected(current: Long, expected: Long) {
        if (current != expected) throw TicketConfigurationPreconditionFailedException(current)
    }

    private fun normalized(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

    private fun audit(eventType: String, formId: UUID, actor: TicketConfigurationAdminActor, metadata: Map<String, String>, now: Instant) {
        auditWriter.append(
            AdminSecurityAudit(
                eventType, ActorType.STAFF, actor.staffId, actor.displayName, actor.source, "TICKET_FORM", formId,
                AdminSecurityOutcome.SUCCEEDED, actor.requestId, actor.correlationId, metadata, now,
            ),
        )
    }

    private fun <T> translateStorageFailure(block: () -> T): T = try {
        block()
    } catch (failure: DataAccessException) {
        throw TicketConfigurationAuditUnavailableException(failure)
    }

    private data class FormDefinition(
        val placements: List<TicketFormFieldPlacement>,
        val conditionalRules: List<TicketFormConditionalRule> = emptyList(),
        val allowedCustomStatusIds: Set<UUID> = emptySet(),
    )

    private data class FormRow(
        val id: UUID,
        val name: String,
        val description: String?,
        val lifecycle: TicketFormLifecycle,
        val defaultForCustomer: Boolean,
        val defaultForAgent: Boolean,
        val definitionJson: String,
        val currentVersion: Int,
        val publishedVersion: Int?,
        val aggregateVersion: Long,
        val createdAt: Instant,
        val updatedAt: Instant,
    )

    private data class FieldState(var visible: Boolean, var editable: Boolean, var required: Boolean) {
        fun apply(behavior: TicketFormFieldBehavior) {
            when (behavior) {
                TicketFormFieldBehavior.SHOW -> visible = true
                TicketFormFieldBehavior.HIDE -> { visible = false; editable = false; required = false }
                TicketFormFieldBehavior.REQUIRED -> if (visible) required = true
                TicketFormFieldBehavior.OPTIONAL -> required = false
                TicketFormFieldBehavior.READ_ONLY -> editable = false
                TicketFormFieldBehavior.EDITABLE -> if (visible) editable = true
            }
        }

        fun normalized() = apply { if (!visible) { editable = false; required = false } }

        companion object {
            fun from(policy: TicketFormActorPolicy) = FieldState(policy.visible, policy.editable, policy.required)
        }
    }

    private fun TicketFormFieldBehavior.contradicts(other: TicketFormFieldBehavior): Boolean =
        (this == TicketFormFieldBehavior.SHOW && other == TicketFormFieldBehavior.HIDE) ||
            (this == TicketFormFieldBehavior.HIDE && other == TicketFormFieldBehavior.SHOW) ||
            (this == TicketFormFieldBehavior.REQUIRED && other == TicketFormFieldBehavior.OPTIONAL) ||
            (this == TicketFormFieldBehavior.OPTIONAL && other == TicketFormFieldBehavior.REQUIRED) ||
            (this == TicketFormFieldBehavior.READ_ONLY && other == TicketFormFieldBehavior.EDITABLE) ||
            (this == TicketFormFieldBehavior.EDITABLE && other == TicketFormFieldBehavior.READ_ONLY)

    private companion object {
        const val FORM_SELECT = """
            select id, name, description, lifecycle, default_for_customer, default_for_agent, draft_definition_json,
                   current_version, published_version, aggregate_version, created_at, updated_at
              from ticket_forms
        """
        const val FORM_ROW_SELECT = FORM_SELECT
        const val FIELD_SELECT = """
            select id, machine_key, field_type, staff_label, staff_description, customer_label, customer_description,
                   customer_visible, customer_editable, agent_visible, agent_editable, searchable, analytics_eligible,
                   sensitive, validation_json, active, definition_version, created_at, updated_at
              from ticket_field_definitions
        """
    }
}
