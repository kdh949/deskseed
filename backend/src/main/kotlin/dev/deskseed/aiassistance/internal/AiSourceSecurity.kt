package dev.deskseed.aiassistance.internal

import dev.deskseed.aiassistance.AiServiceIdentity
import dev.deskseed.foundation.RequestIdFilter
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

@Configuration(proxyBeanMethods = false)
internal class AiSourceSecurityConfiguration(
    private val filter: AiSourceAuthenticationFilter,
    private val problemWriter: AiSourceProblemWriter,
) {
    @Bean
    @Order(1)
    fun aiSourceSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .securityMatcher("/api/v1/internal/ai/**")
            .csrf { it.disable() }
            .cors { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .securityContext { it.requireExplicitSave(true) }
            .requestCache { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .headers {
                it.contentSecurityPolicy { policy -> policy.policyDirectives("default-src 'none'; frame-ancestors 'none'") }
                it.frameOptions { frame -> frame.deny() }
            }
            .exceptionHandling {
                it.authenticationEntryPoint { request, response, _ -> problemWriter.authenticationFailed(request, response) }
                it.accessDeniedHandler { request, response, _ -> problemWriter.accessDenied(request, response) }
            }
            .authorizeHttpRequests { it.anyRequest().authenticated() }
            .addFilterBefore(filter, AnonymousAuthenticationFilter::class.java)
        return http.build()
    }
}

internal enum class AiSourceScope { JOB_SOURCE, KNOWLEDGE_INDEX }

internal data class AiSourcePrincipal(
    val id: UUID,
    val displayName: String,
    val scopes: Set<AiSourceScope>,
)

@Component
internal class AiSourceAuthenticationFilter(
    private val problemWriter: AiSourceProblemWriter,
    @Value("\${deskseed.ai.source-auth.enabled:false}") private val enabled: Boolean,
    @Value("\${deskseed.ai.source-auth.key-id:}") private val configuredKeyId: String,
    @Value("\${deskseed.ai.source-auth.secret-sha256:}") private val configuredSecretSha256: String,
    @Value("\${deskseed.ai.source-auth.principal-id:00000000-0000-0000-0000-000000000000}")
    private val configuredPrincipalId: UUID,
    @Value("\${deskseed.ai.source-auth.principal-name:Deskseed AI Service}") private val configuredPrincipalName: String,
    @Value("\${deskseed.ai.source-auth.indexer-key-id:}") private val indexerKeyId: String,
    @Value("\${deskseed.ai.source-auth.indexer-secret-sha256:}") private val indexerSecretSha256: String,
    @Value("\${deskseed.ai.source-auth.indexer-principal-id:00000000-0000-0000-0000-000000000000}")
    private val indexerPrincipalId: UUID,
    @Value("\${deskseed.ai.source-auth.indexer-principal-name:Deskseed AI Indexer}")
    private val indexerPrincipalName: String,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !request.requestURI.startsWith(AI_SOURCE_PREFIX)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val suppliedKeyId = request.getHeader(KEY_ID_HEADER).orEmpty()
        val secret = request.getHeader("Authorization")
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = false) }
            ?.substring(7)
            .orEmpty()
        val worker = authenticate(
            suppliedKeyId, secret, configuredKeyId, configuredSecretSha256,
            configuredPrincipalId, configuredPrincipalName, setOf(AiSourceScope.JOB_SOURCE),
        )
        val indexer = authenticate(
            suppliedKeyId, secret, indexerKeyId, indexerSecretSha256,
            indexerPrincipalId, indexerPrincipalName, setOf(AiSourceScope.KNOWLEDGE_INDEX),
        )
        val principal = worker ?: indexer
        if (principal == null) {
            problemWriter.authenticationFailed(request, response)
            return
        }
        val requiredScope = when {
            request.requestURI.startsWith("/api/v1/internal/ai/kb/") -> AiSourceScope.KNOWLEDGE_INDEX
            else -> AiSourceScope.JOB_SOURCE
        }
        if (requiredScope !in principal.scopes) {
            problemWriter.accessDenied(request, response)
            return
        }
        val context = SecurityContextHolder.createEmptyContext()
        context.authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null, emptyList())
        SecurityContextHolder.setContext(context)
        try {
            filterChain.doFilter(request, response)
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun constantTimeEquals(left: String, right: String): Boolean = MessageDigest.isEqual(
        left.toByteArray(StandardCharsets.UTF_8),
        right.toByteArray(StandardCharsets.UTF_8),
    )

    private fun authenticate(
        suppliedKeyId: String,
        secret: String,
        expectedKeyId: String,
        expectedDigest: String,
        principalId: UUID,
        principalName: String,
        scopes: Set<AiSourceScope>,
    ): AiSourcePrincipal? {
        val configured = enabled && expectedKeyId.isNotBlank() && expectedKeyId.length <= 80 &&
            expectedDigest.matches(SHA_256) && principalId != UUID(0, 0) && principalName.isNotBlank()
        if (!configured || !constantTimeEquals(suppliedKeyId, expectedKeyId) ||
            !constantTimeEquals(sha256(secret), expectedDigest.lowercase())
        ) return null
        return AiSourcePrincipal(principalId, principalName.take(100), scopes)
    }

    private companion object {
        const val AI_SOURCE_PREFIX = "/api/v1/internal/ai/"
        const val KEY_ID_HEADER = "X-Deskseed-AI-Key-Id"
        val SHA_256 = Regex("^[0-9a-fA-F]{64}$")
    }
}

@Component
internal class AiSourceProblemWriter(private val objectMapper: ObjectMapper) {
    fun authenticationFailed(request: HttpServletRequest, response: HttpServletResponse) = write(
        request, response, 401, "/problems/ai-source-authentication-failed",
        "AI source authentication failed", "The direction-specific AI service credential could not be authenticated.",
    )

    fun accessDenied(request: HttpServletRequest, response: HttpServletResponse) = write(
        request, response, 403, "/problems/ai-source-access-denied",
        "AI source access denied", "The AI service is not allowed to read this bound request.",
    )

    fun write(
        request: HttpServletRequest,
        response: HttpServletResponse,
        status: Int,
        type: String,
        title: String,
        detail: String,
    ) {
        if (response.isCommitted) return
        response.status = status
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.setHeader("Cache-Control", "no-store")
        objectMapper.writeValue(
            response.outputStream,
            linkedMapOf(
                "type" to type,
                "title" to title,
                "status" to status,
                "detail" to detail,
                "requestId" to request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE)?.toString().orEmpty(),
            ),
        )
    }
}

internal fun AiSourcePrincipal.toIdentity() = AiServiceIdentity(id, displayName)
