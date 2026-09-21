package dev.deskseed.staffaccess.internal

import dev.deskseed.audit.AccessAuditAuthType
import dev.deskseed.audit.AccessAuditContext
import dev.deskseed.audit.AccessAuditOutcome
import dev.deskseed.audit.AccessAuditProtectionException
import dev.deskseed.audit.AccessAuditSessionFingerprint
import dev.deskseed.audit.AccessAuditWriter
import dev.deskseed.audit.SearchExecutedAccessAudit
import dev.deskseed.audit.SearchQueryProtector
import dev.deskseed.audit.SearchResultAuditItem
import dev.deskseed.foundation.ActorType
import dev.deskseed.foundation.RequestSource
import dev.deskseed.foundation.SearchDiagnostics
import dev.deskseed.foundation.SearchPhase
import dev.deskseed.foundation.SearchResultCount
import dev.deskseed.foundation.SearchResultCountRelation
import dev.deskseed.ticketing.StaffTicketReadScope
import dev.deskseed.ticketing.StaffTicketReadStore
import dev.deskseed.ticketing.StaffTicketSearchFilter
import dev.deskseed.ticketing.StaffTicketSearchCursor
import dev.deskseed.ticketing.StaffTicketSummary
import dev.deskseed.ticketing.StaffSlaDisplayState
import dev.deskseed.ticketing.TicketPriority
import dev.deskseed.ticketing.TicketStatus
import org.springframework.dao.DataAccessException
import org.springframework.dao.QueryTimeoutException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

internal data class AgentTicketSearchRequest(
    val query: String,
    val filters: AgentTicketSearchFilter,
    val sort: String,
    val cursor: String?,
    val limit: Int,
)

internal data class AgentTicketSearchFilter(
    val status: TicketStatus?,
    val priority: TicketPriority?,
    val groupId: UUID?,
    val assigneeId: String?,
    val slaState: StaffSlaDisplayState?,
)

internal data class AgentTicketSearchPage(
    val searchEventId: UUID,
    val searchInteractionId: UUID,
    val items: List<StaffTicketSummary>,
    val resultCount: SearchResultCount,
    val sort: String,
    val nextCursor: String?,
)

@Service
internal class AgentTicketSearchApplicationService(
    private val ticketStore: StaffTicketReadStore,
    private val queryProtector: SearchQueryProtector,
    private val sessionFingerprint: AccessAuditSessionFingerprint,
    private val accessAuditWriter: AccessAuditWriter,
    private val cursorCodec: AgentTicketSearchCursorCodec,
    private val clock: Clock,
    private val searchDiagnostics: SearchDiagnostics,
) {
    @Transactional
    fun search(
        principal: StaffPrincipal,
        interactionId: UUID,
        request: AgentTicketSearchRequest,
        context: AgentReadRequestContext,
    ): AgentTicketSearchPage = searchDiagnostics.measure(SearchPhase.OVERALL) {
        require(principal.id != UUID(0, 0)) { "Active staff principal is required" }
        require(request.query.isNotBlank() && request.query.length <= 500) {
            "Search query must contain between 1 and 500 characters"
        }
        require(request.sort in SUPPORTED_SORTS) { "Unsupported ticket search sort" }
        require(request.limit in 1..100) { "Search limit must be between 1 and 100" }
        validateAssignee(request.filters.assigneeId)
        validateBroadQuery(request)

        val decodedCursor = request.cursor?.let {
            cursorCodec.decode(request.query, request.filters, request.sort, it)
        }
        val occurredAt = Instant.now(clock)
        val snapshotAt = decodedCursor?.snapshotAt ?: occurredAt

        val result = try {
            ticketStore.search(
                query = request.query,
                scope = StaffTicketReadScope.ALL_TICKETS,
                actorId = principal.id,
                filters = StaffTicketSearchFilter(
                    status = request.filters.status,
                    priority = request.filters.priority,
                    groupId = request.filters.groupId,
                    assignee = request.filters.assigneeId,
                    slaState = request.filters.slaState,
                ),
                sort = request.sort,
                snapshotAt = snapshotAt,
                cursor = decodedCursor,
                limit = request.limit + 1,
            )
        } catch (exception: QueryTimeoutException) {
            throw AgentTicketSearchTooBroadException(exception)
        }
        val returnedHits = result.hits.take(request.limit)
        val hasMore = result.hits.size > request.limit
        val returnedBefore = decodedCursor?.returnedBefore ?: 0L
        val returnedThroughPage = Math.addExact(returnedBefore, returnedHits.size.toLong())
        val resultCount = SearchResultCount(
            value = if (hasMore) Math.addExact(returnedThroughPage, 1L) else returnedThroughPage,
            relation = if (hasMore) SearchResultCountRelation.LOWER_BOUND else SearchResultCountRelation.EXACT,
        )
        val nextCursor = if (hasMore) {
            val last = checkNotNull(returnedHits.lastOrNull())
            cursorCodec.encode(
                query = request.query,
                filters = request.filters,
                sort = request.sort,
                cursor = StaffTicketSearchCursor(
                    snapshotAt = snapshotAt,
                    lastScore = last.score.takeIf { request.sort == SCORE_SORT },
                    lastUpdatedAt = last.ticket.updatedAt.takeIf { request.sort == UPDATED_SORT },
                    lastTicketNumber = last.ticket.ticketNumber,
                    returnedBefore = returnedThroughPage,
                    partition = last.partition,
                ),
            )
        } else {
            null
        }
        val searchEventId = UUID.randomUUID()
        try {
            searchDiagnostics.measure(SearchPhase.AUDIT) {
                val auditContext = context.toAccessAuditContext(
                    principal,
                    sessionFingerprint.fingerprint(context.sessionId),
                )
                val protectedQuery = queryProtector.protect(searchEventId, request.query, occurredAt)
                accessAuditWriter.appendSearchExecuted(
                    SearchExecutedAccessAudit(
                        eventId = searchEventId,
                        context = auditContext,
                        interactionId = interactionId,
                        protectedQuery = protectedQuery,
                        normalizedFilters = normalizedFilters(request.filters),
                        sort = request.sort,
                        resultCount = checkNotNull(resultCount.value),
                        resultCountRelation = resultCount.relation,
                        resultItems = returnedHits.mapIndexed { ordinal, hit ->
                            SearchResultAuditItem(hit.ticket.id, hit.ticket.ticketNumber, ordinal)
                        },
                        outcome = AccessAuditOutcome.SUCCEEDED,
                        httpStatus = 200,
                        occurredAt = occurredAt,
                    ),
                )
            }
        } catch (exception: DataAccessException) {
            throw AccessAuditUnavailableException(exception)
        } catch (exception: AccessAuditProtectionException) {
            throw AccessAuditUnavailableException(exception)
        }
        AgentTicketSearchPage(
            searchEventId = searchEventId,
            searchInteractionId = interactionId,
            items = returnedHits.map { it.ticket },
            resultCount = resultCount,
            sort = request.sort,
            nextCursor = nextCursor,
        )
    }

    private fun normalizedFilters(filters: AgentTicketSearchFilter): Map<String, String> = buildMap {
        filters.status?.let { put("status", it.name) }
        filters.priority?.let { put("priority", it.name) }
        filters.groupId?.let { put("groupId", it.toString()) }
        filters.assigneeId?.let { put("assigneeId", it) }
        filters.slaState?.let { put("slaState", it.name) }
    }

    private fun validateAssignee(assignee: String?) {
        if (assignee == null || assignee == "me" || assignee == "unassigned") return
        runCatching { UUID.fromString(assignee) }
            .getOrElse { throw IllegalArgumentException("assigneeId must be a UUID, me, or unassigned") }
    }

    private fun validateBroadQuery(request: AgentTicketSearchRequest) {
        val normalized = request.query.trim()
        if (normalized.toLongOrNull() != null) return
        val hasNarrowingFilter = with(request.filters) {
            status != null || priority != null || groupId != null || assigneeId != null || slaState != null
        }
        if (normalized.codePointCount(0, normalized.length) < MIN_UNFILTERED_QUERY_CODE_POINTS && !hasNarrowingFilter) {
            throw AgentTicketSearchTooBroadException()
        }
    }

    private companion object {
        const val UPDATED_SORT = "updatedAt:desc,ticketNumber:desc"
        const val SCORE_SORT = "score:desc,ticketNumber:desc"
        const val MIN_UNFILTERED_QUERY_CODE_POINTS = 3
        val SUPPORTED_SORTS = setOf(UPDATED_SORT, SCORE_SORT)
    }
}

internal class AgentTicketSearchTooBroadException(cause: Throwable? = null) : RuntimeException(cause)

internal fun AgentReadRequestContext.toAccessAuditContext(
    principal: StaffPrincipal,
    sessionFingerprint: String,
) = AccessAuditContext(
    actorType = ActorType.STAFF,
    actorId = principal.id,
    actorDisplaySnapshot = principal.displayName,
    source = RequestSource.AGENT_UI,
    sessionFingerprint = sessionFingerprint,
    authType = AccessAuditAuthType.STAFF_SESSION,
    requestId = requestId,
    correlationId = correlationId,
    ipAddress = ipAddress,
    userAgent = userAgent,
)
