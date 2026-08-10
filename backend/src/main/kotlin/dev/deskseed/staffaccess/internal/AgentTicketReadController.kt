package dev.deskseed.staffaccess.internal

import dev.deskseed.foundation.RequestIdFilter
import dev.deskseed.ticketing.DefaultStaffView
import dev.deskseed.ticketing.StaffActorSummary
import dev.deskseed.ticketing.StaffCommentView
import dev.deskseed.ticketing.StaffTicketHistoryItem
import dev.deskseed.ticketing.StaffTicketListFilter
import dev.deskseed.ticketing.StaffTicketSummary
import dev.deskseed.ticketing.TicketPriority
import dev.deskseed.ticketing.TicketStatus
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Positive
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/agent")
@Validated
internal class AgentTicketReadController(
    private val applicationService: AgentTicketReadApplicationService,
) {
    @GetMapping("/views")
    fun views(@AuthenticationPrincipal principal: StaffPrincipal): List<SavedViewResponse> =
        applicationService.listViews(principal).map {
            SavedViewResponse(
                key = it.key,
                name = it.name,
                scope = it.scope,
                categoryPath = listOf(it.category),
                ticketCount = null,
                readScope = it.readScope.name,
            )
        }

    @GetMapping("/views/{viewKey}/tickets")
    fun tickets(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @PathVariable viewKey: String,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "50") @Min(1) @Max(100) limit: Int,
        @RequestParam(defaultValue = STABLE_SORT) sort: String,
        @RequestParam(required = false) status: TicketStatus?,
        @RequestParam(required = false) priority: TicketPriority?,
        @RequestParam(required = false) groupId: UUID?,
        @RequestParam(required = false) assigneeId: String?,
    ): TicketSummaryPageResponse {
        require(sort == STABLE_SORT) { "Unsupported ticket sort" }
        val view = DefaultStaffView.fromKey(viewKey) ?: throw AgentTicketNotFoundException()
        val page = applicationService.listTickets(
            principal = principal,
            view = view,
            filters = StaffTicketListFilter(status, priority, groupId, assigneeId),
            cursor = cursor,
            limit = limit,
        )
        return TicketSummaryPageResponse(
            items = page.items.map(::ticketResponse),
            nextCursor = page.nextCursor,
            totalApproximate = null,
            sort = STABLE_SORT,
        )
    }

    @GetMapping("/tickets/{ticketNumber}")
    fun ticket(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @PathVariable @Positive ticketNumber: Long,
        @RequestHeader("X-Interaction-Id") interactionId: UUID,
        @RequestHeader("X-Deskseed-Read-Intent") readIntent: AgentReadIntent,
        request: HttpServletRequest,
    ): ResponseEntity<AgentTicketDetailResponse> {
        val detail = applicationService.readTicket(
            principal = principal,
            ticketNumber = ticketNumber,
            interactionId = interactionId,
            intent = readIntent,
            context = AgentReadRequestContext(
                requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE).toString(),
                correlationId = request.getAttribute(RequestIdFilter.CORRELATION_ID_ATTRIBUTE).toString(),
                ipAddress = request.remoteAddr,
                userAgent = request.getHeader("User-Agent"),
            ),
        )
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .eTag(detail.ticket.version.toString())
            .body(
                AgentTicketDetailResponse(
                    ticket = ticketResponse(detail.ticket),
                    comments = detail.comments.map(::commentResponse),
                    capabilities = listOf("READ"),
                    context = TicketContextResponse(
                        customer = TicketCustomerResponse(
                            id = detail.customer.id,
                            displayName = detail.customer.displayName,
                            email = detail.customer.email,
                        ),
                        parent = null,
                        children = emptyList(),
                        externalReferences = emptyList(),
                    ),
                    history = detail.history.map(::historyResponse),
                    warnings = emptyList(),
                ),
            )
    }

    private fun ticketResponse(ticket: StaffTicketSummary) = TicketSummaryResponse(
        ticketNumber = ticket.ticketNumber,
        subject = ticket.subject,
        status = ticket.status.name,
        priority = ticket.priority.name,
        requester = actorResponse(ticket.requester),
        group = ticket.group?.let { GroupRefResponse(it.id, it.name) },
        assignee = ticket.assignee?.let { StaffRefResponse(it.id, it.displayName) },
        updatedAt = ticket.updatedAt.toString(),
        version = ticket.version,
        isChild = ticket.isChild,
        openChildCount = ticket.openChildCount,
        sla = null,
    )

    private fun commentResponse(comment: StaffCommentView) = AgentCommentResponse(
        id = comment.id,
        visibility = comment.visibility.name,
        actor = actorResponse(comment.actor),
        body = comment.body,
        createdAt = comment.createdAt.toString(),
        source = comment.source,
        attachments = emptyList(),
    )

    private fun historyResponse(item: StaffTicketHistoryItem) = TicketHistoryResponse(
        id = item.id,
        eventType = item.eventType,
        actor = actorResponse(item.actor),
        occurredAt = item.occurredAt.toString(),
    )

    private fun actorResponse(actor: StaffActorSummary) =
        ActorSummaryResponse(actor.id, actor.type, actor.displayName)

    private companion object {
        const val STABLE_SORT = "updatedAt:desc,ticketNumber:desc"
    }
}

internal data class SavedViewResponse(
    val key: String,
    val name: String,
    val scope: String,
    val categoryPath: List<String>,
    val ticketCount: Long?,
    val readScope: String,
)

internal data class TicketSummaryPageResponse(
    val items: List<TicketSummaryResponse>,
    val nextCursor: String?,
    val totalApproximate: Long?,
    val sort: String,
)

internal data class TicketSummaryResponse(
    val ticketNumber: Long,
    val subject: String,
    val status: String,
    val priority: String,
    val requester: ActorSummaryResponse,
    val group: GroupRefResponse?,
    val assignee: StaffRefResponse?,
    val updatedAt: String,
    val version: Long,
    val isChild: Boolean,
    val openChildCount: Int,
    val sla: Any?,
)

internal data class ActorSummaryResponse(val id: UUID?, val type: String, val displayName: String)

internal data class GroupRefResponse(val id: UUID, val name: String)

internal data class StaffRefResponse(val id: UUID, val displayName: String)

internal data class AgentCommentResponse(
    val id: UUID,
    val visibility: String,
    val actor: ActorSummaryResponse,
    val body: String,
    val createdAt: String,
    val source: String,
    val attachments: List<Any>,
)

internal data class TicketCustomerResponse(val id: UUID, val displayName: String, val email: String)

internal data class TicketContextResponse(
    val customer: TicketCustomerResponse,
    val parent: TicketSummaryResponse?,
    val children: List<TicketSummaryResponse>,
    val externalReferences: List<Any>,
)

internal data class TicketHistoryResponse(
    val id: UUID,
    val eventType: String,
    val actor: ActorSummaryResponse,
    val occurredAt: String,
)

internal data class AgentTicketDetailResponse(
    val ticket: TicketSummaryResponse,
    val comments: List<AgentCommentResponse>,
    val capabilities: List<String>,
    val context: TicketContextResponse,
    val history: List<TicketHistoryResponse>,
    val warnings: List<Any>,
)
