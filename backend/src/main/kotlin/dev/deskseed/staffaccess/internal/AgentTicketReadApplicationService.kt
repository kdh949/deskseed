package dev.deskseed.staffaccess.internal

import dev.deskseed.audit.AccessAuditOutcome
import dev.deskseed.audit.AccessAuditProtectionException
import dev.deskseed.audit.AccessAuditSessionFingerprint
import dev.deskseed.audit.AccessAuditWriter
import dev.deskseed.audit.SearchResultOpenedAccessAudit
import dev.deskseed.audit.TicketResourceReadAccessAudit
import dev.deskseed.audit.TicketViewAccessAudit
import dev.deskseed.organization.TicketAssignmentCatalog
import dev.deskseed.organization.TicketAssignmentGroupOption
import dev.deskseed.ticketing.DefaultStaffView
import dev.deskseed.ticketing.StaffTicketDetail
import dev.deskseed.ticketing.StaffTicketListFilter
import dev.deskseed.ticketing.StaffTicketReadScope
import dev.deskseed.ticketing.StaffTicketReadStore
import dev.deskseed.ticketing.StaffTicketSummary
import dev.deskseed.ticketing.TicketStatus
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

internal enum class AgentReadIntent {
    NAVIGATION,
    BACKGROUND,
}

internal data class AgentReadRequestContext(
    val requestId: String,
    val correlationId: String,
    val sessionId: String,
    val ipAddress: String?,
    val userAgent: String?,
)

internal data class AgentViewDefinition(
    val key: String,
    val name: String,
    val category: String,
    val scope: String,
    val readScope: StaffTicketReadScope,
)

internal data class AgentTicketPage(
    val items: List<StaffTicketSummary>,
    val nextCursor: String?,
)

internal data class AgentTicketWorkspaceDetail(
    val detail: StaffTicketDetail,
    val capabilities: List<String>,
    val assignmentOptions: List<TicketAssignmentGroupOption>,
)

@Component
internal class AgentTicketReadAuthorizationPolicy {
    fun canRead(
        scope: StaffTicketReadScope,
        directGrant: Boolean,
        relationGrant: Boolean,
    ): Boolean = scope == StaffTicketReadScope.ALL_TICKETS || directGrant || relationGrant
}

@Service
internal class AgentTicketReadApplicationService(
    private val ticketStore: StaffTicketReadStore,
    private val accessAuditWriter: AccessAuditWriter,
    private val sessionFingerprint: AccessAuditSessionFingerprint,
    private val cursorCodec: AgentTicketCursorCodec,
    private val assignmentCatalog: TicketAssignmentCatalog,
    private val writeAuthorizationPolicy: GroupOrAssigneeTicketWriteAuthorizationPolicy,
    private val readAuthorizationPolicy: AgentTicketReadAuthorizationPolicy,
    private val clock: Clock,
) {
    val readScope: StaffTicketReadScope = StaffTicketReadScope.ALL_TICKETS

    fun listViews(principal: StaffPrincipal): List<AgentViewDefinition> {
        requireActiveStaffRead(principal)
        return DefaultStaffView.entries.map { view ->
            AgentViewDefinition(
                key = view.key,
                name = view.displayName,
                category = view.category,
                scope = if (view == DefaultStaffView.PENDING) "SHARED" else "SYSTEM",
                readScope = readScope,
            )
        }
    }

    @Transactional(readOnly = true)
    fun listTickets(
        principal: StaffPrincipal,
        view: DefaultStaffView,
        filters: StaffTicketListFilter,
        cursor: String?,
        limit: Int,
    ): AgentTicketPage {
        requireActiveStaffRead(principal)
        require(limit in 1..100) { "limit must be between 1 and 100" }
        validateAssignee(filters.assignee)
        val decodedCursor = cursor?.let { cursorCodec.decode(view, filters, it) }
        val rows = ticketStore.list(
            view = view,
            actorId = principal.id,
            filters = filters,
            cursor = decodedCursor,
            limit = limit + 1,
            recentlySolvedAfter = Instant.now(clock).minus(Duration.ofDays(30)),
        )
        val items = rows.take(limit)
        val nextCursor = if (rows.size > limit) {
            items.last().let { cursorCodec.encode(view, filters, dev.deskseed.ticketing.StaffTicketCursor(it.updatedAt, it.ticketNumber)) }
        } else {
            null
        }
        return AgentTicketPage(items, nextCursor)
    }

    @Transactional
    fun readTicket(
        principal: StaffPrincipal,
        ticketNumber: Long,
        interactionId: UUID,
        intent: AgentReadIntent,
        originSearchEventId: UUID?,
        context: AgentReadRequestContext,
    ): AgentTicketWorkspaceDetail {
        requireActiveStaffRead(principal)
        val detail = ticketStore.findDetail(ticketNumber) ?: throw AgentTicketNotFoundException()
        val directGrant = writeAuthorizationPolicy.canUpdate(
            principal = principal,
            currentGroupId = detail.ticket.group?.id,
            currentAssigneeId = detail.ticket.assignee?.id,
        )
        val relationGrant = readScope != StaffTicketReadScope.ALL_TICKETS &&
            ticketStore.hasRelationReadGrant(detail.ticket.id, principal.id)
        if (!readAuthorizationPolicy.canRead(readScope, directGrant, relationGrant)) {
            throw AgentTicketNotFoundException()
        }
        try {
            val occurredAt = Instant.now(clock)
            val auditContext = context.toAccessAuditContext(
                principal,
                sessionFingerprint.fingerprint(context.sessionId),
            )
            if (originSearchEventId != null && (
                    intent != AgentReadIntent.NAVIGATION ||
                        !accessAuditWriter.isValidSearchOrigin(
                            originSearchEventId,
                            principal.id,
                            auditContext.sessionFingerprint!!,
                        )
                    )
            ) {
                throw InvalidSearchOriginException()
            }
            accessAuditWriter.appendTicketResourceRead(
                TicketResourceReadAccessAudit(
                    context = auditContext,
                    ticketId = detail.ticket.id,
                    ticketNumber = detail.ticket.ticketNumber,
                    interactionId = interactionId,
                    outcome = AccessAuditOutcome.SUCCEEDED,
                    httpStatus = 200,
                    occurredAt = occurredAt,
                ),
            )
            if (intent == AgentReadIntent.NAVIGATION) {
                accessAuditWriter.appendTicketViewed(
                    TicketViewAccessAudit(
                        context = auditContext,
                        ticketId = detail.ticket.id,
                        ticketNumber = detail.ticket.ticketNumber,
                        interactionId = interactionId,
                        originSearchEventId = originSearchEventId,
                        outcome = AccessAuditOutcome.SUCCEEDED,
                        httpStatus = 200,
                        occurredAt = occurredAt,
                    ),
                )
                if (originSearchEventId != null) {
                    accessAuditWriter.appendSearchResultOpened(
                        SearchResultOpenedAccessAudit(
                            context = auditContext,
                            ticketId = detail.ticket.id,
                            ticketNumber = detail.ticket.ticketNumber,
                            interactionId = interactionId,
                            originSearchEventId = originSearchEventId,
                            outcome = AccessAuditOutcome.SUCCEEDED,
                            httpStatus = 200,
                            occurredAt = occurredAt,
                        ),
                    )
                }
            }
        } catch (exception: DataAccessException) {
            throw AccessAuditUnavailableException(exception)
        } catch (exception: AccessAuditProtectionException) {
            throw AccessAuditUnavailableException(exception)
        }
        val canUpdate = detail.ticket.status != TicketStatus.CLOSED && directGrant
        return AgentTicketWorkspaceDetail(
            detail = detail,
            capabilities = if (canUpdate) listOf("READ", "UPDATE") else listOf("READ"),
            assignmentOptions = assignmentCatalog.listActiveGroups(),
        )
    }

    private fun requireActiveStaffRead(principal: StaffPrincipal) {
        check(readScope == StaffTicketReadScope.ALL_TICKETS) { "Unsupported ticket read policy" }
        require(principal.id != UUID(0, 0)) { "Active staff principal is required" }
    }

    private fun validateAssignee(assignee: String?) {
        if (assignee == null || assignee == "me" || assignee == "unassigned") return
        runCatching { UUID.fromString(assignee) }
            .getOrElse { throw IllegalArgumentException("assigneeId must be a UUID, me, or unassigned") }
    }
}

internal class AgentTicketNotFoundException : RuntimeException()

internal class InvalidSearchOriginException : RuntimeException()

internal class AccessAuditUnavailableException(cause: Throwable) : RuntimeException(cause)
