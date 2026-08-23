package dev.deskseed.macro

import dev.deskseed.foundation.RequestSource
import dev.deskseed.ticketing.CommentVisibility
import dev.deskseed.ticketing.TicketConfigurationFieldValue
import dev.deskseed.ticketing.TicketPriority
import dev.deskseed.ticketing.TicketStatus
import java.time.Instant
import java.util.Locale
import java.util.UUID

enum class MacroScope {
    PERSONAL,
    SHARED,
}

enum class MacroActionType {
    STATUS,
    PRIORITY,
    GROUP,
    ASSIGNEE,
    ADD_TAG,
    REMOVE_TAG,
    CUSTOM_FIELD,
    CUSTOM_STATUS,
    COMMENT,
}

sealed interface MacroActionDefinition {
    val type: MacroActionType
}

data class MacroStatusAction(val status: TicketStatus) : MacroActionDefinition {
    override val type = MacroActionType.STATUS
}

data class MacroPriorityAction(val priority: TicketPriority) : MacroActionDefinition {
    override val type = MacroActionType.PRIORITY
}

data class MacroGroupAction(val groupId: UUID) : MacroActionDefinition {
    override val type = MacroActionType.GROUP
}

data class MacroAssigneeAction(val assigneeId: UUID?) : MacroActionDefinition {
    override val type = MacroActionType.ASSIGNEE
}

data class MacroAddTagAction(val tagId: UUID) : MacroActionDefinition {
    override val type = MacroActionType.ADD_TAG
}

data class MacroRemoveTagAction(val tagId: UUID) : MacroActionDefinition {
    override val type = MacroActionType.REMOVE_TAG
}

data class MacroCustomFieldAction(
    val fieldKey: String,
    val value: TicketConfigurationFieldValue,
) : MacroActionDefinition {
    override val type = MacroActionType.CUSTOM_FIELD

    init {
        require(FIELD_KEY.matches(fieldKey)) { "Macro custom field key is invalid" }
    }

    private companion object {
        val FIELD_KEY = Regex("^[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*$")
    }
}

data class MacroCustomStatusAction(val customStatusId: UUID) : MacroActionDefinition {
    override val type = MacroActionType.CUSTOM_STATUS
}

data class MacroCommentAction(
    val visibility: CommentVisibility,
    val template: String,
) : MacroActionDefinition {
    override val type = MacroActionType.COMMENT

    init {
        require(template.isNotBlank() && template.length <= 10_000) { "Macro comment template is invalid" }
        require(template.none(::isForbiddenControl)) { "Macro comment template contains a control character" }
        val placeholders = PLACEHOLDER.findAll(template).map { it.groupValues[1] }.toSet()
        require(placeholders.all(ALLOWED_PLACEHOLDERS::contains)) { "Macro comment template contains an unsupported placeholder" }
        require(template.replace(PLACEHOLDER, "").let { "{{" !in it && "}}" !in it }) {
            "Macro comment template contains an invalid placeholder"
        }
    }

    companion object {
        val ALLOWED_PLACEHOLDERS = setOf(
            "ticket.number",
            "ticket.subject",
            "ticket.status",
            "ticket.priority",
            "requester.name",
            "agent.name",
        )
        private val PLACEHOLDER = Regex("\\{\\{([a-z]+(?:\\.[a-z]+)+)}}")
        private fun isForbiddenControl(value: Char): Boolean = value.isISOControl() && value !in setOf('\n', '\r', '\t')
    }
}

data class MacroDefinitionDraft(
    val name: String,
    val actions: List<MacroActionDefinition>,
) {
    init {
        require(name.trim().length in 1..120 && name.none(Char::isISOControl)) { "Macro name is invalid" }
        require(actions.size in 1..50) { "Macro must contain between 1 and 50 actions" }
        require(actions.count { it.type == MacroActionType.COMMENT } <= 1) { "Macro can contain at most one comment action" }
        val singular = setOf(
            MacroActionType.STATUS,
            MacroActionType.PRIORITY,
            MacroActionType.GROUP,
            MacroActionType.ASSIGNEE,
            MacroActionType.CUSTOM_STATUS,
        )
        require(actions.filter { it.type in singular }.groupingBy(MacroActionDefinition::type).eachCount().values.all { it == 1 }) {
            "Macro contains duplicate singular actions"
        }
        require(actions.map(MacroActionDefinition::type).toSet().let {
            MacroActionType.STATUS !in it || MacroActionType.CUSTOM_STATUS !in it
        }) { "Macro cannot set both status and custom status" }
        val addedTags = actions.filterIsInstance<MacroAddTagAction>().map(MacroAddTagAction::tagId)
        val removedTags = actions.filterIsInstance<MacroRemoveTagAction>().map(MacroRemoveTagAction::tagId)
        require(addedTags.distinct().size == addedTags.size && removedTags.distinct().size == removedTags.size) {
            "Macro contains duplicate tag actions"
        }
        require((addedTags.toSet() intersect removedTags.toSet()).isEmpty()) { "Macro cannot add and remove the same tag" }
        val fieldKeys = actions.filterIsInstance<MacroCustomFieldAction>().map(MacroCustomFieldAction::fieldKey)
        require(fieldKeys.distinct().size == fieldKeys.size) { "Macro contains duplicate custom field actions" }
    }

    val normalizedName: String get() = name.trim().lowercase(Locale.ROOT)
}

data class MacroDefinitionActor(
    val staffId: UUID,
    val displayName: String,
    val isAdmin: Boolean,
    val authorities: Set<String>,
    val source: RequestSource,
    val requestId: String,
    val correlationId: String,
) {
    init {
        require(source in setOf(RequestSource.AGENT_UI, RequestSource.ADMIN_UI)) { "Macro definition source is invalid" }
    }
}

data class MacroDefinitionView(
    val id: UUID,
    val name: String,
    val scope: MacroScope,
    val ownerStaffId: UUID?,
    val currentVersion: Int,
    val activeVersion: Int?,
    val aggregateVersion: Long,
    val actions: List<MacroActionDefinition>,
    val createdAt: Instant,
    val updatedAt: Instant,
)

interface MacroDefinitionAdministration {
    fun listAccessible(actor: MacroDefinitionActor): List<MacroDefinitionView>

    fun getActive(macroId: UUID, actor: MacroDefinitionActor): MacroDefinitionView

    fun listManaged(scope: MacroScope, actor: MacroDefinitionActor): List<MacroDefinitionView>

    fun create(scope: MacroScope, draft: MacroDefinitionDraft, actor: MacroDefinitionActor): MacroDefinitionView

    fun createVersion(
        macroId: UUID,
        expectedAggregateVersion: Long,
        draft: MacroDefinitionDraft,
        actor: MacroDefinitionActor,
    ): MacroDefinitionView

    fun activate(
        macroId: UUID,
        macroVersion: Int,
        expectedAggregateVersion: Long,
        actor: MacroDefinitionActor,
    ): MacroDefinitionView

    fun deactivate(
        macroId: UUID,
        expectedAggregateVersion: Long,
        actor: MacroDefinitionActor,
    ): MacroDefinitionView
}

class MacroNotFoundException : RuntimeException()

class MacroConflictException(val code: String) : RuntimeException(code)

class MacroPreconditionFailedException(val currentVersion: Long) : RuntimeException()

class MacroValidationException(val code: String, message: String) : IllegalArgumentException(message)

class MacroAuditUnavailableException(cause: Throwable) : RuntimeException(cause)
