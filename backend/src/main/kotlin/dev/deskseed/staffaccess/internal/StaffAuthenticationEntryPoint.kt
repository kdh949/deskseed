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
        problemWriter.write(
            response = response,
            request = request,
            status = 401,
            type = "/problems/staff-authentication-required",
            title = "Staff authentication required",
            detail = "Sign in to continue.",
        )
    }
}
