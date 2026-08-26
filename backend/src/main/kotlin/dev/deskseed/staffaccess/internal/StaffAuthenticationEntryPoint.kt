package dev.deskseed.staffaccess.internal

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.stereotype.Component

@Component
internal class StaffAuthenticationEntryPoint(
    private val problemWriter: StaffSecurityProblemWriter,
) : AuthenticationEntryPoint {
    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        val customerRoute = request.requestURI.startsWith("/api/v1/customer/")
        val customerConsentAdminRoute = request.requestURI.startsWith("/api/v1/admin/customer-consent-policies")
        problemWriter.write(
            response = response,
            request = request,
            status = 401,
            type = when {
                customerRoute -> "/problems/customer-authentication-required"
                customerConsentAdminRoute -> "/problems/staff-session-required"
                else -> "/problems/staff-authentication-required"
            },
            title = if (customerRoute) "Customer authentication required" else "Staff authentication required",
            detail = if (customerRoute) "Request a new sign-in link to continue." else "Sign in to continue.",
        )
    }
}
