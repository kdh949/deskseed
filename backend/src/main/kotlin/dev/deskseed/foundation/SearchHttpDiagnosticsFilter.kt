package dev.deskseed.foundation

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/** Measures the complete synchronous HTTP exchange, including MVC response conversion. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
class SearchHttpDiagnosticsFilter(
    private val searchDiagnostics: SearchDiagnostics,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.method != "POST" || request.requestURI != SEARCH_PATH

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) = searchDiagnostics.measureHttp(request, response) {
        filterChain.doFilter(request, response)
    }

    private companion object {
        const val SEARCH_PATH = "/api/v1/agent/search"
    }
}
