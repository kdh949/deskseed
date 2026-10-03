package dev.deskseed.staffaccess.internal

import dev.deskseed.audit.AccessAuditAuthType
import dev.deskseed.audit.AccessAuditContext
import dev.deskseed.audit.AccessAuditProtectionException
import dev.deskseed.audit.AccessAuditSessionFingerprint
import dev.deskseed.audit.AccessAuditWriter
import dev.deskseed.audit.AdminDirectorySearchAccessAudit
import dev.deskseed.audit.AdminDirectorySearchKind
import dev.deskseed.audit.AdminDirectorySearchScope
import dev.deskseed.audit.SearchQueryProtector
import dev.deskseed.foundation.ActorType
import dev.deskseed.foundation.RequestSource
import dev.deskseed.organization.AdminGroupDirectoryFilter
import dev.deskseed.organization.AdminStaffDirectoryFilter
import dev.deskseed.organization.OrganizationAdministration
import dev.deskseed.organization.OrganizationPage
import dev.deskseed.organization.StaffAccountView
import dev.deskseed.organization.SupportGroupView
import org.springframework.dao.DataAccessException
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

internal data class AdminDirectoryReadContext(
    val sessionId: String,
    val requestId: String,
    val correlationId: String,
    val ipAddress: String?,
    val userAgent: String?,
)

@Service
internal class AdminDirectorySearchApplicationService(
    private val administration: OrganizationAdministration,
    private val queryProtector: SearchQueryProtector,
    private val sessionFingerprint: AccessAuditSessionFingerprint,
    private val auditWriter: AccessAuditWriter,
    private val clock: Clock,
) {
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    fun staff(
        principal: StaffPrincipal,
        filter: AdminStaffDirectoryFilter,
        page: Int,
        size: Int,
        interactionId: UUID,
        context: AdminDirectoryReadContext,
    ): OrganizationPage<StaffAccountView> = audited(
        principal, filter.query, interactionId, context, AdminDirectorySearchKind.STAFF,
        AdminDirectorySearchScope(page, size, filter.role?.name, filter.status?.name, filter.memberOfGroupId, filter.excludeGroupId),
    ) { administration.searchStaff(filter, page, size) }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    fun groups(
        principal: StaffPrincipal,
        filter: AdminGroupDirectoryFilter,
        page: Int,
        size: Int,
        interactionId: UUID,
        context: AdminDirectoryReadContext,
    ): OrganizationPage<SupportGroupView> = audited(
        principal, filter.query, interactionId, context, AdminDirectorySearchKind.GROUP,
        AdminDirectorySearchScope(page, size, status = filter.status?.name),
    ) { administration.searchGroups(filter, page, size) }

    private fun <T> audited(
        principal: StaffPrincipal,
        query: String,
        interactionId: UUID,
        context: AdminDirectoryReadContext,
        kind: AdminDirectorySearchKind,
        scope: AdminDirectorySearchScope,
        read: () -> OrganizationPage<T>,
    ): OrganizationPage<T> {
        require(query.isNotBlank() && query.length <= 254 && query.none(Char::isISOControl))
        try {
            val result = read()
            val eventId = UUID.randomUUID()
            val now = Instant.now(clock)
            val auditContext = AccessAuditContext(
                actorType = ActorType.STAFF,
                actorId = principal.id,
                actorDisplaySnapshot = principal.displayName,
                source = RequestSource.ADMIN_UI,
                sessionFingerprint = sessionFingerprint.fingerprint(context.sessionId),
                authType = AccessAuditAuthType.STAFF_SESSION,
                requestId = context.requestId,
                correlationId = context.correlationId,
                ipAddress = context.ipAddress,
                userAgent = context.userAgent,
            )
            auditWriter.appendAdminDirectorySearch(AdminDirectorySearchAccessAudit(
                eventId, auditContext, interactionId, kind, scope,
                queryProtector.protect(eventId, query, now), result.totalCount, now,
            ))
            return result
        } catch (exception: DataAccessException) {
            throw AccessAuditUnavailableException(exception)
        } catch (exception: AccessAuditProtectionException) {
            throw AccessAuditUnavailableException(exception)
        }
    }
}
