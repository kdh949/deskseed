package dev.deskseed.staffaccess.internal

import dev.deskseed.foundation.CommandContext
import dev.deskseed.foundation.CommandContexts
import dev.deskseed.foundation.RequestSource
import dev.deskseed.ticketing.AgentCommentDraft
import dev.deskseed.ticketing.CanonicalCommentContentCodec
import dev.deskseed.ticketing.InvalidCommentContentException
import dev.deskseed.ticketing.CommentVisibility
import dev.deskseed.ticketing.CreateChildTicketResult
import dev.deskseed.ticketing.TicketCommandInvalidException
import dev.deskseed.ticketing.TicketCommandResult
import dev.deskseed.ticketing.TicketField
import dev.deskseed.ticketing.TicketPriority
import dev.deskseed.ticketing.TicketStatus
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.util.UUID

@RestController
@RequestMapping("/api/v1/agent/tickets")
@Validated
internal class AgentTicketCommandController(
    private val applicationService: AgentTicketCommandApplicationService,
    private val objectMapper: ObjectMapper,
) {
    @PostMapping
    fun create(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @Valid @RequestBody body: CreateAgentTicketRequest,
        request: HttpServletRequest,
    ): ResponseEntity<TicketCommandResponse> {
        val result = applicationService.create(
            principal = principal,
            input = CreateAgentTicketInput(
                requester = body.requester.toInput(),
                subject = body.subject,
                firstComment = body.firstComment.toDraft(),
                priority = body.priority,
                groupId = body.groupId,
                assigneeId = body.assigneeId,
            ),
            context = commandContext(request, body.clientCommandId),
        )
        return ResponseEntity.created(URI.create("/api/v1/agent/tickets/${result.ticketNumber}"))
            .eTag(result.version.toString())
            .body(result.toResponse())
    }

    @PostMapping("/{ticketNumber}/commands")
    fun update(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @PathVariable @Positive ticketNumber: Long,
        @Valid @RequestBody body: UpdateAgentTicketRequest,
        request: HttpServletRequest,
    ): ResponseEntity<TicketCommandResponse> {
        val fields = body.changedFields.map { externalName ->
            TicketField.fromExternalName(externalName)
                ?: throw TicketCommandInvalidException("Unsupported changed field")
        }
        if (fields.size != fields.toSet().size) {
            throw TicketCommandInvalidException("changedFields must be unique")
        }
        validateDeclaredFields(body, fields.toSet())
        val groupId = nullableUuidField(body.groupId, TicketField.GROUP_ID, fields.toSet())
        val assigneeId = nullableUuidField(body.assigneeId, TicketField.ASSIGNEE_ID, fields.toSet())
        val result = applicationService.update(
            principal = principal,
            ticketNumber = ticketNumber,
            input = UpdateAgentTicketInput(
                expectedVersion = body.expectedVersion,
                changedFields = fields.toSet(),
                status = body.status,
                priority = body.priority,
                groupId = groupId,
                assigneeId = assigneeId,
                comment = body.comment?.toDraft(),
            ),
            context = commandContext(request, body.clientCommandId),
        )
        return ResponseEntity.ok().eTag(result.version.toString()).body(result.toResponse())
    }

    @PostMapping("/{ticketNumber}/transfer")
    fun transfer(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @PathVariable @Positive ticketNumber: Long,
        @RequestHeader("If-Match") ifMatch: String,
        @Valid @RequestBody body: TransferTicketRequest,
        request: HttpServletRequest,
    ): ResponseEntity<TicketCommandResponse> {
        requireMatchingIfMatch(ifMatch, body.expectedVersion)
        val result = applicationService.transfer(
            principal = principal,
            ticketNumber = ticketNumber,
            input = TransferTicketInput(
                expectedVersion = body.expectedVersion,
                groupId = body.groupId,
                assigneeId = body.assigneeId,
                reason = body.reason,
            ),
            context = commandContext(request, body.clientCommandId),
        )
        return ResponseEntity.ok().eTag(result.version.toString()).body(result.toResponse())
    }

    @PostMapping("/{ticketNumber}/children")
    fun createChild(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @PathVariable @Positive ticketNumber: Long,
        @RequestHeader("If-Match") ifMatch: String,
        @Valid @RequestBody body: CreateChildTicketRequest,
        request: HttpServletRequest,
    ): ResponseEntity<CreateChildTicketResponse> {
        requireMatchingIfMatch(ifMatch, body.expectedVersion)
        val result = applicationService.createChild(
            principal = principal,
            parentTicketNumber = ticketNumber,
            input = CreateChildTicketInput(
                expectedVersion = body.expectedVersion,
                subject = body.subject,
                body = body.body,
                groupId = body.groupId,
                assigneeId = body.assigneeId,
                priority = body.priority,
            ),
            context = commandContext(request, body.clientCommandId),
        )
        return ResponseEntity.created(URI.create("/api/v1/agent/tickets/${result.childTicketNumber}"))
            .eTag(result.parentVersion.toString())
            .body(result.toResponse())
    }

    private fun validateDeclaredFields(body: UpdateAgentTicketRequest, fields: Set<TicketField>) {
        if (fields.isEmpty() && body.comment == null) {
            throw TicketCommandInvalidException("A field or comment is required")
        }
        if ((TicketField.STATUS in fields) != (body.status != null)) {
            throw TicketCommandInvalidException("status and changedFields do not match")
        }
        if ((TicketField.PRIORITY in fields) != (body.priority != null)) {
            throw TicketCommandInvalidException("priority and changedFields do not match")
        }
    }

    private fun nullableUuidField(node: JsonNode?, field: TicketField, fields: Set<TicketField>): UUID? {
        if (field !in fields) {
            if (node != null) throw TicketCommandInvalidException("${field.externalName} must be declared in changedFields")
            return null
        }
        if (node == null) {
            throw TicketCommandInvalidException("${field.externalName} must be provided when declared in changedFields")
        }
        if (node.isNull) return null
        if (!node.isString) throw TicketCommandInvalidException("${field.externalName} must be a UUID or null")
        return runCatching { UUID.fromString(node.asString()) }
            .getOrElse { throw TicketCommandInvalidException("${field.externalName} must be a UUID or null") }
    }

    private fun commandContext(request: HttpServletRequest, clientCommandId: UUID?): CommandContext {
        val accepted = CommandContexts.from(request, RequestSource.AGENT_UI)
        return clientCommandId?.let { accepted.copy(commandId = it.toString()) } ?: accepted
    }

    private fun requireMatchingIfMatch(ifMatch: String, expectedVersion: Long) {
        val parsed = ifMatch.trim().removeSurrounding("\"").toLongOrNull()
            ?: throw TicketCommandInvalidException("If-Match must contain a numeric ticket ETag")
        if (parsed != expectedVersion) {
            throw TicketCommandInvalidException("If-Match and expectedVersion must identify the same version")
        }
    }

    private fun TicketCommandResult.toResponse() = TicketCommandResponse(
        ticketNumber = ticketNumber,
        version = version,
        auditId = auditId,
        warnings = warnings.map {
            TicketCommandWarningResponse(
                code = it.code,
                message = it.message,
                count = it.count,
                relatedTicketNumbers = it.relatedTicketNumbers,
            )
        },
    )

    private fun CreateChildTicketResult.toResponse() = CreateChildTicketResponse(
        parentTicketNumber = parentTicketNumber,
        parentVersion = parentVersion,
        childTicketNumber = childTicketNumber,
        parentAuditId = parentAuditId,
        childAuditId = childAuditId,
    )

    private fun CommentDraftRequest.toDraft(): AgentCommentDraft {
        if (attachmentIds.size != attachmentIds.toSet().size) {
            throw TicketCommandInvalidException("attachmentIds must be unique")
        }
        val canonical = try {
            CanonicalCommentContentCodec(objectMapper).decode(body, content, attachmentIds.toSet())
        } catch (failure: InvalidCommentContentException) {
            throw TicketCommandInvalidException(failure.message ?: "Comment content is invalid")
        }
        return AgentCommentDraft(
            visibility = visibility,
            body = canonical.body,
            attachmentIds = attachmentIds.toSet(),
            contentFormat = canonical.format,
            contentDocument = canonical.document,
        )
    }
}

internal data class AgentRequesterRequest(
    val customerId: UUID? = null,
    @field:Size(max = 100) val name: String? = null,
    @field:Email @field:Size(max = 254) val email: String? = null,
) {
    fun toInput(): AgentTicketRequesterInput {
        if (customerId != null) {
            if (name != null || email != null) {
                throw TicketCommandInvalidException("requester must specify either customerId or name and email, not both")
            }
            return AgentTicketRequesterInput.ExistingCustomer(customerId)
        }
        if (name.isNullOrBlank() || email.isNullOrBlank()) {
            throw TicketCommandInvalidException("requester must specify either customerId or both name and email")
        }
        return AgentTicketRequesterInput.NewCustomer(name, email)
    }
}

internal data class CommentDraftRequest(
    val visibility: CommentVisibility,
    @field:Size(max = 20_000) val body: String? = null,
    val content: JsonNode? = null,
    @field:Size(max = 5) val attachmentIds: List<UUID> = emptyList(),
)

internal data class CreateAgentTicketRequest(
    @field:Valid val requester: AgentRequesterRequest,
    @field:NotBlank @field:Size(max = 200) val subject: String,
    @field:Valid val firstComment: CommentDraftRequest,
    val priority: TicketPriority,
    val groupId: UUID? = null,
    val assigneeId: UUID? = null,
    val clientCommandId: UUID? = null,
)

internal data class UpdateAgentTicketRequest(
    @field:PositiveOrZero val expectedVersion: Long,
    @field:Size(max = 4) val changedFields: List<String>,
    val status: TicketStatus? = null,
    val priority: TicketPriority? = null,
    val groupId: JsonNode? = null,
    val assigneeId: JsonNode? = null,
    @field:Valid val comment: CommentDraftRequest? = null,
    val clientCommandId: UUID? = null,
)

internal data class TransferTicketRequest(
    @field:PositiveOrZero val expectedVersion: Long,
    val groupId: UUID,
    val assigneeId: UUID? = null,
    @field:Size(max = 2_000) val reason: String? = null,
    val clientCommandId: UUID? = null,
)

internal data class CreateChildTicketRequest(
    @field:PositiveOrZero val expectedVersion: Long,
    @field:NotBlank @field:Size(max = 200) val subject: String,
    @field:NotBlank @field:Size(max = 20_000) val body: String,
    val groupId: UUID,
    val assigneeId: UUID? = null,
    val priority: TicketPriority,
    val clientCommandId: UUID? = null,
)

internal data class TicketCommandWarningResponse(
    val code: String,
    val message: String,
    val count: Int,
    val relatedTicketNumbers: List<Long>,
)

internal data class TicketCommandResponse(
    val ticketNumber: Long,
    val version: Long,
    val auditId: UUID,
    val warnings: List<TicketCommandWarningResponse>,
)

internal data class CreateChildTicketResponse(
    val parentTicketNumber: Long,
    val parentVersion: Long,
    val childTicketNumber: Long,
    val parentAuditId: UUID,
    val childAuditId: UUID,
)
