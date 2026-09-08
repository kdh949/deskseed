package dev.deskseed.ticketconfiguration

import dev.deskseed.ticketing.TicketStatus
import dev.deskseed.ticketing.TicketKind
import java.util.UUID

/** Server-authorized projection boundary for agent reads; it exposes no JDBC entities. */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
data class TicketConfigurationRuntimeValue(
    val booleanValue: Boolean? = null,
    val numberValue: String? = null,
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
    val form: AgentTicketFormProjection? = null,
    val availableTags: List<AgentConfigurationChoice> = emptyList(),
    val availableStatuses: List<AgentConfigurationChoice> = emptyList(),
    val writable: Boolean = false,
)

data class TicketConfigurationDescriptorView(
    val key: String,
    val schemaVersion: Int,
    val contexts: List<String>,
    val sensitive: Boolean,
)

data class AgentConfigurationChoice(val id: UUID, val label: String)
data class AgentTicketFormField(
    val id: UUID, val machineKey: String, val type: TicketCustomFieldType,
    val label: String, val description: String?, val validation: TicketFieldValidation,
    val visible: Boolean, val editable: Boolean, val required: Boolean,
    val options: List<AgentConfigurationChoice>,
)
data class AgentTicketFormProjection(val formId: UUID, val formVersion: Int, val fields: List<AgentTicketFormField>)

interface TicketConfigurationRuntimeQuery {
    fun listAgentDescriptors(): List<TicketConfigurationDescriptorView>
    fun savedViewCatalog(): SavedViewConfigurationCatalog

    fun readAgentConfiguration(
        ticketId: UUID,
        ticketNumber: Long,
        version: Long,
        status: TicketStatus,
        candidates: Map<String, dev.deskseed.ticketing.TicketConfigurationFieldValue> = emptyMap(),
        customStatusId: UUID? = null,
    ): TicketConfigurationRuntimeValues
}

data class CustomerTicketFieldDefinition(
    val id: UUID,
    val machineKey: String,
    val type: TicketCustomFieldType,
    val label: String,
    val description: String?,
    val validation: TicketFieldValidation = TicketFieldValidation(),
)

data class CustomerTicketFieldOption(
    val id: UUID,
    val machineKey: String,
    val label: String,
    val order: Int = 0,
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
    fun projectCandidate(input: dev.deskseed.ticketing.CustomerRequestFormValues): CustomerTicketFormProjection
}

data class SavedViewCatalogField(val id: UUID, val machineKey: String, val label: String, val type: TicketCustomFieldType, val options: List<AgentConfigurationChoice>)
data class SavedViewConfigurationCatalog(val fields: List<SavedViewCatalogField>, val tags: List<AgentConfigurationChoice>, val forms: List<AgentConfigurationChoice>, val statuses: List<AgentConfigurationChoice>)
