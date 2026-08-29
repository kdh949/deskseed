package dev.deskseed.staffaccess.internal

import dev.deskseed.collaboration.TicketDraft
import dev.deskseed.collaboration.TicketDraftChannel
import dev.deskseed.foundation.CommandContexts
import dev.deskseed.foundation.RequestSource
import dev.deskseed.ticketing.CanonicalCommentContentCodec
import dev.deskseed.ticketing.CommentContentView
import dev.deskseed.ticketing.InvalidCommentContentException
import dev.deskseed.ticketing.TicketCommandInvalidException
import dev.deskseed.ticketing.commentContentView
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@RestController
@RequestMapping("/api/v1/agent")
@Validated
internal class AgentTicketDraftController(
    private val applicationService: AgentTicketDraftApplicationService,
    private val objectMapper: ObjectMapper,
) {
    @GetMapping("/tickets/{ticketNumber}/drafts/{channel}")
    fun draft(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @PathVariable @Positive ticketNumber: Long,
        @PathVariable channel: TicketDraftChannel,
    ): ResponseEntity<TicketDraftResponse> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(applicationService.read(principal, ticketNumber, channel).toResponse())

    @PutMapping("/tickets/{ticketNumber}/drafts/{channel}")
    fun save(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @PathVariable @Positive ticketNumber: Long,
        @PathVariable channel: TicketDraftChannel,
        @Valid @RequestBody body: SaveTicketDraftRequest,
        request: HttpServletRequest,
    ): ResponseEntity<TicketDraftResponse> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            applicationService.save(
                principal,
                ticketNumber,
                channel,
                body.toCommand(objectMapper),
                CommandContexts.from(request, RequestSource.AGENT_UI),
            ).toResponse(),
        )

    @DeleteMapping("/tickets/{ticketNumber}/drafts/{channel}")
    fun clear(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @PathVariable @Positive ticketNumber: Long,
        @PathVariable channel: TicketDraftChannel,
        @RequestParam @Positive expectedDraftVersion: Long,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        applicationService.clear(
            principal,
            ticketNumber,
            channel,
            expectedDraftVersion,
            CommandContexts.from(request, RequestSource.AGENT_UI),
        )
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
    }

    @GetMapping("/drafts/recoverable")
    fun recoverable(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @RequestParam(defaultValue = "50") @Positive limit: Int,
    ): ResponseEntity<RecoverableTicketDraftsResponse> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(RecoverableTicketDraftsResponse(applicationService.listRecoverable(principal, limit).map(TicketDraft::toResponse)))
}

internal data class SaveTicketDraftRequest(
    @field:Size(max = 20_000)
    val body: String? = null,
    val content: JsonNode? = null,
    @field:Size(max = 5)
    val attachmentIds: List<@NotNull UUID>,
    @field:NotNull
    val clientDeviceId: UUID,
    @field:PositiveOrZero
    val baseTicketVersion: Long,
    @field:PositiveOrZero
    val expectedDraftVersion: Long,
) {
    fun toCommand(objectMapper: ObjectMapper): SaveAgentTicketDraft {
        val canonical = try {
            CanonicalCommentContentCodec(objectMapper).decode(
                body = body,
                content = content,
                attachmentIds = attachmentIds.toSet(),
                allowEmpty = attachmentIds.isNotEmpty(),
            )
        } catch (failure: InvalidCommentContentException) {
            throw TicketCommandInvalidException(failure.message ?: "Draft content is invalid")
        }
        return SaveAgentTicketDraft(
        body = canonical.body,
        attachmentIds = attachmentIds,
        clientDeviceId = clientDeviceId,
        baseTicketVersion = baseTicketVersion,
        expectedDraftVersion = expectedDraftVersion,
        contentFormat = canonical.format,
        contentDocument = canonical.document,
        )
    }
}

internal data class TicketDraftResponse(
    val ticketNumber: Long,
    val channel: TicketDraftChannel,
    val body: String,
    val content: CommentContentView,
    val attachmentIds: List<UUID>,
    val clientDeviceId: UUID,
    val baseTicketVersion: Long,
    val draftVersion: Long,
    val updatedAt: Instant,
    val expiresAt: Instant,
)

internal data class RecoverableTicketDraftsResponse(
    val items: List<TicketDraftResponse>,
)

private fun TicketDraft.toResponse() = TicketDraftResponse(
    ticketNumber = ticketNumber,
    channel = channel,
    body = body,
    content = commentContentView(contentFormat, body, contentDocument),
    attachmentIds = attachmentIds,
    clientDeviceId = clientDeviceId,
    baseTicketVersion = baseTicketVersion,
    draftVersion = draftVersion,
    updatedAt = updatedAt,
    expiresAt = expiresAt,
)
