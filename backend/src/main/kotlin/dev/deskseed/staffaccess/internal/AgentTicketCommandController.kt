package dev.deskseed.staffaccess.internal

import dev.deskseed.foundation.CommandContext
import dev.deskseed.foundation.CommandContexts
import dev.deskseed.foundation.RequestSource
import dev.deskseed.ticketing.AgentCommentDraft
import dev.deskseed.ticketing.CommentVisibility
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
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import java.net.URI
import java.util.UUID

@RestController
@RequestMapping("/api/v1/agent/tickets")
@Validated
internal class AgentTicketCommandController(
    private val applicationService: AgentTicketCommandApplicationService,
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
                requesterName = body.requester.name,
                requesterEmail = body.requester.email,
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

    private fun TicketCommandResult.toResponse() = TicketCommandResponse(
        ticketNumber = ticketNumber,
        version = version,
        auditId = auditId,
        warnings = emptyList(),
    )

    private fun CommentDraftRequest.toDraft() = AgentCommentDraft(visibility, body)
}

internal data class AgentRequesterRequest(
    @field:NotBlank @field:Size(max = 100) val name: String,
    @field:NotBlank @field:Email @field:Size(max = 254) val email: String,
)

internal data class CommentDraftRequest(
    val visibility: CommentVisibility,
    @field:NotBlank @field:Size(max = 20_000) val body: String,
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

internal data class TicketCommandResponse(
    val ticketNumber: Long,
    val version: Long,
    val auditId: UUID,
    val warnings: List<Any>,
)
