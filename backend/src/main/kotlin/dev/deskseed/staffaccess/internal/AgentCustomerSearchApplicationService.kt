package dev.deskseed.staffaccess.internal

import dev.deskseed.audit.AccessAuditOutcome
import dev.deskseed.audit.AccessAuditProtectionException
import dev.deskseed.audit.AccessAuditSessionFingerprint
import dev.deskseed.audit.AccessAuditWriter
import dev.deskseed.audit.CustomerSearchExecutedAccessAudit
import dev.deskseed.audit.CustomerSearchResultAuditItem
import dev.deskseed.audit.SearchQueryProtector
import dev.deskseed.customer.CustomerDirectory
import dev.deskseed.customer.CustomerRef
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

internal data class AgentCustomerSearchRequest(
    val query: String,
    val limit: Int,
)

internal data class AgentCustomerSearchPage(
    val searchEventId: UUID,
    val searchInteractionId: UUID,
    val items: List<CustomerRef>,
    val resultCount: Long,
)

@Service
internal class AgentCustomerSearchApplicationService(
    private val customerDirectory: CustomerDirectory,
    private val queryProtector: SearchQueryProtector,
    private val sessionFingerprint: AccessAuditSessionFingerprint,
    private val accessAuditWriter: AccessAuditWriter,
    private val clock: Clock,
) {
    @Transactional
    fun search(
        principal: StaffPrincipal,
        interactionId: UUID,
        request: AgentCustomerSearchRequest,
        context: AgentReadRequestContext,
    ): AgentCustomerSearchPage {
        require(principal.id != UUID(0, 0)) { "Active staff principal is required" }
        require(request.query.isNotBlank() && request.query.length <= 200) {
            "Search query must contain between 1 and 200 characters"
        }
        require(request.limit in 1..25) { "Search limit must be between 1 and 25" }

        val items = customerDirectory.search(query = request.query, limit = request.limit)
        val searchEventId = UUID.randomUUID()
        val occurredAt = Instant.now(clock)
        try {
            val auditContext = context.toAccessAuditContext(
                principal,
                sessionFingerprint.fingerprint(context.sessionId),
            )
            val protectedQuery = queryProtector.protect(searchEventId, request.query, occurredAt)
            accessAuditWriter.appendCustomerSearchExecuted(
                CustomerSearchExecutedAccessAudit(
                    eventId = searchEventId,
                    context = auditContext,
                    interactionId = interactionId,
                    protectedQuery = protectedQuery,
                    resultCount = items.size.toLong(),
                    resultItems = items.mapIndexed { ordinal, customer ->
                        CustomerSearchResultAuditItem(customer.id, ordinal)
                    },
                    outcome = AccessAuditOutcome.SUCCEEDED,
                    httpStatus = 200,
                    occurredAt = occurredAt,
                ),
            )
        } catch (exception: DataAccessException) {
            throw AccessAuditUnavailableException(exception)
        } catch (exception: AccessAuditProtectionException) {
            throw AccessAuditUnavailableException(exception)
        }
        return AgentCustomerSearchPage(
            searchEventId = searchEventId,
            searchInteractionId = interactionId,
            items = items,
            resultCount = items.size.toLong(),
        )
    }
}
