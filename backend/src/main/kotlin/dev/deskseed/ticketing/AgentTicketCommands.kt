package dev.deskseed.ticketing

import dev.deskseed.foundation.CommandContext
import dev.deskseed.integration.ExternalObjectType
import dev.deskseed.integration.ExternalReferenceView
import java.time.Instant
import java.util.UUID

enum class TicketField(val externalName: String) {
    STATUS("status"),
    PRIORITY("priority"),
    GROUP_ID("groupId"),
    ASSIGNEE_ID("assigneeId"),
    /** One optimistic-concurrency unit for typed values, tags, and custom status. */
    CONFIGURATION("configuration");

    companion object {
        fun fromExternalName(value: String): TicketField? = entries.firstOrNull { it.externalName == value }
    }
}

data class StaffTicketCommandActor(
    val id: UUID,
    val displayName: String,
    val isAdmin: Boolean,
)

data class AgentCommentDraft(
    val visibility: CommentVisibility,
    val body: String,
    /** Handles returned only by the private attachment upload boundary. */
    val attachmentIds: Set<UUID> = emptySet(),
)

data class CreateAgentTicketCommand(
    val requesterId: UUID,
    val subject: String,
    val firstComment: AgentCommentDraft,
    val priority: TicketPriority,
    val groupId: UUID?,
    val assigneeId: UUID?,
    val actor: StaffTicketCommandActor,
    val context: CommandContext,
)

data class UpdateAgentTicketCommand(
    val ticketNumber: Long,
    val expectedVersion: Long,
    val changedFields: Set<TicketField>,
    val status: TicketStatus?,
    val priority: TicketPriority?,
    val groupId: UUID?,
    val assigneeId: UUID?,
    val comment: AgentCommentDraft?,
    val actor: StaffTicketCommandActor,
    val context: CommandContext,
)

/**
 * Deliberately typed, one-of wire value for the ticket configuration command.
 * The ticketing module owns command/replay/audit semantics; the configuration
 * module validates the field definition, projected form, and storage shape.
 */
data class TicketConfigurationFieldValue(
    val booleanValue: Boolean? = null,
    val numberValue: String? = null,
    val optionId: UUID? = null,
    val shortTextValue: String? = null,
    val longTextValue: String? = null,
) {
    init {
        require(
            listOf(booleanValue, numberValue, optionId, shortTextValue, longTextValue).count { it != null } == 1,
        ) { "A ticket configuration value must contain exactly one typed value" }
    }
}

data class UpdateTicketConfigurationCommand(
    val ticketNumber: Long,
    val expectedVersion: Long,
    val formVersion: Int?,
    val fieldValues: Map<String, TicketConfigurationFieldValue>,
    val addTagIds: Set<UUID>,
    val removeTagIds: Set<UUID>,
    val customStatusId: UUID?,
    val actor: StaffTicketCommandActor,
    val context: CommandContext,
)

data class ApplyMacroTicketCommand(
    val ticketNumber: Long,
    val expectedVersion: Long,
    val macroId: UUID,
    val macroVersion: Int,
    val orderedActionTypes: List<String>,
    val changedFields: Set<TicketField>,
    val status: TicketStatus?,
    val priority: TicketPriority?,
    val groupId: UUID?,
    val assigneeId: UUID?,
    val comment: AgentCommentDraft?,
    val formVersion: Int?,
    val fieldValues: Map<String, TicketConfigurationFieldValue>,
    val addTagIds: Set<UUID>,
    val removeTagIds: Set<UUID>,
    val customStatusId: UUID?,
    val actor: StaffTicketCommandActor,
    val context: CommandContext,
)

/** Rechecked inside the ticket transaction after idempotent replay lookup. */
fun interface TicketMacroActivationGuard {
    fun requireActive(macroId: UUID, macroVersion: Int, actorStaffId: UUID)
}

data class TicketConfigurationMutationRequest(
    val ticketId: UUID,
    val ticketNumber: Long,
    val ticketKind: TicketKind,
    val currentStatus: TicketStatus,
    val formVersion: Int?,
    val fieldValues: Map<String, TicketConfigurationFieldValue>,
    val addTagIds: Set<UUID>,
    val removeTagIds: Set<UUID>,
    val customStatusId: UUID?,
    val occurredAt: Instant,
)

data class TicketConfigurationAuditChange(
    val type: String,
    /** Bounded JSON summaries only; typed field values are never copied into TicketAudit. */
    val before: String?,
    val after: String?,
    val metadata: Map<String, Any?>,
)

data class TicketConfigurationMutationResult(
    val status: TicketStatus,
    val auditChanges: List<TicketConfigurationAuditChange>,
)

/** Public named interface that keeps ticketing independent of configuration persistence internals. */
interface TicketConfigurationMutationHandler {
    /** Validates the same current configuration boundary without writing rows. */
    fun validate(request: TicketConfigurationMutationRequest)

    fun apply(request: TicketConfigurationMutationRequest): TicketConfigurationMutationResult
}

data class TransferTicketCommand(
    val ticketNumber: Long,
    val expectedVersion: Long,
    val groupId: UUID,
    val assigneeId: UUID?,
    val reason: String?,
    val actor: StaffTicketCommandActor,
    val context: CommandContext,
)

data class CreateChildTicketCommand(
    val parentTicketNumber: Long,
    val expectedVersion: Long,
    val subject: String,
    val body: String,
    val groupId: UUID,
    val assigneeId: UUID?,
    val priority: TicketPriority,
    val actor: StaffTicketCommandActor,
    val context: CommandContext,
)

data class TicketCommandWarning(
    val code: String,
    val message: String,
    val relatedTicketNumbers: List<Long>,
) {
    val count: Int = relatedTicketNumbers.size
}

data class TicketCommandResult(
    val ticketNumber: Long,
    val version: Long,
    val auditId: UUID,
    val warnings: List<TicketCommandWarning> = emptyList(),
    /** True only when an exact client-command replay returned the original committed result. */
    val replayed: Boolean = false,
)

data class CreateChildTicketResult(
    val parentTicketNumber: Long,
    val parentVersion: Long,
    val childTicketNumber: Long,
    val parentAuditId: UUID,
    val childAuditId: UUID,
)

data class CreateTicketExternalReferenceCommand(
    val ticketNumber: Long,
    val expectedVersion: Long,
    val externalSystemId: UUID,
    val objectType: ExternalObjectType,
    val externalId: String,
    val displayLabel: String,
    val safeDeepLink: String,
    val metadata: Map<String, Any>,
    val metadataObservedAt: Instant,
    val actor: StaffTicketCommandActor,
    val context: CommandContext,
)

data class DeleteTicketExternalReferenceCommand(
    val ticketNumber: Long,
    val expectedVersion: Long,
    val referenceId: UUID,
    val actor: StaffTicketCommandActor,
    val context: CommandContext,
)

data class TicketExternalReferenceCommandResult(
    val ticketNumber: Long,
    val version: Long,
    val auditId: UUID,
    val reference: ExternalReferenceView,
)

interface AgentTicketCommandService {
    fun create(command: CreateAgentTicketCommand): TicketCommandResult

    fun update(command: UpdateAgentTicketCommand): TicketCommandResult

    fun updateConfiguration(command: UpdateTicketConfigurationCommand): TicketCommandResult

    fun applyMacro(command: ApplyMacroTicketCommand): TicketCommandResult

    fun transfer(command: TransferTicketCommand): TicketCommandResult

    fun createChild(command: CreateChildTicketCommand): CreateChildTicketResult

    fun createExternalReference(command: CreateTicketExternalReferenceCommand): TicketExternalReferenceCommandResult

    fun deleteExternalReference(command: DeleteTicketExternalReferenceCommand): TicketExternalReferenceCommandResult
}

interface TicketWriteAuthorizationPolicy {
    fun canUpdate(
        actor: StaffTicketCommandActor,
        currentGroupId: UUID?,
        currentAssigneeId: UUID?,
    ): Boolean
}

interface TicketAssignmentPolicy {
    fun isActiveGroup(groupId: UUID): Boolean

    fun isActiveMember(groupId: UUID, staffId: UUID): Boolean
}

interface TicketOrganizationConsistencyGuard {
    fun acquire()
}

class AgentTicketNotFoundException : RuntimeException()

class TicketWriteForbiddenException : RuntimeException()

class TicketAssignmentInvalidException(val reason: String) : RuntimeException(reason)

class TicketTransitionInvalidException(val reason: String) : RuntimeException(reason)

class TicketCommandInvalidException(val reason: String) : RuntimeException(reason)

class TicketCommandIdReusedException : RuntimeException()

class TicketFieldConflictException(
    val currentVersion: Long,
    val conflictingFields: List<String>,
) : RuntimeException()

class TicketVersionPreconditionFailedException(val currentVersion: Long) : RuntimeException()

class TicketMacroVersionUnavailableException : RuntimeException()

class TicketRelationInvalidException(val reason: String) : RuntimeException(reason)

class TicketAuditUnavailableException(cause: Throwable) : RuntimeException(cause)

class TicketUpdateContentionException(cause: Throwable) : RuntimeException(cause)
