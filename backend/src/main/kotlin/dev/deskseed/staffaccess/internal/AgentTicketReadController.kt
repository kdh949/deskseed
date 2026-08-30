package dev.deskseed.staffaccess.internal

import dev.deskseed.foundation.RequestIdFilter
import dev.deskseed.attachments.TicketAttachment
import dev.deskseed.ticketing.SavedTicketView
import dev.deskseed.ticketing.SavedViewColumn
import dev.deskseed.ticketing.SavedViewConditions
import dev.deskseed.ticketing.SavedViewDefinition
import dev.deskseed.ticketing.SavedViewScope
import dev.deskseed.ticketing.StaffActorSummary
import dev.deskseed.ticketing.StaffCommentView
import dev.deskseed.ticketing.CommentContentView
import dev.deskseed.ticketing.commentContentView
import dev.deskseed.ticketing.StaffTicketHistoryItem
import dev.deskseed.ticketing.StaffTicketListFilter
import dev.deskseed.ticketing.StaffTicketSummary
import dev.deskseed.ticketing.StaffSlaDisplayState
import dev.deskseed.ticketing.TicketPriority
import dev.deskseed.ticketing.TicketStatus
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
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
internal class AgentTicketReadController(
    private val applicationService: AgentTicketReadApplicationService,
    private val savedViewApplicationService: SavedViewApplicationService,
    private val searchApplicationService: AgentTicketSearchApplicationService,
    private val customerSearchApplicationService: AgentCustomerSearchApplicationService,
) {
    @GetMapping("/views")
    fun views(@AuthenticationPrincipal principal: StaffPrincipal): List<SavedViewResponse> =
        savedViewApplicationService.list(principal).map { item -> item.toResponse() }

    @PostMapping("/views")
    fun createView(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @Valid @RequestBody body: CreateSavedViewRequest,
        request: HttpServletRequest,
    ): ResponseEntity<SavedViewResponse> {
        val view = savedViewApplicationService.create(principal, body.scope, body.toDefinition(), request.readContext())
        return ResponseEntity.status(201).eTag(view.definitionVersion.toString()).body(
            SavedViewListItem(view, null, "OMITTED_VISIBLE_LIMIT", null).toResponse(),
        )
    }

    @PostMapping("/views/preview")
    fun previewView(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @RequestHeader("X-Interaction-Id") interactionId: UUID,
        @Valid @RequestBody body: SavedViewDefinitionRequest,
        request: HttpServletRequest,
    ): ResponseEntity<SavedViewPreviewResponse> {
        val preview = savedViewApplicationService.preview(principal, body.toDefinition(), interactionId, request.readContext())
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            SavedViewPreviewResponse(
                preview.items.map(::ticketResponse),
                preview.ticketCount,
                preview.sort,
                preview.ticketCountAsOf,
            ),
        )
    }

    @PostMapping("/views/reorder")
    fun reorderViews(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @Valid @RequestBody body: ReorderSavedViewsRequest,
        request: HttpServletRequest,
    ): SavedViewOrderResponse {
        val result = savedViewApplicationService.reorder(
            principal,
            body.scope,
            body.expectedOrderVersion,
            body.viewKeys,
            request.readContext(),
        )
        return SavedViewOrderResponse(result.scope.name, result.orderVersion, result.viewKeys)
    }

    @PatchMapping("/views/{viewKey}")
    fun updateView(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @PathVariable viewKey: String,
        @Valid @RequestBody body: UpdateSavedViewRequest,
        request: HttpServletRequest,
    ): ResponseEntity<SavedViewResponse> {
        val view = savedViewApplicationService.update(
            principal,
            viewKey,
            body.expectedVersion,
            body.toDefinition(),
            request.readContext(),
        )
        return ResponseEntity.ok().eTag(view.definitionVersion.toString()).body(
            SavedViewListItem(view, null, "OMITTED_VISIBLE_LIMIT", null).toResponse(),
        )
    }

    @DeleteMapping("/views/{viewKey}")
    fun deleteView(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @PathVariable viewKey: String,
        @RequestHeader("If-Match") ifMatch: String,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        savedViewApplicationService.delete(principal, viewKey, parseViewVersion(ifMatch), request.readContext())
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/assignment-options")
    fun assignmentOptions(
        @AuthenticationPrincipal principal: StaffPrincipal,
    ): ResponseEntity<TicketAssignmentOptionsResponse> {
        val groups = applicationService.assignmentOptions(principal)
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(
                TicketAssignmentOptionsResponse(
                    groups = groups.map { group ->
                        TicketAssignmentGroupOptionResponse(
                            id = group.id,
                            name = group.name,
                            members = group.members.map { member ->
                                TicketAssignmentStaffOptionResponse(member.id, member.displayName)
                            },
                        )
                    },
                ),
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
        @RequestParam(required = false) slaState: StaffSlaDisplayState?,
        request: HttpServletRequest,
    ): TicketSummaryPageResponse {
        require(sort == STABLE_SORT) { "Unsupported ticket sort" }
        val page = savedViewApplicationService.listTickets(
            principal = principal,
            viewKey = viewKey,
            filters = StaffTicketListFilter(status, priority, groupId, assigneeId, slaState),
            cursor = cursor,
            limit = limit,
            interactionId = UUID.randomUUID(),
            context = request.readContext(),
        )
        return TicketSummaryPageResponse(
            items = page.items.map(::ticketResponse),
            nextCursor = page.nextCursor,
            totalApproximate = null,
            sort = page.sort,
        )
    }

    @GetMapping("/tickets/{ticketNumber}")
    fun ticket(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @PathVariable @Positive ticketNumber: Long,
        @RequestHeader("X-Interaction-Id") interactionId: UUID,
        @RequestHeader("X-Deskseed-Read-Intent") readIntent: AgentReadIntent,
        @RequestHeader("X-Origin-Search-Event-Id", required = false) originSearchEventId: UUID?,
        request: HttpServletRequest,
    ): ResponseEntity<AgentTicketDetailResponse> {
        val workspace = applicationService.readTicket(
            principal = principal,
            ticketNumber = ticketNumber,
            interactionId = interactionId,
            intent = readIntent,
            originSearchEventId = originSearchEventId,
            context = AgentReadRequestContext(
                requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE).toString(),
                correlationId = request.getAttribute(RequestIdFilter.CORRELATION_ID_ATTRIBUTE).toString(),
                sessionId = authenticatedSessionId(request),
                ipAddress = request.remoteAddr,
                userAgent = request.getHeader("User-Agent"),
            ),
        )
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .eTag(workspace.detail.ticket.version.toString())
            .body(
                AgentTicketDetailResponse(
                    ticket = ticketResponse(workspace.detail.ticket),
                    comments = workspace.detail.comments.map(::commentResponse),
                    capabilities = workspace.capabilities,
                    assignmentOptions = TicketAssignmentOptionsResponse(
                        groups = workspace.assignmentOptions.map { group ->
                            TicketAssignmentGroupOptionResponse(
                                id = group.id,
                                name = group.name,
                                members = group.members.map { member ->
                                    TicketAssignmentStaffOptionResponse(member.id, member.displayName)
                                },
                            )
                        },
                    ),
                    context = TicketContextResponse(
                        customer = workspace.detail.customer?.let {
                            TicketCustomerResponse(
                                id = it.id,
                                displayName = it.displayName,
                                email = it.email,
                            )
                        },
                        parent = workspace.detail.parent?.let(::ticketResponse),
                        children = workspace.detail.children.map(::ticketResponse),
                        externalReferenceCount = workspace.detail.externalReferenceCount,
                    ),
                    history = workspace.detail.history.map(::historyResponse),
                    warnings = emptyList(),
                ),
            )
    }

    @PostMapping("/search")
    fun search(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @RequestHeader("X-Interaction-Id") interactionId: UUID,
        @Valid @RequestBody body: AgentTicketSearchRequestBody,
        request: HttpServletRequest,
    ): ResponseEntity<AgentTicketSearchPageResponse> {
        val page = searchApplicationService.search(
            principal = principal,
            interactionId = interactionId,
            request = AgentTicketSearchRequest(
                query = body.query,
                filters = AgentTicketSearchFilter(
                    status = body.filters.status,
                    priority = body.filters.priority,
                    groupId = body.filters.groupId,
                    assigneeId = body.filters.assigneeId,
                    slaState = body.filters.slaState,
                ),
                sort = body.sort,
                cursor = body.cursor,
                limit = body.limit,
            ),
            context = AgentReadRequestContext(
                requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE).toString(),
                correlationId = request.getAttribute(RequestIdFilter.CORRELATION_ID_ATTRIBUTE).toString(),
                sessionId = authenticatedSessionId(request),
                ipAddress = request.remoteAddr,
                userAgent = request.getHeader("User-Agent"),
            ),
        )
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(
                AgentTicketSearchPageResponse(
                    searchEventId = page.searchEventId,
                    searchInteractionId = page.searchInteractionId,
                    items = page.items.map(::ticketResponse),
                    resultCount = page.resultCount,
                    sort = page.sort,
                    nextCursor = page.nextCursor,
                ),
            )
    }

    @PostMapping("/customers/search")
    fun searchCustomers(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @RequestHeader("X-Interaction-Id") interactionId: UUID,
        @Valid @RequestBody body: AgentCustomerSearchRequestBody,
        request: HttpServletRequest,
    ): ResponseEntity<AgentCustomerSearchPageResponse> {
        val page = customerSearchApplicationService.search(
            principal = principal,
            interactionId = interactionId,
            request = AgentCustomerSearchRequest(
                query = body.query,
                limit = body.limit,
            ),
            context = AgentReadRequestContext(
                requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE).toString(),
                correlationId = request.getAttribute(RequestIdFilter.CORRELATION_ID_ATTRIBUTE).toString(),
                sessionId = authenticatedSessionId(request),
                ipAddress = request.remoteAddr,
                userAgent = request.getHeader("User-Agent"),
            ),
        )
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(
                AgentCustomerSearchPageResponse(
                    searchEventId = page.searchEventId,
                    searchInteractionId = page.searchInteractionId,
                    items = page.items.map {
                        CustomerSummaryResponse(
                            id = it.id,
                            name = it.name,
                            email = it.email,
                            verified = it.verifiedAt != null,
                        )
                    },
                    resultCount = page.resultCount,
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
        createdAt = ticket.createdAt.toString(),
        updatedAt = ticket.updatedAt.toString(),
        version = ticket.version,
        isChild = ticket.isChild,
        openChildCount = ticket.openChildCount,
        sla = ticket.sla?.let {
            SlaBadgeResponse(
                metric = it.metric,
                state = it.state.name,
                dueAt = it.dueAt?.toString(),
                targetMinutes = it.targetMinutes,
                policyVersion = it.policyVersion,
                scheduleVersion = it.scheduleVersion,
            )
        },
    )

    private fun commentResponse(comment: StaffCommentView) = AgentCommentResponse(
        id = comment.id,
        visibility = comment.visibility.name,
        actor = actorResponse(comment.actor),
        body = comment.body,
        content = commentContentView(comment.contentFormat, comment.body, comment.contentDocument),
        createdAt = comment.createdAt.toString(),
        source = comment.source,
        attachments = comment.attachments,
    )

    private fun historyResponse(item: StaffTicketHistoryItem) = TicketHistoryResponse(
        id = item.id,
        eventType = item.eventType,
        actor = actorResponse(item.actor),
        occurredAt = item.occurredAt.toString(),
    )

    private fun actorResponse(actor: StaffActorSummary) =
        ActorSummaryResponse(actor.id, actor.type, actor.displayName)

    private fun SavedViewListItem.toResponse() = SavedViewResponse(
        id = view.id,
        key = view.key,
        name = view.definition.name,
        description = view.definition.description,
        scope = view.scope.name,
        ownerStaffId = view.ownerStaffId,
        active = view.active,
        definitionVersion = view.definitionVersion,
        orderVersion = view.orderVersion,
        categoryPath = view.categoryPath,
        conditions = view.definition.conditions,
        columns = view.definition.columns,
        sort = view.definition.sort,
        ticketCount = ticketCount,
        ticketCountState = ticketCountState,
        ticketCountAsOf = ticketCountAsOf,
        readScope = "ALL_TICKETS",
    )

    private fun HttpServletRequest.readContext() = AgentReadRequestContext(
        requestId = getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE).toString(),
        correlationId = getAttribute(RequestIdFilter.CORRELATION_ID_ATTRIBUTE).toString(),
        sessionId = authenticatedSessionId(this),
        ipAddress = remoteAddr,
        userAgent = getHeader("User-Agent"),
    )

    private fun parseViewVersion(ifMatch: String): Long = ifMatch.trim().removeSurrounding("\"").toLongOrNull()
        ?.takeIf { it >= 1 }
        ?: throw IllegalArgumentException("If-Match must contain a positive saved view definition version")

    private fun authenticatedSessionId(request: HttpServletRequest): String =
        request.getSession(false)?.id
            ?: throw AccessAuditUnavailableException(IllegalStateException("Authenticated staff session is unavailable"))

    private companion object {
        const val STABLE_SORT = "updatedAt:desc,ticketNumber:desc"
    }
}

internal data class AgentTicketSearchFiltersRequest(
    val status: TicketStatus? = null,
    val priority: TicketPriority? = null,
    val groupId: UUID? = null,
    @field:Size(max = 64)
    val assigneeId: String? = null,
    val slaState: StaffSlaDisplayState? = null,
)

internal data class AgentTicketSearchRequestBody(
    @field:NotBlank
    @field:Size(max = 500)
    val query: String,
    @field:Valid
    val filters: AgentTicketSearchFiltersRequest,
    @field:NotBlank
    val sort: String,
    @field:Size(max = 2048)
    val cursor: String? = null,
    @field:Min(1)
    @field:Max(100)
    val limit: Int,
)

internal data class AgentTicketSearchPageResponse(
    val searchEventId: UUID,
    val searchInteractionId: UUID,
    val items: List<TicketSummaryResponse>,
    val resultCount: Long,
    val sort: String,
    val nextCursor: String?,
)

internal data class AgentCustomerSearchRequestBody(
    @field:NotBlank
    @field:Size(max = 200)
    val query: String,
    @field:Min(1)
    @field:Max(25)
    val limit: Int,
)

internal data class AgentCustomerSearchPageResponse(
    val searchEventId: UUID,
    val searchInteractionId: UUID,
    val items: List<CustomerSummaryResponse>,
    val resultCount: Long,
)

internal data class CustomerSummaryResponse(
    val id: UUID,
    val name: String,
    val email: String,
    val verified: Boolean,
)

internal data class SavedViewResponse(
    val id: UUID,
    val key: String,
    val name: String,
    val description: String,
    val scope: String,
    val ownerStaffId: UUID?,
    val active: Boolean,
    val definitionVersion: Long,
    val orderVersion: Long,
    val categoryPath: List<String>,
    val conditions: SavedViewConditions,
    val columns: List<SavedViewColumn>,
    val sort: String,
    val ticketCount: Long?,
    val ticketCountState: String,
    val ticketCountAsOf: Instant?,
    val readScope: String,
)

internal data class SavedViewDefinitionRequest(
    @field:NotBlank @field:Size(max = 120)
    val name: String,
    @field:Size(max = 500)
    val description: String = "",
    val conditions: SavedViewConditions,
    @field:Size(min = 1, max = 12)
    val columns: List<SavedViewColumn>,
    @field:NotBlank @field:Size(max = 80)
    val sort: String,
) {
    fun toDefinition() = SavedViewDefinition(name, description, conditions, columns, sort)
}

internal data class CreateSavedViewRequest(
    val scope: SavedViewScope,
    @field:NotBlank @field:Size(max = 120)
    val name: String,
    @field:Size(max = 500)
    val description: String = "",
    val conditions: SavedViewConditions,
    @field:Size(min = 1, max = 12)
    val columns: List<SavedViewColumn>,
    @field:NotBlank @field:Size(max = 80)
    val sort: String,
) {
    fun toDefinition() = SavedViewDefinition(name, description, conditions, columns, sort)
}

internal data class UpdateSavedViewRequest(
    @field:Positive val expectedVersion: Long,
    @field:NotBlank @field:Size(max = 120)
    val name: String,
    @field:Size(max = 500)
    val description: String = "",
    val conditions: SavedViewConditions,
    @field:Size(min = 1, max = 12)
    val columns: List<SavedViewColumn>,
    @field:NotBlank @field:Size(max = 80)
    val sort: String,
) {
    fun toDefinition() = SavedViewDefinition(name, description, conditions, columns, sort)
}

internal data class ReorderSavedViewsRequest(
    val scope: SavedViewScope,
    @field:Positive val expectedOrderVersion: Long,
    @field:Size(min = 1, max = 50)
    val viewKeys: List<@NotBlank @Size(max = 100) String>,
)

internal data class SavedViewPreviewResponse(
    val items: List<TicketSummaryResponse>,
    val ticketCount: Long,
    val sort: String,
    val ticketCountAsOf: Instant,
)

internal data class SavedViewOrderResponse(
    val scope: String,
    val orderVersion: Long,
    val viewKeys: List<String>,
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
    val createdAt: String,
    val updatedAt: String,
    val version: Long,
    val isChild: Boolean,
    val openChildCount: Int,
    val sla: SlaBadgeResponse?,
)

internal data class SlaBadgeResponse(
    val metric: String,
    val state: String,
    val dueAt: String?,
    val targetMinutes: Long?,
    val policyVersion: Int?,
    val scheduleVersion: Int?,
)

internal data class ActorSummaryResponse(val id: UUID?, val type: String, val displayName: String)

internal data class GroupRefResponse(val id: UUID, val name: String)

internal data class StaffRefResponse(val id: UUID, val displayName: String)

internal data class AgentCommentResponse(
    val id: UUID,
    val visibility: String,
    val actor: ActorSummaryResponse,
    val body: String,
    val content: CommentContentView,
    val createdAt: String,
    val source: String,
    val attachments: List<TicketAttachment>,
)

internal data class TicketCustomerResponse(val id: UUID, val displayName: String, val email: String)

internal data class TicketContextResponse(
    val customer: TicketCustomerResponse?,
    val parent: TicketSummaryResponse?,
    val children: List<TicketSummaryResponse>,
    val externalReferenceCount: Int,
)

internal data class TicketAssignmentStaffOptionResponse(
    val id: UUID,
    val displayName: String,
)

internal data class TicketAssignmentGroupOptionResponse(
    val id: UUID,
    val name: String,
    val members: List<TicketAssignmentStaffOptionResponse>,
)

internal data class TicketAssignmentOptionsResponse(
    val groups: List<TicketAssignmentGroupOptionResponse>,
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
    val assignmentOptions: TicketAssignmentOptionsResponse,
    val context: TicketContextResponse,
    val history: List<TicketHistoryResponse>,
    val warnings: List<Any>,
)
