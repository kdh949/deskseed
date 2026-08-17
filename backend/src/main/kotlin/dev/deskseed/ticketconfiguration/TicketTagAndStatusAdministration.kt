package dev.deskseed.ticketconfiguration

import dev.deskseed.ticketing.TicketStatus
import java.time.Instant
import java.util.UUID

data class TicketTagDefinitionDraft(
    val value: String,
    val label: String,
    val active: Boolean = true,
) {
    val normalizedValue: String = value.trim().lowercase()

    init {
        require(TAG_VALUE.matches(normalizedValue)) { "tag value is invalid" }
        TicketFieldDefinitionDraft.requireLabel(label, "label", 120)
    }

    companion object {
        private val TAG_VALUE = Regex("^[a-z0-9](?:[a-z0-9_-]{0,78}[a-z0-9])?$")
    }
}

data class TicketTagDefinitionView(
    val id: UUID,
    val value: String,
    val label: String,
    val active: Boolean,
    val highCardinalityWarning: Boolean,
    val version: Long,
)

data class CustomTicketStatusDraft(
    val machineKey: String,
    val agentLabel: String,
    val customerLabel: String? = null,
    val statusCategory: TicketStatus,
    val active: Boolean,
    val order: Int,
    val defaultForCategory: Boolean = false,
    val allowedFormIds: Set<UUID> = emptySet(),
    val description: String? = null,
) {
    init {
        require(MACHINE_KEY.matches(machineKey)) { "machineKey is invalid" }
        TicketFieldDefinitionDraft.requireLabel(agentLabel, "agentLabel", 120)
        TicketFieldDefinitionDraft.requireOptionalText(customerLabel, "customerLabel", 120)
        TicketFieldDefinitionDraft.requireOptionalText(description, "description", 500)
        require(statusCategory != TicketStatus.CLOSED) { "CLOSED cannot have a mutable custom status" }
        require(order >= 0) { "order must be non-negative" }
        require(!defaultForCategory || active) { "defaultForCategory requires active" }
        require(allowedFormIds.size <= 50) { "allowedFormIds must have at most 50 entries" }
    }

    companion object {
        private val MACHINE_KEY = Regex("^[a-z][a-z0-9-]*$")
    }
}

data class CustomTicketStatusView(
    val id: UUID,
    val machineKey: String,
    val agentLabel: String,
    val customerLabel: String?,
    val statusCategory: TicketStatus,
    val active: Boolean,
    val order: Int,
    val defaultForCategory: Boolean,
    val allowedFormIds: Set<UUID>,
    val description: String?,
    val version: Long,
)

interface TicketTagAndStatusAdministration {
    fun listTags(): List<TicketTagDefinitionView>

    fun createTag(draft: TicketTagDefinitionDraft, actor: TicketConfigurationAdminActor): TicketTagDefinitionView

    fun updateTag(
        tagId: UUID,
        expectedVersion: Long,
        draft: TicketTagDefinitionDraft,
        actor: TicketConfigurationAdminActor,
    ): TicketTagDefinitionView

    fun listStatuses(): List<CustomTicketStatusView>

    fun createStatus(draft: CustomTicketStatusDraft, actor: TicketConfigurationAdminActor): CustomTicketStatusView

    fun updateStatus(
        statusId: UUID,
        expectedVersion: Long,
        draft: CustomTicketStatusDraft,
        actor: TicketConfigurationAdminActor,
    ): CustomTicketStatusView

    fun reorderStatuses(ids: List<UUID>, actor: TicketConfigurationAdminActor): List<CustomTicketStatusView>
}
