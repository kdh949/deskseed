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
import dev.deskseed.integration.IntegrationAuthenticator
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
    private val authenticator: IntegrationAuthenticator,
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
            if (!appendDenialOrUnavailable(request, response, null, requestId, correlationId, "NETWORK_NOT_ALLOWED")) return
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
        val authentication = try {
            authenticator.authenticate(IntegrationAuthenticationRequest(apiKey, remoteIp, requestId, correlationId))
        } catch (_: RuntimeException) {
            problemWriter.write(
                request,
                response,
                503,
                "/problems/platform-security-audit-unavailable",
                "Platform authentication unavailable",
                "Required authentication audit persistence could not be completed.",
            )
            return
        }
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
        val rate = try {
            rateLimiter.consume(principal.id, principal.rateLimitPerMinute)
        } catch (_: PlatformRateLimitUnavailableException) {
            problemWriter.write(
                request,
                response,
                503,
                "/problems/platform-rate-limit-unavailable",
                "Platform rate limit unavailable",
                "The shared rate-limit store is unavailable, so this request was not accepted.",
            )
            return
        }
        response.setHeader("X-RateLimit-Limit", rate.limit.toString())
        response.setHeader("X-RateLimit-Remaining", rate.remaining.toString())
        response.setHeader("X-RateLimit-Reset", rate.resetAtEpochSecond.toString())
        if (!rate.allowed) {
            response.setHeader("Retry-After", rate.retryAfterSeconds.toString())
            if (!appendDenialOrUnavailable(request, response, principal, requestId, correlationId, "RATE_LIMITED")) return
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

    private fun appendDenialOrUnavailable(
        request: HttpServletRequest,
        response: HttpServletResponse,
        principal: AuthenticatedIntegrationClient?,
        requestId: String,
        correlationId: String,
        reason: String,
    ): Boolean = try {
        appendDenial(principal, requestId, correlationId, reason)
        true
    } catch (_: RuntimeException) {
        problemWriter.write(
            request,
            response,
            503,
            "/problems/platform-security-audit-unavailable",
            "Platform security audit unavailable",
            "Required security audit persistence could not be completed.",
        )
        false
    }

    private fun HttpServletRequest.identifier(attribute: String): String =
        getAttribute(attribute)?.toString()?.takeIf(RequestIdFilter::isValidIdentifier)
            ?: error("RequestIdFilter must run before Platform security")

    companion object {
        const val EFFECTIVE_REMOTE_IP_ATTRIBUTE = "deskseed.platform.effectiveRemoteIp"
        private const val PLATFORM_PREFIX = "/api/v1/platform/"
    }
}
