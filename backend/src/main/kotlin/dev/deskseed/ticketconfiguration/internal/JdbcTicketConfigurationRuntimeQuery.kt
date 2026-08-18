package dev.deskseed.ticketconfiguration.internal

import dev.deskseed.ticketconfiguration.CustomTicketStatusView
import dev.deskseed.ticketconfiguration.TicketConfigurationDescriptorView
import dev.deskseed.ticketconfiguration.TicketConfigurationRuntimeQuery
import dev.deskseed.ticketconfiguration.TicketConfigurationRuntimeValue
import dev.deskseed.ticketconfiguration.TicketConfigurationRuntimeValues
import dev.deskseed.ticketconfiguration.TicketTagDefinitionView
import dev.deskseed.ticketing.TicketStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.util.UUID

@Service
internal class JdbcTicketConfigurationRuntimeQuery(
    private val jdbc: JdbcTemplate,
) : TicketConfigurationRuntimeQuery {
    override fun listAgentDescriptors(): List<TicketConfigurationDescriptorView> = jdbc.query(
        """
        select machine_key, definition_version, searchable, analytics_eligible, sensitive
          from ticket_field_definitions
         where active and agent_visible
         order by machine_key, id
        """.trimIndent(),
    ) { result, _ ->
        val contexts = buildList {
            if (result.getBoolean("searchable")) add("SAVED_VIEW")
            add("MACRO")
            add("TRIGGER")
            add("AUTOMATION")
            if (result.getBoolean("analytics_eligible")) add("ANALYTICS")
        }
        TicketConfigurationDescriptorView(
            key = result.getString("machine_key"),
            schemaVersion = result.getLong("definition_version").coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            contexts = contexts,
            sensitive = result.getBoolean("sensitive"),
        )
    }

    override fun readAgentConfiguration(
        ticketId: UUID,
        ticketNumber: Long,
        version: Long,
        status: TicketStatus,
    ): TicketConfigurationRuntimeValues {
        val values = jdbc.query(
            """
            select definition.machine_key, value.boolean_value, value.number_value, value.option_id,
                   value.short_text_value, value.long_text_value
              from ticket_custom_field_values value
              join ticket_field_definitions definition on definition.id = value.field_definition_id
             where value.ticket_id = ? and definition.agent_visible
             order by definition.machine_key, definition.id
            """.trimIndent(),
            { result, _ ->
                result.getString("machine_key") to TicketConfigurationRuntimeValue(
                    booleanValue = result.nullableBoolean("boolean_value"),
                    numberValue = result.getBigDecimal("number_value"),
                    optionId = result.getObject("option_id", UUID::class.java),
                    shortTextValue = result.getString("short_text_value"),
                    longTextValue = result.getString("long_text_value"),
                )
            },
            ticketId,
        ).toMap()
        val tags = jdbc.query(
            """
            select tag.id, tag.normalized_value, tag.label, tag.active, tag.definition_version,
                   (select count(*) >= 10000 from ticket_tag_assignments assignment_count
                     where assignment_count.tag_definition_id = tag.id) as high_cardinality_warning
              from ticket_tag_assignments assignment
              join ticket_tag_definitions tag on tag.id = assignment.tag_definition_id
             where assignment.ticket_id = ?
             order by tag.normalized_value, tag.id
            """.trimIndent(),
            { result, _ ->
                TicketTagDefinitionView(
                    result.getObject("id", UUID::class.java), result.getString("normalized_value"),
                    result.getString("label"), result.getBoolean("active"),
                    result.getBoolean("high_cardinality_warning"), result.getLong("definition_version"),
                )
            },
            ticketId,
        )
        val customStatus = jdbc.query(
            """
            select status.id, status.machine_key, status.agent_label, status.customer_label, status.status_category,
                   status.active, status.display_order, status.default_for_category, status.allowed_form_ids,
                   status.description, status.definition_version
              from tickets ticket
              join custom_ticket_statuses status on status.id = ticket.custom_status_id
             where ticket.id = ?
            """.trimIndent(),
            { result, _ ->
                CustomTicketStatusView(
                    result.getObject("id", UUID::class.java), result.getString("machine_key"),
                    result.getString("agent_label"), result.getString("customer_label"),
                    TicketStatus.valueOf(result.getString("status_category")), result.getBoolean("active"),
                    result.getInt("display_order"), result.getBoolean("default_for_category"),
                    ((result.getArray("allowed_form_ids")?.array as? Array<*>)?.map { UUID.fromString(it.toString()) }
                        ?: emptyList()).toSet(),
                    result.getString("description"), result.getLong("definition_version"),
                )
            },
            ticketId,
        ).singleOrNull()
        return TicketConfigurationRuntimeValues(ticketNumber, version, values, tags, status, customStatus)
    }

    private fun java.sql.ResultSet.nullableBoolean(column: String): Boolean? = getBoolean(column).let { value ->
        if (wasNull()) null else value
    }
}
