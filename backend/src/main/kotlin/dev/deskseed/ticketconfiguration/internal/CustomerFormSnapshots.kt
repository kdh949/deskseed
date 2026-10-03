package dev.deskseed.ticketconfiguration.internal

import dev.deskseed.ticketconfiguration.TicketCustomFieldType
import dev.deskseed.ticketconfiguration.TicketFieldValidation
import dev.deskseed.ticketconfiguration.TicketFormFieldPlacement
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.util.UUID

internal data class CustomerFormFieldSnapshot(
    val id: UUID,
    val machineKey: String,
    val type: TicketCustomFieldType,
    val definitionVersion: Long,
    val customerEditable: Boolean,
    val validation: TicketFieldValidation,
    val options: List<CustomerFormOptionSnapshot>,
)
internal data class CustomerFormOptionSnapshot(val id: UUID, val machineKey: String, val order: Int)

@Component
internal class CustomerFormSnapshots(private val jdbc: JdbcTemplate, private val mapper: ObjectMapper) {
    /** Called inside publish while field/option rows are held against concurrent edits. */
    fun capture(placements: List<TicketFormFieldPlacement>): String = mapper.writeValueAsString(
        placements.sortedBy { it.fieldId }.mapNotNull { placement ->
            jdbc.query(
                """select id, machine_key, field_type, definition_version, customer_editable, validation_json
                   from ticket_field_definitions where id = ? and active and customer_visible and customer_label is not null for share""",
                { row, _ -> CustomerFormFieldSnapshot(
                    row.getObject("id", UUID::class.java), row.getString("machine_key"),
                    TicketCustomFieldType.valueOf(row.getString("field_type")), row.getLong("definition_version"),
                    row.getBoolean("customer_editable"), mapper.readValue(row.getString("validation_json"), TicketFieldValidation::class.java),
                    jdbc.query(
                        "select id, machine_key, display_order from ticket_field_options where field_definition_id = ? and active and customer_label is not null order by display_order, id for share",
                        { option, _ -> CustomerFormOptionSnapshot(option.getObject("id", UUID::class.java), option.getString("machine_key"), option.getInt("display_order")) },
                        placement.fieldId,
                    ),
                ) },
                placement.fieldId,
            ).singleOrNull()
        },
    )
    fun decode(json: String): List<CustomerFormFieldSnapshot> = mapper.readValue(json, Array<CustomerFormFieldSnapshot>::class.java).toList()
}
