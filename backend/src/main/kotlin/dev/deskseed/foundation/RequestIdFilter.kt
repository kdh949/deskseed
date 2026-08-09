package dev.deskseed.foundation

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestIdFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val requestId = request
            .getHeader(REQUEST_ID_HEADER)
            ?.takeIf(::isValidIdentifier)
            ?: UUID.randomUUID().toString()
        val correlationId = request
            .getHeader(CORRELATION_ID_HEADER)
            ?.takeIf(::isValidIdentifier)
            ?: UUID.randomUUID().toString()

        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId)
        request.setAttribute(CORRELATION_ID_ATTRIBUTE, correlationId)
        response.setHeader(REQUEST_ID_HEADER, requestId)
        response.setHeader(CORRELATION_ID_HEADER, correlationId)
        MDC.put(REQUEST_ID_MDC_KEY, requestId)
        MDC.put(CORRELATION_ID_MDC_KEY, correlationId)

        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove(REQUEST_ID_MDC_KEY)
            MDC.remove(CORRELATION_ID_MDC_KEY)
        }
    }

    companion object {
        const val REQUEST_ID_HEADER = "X-Request-Id"
        const val CORRELATION_ID_HEADER = "X-Correlation-Id"
        const val REQUEST_ID_ATTRIBUTE = "deskseed.requestId"
        const val CORRELATION_ID_ATTRIBUTE = "deskseed.correlationId"
        const val REQUEST_ID_MDC_KEY = "requestId"
        const val CORRELATION_ID_MDC_KEY = "correlationId"
        private val IDENTIFIER_PATTERN = Regex("[A-Za-z0-9._:-]{1,100}")

        fun isValidIdentifier(value: String): Boolean = IDENTIFIER_PATTERN.matches(value)
    }
}
