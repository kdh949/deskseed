package dev.deskseed.staffaccess.internal

import dev.deskseed.audit.AccessAuditAuthType
import dev.deskseed.audit.AccessAuditContext
import dev.deskseed.audit.AccessAuditProtectionException
import dev.deskseed.audit.AccessAuditSessionFingerprint
import dev.deskseed.audit.AccessAuditWriter
import dev.deskseed.audit.AdminKnowledgeSearchAccessAudit
import dev.deskseed.audit.AdminKnowledgeSearchScope
import dev.deskseed.audit.SearchQueryProtector
import dev.deskseed.foundation.ActorType
import dev.deskseed.foundation.RequestSource
import dev.deskseed.knowledge.KnowledgeAdminActor
import dev.deskseed.knowledge.KnowledgeAdministration
import dev.deskseed.knowledge.KnowledgeArticleListFilter
import dev.deskseed.knowledge.KnowledgeArticleSearchPage
import org.springframework.dao.DataAccessException
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
internal class AdminKnowledgeSearchApplicationService(
    private val administration: KnowledgeAdministration,
    private val queryProtector: SearchQueryProtector,
    private val sessionFingerprint: AccessAuditSessionFingerprint,
    private val auditWriter: AccessAuditWriter,
    private val clock: Clock,
) {
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    fun search(
        actor: KnowledgeAdminActor,
        query: String,
        cursor: String?,
        filter: KnowledgeArticleListFilter,
        interactionId: UUID,
        context: AdminDirectoryReadContext,
    ): KnowledgeArticleSearchPage {
        require(query.isNotBlank() && query.length <= 254 && query.none(Char::isISOControl))
        try {
            val result = administration.searchArticles(query, cursor, filter, actor)
            val eventId = UUID.randomUUID()
            val now = Instant.now(clock)
            auditWriter.appendAdminKnowledgeSearch(AdminKnowledgeSearchAccessAudit(
                eventId,
                AccessAuditContext(
                    actorType = ActorType.STAFF,
                    actorId = actor.staffId,
                    actorDisplaySnapshot = actor.displayName,
                    source = RequestSource.ADMIN_UI,
                    sessionFingerprint = sessionFingerprint.fingerprint(context.sessionId),
                    authType = AccessAuditAuthType.STAFF_SESSION,
                    requestId = context.requestId,
                    correlationId = context.correlationId,
                    ipAddress = context.ipAddress,
                    userAgent = context.userAgent,
                ),
                interactionId,
                AdminKnowledgeSearchScope(filter.lifecycle?.name, filter.sectionId, filter.audience?.name, cursor != null),
                queryProtector.protect(eventId, query, now), result.resultCount, now,
            ))
            return result
        } catch (exception: DataAccessException) {
            throw AccessAuditUnavailableException(exception)
        } catch (exception: AccessAuditProtectionException) {
            throw AccessAuditUnavailableException(exception)
        }
    }
}
