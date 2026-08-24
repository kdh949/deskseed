package dev.deskseed.ticketconfiguration.internal

import dev.deskseed.ticketconfiguration.TicketConfigurationValidationException
import dev.deskseed.ticketconfiguration.TicketCustomFieldType
import dev.deskseed.ticketconfiguration.TicketFormActorPolicy
import dev.deskseed.ticketconfiguration.TicketFormConditionalRule
import dev.deskseed.ticketconfiguration.TicketFormFieldBehavior
import dev.deskseed.ticketconfiguration.TicketFormFieldEffect
import dev.deskseed.ticketconfiguration.TicketFormFieldPlacement
import dev.deskseed.ticketing.TicketConfigurationAuditChange
import dev.deskseed.ticketing.TicketConfigurationFieldValue
import dev.deskseed.ticketing.TicketConfigurationMutationHandler
import dev.deskseed.ticketing.TicketConfigurationMutationRequest
import dev.deskseed.ticketing.TicketConfigurationMutationResult
import dev.deskseed.ticketing.TicketStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.ZoneOffset
import java.util.UUID

/**
 * Transaction participant for the public ticketing command boundary.  It never
 * creates its own transaction: typed values/tag rows and the caller's TicketAudit
 * therefore commit or roll back with the ticket version update as one unit.
 */
@Service
internal class JdbcTicketConfigurationMutationHandler(
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val conditions: TicketFormConditionEngine,
) : TicketConfigurationMutationHandler {
    override fun validate(request: TicketConfigurationMutationRequest) {
        val currentCustomStatus = jdbc.query(
            "select custom_status_id from tickets where id = ?",
            { result, _ -> result.getObject(1, UUID::class.java) },
            request.ticketId,
        ).singleOrNull()
        val form = resolveAgentForm(request)
        val requestedCustomStatus = request.customStatusId?.let(::activeStatus)
        requestedCustomStatus?.let { validateStatusFormCompatibility(it, form) }
        val requestedFields = request.fieldValues.mapValues { (machineKey, _) ->
            fieldByMachineKey(machineKey) ?: invalid("FIELD_NOT_FOUND", "The configured field does not exist")
        }
        val projectionFields = form?.definition?.placements
            ?.mapNotNull { placement -> fieldById(placement.fieldId) }
            ?.associateBy { it.id }
            ?: emptyMap()
        val projection = form?.let {
            projectAgentFields(it, request, projectionFields, currentCustomStatus, requestedCustomStatus)
        }
            ?: emptyMap()
        if (request.fieldValues.isNotEmpty() && form == null) {
            invalid("AGENT_FORM_UNAVAILABLE", "Agent field updates require one published default agent form")
        }
        requestedFields.toSortedMap().forEach { (machineKey, field) ->
            val policy = projection[field.id]
                ?: invalid("FIELD_NOT_IN_AGENT_FORM", "The field is not projected by the selected agent form")
            if (!field.active || !field.agentVisible || !field.agentEditable || !policy.visible || !policy.editable) {
                invalid("FIELD_NOT_EDITABLE", "The field is not editable in the current server projection")
            }
            validateValue(field, request.fieldValues.getValue(machineKey))
        }
        requireTagDefinitions(request.addTagIds, activeOnly = true)
        requireTagDefinitions(request.removeTagIds, activeOnly = false)
    }

    override fun apply(request: TicketConfigurationMutationRequest): TicketConfigurationMutationResult {
        val currentCustomStatus = jdbc.query(
            "select custom_status_id from tickets where id = ?",
            { result, _ -> result.getObject(1, UUID::class.java) },
            request.ticketId,
        ).singleOrNull()
        val form = resolveAgentForm(request)
        val requestedCustomStatus = request.customStatusId?.let(::activeStatus)
        requestedCustomStatus?.let { validateStatusFormCompatibility(it, form) }
        val requestedFields = request.fieldValues.mapValues { (machineKey, value) ->
            fieldByMachineKey(machineKey) ?: invalid("FIELD_NOT_FOUND", "The configured field does not exist")
        }
        val projectionFields = form?.definition?.placements
            ?.mapNotNull { placement -> fieldById(placement.fieldId) }
            ?.associateBy { it.id }
            ?: emptyMap()
        val projection = form?.let {
            projectAgentFields(it, request, projectionFields, currentCustomStatus, requestedCustomStatus)
        }
            ?: emptyMap()
        if (request.fieldValues.isNotEmpty() && form == null) {
            invalid("AGENT_FORM_UNAVAILABLE", "Agent field updates require one published default agent form")
        }

        val changedFieldKeys = mutableListOf<String>()
        requestedFields.toSortedMap().forEach { (machineKey, field) ->
            val selectedForm = checkNotNull(form) { "Field writes require the validated agent form" }
            val policy = projection[field.id]
                ?: invalid("FIELD_NOT_IN_AGENT_FORM", "The field is not projected by the selected agent form")
            if (!field.active || !field.agentVisible || !field.agentEditable || !policy.visible || !policy.editable) {
                invalid("FIELD_NOT_EDITABLE", "The field is not editable in the current server projection")
            }
            val normalized = validateValue(field, request.fieldValues.getValue(machineKey))
            val current = currentValue(request.ticketId, field.id)
            if (current != normalized) {
                jdbc.update(
                    """
                    insert into ticket_custom_field_values
                        (ticket_id, field_definition_id, boolean_value, number_value, option_id, short_text_value,
                         long_text_value, field_definition_version, form_id, form_version, updated_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    on conflict (ticket_id, field_definition_id) do update set
                        boolean_value = excluded.boolean_value,
                        number_value = excluded.number_value,
                        option_id = excluded.option_id,
                        short_text_value = excluded.short_text_value,
                        long_text_value = excluded.long_text_value,
                        field_definition_version = excluded.field_definition_version,
                        form_id = excluded.form_id,
                        form_version = excluded.form_version,
                        updated_at = excluded.updated_at
                    """.trimIndent(),
                    request.ticketId, field.id, normalized.booleanValue, normalized.numberValue, normalized.optionId,
                    normalized.shortTextValue, normalized.longTextValue, field.version, selectedForm.id, selectedForm.version,
                    request.occurredAt.atOffset(ZoneOffset.UTC),
                )
                changedFieldKeys += machineKey
            }
        }

        val added = applyAddedTags(request.ticketId, request.addTagIds, request.occurredAt)
        val removed = applyRemovedTags(request.ticketId, request.removeTagIds)
        val newCustomStatus = requestedCustomStatus?.also { status ->
            if (currentCustomStatus != status.id) {
                jdbc.update("update tickets set custom_status_id = ? where id = ?", status.id, request.ticketId)
            }
        }
        val newStatus = newCustomStatus?.category ?: request.currentStatus
        val customStatusChanged = request.customStatusId != null && request.customStatusId != currentCustomStatus
        if (changedFieldKeys.isEmpty() && added.isEmpty() && removed.isEmpty() && !customStatusChanged) {
            return TicketConfigurationMutationResult(request.currentStatus, emptyList())
        }

        val before = objectMapper.writeValueAsString(
            mapOf(
                "changedFieldKeys" to changedFieldKeys.sorted(),
                "addedTagIds" to added.map(UUID::toString).sorted(),
                "removedTagIds" to removed.map(UUID::toString).sorted(),
                "customStatusId" to currentCustomStatus?.toString(),
            ),
        )
        val after = objectMapper.writeValueAsString(
            mapOf(
                "changedFieldKeys" to changedFieldKeys.sorted(),
                "addedTagIds" to added.map(UUID::toString).sorted(),
                "removedTagIds" to removed.map(UUID::toString).sorted(),
                "customStatusId" to (request.customStatusId?.toString() ?: currentCustomStatus?.toString()),
            ),
        )
        return TicketConfigurationMutationResult(
            status = newStatus,
            auditChanges = listOf(
                TicketConfigurationAuditChange(
                    type = "TICKET_CONFIGURATION_UPDATED",
                    before = before,
                    after = after,
                    metadata = mapOf(
                        "changedFieldKeys" to changedFieldKeys.sorted(),
                        "addedTagIds" to added.map(UUID::toString).sorted(),
                        "removedTagIds" to removed.map(UUID::toString).sorted(),
                        "customStatusCategory" to newCustomStatus?.category?.name,
                        // The actual field values, including non-sensitive values, are intentionally absent.
                        "fieldValuesRedacted" to true,
                    ),
                ),
            ),
        )
    }

    private fun resolveAgentForm(request: TicketConfigurationMutationRequest): FormSnapshot? {
        val forms = jdbc.query(
            """
            select form.id, form.published_version, version.definition_json
              from ticket_forms form
              join ticket_form_versions version
                on version.form_id = form.id and version.version = form.published_version
             where form.lifecycle = 'PUBLISHED' and form.default_for_agent = true
             order by form.updated_at desc, form.id
            """.trimIndent(),
        ) { result, _ ->
            FormSnapshot(
                result.getObject("id", UUID::class.java),
                result.getInt("published_version"),
                objectMapper.readValue(result.getString("definition_json"), FormDefinition::class.java),
            )
        }
        if (forms.size > 1) invalid("AMBIGUOUS_AGENT_FORM", "Only one default published agent form may be selected")
        val form = forms.singleOrNull()
        if (request.formVersion != null && (form == null || form.version != request.formVersion)) {
            invalid("FORM_VERSION_NOT_PROJECTED", "The request formVersion is not the current default agent form snapshot")
        }
        return form
    }

    private fun projectAgentFields(
        form: FormSnapshot,
        request: TicketConfigurationMutationRequest,
        fields: Map<UUID, FieldDefinition>,
        currentCustomStatus: UUID?,
        requestedCustomStatus: CustomStatus?,
    ): Map<UUID, FieldState> {
        val states = form.definition.placements.associate { placement ->
            placement.fieldId to FieldState.from(placement.agent)
        }.toMutableMap()
        val facts = currentFacts(request, form, fields.values, currentCustomStatus, requestedCustomStatus)
        form.definition.conditionalRules.sortedWith(compareBy<TicketFormConditionalRule> { it.priority }.thenBy { it.id }).forEach { rule ->
            if (conditions.evaluate(rule.condition, facts) == dev.deskseed.workflow.ConditionTruth.TRUE) {
                rule.effects.forEach { effect -> states[effect.fieldId]?.apply(effect.behavior) }
            }
        }
        return states.mapValues { (_, state) -> state.normalized() }
    }

    private fun currentFacts(
        request: TicketConfigurationMutationRequest,
        form: FormSnapshot,
        fields: Collection<FieldDefinition>,
        currentCustomStatus: UUID?,
        requestedCustomStatus: CustomStatus?,
    ): MutableMap<String, String> = mutableMapOf(
        "actorKind" to "AGENT",
        "ticketKind" to request.ticketKind.name,
        "statusCategory" to (requestedCustomStatus?.category ?: request.currentStatus).name,
        "formId" to form.id.toString(),
        "formVersion" to form.version.toString(),
    ).apply {
        (requestedCustomStatus?.id ?: currentCustomStatus)?.let { put("customStatusId", it.toString()) }
        fields.forEach { field ->
            currentValue(request.ticketId, field.id)?.factValue()?.let { put("field.${field.id}", it) }
            request.fieldValues[field.machineKey]?.let { value ->
                value.booleanValue?.toString()
                    ?: value.numberValue
                    ?: value.optionId?.toString()
                    ?: value.shortTextValue
                    ?: value.longTextValue
            }?.let { put("field.${field.id}", it) }
        }
    }

    private fun validateValue(field: FieldDefinition, value: TicketConfigurationFieldValue): StoredValue {
        val validation = objectMapper.readTree(field.validationJson)
        return when (field.type) {
            TicketCustomFieldType.CHECKBOX -> value.booleanValue?.let { StoredValue(booleanValue = it) }
            TicketCustomFieldType.SINGLE_SELECT -> value.optionId?.let { optionId ->
                val valid = jdbc.queryForObject(
                    "select exists(select 1 from ticket_field_options where id = ? and field_definition_id = ? and active = true)",
                    Boolean::class.java,
                    optionId,
                    field.id,
                ) ?: false
                if (!valid) invalid("OPTION_NOT_ACTIVE_FOR_FIELD", "The selected option is not active for this field")
                StoredValue(optionId = optionId)
            }
            TicketCustomFieldType.NUMBER -> value.numberValue?.let { raw ->
                val decimal = try { BigDecimal(raw) } catch (_: NumberFormatException) {
                    invalid("NUMBER_VALUE_INVALID", "The number value is invalid")
                }
                checkNumberValidation(decimal, validation)
                StoredValue(numberValue = decimal)
            }
            TicketCustomFieldType.SHORT_TEXT -> value.shortTextValue?.let { raw ->
                checkTextValidation(raw, validation, 1_000)
                StoredValue(shortTextValue = raw)
            }
            TicketCustomFieldType.LONG_TEXT -> value.longTextValue?.let { raw ->
                checkTextValidation(raw, validation, 10_000)
                StoredValue(longTextValue = raw)
            }
        } ?: invalid("FIELD_VALUE_TYPE_MISMATCH", "The value does not match the configured field type")
    }

    private fun checkNumberValidation(value: BigDecimal, validation: JsonNode) {
        validation.path("minimum").takeIf(JsonNode::isNumber)?.decimalValue()?.let {
            if (value < it) invalid("NUMBER_BELOW_MINIMUM", "The number value is below the configured minimum")
        }
        validation.path("maximum").takeIf(JsonNode::isNumber)?.decimalValue()?.let {
            if (value > it) invalid("NUMBER_ABOVE_MAXIMUM", "The number value is above the configured maximum")
        }
        validation.path("precision").takeIf(JsonNode::isInt)?.asInt()?.let {
            if (value.precision() > it) invalid("NUMBER_PRECISION_EXCEEDED", "The number value exceeds configured precision")
        }
        validation.path("scale").takeIf(JsonNode::isInt)?.asInt()?.let {
            if (value.scale().coerceAtLeast(0) > it) invalid("NUMBER_SCALE_EXCEEDED", "The number value exceeds configured scale")
        }
    }

    private fun checkTextValidation(value: String, validation: JsonNode, hardMaximum: Int) {
        if (value.length > hardMaximum || value.any(Char::isISOControl)) {
            invalid("TEXT_VALUE_INVALID", "The text value exceeds its allowed shape")
        }
        validation.path("minLength").takeIf(JsonNode::isInt)?.asInt()?.let {
            if (value.length < it) invalid("TEXT_BELOW_MINIMUM_LENGTH", "The text value is shorter than configured")
        }
        validation.path("maxLength").takeIf(JsonNode::isInt)?.asInt()?.let {
            if (value.length > it) invalid("TEXT_ABOVE_MAXIMUM_LENGTH", "The text value is longer than configured")
        }
        validation.path("regex").takeIf(JsonNode::isTextual)?.asText()?.let { pattern ->
            val matches = runCatching { Regex(pattern).matches(value) }.getOrElse {
                invalid("FIELD_VALIDATION_INVALID", "The persisted field validation expression is invalid")
            }
            if (!matches) invalid("TEXT_REGEX_MISMATCH", "The text value does not satisfy configured validation")
        }
    }

    private fun applyAddedTags(ticketId: UUID, tagIds: Set<UUID>, occurredAt: java.time.Instant): Set<UUID> {
        if (tagIds.isEmpty()) return emptySet()
        requireTagDefinitions(tagIds, activeOnly = true)
        return tagIds.filterTo(linkedSetOf()) { tagId ->
            jdbc.update(
                "insert into ticket_tag_assignments (ticket_id, tag_definition_id, assigned_at) values (?, ?, ?) on conflict do nothing",
                ticketId, tagId, occurredAt.atOffset(ZoneOffset.UTC),
            ) == 1
        }
    }

    private fun applyRemovedTags(ticketId: UUID, tagIds: Set<UUID>): Set<UUID> {
        if (tagIds.isEmpty()) return emptySet()
        requireTagDefinitions(tagIds, activeOnly = false)
        return tagIds.filterTo(linkedSetOf()) { tagId ->
            jdbc.update("delete from ticket_tag_assignments where ticket_id = ? and tag_definition_id = ?", ticketId, tagId) == 1
        }
    }

    private fun requireTagDefinitions(tagIds: Set<UUID>, activeOnly: Boolean) {
        val found = jdbc.query(
            "select id from ticket_tag_definitions where id = any (cast(? as uuid[]))${if (activeOnly) " and active = true" else ""}",
            { result, _ -> result.getObject(1, UUID::class.java) },
            tagIds.toTypedArray(),
        ).toSet()
        if (found != tagIds) invalid("TAG_NOT_FOUND_OR_INACTIVE", "Tags must exist and additions must be active")
    }

    private fun activeStatus(id: UUID): CustomStatus = jdbc.query(
        "select id, status_category, allowed_form_ids from custom_ticket_statuses where id = ? and active = true",
        { result, _ ->
            CustomStatus(
                result.getObject("id", UUID::class.java),
                TicketStatus.valueOf(result.getString("status_category")),
                ((result.getArray("allowed_form_ids")?.array as? Array<*>)?.map { UUID.fromString(it.toString()) } ?: emptyList()).toSet(),
            )
        },
        id,
    ).singleOrNull() ?: invalid("CUSTOM_STATUS_NOT_ACTIVE", "The custom status is unavailable")

    private fun validateStatusFormCompatibility(status: CustomStatus, form: FormSnapshot?) {
        if (status.allowedFormIds.isNotEmpty() && (form == null || form.id !in status.allowedFormIds)) {
            invalid("CUSTOM_STATUS_FORM_MISMATCH", "The custom status is not allowed by the selected form")
        }
        if (form != null && form.definition.allowedCustomStatusIds.isNotEmpty() && status.id !in form.definition.allowedCustomStatusIds) {
            invalid("FORM_CUSTOM_STATUS_MISMATCH", "The selected form does not allow this custom status")
        }
    }

    private fun fieldByMachineKey(machineKey: String): FieldDefinition? = jdbc.query(
        """
        select id, machine_key, field_type, active, agent_visible, agent_editable, sensitive,
               validation_json, definition_version
          from ticket_field_definitions where machine_key = ?
        """.trimIndent(),
        { result, _ -> field(result) },
        machineKey,
    ).singleOrNull()

    private fun fieldById(fieldId: UUID): FieldDefinition? = jdbc.query(
        """
        select id, machine_key, field_type, active, agent_visible, agent_editable, sensitive,
               validation_json, definition_version
          from ticket_field_definitions where id = ?
        """.trimIndent(),
        { result, _ -> field(result) },
        fieldId,
    ).singleOrNull()

    private fun field(result: java.sql.ResultSet) = FieldDefinition(
        result.getObject("id", UUID::class.java), result.getString("machine_key"),
        TicketCustomFieldType.valueOf(result.getString("field_type")), result.getBoolean("active"),
        result.getBoolean("agent_visible"), result.getBoolean("agent_editable"), result.getBoolean("sensitive"),
        result.getString("validation_json"), result.getLong("definition_version"),
    )

    private fun currentValue(ticketId: UUID, fieldId: UUID): StoredValue? = jdbc.query(
        """
        select boolean_value, number_value, option_id, short_text_value, long_text_value
          from ticket_custom_field_values where ticket_id = ? and field_definition_id = ?
        """.trimIndent(),
        { result, _ ->
            StoredValue(
                booleanValue = result.nullableBoolean("boolean_value"),
                numberValue = result.getBigDecimal("number_value"),
                optionId = result.getObject("option_id", UUID::class.java),
                shortTextValue = result.getString("short_text_value"),
                longTextValue = result.getString("long_text_value"),
            )
        },
        ticketId,
        fieldId,
    ).singleOrNull()

    private fun invalid(code: String, message: String): Nothing = throw TicketConfigurationValidationException(code, message)

    private fun java.sql.ResultSet.nullableBoolean(column: String): Boolean? = getBoolean(column).let { value ->
        if (wasNull()) null else value
    }

    private data class FormDefinition(
        val placements: List<TicketFormFieldPlacement>,
        val conditionalRules: List<TicketFormConditionalRule> = emptyList(),
        val allowedCustomStatusIds: Set<UUID> = emptySet(),
    )

    private data class FormSnapshot(val id: UUID, val version: Int, val definition: FormDefinition)

    private data class FieldDefinition(
        val id: UUID,
        val machineKey: String,
        val type: TicketCustomFieldType,
        val active: Boolean,
        val agentVisible: Boolean,
        val agentEditable: Boolean,
        @Suppress("unused") val sensitive: Boolean,
        val validationJson: String,
        val version: Long,
    )

    private data class StoredValue(
        val booleanValue: Boolean? = null,
        val numberValue: BigDecimal? = null,
        val optionId: UUID? = null,
        val shortTextValue: String? = null,
        val longTextValue: String? = null,
    ) {
        fun factValue(): String? = booleanValue?.toString() ?: numberValue?.toPlainString() ?: optionId?.toString()
            ?: shortTextValue ?: longTextValue
    }

    private data class CustomStatus(val id: UUID, val category: TicketStatus, val allowedFormIds: Set<UUID>)

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
}
