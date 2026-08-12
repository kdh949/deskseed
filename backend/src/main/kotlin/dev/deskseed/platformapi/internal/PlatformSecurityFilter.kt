package dev.deskseed.platformapi.internal

import dev.deskseed.audit.AdminSecurityAudit
import dev.deskseed.audit.AdminSecurityAuditWriter
import dev.deskseed.audit.AdminSecurityOutcome
import dev.deskseed.foundation.ActorType
import dev.deskseed.foundation.RequestIdFilter
import dev.deskseed.foundation.RequestSource
import dev.deskseed.integration.AuthenticatedIntegrationClient
import dev.deskseed.integration.IntegrationAuthenticationRequest
import dev.deskseed.integration.IntegrationAuthenticationResult
import dev.deskseed.integration.IntegrationClientAuthenticator
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Clock
import java.time.Instant

@Component
internal class PlatformSecurityFilter(
    private val networkBoundary: PlatformNetworkBoundary,
    private val authenticator: IntegrationClientAuthenticator,
    private val rateLimiter: PlatformRateLimiter,
    private val auditWriter: AdminSecurityAuditWriter,
    private val problemWriter: PlatformProblemWriter,
    private val clock: Clock,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !request.requestURI.startsWith(PLATFORM_PREFIX)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val requestId = request.identifier(RequestIdFilter.REQUEST_ID_ATTRIBUTE)
        val correlationId = request.identifier(RequestIdFilter.CORRELATION_ID_ATTRIBUTE)
        val remoteIp = networkBoundary.resolveAllowedClient(request)
        if (remoteIp == null) {
            appendDenial(null, requestId, correlationId, "NETWORK_NOT_ALLOWED")
            problemWriter.write(
                request,
                response,
                403,
                "/problems/platform-network-denied",
                "Platform API access denied",
                "The request is outside the configured private network boundary.",
            )
            return
        }

        val apiKey = request.getHeader("Authorization")
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = false) }
            ?.substring(7)
            .orEmpty()
        val authentication = authenticator.authenticate(
            IntegrationAuthenticationRequest(apiKey, remoteIp, requestId, correlationId),
        )
        if (authentication !is IntegrationAuthenticationResult.Success) {
            problemWriter.write(
                request,
                response,
                401,
                "/problems/platform-authentication-failed",
                "Authentication failed",
                "The supplied machine credential could not be authenticated.",
            )
            return
        }

        val principal = authentication.principal
        val rate = rateLimiter.consume(principal.id)
        response.setHeader("X-RateLimit-Limit", rate.limit.toString())
        response.setHeader("X-RateLimit-Remaining", rate.remaining.toString())
        response.setHeader("X-RateLimit-Reset", rate.resetAtEpochSecond.toString())
        if (!rate.allowed) {
            response.setHeader("Retry-After", rate.retryAfterSeconds.toString())
            appendDenial(principal, requestId, correlationId, "RATE_LIMITED")
            problemWriter.write(
                request,
                response,
                429,
                "/problems/platform-rate-limit-exceeded",
                "Rate limit exceeded",
                "Retry after the current client rate-limit window resets.",
            )
            return
        }

        val context = SecurityContextHolder.createEmptyContext()
        context.authentication = UsernamePasswordAuthenticationToken.authenticated(
            principal,
            null,
            principal.scopes.map { SimpleGrantedAuthority("SCOPE_${it.value}") },
        )
        SecurityContextHolder.setContext(context)
        request.setAttribute(EFFECTIVE_REMOTE_IP_ATTRIBUTE, remoteIp)
        try {
            filterChain.doFilter(request, response)
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    private fun appendDenial(
        principal: AuthenticatedIntegrationClient?,
        requestId: String,
        correlationId: String,
        reason: String,
    ) {
        auditWriter.append(
            AdminSecurityAudit(
                eventType = "ACCESS_DENIED",
                actorType = principal?.actorType ?: ActorType.SYSTEM,
                actorId = principal?.id,
                actorDisplaySnapshot = principal?.name,
                source = RequestSource.PLATFORM_API,
                targetType = "PLATFORM_API",
                targetId = null,
                outcome = AdminSecurityOutcome.DENIED,
                requestId = requestId,
                correlationId = correlationId,
                metadata = mapOf("reason" to reason),
                occurredAt = Instant.now(clock),
            ),
        )
    }

    private fun HttpServletRequest.identifier(attribute: String): String =
        getAttribute(attribute)?.toString()?.takeIf(RequestIdFilter::isValidIdentifier)
            ?: error("RequestIdFilter must run before Platform security")

    companion object {
        const val EFFECTIVE_REMOTE_IP_ATTRIBUTE = "deskseed.platform.effectiveRemoteIp"
        private const val PLATFORM_PREFIX = "/api/v1/platform/"
    }
}

