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
                customerRoute -> "/problems/customer-session-required"
                customerConsentAdminRoute -> "/problems/staff-session-required"
                else -> "/problems/staff-authentication-required"
            },
            title = if (customerRoute) "고객 로그인이 필요합니다" else "Staff authentication required",
            detail = if (customerRoute) "다시 로그인한 뒤 계속해 주세요." else "Sign in to continue.",
        )
    }
}
