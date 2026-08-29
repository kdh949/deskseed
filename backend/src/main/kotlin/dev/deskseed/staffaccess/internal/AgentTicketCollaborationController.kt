package dev.deskseed.staffaccess.internal

import dev.deskseed.collaboration.AgentNotification
import dev.deskseed.collaboration.CollaborationStaffSummary
import dev.deskseed.collaboration.TicketCollaborationNote
import dev.deskseed.foundation.CommandContexts
import dev.deskseed.foundation.RequestIdFilter
import dev.deskseed.foundation.RequestSource
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1/agent")
@Validated
internal class AgentTicketCollaborationController(
    private val applicationService: AgentTicketCollaborationApplicationService,
) {
    @GetMapping("/tickets/{ticketNumber}/collaboration-notes")
    fun listNotes(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @PathVariable @Positive ticketNumber: Long,
        @RequestHeader("X-Interaction-Id") interactionId: UUID,
        @RequestParam(required = false) @Size(max = 512) before: String?,
        @RequestParam(defaultValue = "20") @Positive @Max(100) limit: Int,
        request: HttpServletRequest,
    ): ResponseEntity<CollaborationNotePageResponse> {
        val (page, nextCursor) = applicationService.listNotes(
            principal,
            ticketNumber,
            before,
            limit,
            interactionId,
            request.readContext(),
        )
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            CollaborationNotePageResponse(page.items.map(TicketCollaborationNote::toResponse), nextCursor),
        )
    }

    @PostMapping("/tickets/{ticketNumber}/collaboration-notes")
    fun createNote(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @PathVariable @Positive ticketNumber: Long,
        @Valid @RequestBody body: CreateCollaborationNoteRequest,
        request: HttpServletRequest,
    ): ResponseEntity<CreateCollaborationNoteResponse> {
        val result = applicationService.createNote(
            principal = principal,
            ticketNumber = ticketNumber,
            rawBody = body.body,
            mentionedStaffIds = body.mentionedStaffIds,
            clientCommandId = body.clientCommandId,
            context = CommandContexts.from(request, RequestSource.AGENT_UI).copy(commandId = body.clientCommandId.toString()),
        )
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(
            CreateCollaborationNoteResponse(result.note.toResponse(), result.auditId, result.replayed),
        )
    }

    @GetMapping("/notifications")
    fun notifications(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @RequestParam(required = false) @Size(max = 512) before: String?,
        @RequestParam(defaultValue = "20") @Positive @Max(100) limit: Int,
    ): ResponseEntity<AgentNotificationPageResponse> {
        val (page, nextCursor) = applicationService.listNotifications(principal, before, limit)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            AgentNotificationPageResponse(page.items.map(AgentNotification::toResponse), page.unreadCount, nextCursor),
        )
    }

    @PutMapping("/notifications/{notificationId}/read")
    fun markRead(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @PathVariable notificationId: UUID,
    ): ResponseEntity<Void> {
        applicationService.markNotificationRead(principal, notificationId)
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
    }

    private fun HttpServletRequest.readContext() = AgentReadRequestContext(
        requestId = getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE).toString(),
        correlationId = getAttribute(RequestIdFilter.CORRELATION_ID_ATTRIBUTE).toString(),
        sessionId = getSession(false)?.id
            ?: throw AccessAuditUnavailableException(IllegalStateException("Authenticated staff session is unavailable")),
        ipAddress = remoteAddr,
        userAgent = getHeader("User-Agent"),
    )
}

internal data class CreateCollaborationNoteRequest(
    @field:NotBlank @field:Size(max = 4_000) val body: String,
    @field:Size(max = 20) val mentionedStaffIds: List<@NotNull UUID>,
    @field:NotNull val clientCommandId: UUID,
)

internal data class CollaborationActorResponse(val id: UUID, val type: String = "STAFF", val displayName: String)

internal data class CollaborationMentionedStaffResponse(val id: UUID, val displayName: String)

internal data class CollaborationNoteResponse(
    val id: UUID,
    val ticketNumber: Long,
    val author: CollaborationActorResponse,
    val body: String,
    val mentionedStaff: List<CollaborationMentionedStaffResponse>,
    val createdAt: Instant,
)

internal data class CollaborationNotePageResponse(val items: List<CollaborationNoteResponse>, val nextCursor: String?)

internal data class CreateCollaborationNoteResponse(
    val note: CollaborationNoteResponse,
    val auditId: UUID,
    val replayed: Boolean,
)

internal data class AgentNotificationResponse(
    val id: UUID,
    val type: String,
    val ticketNumber: Long,
    val noteId: UUID,
    val actor: CollaborationActorResponse,
    val createdAt: Instant,
    val readAt: Instant?,
)

internal data class AgentNotificationPageResponse(
    val items: List<AgentNotificationResponse>,
    val unreadCount: Int,
    val nextCursor: String?,
)

private fun TicketCollaborationNote.toResponse() = CollaborationNoteResponse(
    id = id,
    ticketNumber = ticketNumber,
    author = author.toResponse(),
    body = body,
    mentionedStaff = mentionedStaff.map { CollaborationMentionedStaffResponse(it.id, it.displayName) },
    createdAt = createdAt,
)

private fun CollaborationStaffSummary.toResponse() = CollaborationActorResponse(id, displayName = displayName)

private fun AgentNotification.toResponse() = AgentNotificationResponse(
    id = id,
    type = type.name,
    ticketNumber = ticketNumber,
    noteId = noteId,
    actor = actor.toResponse(),
    createdAt = createdAt,
    readAt = readAt,
)
