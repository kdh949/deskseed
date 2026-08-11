package dev.deskseed.staffaccess.internal

import dev.deskseed.audit.AdminSecurityAudit
import dev.deskseed.audit.AdminSecurityAuditWriter
import dev.deskseed.audit.AdminSecurityOutcome
import dev.deskseed.foundation.ActorType
import dev.deskseed.foundation.RequestIdFilter
import dev.deskseed.foundation.RequestSource
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

@Component
internal class StaffAccessDeniedHandler(
    private val problemWriter: StaffSecurityProblemWriter,
    private val auditWriter: AdminSecurityAuditWriter,
    private val clock: Clock,
) : AccessDeniedHandler {
    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: AccessDeniedException,
    ) {
        val principal = SecurityContextHolder.getContext().authentication?.principal as? StaffPrincipal
        val requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE)?.toString() ?: "missing-request-id"
        val correlationId = request.getAttribute(RequestIdFilter.CORRELATION_ID_ATTRIBUTE)?.toString() ?: requestId
        runCatching {
            auditWriter.append(
                AdminSecurityAudit(
                    eventType = "ACCESS_DENIED",
                    actorType = principal?.let { ActorType.STAFF } ?: ActorType.SYSTEM,
                    actorId = principal?.id,
                    actorDisplaySnapshot = principal?.displayName,
                    source = if (request.requestURI.startsWith("/api/v1/admin/")) {
                        RequestSource.ADMIN_UI
                    } else {
                        RequestSource.AGENT_UI
                    },
                    targetType = "HTTP_ROUTE",
                    targetId = null,
                    outcome = AdminSecurityOutcome.DENIED,
                    requestId = requestId,
                    correlationId = correlationId,
                    metadata = mapOf(
                        "method" to request.method,
                        "path" to request.requestURI.take(200),
                    ),
                    occurredAt = Instant.now(clock),
                ),
            )
        }
        problemWriter.write(
            response = response,
            request = request,
            status = 403,
            type = "/problems/staff-access-denied",
            title = "Staff access denied",
            detail = "You do not have permission to perform this action.",
        )
    }
}
