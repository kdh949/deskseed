package dev.deskseed.ticketconfiguration

import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

enum class TicketFormLifecycle {
    DRAFT,
    PUBLISHED,
    ARCHIVED,
}

enum class TicketFormActorKind {
    CUSTOMER,
    AGENT,
}

enum class TicketFormFieldBehavior {
    SHOW,
    HIDE,
    REQUIRED,
    OPTIONAL,
    READ_ONLY,
    EDITABLE,
}

data class TicketFormActorPolicy(
    val visible: Boolean,
    val editable: Boolean,
    val required: Boolean,
) {
    init {
        require(!editable || visible) { "editable requires visible" }
        require(!required || visible) { "required requires visible" }
    }
}

data class TicketFormFieldPlacement(
    val fieldId: UUID,
    val order: Int,
    val customer: TicketFormActorPolicy,
    val agent: TicketFormActorPolicy,
) {
    init {
        require(order >= 0) { "field placement order must be non-negative" }
    }
}

data class TicketFormFieldEffect(
    val fieldId: UUID,
    val behavior: TicketFormFieldBehavior,
)

data class TicketFormConditionalRule(
    val id: UUID,
    val priority: Int,
    /**
     * A non-executable JSON representation of Foundation VersionedConditionAst.
     * Parsing/evaluation are server-owned and only registered ConditionHandlers run.
     */
    val condition: JsonNode,
    val effects: List<TicketFormFieldEffect>,
) {
    init {
        require(priority >= 0) { "conditional rule priority must be non-negative" }
        require(effects.isNotEmpty() && effects.size <= 20) { "conditional rule requires 1..20 effects" }
    }
}

data class TicketFormDraft(
    val name: String,
    val description: String? = null,
    val defaultForCustomer: Boolean = false,
    val defaultForAgent: Boolean = false,
    val placements: List<TicketFormFieldPlacement>,
    val conditionalRules: List<TicketFormConditionalRule> = emptyList(),
    val allowedCustomStatusIds: Set<UUID> = emptySet(),
) {
    init {
        TicketFieldDefinitionDraft.requireLabel(name, "name", 120)
        TicketFieldDefinitionDraft.requireOptionalText(description, "description", 500)
        require(placements.size in 1..100) { "form requires 1..100 field placements" }
        require(placements.map { it.fieldId }.distinct().size == placements.size) { "field placement IDs must be unique" }
        require(placements.map { it.order }.distinct().size == placements.size) { "field placement order must be unique" }
        require(conditionalRules.size <= 100) { "form permits at most 100 conditional rules" }
        require(allowedCustomStatusIds.size <= 50) { "form permits at most 50 custom statuses" }
    }
}

data class TicketFormView(
    val id: UUID,
    val name: String,
    val description: String?,
    val lifecycle: TicketFormLifecycle,
    val defaultForCustomer: Boolean,
    val defaultForAgent: Boolean,
    /** Optimistic aggregate version, including publish/archive lifecycle changes. */
    val version: Long,
    val placements: List<TicketFormFieldPlacement>,
    val conditionalRules: List<TicketFormConditionalRule>,
    val allowedCustomStatusIds: Set<UUID>,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class TicketFormValidationIssue(
    val code: String,
    val path: String,
    val message: String,
)

data class TicketFormValidationResult(
    val valid: Boolean,
    val issues: List<TicketFormValidationIssue>,
)

data class TicketFormPreviewContext(
    val actorKind: TicketFormActorKind,
    val facts: Map<String, String>,
)

data class ProjectedTicketFormField(
    val field: TicketFieldDefinitionView,
    val visible: Boolean,
    val editable: Boolean,
    val required: Boolean,
    val options: List<TicketFieldOptionView>,
)

data class TicketFormProjection(
    val formId: UUID,
    val formVersion: Long,
    val fields: List<ProjectedTicketFormField>,
)

interface TicketFormAdministration {
    fun listForms(): List<TicketFormView>

    fun getForm(formId: UUID): TicketFormView

    fun createForm(draft: TicketFormDraft, actor: TicketConfigurationAdminActor): TicketFormView

    fun updateForm(
        formId: UUID,
        expectedVersion: Long,
        draft: TicketFormDraft,
        actor: TicketConfigurationAdminActor,
    ): TicketFormView

    fun publishForm(formId: UUID, expectedVersion: Long, actor: TicketConfigurationAdminActor): TicketFormView

    fun archiveForm(formId: UUID, expectedVersion: Long, actor: TicketConfigurationAdminActor): TicketFormView

    fun validateForm(draft: TicketFormDraft): TicketFormValidationResult

    fun previewForm(formId: UUID, context: TicketFormPreviewContext): TicketFormProjection
}
