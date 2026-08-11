package dev.deskseed.staffaccess.internal

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
            !request.requestURI.startsWith("/api/v1/audit/")

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

        session.setAttribute(LAST_ACTIVITY_AT, now)
        filterChain.doFilter(request, response)
    }

    companion object {
        const val ABSOLUTE_EXPIRES_AT = "deskseed.staff.session.absolute-expires-at"
        const val LAST_ACTIVITY_AT = "deskseed.staff.session.last-activity-at"
        const val AUTHENTICATED_AT = "deskseed.staff.session.authenticated-at"
        const val MFA_VERIFIED_AT = "deskseed.staff.session.mfa-verified-at"
    }
}
