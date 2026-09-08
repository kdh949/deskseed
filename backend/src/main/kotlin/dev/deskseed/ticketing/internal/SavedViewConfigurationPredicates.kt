package dev.deskseed.ticketing.internal

import dev.deskseed.ticketing.SavedViewCondition
import dev.deskseed.ticketing.SavedViewConditionField
import dev.deskseed.ticketing.SavedViewConditionOperator
import dev.deskseed.ticketing.SavedViewConditions
import dev.deskseed.ticketing.SavedViewConfigurationValidation
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.stereotype.Component
import java.util.UUID

/** Read-model SQL only. Client keys and values are always parameters, never SQL identifiers. */
@Component
internal class SavedViewConfigurationPredicates(private val jdbc: NamedParameterJdbcTemplate) : SavedViewConfigurationValidation {
    data class Field(val id: UUID, val type: String, val eligible: Boolean)

    override fun validate(conditions: SavedViewConditions) {
        val cache = mutableMapOf<String, Field?>()
        (conditions.all + conditions.any).filter { it.field == SavedViewConditionField.CUSTOM_FIELD }.forEach { condition ->
            val field = resolve(requireNotNull(condition.fieldKey), cache)
            require(field?.eligible == true) { "The field is unavailable for saved views" }
            typedValues(field, condition.values)
        }
    }

    fun compile(condition: SavedViewCondition, parameters: MapSqlParameterSource, prefix: String, cache: MutableMap<String, Field?>): String {
        val negative = condition.operator in setOf(SavedViewConditionOperator.NOT_EQUALS, SavedViewConditionOperator.NOT_IN)
        val negation = if (negative) "not " else ""
        val valuesParameter = "${prefix}ConfigurationValues"
        return when (condition.field) {
            SavedViewConditionField.TAG -> {
                parameters.addValue(valuesParameter, condition.values.map(UUID::fromString))
                "${negation}exists (select 1 from ticket_tag_assignments filter_tag where filter_tag.ticket_id = t.id and filter_tag.tag_definition_id in (:$valuesParameter))"
            }
            SavedViewConditionField.FORM -> {
                parameters.addValue(valuesParameter, condition.values.map(UUID::fromString))
                "${negation}exists (select 1 from ticket_customer_form_bindings filter_form where filter_form.ticket_id = t.id and filter_form.form_id in (:$valuesParameter))"
            }
            SavedViewConditionField.CUSTOM_STATUS -> {
                parameters.addValue(valuesParameter, condition.values.map(UUID::fromString))
                if (negative) "(t.custom_status_id is null or t.custom_status_id not in (:$valuesParameter))" else "t.custom_status_id in (:$valuesParameter)"
            }
            SavedViewConditionField.CUSTOM_FIELD -> {
                val field = resolve(requireNotNull(condition.fieldKey), cache)
                // Retired/restricted fields never make a negative condition match every ticket.
                if (field?.eligible != true) return "false"
                val fieldParameter = "${prefix}ConfigurationField"
                parameters.addValue(fieldParameter, field.id)
                parameters.addValue(valuesParameter, typedValues(field, condition.values))
                val column = when (field.type) { "CHECKBOX" -> "boolean_value"; "NUMBER" -> "number_value"; "SINGLE_SELECT" -> "option_id"; "SHORT_TEXT" -> "short_text_value"; else -> error("Unsupported view field") }
                """exists (select 1 from ticket_field_definitions filter_definition
                    where filter_definition.id = :$fieldParameter and filter_definition.active and filter_definition.agent_visible
                      and filter_definition.searchable and not filter_definition.sensitive)
                    and ${negation}exists (select 1 from ticket_custom_field_values filter_value
                    where filter_value.ticket_id = t.id and filter_value.field_definition_id = :$fieldParameter
                      and filter_value.$column in (:$valuesParameter))""".trimIndent().let { "($it)" }
            }
            else -> error("Only configuration predicates are delegated")
        }
    }

    private fun resolve(key: String, cache: MutableMap<String, Field?>): Field? = if (cache.containsKey(key)) cache[key] else jdbc.query(
        """select id, field_type, active and agent_visible and searchable and not sensitive and field_type <> 'LONG_TEXT' as eligible
            from ticket_field_definitions where machine_key = :key""", mapOf("key" to key),
        { result, _ -> Field(result.getObject("id", UUID::class.java), result.getString("field_type"), result.getBoolean("eligible")) },
    ).singleOrNull().also { cache[key] = it }

    private fun typedValues(field: Field, values: List<String>): List<Any> = values.map { value -> when (field.type) {
        "CHECKBOX" -> { require(value == "true" || value == "false") { "Boolean view values must be true or false" }; value.toBooleanStrict() }
        "NUMBER" -> { val number = value.toBigDecimalOrNull() ?: throw IllegalArgumentException("View number is invalid"); require(number.precision() <= 30 && number.scale() in -30..12) { "View number is out of bounds" }; number }
        "SINGLE_SELECT" -> UUID.fromString(value)
        "SHORT_TEXT" -> value
        else -> throw IllegalArgumentException("The field is unavailable for saved views")
    } }
}
