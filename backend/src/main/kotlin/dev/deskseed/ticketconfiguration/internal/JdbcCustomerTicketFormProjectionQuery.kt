package dev.deskseed.ticketconfiguration.internal

import dev.deskseed.ticketconfiguration.CustomerProjectedTicketField
import dev.deskseed.ticketconfiguration.CustomerTicketFieldDefinition
import dev.deskseed.ticketconfiguration.CustomerTicketFieldOption
import dev.deskseed.ticketconfiguration.CustomerTicketFormProjection
import dev.deskseed.ticketconfiguration.CustomerTicketFormProjectionQuery
import dev.deskseed.ticketconfiguration.TicketConfigurationNotFoundException
import dev.deskseed.ticketconfiguration.TicketFormActorPolicy
import dev.deskseed.ticketconfiguration.TicketFormConditionalRule
import dev.deskseed.ticketconfiguration.TicketFormFieldBehavior
import dev.deskseed.ticketconfiguration.TicketFormFieldPlacement
import dev.deskseed.ticketconfiguration.TicketCustomFieldType
import dev.deskseed.ticketing.TicketKind
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * Customer form rendering has its own projection model so staff labels,
 * descriptions, search/analytics flags, and unpublished definitions cannot
 * cross the customer API boundary.
 */
@Service
internal class JdbcCustomerTicketFormProjectionQuery(
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val conditions: TicketFormConditionEngine,
) : CustomerTicketFormProjectionQuery {
    override fun project(formId: UUID?, ticketKind: TicketKind): CustomerTicketFormProjection {
        val form = resolvePublishedCustomerForm(formId)
        val fields = form.definition.placements.associateWith { placement -> field(placement.fieldId) }
        val states = form.definition.placements.associate { placement ->
            placement.fieldId to FieldState.from(placement.customer)
        }.toMutableMap()
        val facts = mapOf(
            "actorKind" to "CUSTOMER",
            "ticketKind" to ticketKind.name,
            "statusCategory" to "NEW",
            "formId" to form.id.toString(),
            "formVersion" to form.version.toString(),
        )
        form.definition.conditionalRules.sortedWith(compareBy<TicketFormConditionalRule> { it.priority }.thenBy { it.id }).forEach { rule ->
            if (conditions.evaluate(rule.condition, facts) == dev.deskseed.workflow.ConditionTruth.TRUE) {
                rule.effects.forEach { effect -> states[effect.fieldId]?.apply(effect.behavior) }
            }
        }
        return CustomerTicketFormProjection(
            form.id,
            form.version,
            form.definition.placements.sortedBy { it.order }.mapNotNull { placement ->
                val definition = fields.getValue(placement) ?: return@mapNotNull null
                val state = checkNotNull(states[placement.fieldId]).normalized()
                if (!state.visible) return@mapNotNull null
                CustomerProjectedTicketField(
                    field = CustomerTicketFieldDefinition(
                        definition.id,
                        definition.machineKey,
                        definition.type,
                        definition.customerLabel,
                        definition.customerDescription,
                    ),
                    visible = true,
                    editable = state.editable,
                    required = state.required,
                    options = if (definition.type == TicketCustomFieldType.SINGLE_SELECT) options(definition.id) else emptyList(),
                )
            },
        )
    }

    private fun resolvePublishedCustomerForm(formId: UUID?): FormSnapshot {
        val sql = buildString {
            append(
                """
                select form.id, form.published_version, version.definition_json
                  from ticket_forms form
                  join ticket_form_versions version
                    on version.form_id = form.id and version.version = form.published_version
                 where form.lifecycle = 'PUBLISHED'
                """.trimIndent(),
            )
            if (formId == null) append(" and form.default_for_customer = true") else append(" and form.id = ?")
            append(" order by form.updated_at desc, form.id")
        }
        val forms = if (formId == null) jdbc.query(sql, ::form) else jdbc.query(sql, ::form, formId)
        return forms.singleOrNull() ?: throw TicketConfigurationNotFoundException()
    }

    private fun form(result: java.sql.ResultSet, @Suppress("UNUSED_PARAMETER") row: Int) = FormSnapshot(
        result.getObject("id", UUID::class.java),
        result.getInt("published_version"),
        objectMapper.readValue(result.getString("definition_json"), FormDefinition::class.java),
    )

    private fun field(id: UUID): CustomerField? = jdbc.query(
        """
        select id, machine_key, field_type, customer_label, customer_description
          from ticket_field_definitions
         where id = ? and active and customer_visible and customer_label is not null
        """.trimIndent(),
        { result, _ ->
            CustomerField(
                result.getObject("id", UUID::class.java), result.getString("machine_key"),
                TicketCustomFieldType.valueOf(result.getString("field_type")), result.getString("customer_label"),
                result.getString("customer_description"),
            )
        },
        id,
    ).singleOrNull()

    private fun options(fieldId: UUID): List<CustomerTicketFieldOption> = jdbc.query(
        """
        select id, machine_key, customer_label
          from ticket_field_options
         where field_definition_id = ? and active and customer_label is not null
         order by display_order, id
        """.trimIndent(),
        { result, _ ->
            CustomerTicketFieldOption(result.getObject("id", UUID::class.java), result.getString("machine_key"), result.getString("customer_label"))
        },
        fieldId,
    )

    private data class FormDefinition(
        val placements: List<TicketFormFieldPlacement>,
        val conditionalRules: List<TicketFormConditionalRule> = emptyList(),
        @Suppress("unused") val allowedCustomStatusIds: Set<UUID> = emptySet(),
    )

    private data class FormSnapshot(val id: UUID, val version: Int, val definition: FormDefinition)

    private data class CustomerField(
        val id: UUID,
        val machineKey: String,
        val type: TicketCustomFieldType,
        val customerLabel: String,
        val customerDescription: String?,
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
}
