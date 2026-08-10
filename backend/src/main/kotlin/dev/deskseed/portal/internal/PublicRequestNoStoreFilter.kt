package dev.deskseed.portal.internal

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Request access tokens are capabilities supplied outside the URL. Every response from this
 * surface, including a problem response, must therefore be unusable by a shared cache.
 */
@Component
internal class PublicRequestNoStoreFilter : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.requestURI.removePrefix(request.contextPath)
        return path != REQUESTS_PATH && !path.startsWith("$REQUESTS_PATH/")
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().headerValue ?: "no-store")
        filterChain.doFilter(request, response)
    }

    private companion object {
        const val REQUESTS_PATH = "/api/v1/requests"
    }
}
