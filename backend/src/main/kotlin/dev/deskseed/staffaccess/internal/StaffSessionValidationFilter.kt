package dev.deskseed.staffaccess.internal

import dev.deskseed.foundation.EXPECTED_STAFF_ACTOR_HEADER
import dev.deskseed.organization.StaffIdentityService
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Component
internal class StaffSessionValidationFilter(
    private val staffIdentityService: StaffIdentityService,
    private val problemWriter: StaffSecurityProblemWriter,
    private val clock: Clock,
    @Value("\${deskseed.staff-auth.session-idle:60m}")
    private val sessionIdle: Duration,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !request.requestURI.startsWith("/api/v1/agent/") &&
        !request.requestURI.startsWith("/api/v1/admin/") &&
        !request.requestURI.startsWith("/api/v1/audit/") &&
            !request.requestURI.startsWith("/ws/agent/") &&
            !request.requestURI.startsWith("/docs/api") &&
            !request.requestURI.startsWith("/api-docs/specs/") &&
            !request.requestURI.startsWith("/v3/api-docs/")

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val authentication = SecurityContextHolder.getContext().authentication
        val principal = authentication?.principal as? StaffPrincipal
        if (principal == null || authentication.isAuthenticated.not()) {
            filterChain.doFilter(request, response)
            return
        }

        val session = request.getSession(false)
        val now = Instant.now(clock)
        val absoluteExpiry = session?.getAttribute(ABSOLUTE_EXPIRES_AT) as? Instant
        val lastActivity = session?.getAttribute(LAST_ACTIVITY_AT) as? Instant
        val activeIdentity = staffIdentityService.findActiveById(principal.id)
        val expired = absoluteExpiry == null || !absoluteExpiry.isAfter(now) ||
            lastActivity == null || !lastActivity.plus(sessionIdle).isAfter(now)
        val identityChanged = activeIdentity == null || activeIdentity.role != principal.role ||
            activeIdentity.authorities != principal.authorities

        if (expired || identityChanged) {
            session?.invalidate()
            SecurityContextHolder.clearContext()
            problemWriter.write(
                response = response,
                request = request,
                status = 401,
                type = "/problems/staff-session-invalid",
                title = "Staff session is invalid",
                detail = "Sign in again to continue.",
            )
            return
        }

        val expectedActorHeaders = request.getHeaders(EXPECTED_STAFF_ACTOR_HEADER).toList()
        if (expectedActorHeaders.isNotEmpty()) {
            val expectedActor = expectedActorHeaders
                .singleOrNull()
                ?.let(::parseCanonicalUuid)
            if (expectedActor == null) {
                problemWriter.write(
                    response = response,
                    request = request,
                    status = 400,
                    type = "/problems/invalid-staff-session-actor",
                    title = "Invalid staff session actor",
                    detail = "The expected staff actor header is invalid.",
                )
                return
            }
            if (expectedActor != principal.id) {
                problemWriter.write(
                    response = response,
                    request = request,
                    status = 409,
                    type = "/problems/staff-session-actor-mismatch",
                    title = "Staff session actor changed",
                    detail = "Refresh the staff session before continuing.",
                )
                return
            }
        }

        session.setAttribute(LAST_ACTIVITY_AT, now)
        filterChain.doFilter(request, response)
    }

    private fun parseCanonicalUuid(value: String): UUID? {
        if (value.isBlank()) return null
        val parsed = runCatching { UUID.fromString(value) }.getOrNull() ?: return null
        return parsed.takeIf { it.toString().equals(value, ignoreCase = true) }
    }

    companion object {
        const val ABSOLUTE_EXPIRES_AT = "deskseed.staff.session.absolute-expires-at"
        const val LAST_ACTIVITY_AT = "deskseed.staff.session.last-activity-at"
        const val AUTHENTICATED_AT = "deskseed.staff.session.authenticated-at"
        const val MFA_VERIFIED_AT = "deskseed.staff.session.mfa-verified-at"
    }
}
