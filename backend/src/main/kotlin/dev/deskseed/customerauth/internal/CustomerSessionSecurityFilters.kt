package dev.deskseed.customerauth

import dev.deskseed.customerauth.internal.CustomerAccountSessionStore
import dev.deskseed.customerauth.internal.CustomerAuthProperties
import dev.deskseed.customerauth.internal.CustomerAuthSecrets
import dev.deskseed.customerauth.internal.CustomerMagicLinkController
import dev.deskseed.customerauth.internal.CustomerSecurityProblemWriter

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
internal class CustomerSessionAuthenticationFilter(
    private val sessionStore: CustomerAccountSessionStore,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        (!request.requestURI.startsWith("/api/v1/customer/") && !request.requestURI.startsWith("/api/v1/help/") &&
            !(request.method == "POST" && request.requestURI == "/api/v1/requests")) ||
            request.requestURI.startsWith("/api/v1/customer/auth/")

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        SecurityContextHolder.clearContext()
        val rawSession = request.customerSessionCookie()
        val principal = rawSession?.let(sessionStore::resolveSession)
        if (principal != null) {
            val context = SecurityContextHolder.createEmptyContext().apply {
                authentication = UsernamePasswordAuthenticationToken.authenticated(
                    principal,
                    null,
                    listOf(SimpleGrantedAuthority("ROLE_CUSTOMER")),
                )
            }
            SecurityContextHolder.setContext(context)
        }
        filterChain.doFilter(request, response)
    }
}

@Component
internal class CustomerCsrfFilter(
    private val properties: CustomerAuthProperties,
    private val problemWriter: CustomerSecurityProblemWriter,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        ((!request.requestURI.startsWith("/api/v1/customer/") &&
            !(request.method == "POST" && request.requestURI == "/api/v1/requests" && authenticatedCustomer()))) ||
            request.requestURI.startsWith("/api/v1/customer/auth/") ||
            (request.method == "POST" && request.requestURI == "/api/v1/customer/registrations") ||
            request.method in setOf("GET", "HEAD", "OPTIONS", "TRACE")

    private fun authenticatedCustomer(): Boolean = SecurityContextHolder.getContext().authentication
        ?.takeIf { it.isAuthenticated }
        ?.authorities
        ?.any { it.authority == "ROLE_CUSTOMER" } == true

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val rawSession = request.customerSessionCookie()
        val supplied = request.getHeader(CSRF_HEADER)
        val expected = rawSession?.let { CustomerAuthSecrets.csrf(properties.csrfKey, it) }
        if (supplied == null || expected == null || !CustomerAuthSecrets.constantTimeEquals(supplied, expected)) {
            problemWriter.write(
                response,
                request,
                403,
                "/problems/customer-csrf-invalid",
                "Customer CSRF token is invalid",
                "Refresh the CSRF token and try again.",
            )
            return
        }
        filterChain.doFilter(request, response)
    }

    companion object {
        const val CSRF_HEADER = "X-CSRF-TOKEN"
    }
}

internal fun HttpServletRequest.customerSessionCookie(): String? =
    cookies?.filter { it.name == CustomerMagicLinkController.CUSTOMER_COOKIE }
        ?.singleOrNull()
        ?.value
        ?.takeIf { it.length in 32..256 }
