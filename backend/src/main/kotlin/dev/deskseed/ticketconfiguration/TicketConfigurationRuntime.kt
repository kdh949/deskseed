package dev.deskseed.ticketconfiguration

import dev.deskseed.ticketing.TicketStatus
import dev.deskseed.ticketing.TicketKind
import java.math.BigDecimal
import java.util.UUID

/** Server-authorized projection boundary for agent reads; it exposes no JDBC entities. */
data class TicketConfigurationRuntimeValue(
    val booleanValue: Boolean? = null,
    val numberValue: BigDecimal? = null,
    val optionId: UUID? = null,
    val shortTextValue: String? = null,
    val longTextValue: String? = null,
)

data class TicketConfigurationRuntimeValues(
    val ticketNumber: Long,
    val version: Long,
    val fieldValues: Map<String, TicketConfigurationRuntimeValue>,
    val tags: List<TicketTagDefinitionView>,
    val statusCategory: TicketStatus,
    val customStatus: CustomTicketStatusView?,
)

data class TicketConfigurationDescriptorView(
    val key: String,
    val schemaVersion: Int,
    val contexts: List<String>,
    val sensitive: Boolean,
)

interface TicketConfigurationRuntimeQuery {
    fun listAgentDescriptors(): List<TicketConfigurationDescriptorView>

    fun readAgentConfiguration(
        ticketId: UUID,
        ticketNumber: Long,
        version: Long,
        status: TicketStatus,
    ): TicketConfigurationRuntimeValues
}

data class CustomerTicketFieldDefinition(
    val id: UUID,
    val machineKey: String,
    val type: TicketCustomFieldType,
    val label: String,
    val description: String?,
)

data class CustomerTicketFieldOption(
    val id: UUID,
    val machineKey: String,
    val label: String,
)

data class CustomerProjectedTicketField(
    val field: CustomerTicketFieldDefinition,
    val visible: Boolean,
    val editable: Boolean,
    val required: Boolean,
    val options: List<CustomerTicketFieldOption>,
)

data class CustomerTicketFormProjection(
    val formId: UUID,
    val formVersion: Int,
    val fields: List<CustomerProjectedTicketField>,
)

interface CustomerTicketFormProjectionQuery {
    fun project(formId: UUID?, ticketKind: TicketKind): CustomerTicketFormProjection
}
