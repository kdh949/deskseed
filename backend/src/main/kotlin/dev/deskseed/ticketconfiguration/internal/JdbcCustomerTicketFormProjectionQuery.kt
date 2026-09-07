package dev.deskseed.ticketconfiguration.internal

import dev.deskseed.ticketconfiguration.CustomerProjectedTicketField
import dev.deskseed.ticketconfiguration.CustomerTicketFieldDefinition
import dev.deskseed.ticketconfiguration.CustomerTicketFieldOption
import dev.deskseed.ticketconfiguration.CustomerTicketFormProjection
import dev.deskseed.ticketconfiguration.CustomerTicketFormProjectionQuery
import dev.deskseed.ticketconfiguration.TicketFormActorPolicy
import dev.deskseed.ticketconfiguration.TicketFormConditionalRule
import dev.deskseed.ticketconfiguration.TicketFormFieldBehavior
import dev.deskseed.ticketconfiguration.TicketFormFieldPlacement
import dev.deskseed.ticketconfiguration.TicketCustomFieldType
import dev.deskseed.ticketing.CustomerFormUnavailableException
import dev.deskseed.ticketing.CustomerFormValidationException
import dev.deskseed.ticketing.CustomerFormVersionConflictException
import dev.deskseed.ticketing.CustomerRequestConfigurationConflictException
import dev.deskseed.ticketing.CustomerRequestFormBinding
import dev.deskseed.ticketing.CustomerRequestFormValues
import dev.deskseed.ticketing.TicketConfigurationAuditChange
import dev.deskseed.ticketing.TicketConfigurationFieldValue
import dev.deskseed.ticketing.TicketKind
import dev.deskseed.workflow.ConditionTruth
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Service
internal class JdbcCustomerTicketFormProjectionQuery(
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val conditions: TicketFormConditionEngine,
    private val snapshots: CustomerFormSnapshots,
) : CustomerTicketFormProjectionQuery, CustomerRequestFormBinding {
    @Transactional(readOnly = true)
    override fun project(formId: UUID?, ticketKind: TicketKind): CustomerTicketFormProjection {
        if (ticketKind != TicketKind.CUSTOMER_REQUEST) throw CustomerFormValidationException()
        val form = currentForm(formId) ?: throw CustomerFormUnavailableException()
        return display(evaluate(form, emptyMap(), required = false))
    }

    @Transactional(readOnly = true)
    override fun projectCandidate(input: CustomerRequestFormValues): CustomerTicketFormProjection {
        val form = resolve(input, requireCurrent = true) ?: throw CustomerFormUnavailableException()
        return display(evaluate(form, input.fieldValues, required = false))
    }

    @Transactional(readOnly = true)
    override fun normalize(input: CustomerRequestFormValues, requireCurrent: Boolean): CustomerRequestFormValues {
        val form = resolve(input, requireCurrent) ?: return input
        val result = evaluate(form, input.fieldValues, required = true)
        return CustomerRequestFormValues(form.id, form.version, result.values)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun bind(ticketId: UUID, input: CustomerRequestFormValues, occurredAt: Instant): List<TicketConfigurationAuditChange> {
        jdbc.queryForList("select pg_advisory_xact_lock_shared(1067539004)")
        // Hold the current form against publication/archive until ticket, values and audit commit.
        input.formId?.let { jdbc.queryForList("select id from ticket_forms where id = ? for share", it) }
        val form = resolve(input, requireCurrent = true) ?: return emptyList()
        val result = evaluate(form, input.fieldValues, required = true)
        display(result) // Current customer copy must still be available; never fall back to staff labels.
        jdbc.update("insert into ticket_customer_form_bindings(ticket_id, form_id, form_version, bound_at) values (?, ?, ?, ?)",
            ticketId, form.id, form.version, Timestamp.from(occurredAt))
        result.values.forEach { (key, value) ->
            val field = form.fields.single { it.machineKey == key }
            jdbc.update(
                """insert into ticket_custom_field_values
                   (ticket_id, field_definition_id, boolean_value, number_value, option_id, short_text_value,
                    long_text_value, field_definition_version, form_id, form_version, updated_at)
                   values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                ticketId, field.id, value.booleanValue, value.numberValue?.let(::BigDecimal), value.optionId,
                value.shortTextValue, value.longTextValue, field.definitionVersion, form.id, form.version, Timestamp.from(occurredAt),
            )
        }
        return listOf(TicketConfigurationAuditChange("TICKET_CONFIGURATION_UPDATED", null, null,
            mapOf("formId" to form.id.toString(), "formVersion" to form.version, "changedFieldKeys" to result.values.keys.sorted())))
    }

    private fun resolve(input: CustomerRequestFormValues, requireCurrent: Boolean): FormSnapshot? {
        if ((input.formId == null) != (input.formVersion == null) || input.fieldValues.size > 100) throw CustomerFormValidationException()
        if (input.formId == null) {
            if (input.fieldValues.isNotEmpty()) throw CustomerFormValidationException()
            if (requireCurrent && currentForm(null) != null) throw CustomerRequestConfigurationConflictException()
            return null
        }
        val form = if (requireCurrent) currentForm(input.formId) else jdbc.query(
            "$FORM_SELECT where form.id = ? and version.version = ?", ::form, input.formId, input.formVersion,
        ).singleOrNull()
        if (form == null || !form.customerEligible()) throw CustomerFormUnavailableException()
        if (form.version != input.formVersion) throw CustomerFormVersionConflictException()
        return form
    }

    private fun currentForm(id: UUID?): FormSnapshot? {
        val query = "$FORM_SELECT where form.lifecycle = 'PUBLISHED' and version.version = form.published_version" +
            if (id == null) " and form.default_for_customer" else " and form.id = ?"
        val rows = if (id == null) jdbc.query(query, ::form) else jdbc.query(query, ::form, id)
        return rows.singleOrNull()?.takeIf { it.customerEligible() }
    }

    private fun evaluate(form: FormSnapshot, input: Map<String, TicketConfigurationFieldValue>, required: Boolean): EvaluatedForm {
        val fields = form.fields.associateBy { it.machineKey }
        if (input.size > 100 || input.keys.any { it !in fields }) throw CustomerFormValidationException()
        var values = input
        // Removing hidden/read-only sources reaches a fixed point in at most the bounded field count.
        repeat(form.fields.size * 2 + 3) {
            val states = states(form, values)
            val retained = values.filterKeys { key ->
                val field = fields.getValue(key)
                states[field.id]?.let { it.visible && it.editable && field.customerEditable } == true
            }
            if (retained == values) {
                val normalized = retained.mapValues { (key, value) -> normalizeValue(fields.getValue(key), value) }
                if (normalized != values) {
                    values = normalized
                    return@repeat
                }
                if (required && form.fields.any { field ->
                    val state = states[field.id]
                    state?.visible == true && state.editable && field.customerEditable && state.required &&
                        (normalized[field.machineKey] == null || normalized[field.machineKey]?.let { value ->
                            value.shortTextValue?.isBlank() == true || value.longTextValue?.isBlank() == true
                        } == true)
                }) throw CustomerFormValidationException()
                return EvaluatedForm(form, states, normalized)
            }
            values = retained
        }
        throw CustomerFormValidationException()
    }

    private fun states(form: FormSnapshot, values: Map<String, TicketConfigurationFieldValue>): Map<UUID, FieldState> {
        val states = form.definition.placements.associate { it.fieldId to FieldState.from(it.customer) }.toMutableMap()
        val facts = mutableMapOf("actorKind" to "CUSTOMER", "ticketKind" to "CUSTOMER_REQUEST", "statusCategory" to "NEW",
            "formId" to form.id.toString(), "formVersion" to form.version.toString())
        form.fields.forEach { field -> values[field.machineKey]?.let { value ->
            val text = value.booleanValue?.toString() ?: value.numberValue ?: value.optionId?.toString() ?: value.shortTextValue ?: value.longTextValue
            if (text != null) facts["field.${field.id}"] = text
        } }
        form.definition.conditionalRules.sortedWith(compareBy<TicketFormConditionalRule> { it.priority }.thenBy { it.id }).forEach { rule ->
            if (conditions.evaluate(rule.condition, facts) == ConditionTruth.TRUE) rule.effects.forEach { states[it.fieldId]?.apply(it.behavior) }
        }
        return states
    }

    private fun display(result: EvaluatedForm): CustomerTicketFormProjection {
        val fields = result.form.fields.associateBy { it.id }
        if (fields.isEmpty()) return CustomerTicketFormProjection(result.form.id, result.form.version, emptyList())
        val placeholders = fields.keys.joinToString(",") { "?" }
        val copies = jdbc.query("select id, customer_label, customer_description from ticket_field_definitions where id in ($placeholders) and customer_visible and customer_label is not null",
            { row, _ -> row.getObject("id", UUID::class.java) to (row.getString("customer_label") to row.getString("customer_description")) }, *fields.keys.toTypedArray()).toMap()
        val optionLabels = jdbc.query("select id, customer_label from ticket_field_options where field_definition_id in ($placeholders) and customer_label is not null",
            { row, _ -> row.getObject("id", UUID::class.java) to row.getString("customer_label") }, *fields.keys.toTypedArray()).toMap()
        return CustomerTicketFormProjection(result.form.id, result.form.version,
            result.form.definition.placements.sortedBy { it.order }.mapNotNull { placement ->
                val field = fields[placement.fieldId] ?: return@mapNotNull null
                val state = result.states.getValue(field.id)
                if (!state.visible) return@mapNotNull null
                val copy = copies[field.id] ?: throw CustomerFormUnavailableException()
                CustomerProjectedTicketField(
                    CustomerTicketFieldDefinition(field.id, field.machineKey, field.type, copy.first, copy.second, field.validation),
                    true, state.editable && field.customerEditable, state.required,
                    field.options.map { option ->
                        val label = optionLabels[option.id] ?: throw CustomerFormUnavailableException()
                        CustomerTicketFieldOption(option.id, option.machineKey, label, option.order)
                    },
                )
            })
    }

    private fun normalizeValue(field: CustomerFormFieldSnapshot, value: TicketConfigurationFieldValue): TicketConfigurationFieldValue {
        val validation = field.validation
        fun invalid(): Nothing = throw CustomerFormValidationException()
        fun text(raw: String?, maximum: Int): String {
            if (raw == null) invalid()
            val clean = raw.trim()
            if (clean.length > maximum || clean.any(Char::isISOControl) ||
                validation.minLength?.let { clean.length < it } == true || validation.maxLength?.let { clean.length > it } == true) invalid()
            if (validation.regex?.let { pattern -> runCatching { Regex(pattern).matches(clean) }.getOrDefault(false) } == false) invalid()
            return clean
        }
        return when (field.type) {
            TicketCustomFieldType.CHECKBOX -> TicketConfigurationFieldValue(booleanValue = value.booleanValue ?: invalid())
            TicketCustomFieldType.SINGLE_SELECT -> TicketConfigurationFieldValue(optionId = value.optionId?.takeIf { id -> field.options.any { it.id == id } } ?: invalid())
            TicketCustomFieldType.SHORT_TEXT -> TicketConfigurationFieldValue(shortTextValue = text(value.shortTextValue, 1000))
            TicketCustomFieldType.LONG_TEXT -> TicketConfigurationFieldValue(longTextValue = text(value.longTextValue, 10000))
            TicketCustomFieldType.NUMBER -> {
                if ((value.numberValue?.length ?: 0) > 80) invalid()
                val number = runCatching { BigDecimal(value.numberValue ?: invalid()).stripTrailingZeros() }.getOrElse { invalid() }
                if (number.scale() < -30 || number.precision() - number.scale() > 18 || number.precision() > (validation.precision ?: 30) || number.scale().coerceAtLeast(0) > (validation.scale ?: 12) ||
                    validation.minimum?.let { number < BigDecimal.valueOf(it) } == true || validation.maximum?.let { number > BigDecimal.valueOf(it) } == true ||
                    number.toPlainString().length > 64) invalid()
                TicketConfigurationFieldValue(numberValue = number.toPlainString())
            }
        }
    }

    private fun form(row: ResultSet, @Suppress("UNUSED_PARAMETER") index: Int) = FormSnapshot(
        row.getObject("id", UUID::class.java), row.getInt("version"),
        objectMapper.readValue(row.getString("definition_json"), FormDefinition::class.java), snapshots.decode(row.getString("customer_field_snapshot_json")),
    )
    private data class FormDefinition(val placements: List<TicketFormFieldPlacement>, val conditionalRules: List<TicketFormConditionalRule> = emptyList(), @Suppress("unused") val allowedCustomStatusIds: Set<UUID> = emptySet())
    private data class FormSnapshot(val id: UUID, val version: Int, val definition: FormDefinition, val fields: List<CustomerFormFieldSnapshot>) {
        fun customerEligible() = fields.any { field -> definition.placements.any { it.fieldId == field.id && it.customer.visible } }
    }
    private data class EvaluatedForm(val form: FormSnapshot, val states: Map<UUID, FieldState>, val values: Map<String, TicketConfigurationFieldValue>)
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
        companion object { fun from(policy: TicketFormActorPolicy) = FieldState(policy.visible, policy.editable, policy.required) }
    }
    private companion object {
        const val FORM_SELECT = """select form.id, version.version, version.definition_json, version.customer_field_snapshot_json
            from ticket_forms form join ticket_form_versions version on version.form_id = form.id"""
    }
}
