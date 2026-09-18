package dev.deskseed.aiassistance.internal

import com.fasterxml.jackson.annotation.JsonInclude
import dev.deskseed.aiassistance.AiRequestMetadata
import dev.deskseed.aiassistance.AiSourceRequestSupersededException
import dev.deskseed.aiassistance.AiSourceRequestUnavailableException
import dev.deskseed.aiassistance.AiSourceContextService
import dev.deskseed.foundation.RequestIdFilter
import jakarta.servlet.http.HttpServletRequest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1/internal/ai/requests")
internal class AiSourceController(private val contextService: AiSourceContextService) {
    @GetMapping("/{jobId}/context")
    fun context(
        @AuthenticationPrincipal principal: AiSourcePrincipal,
        @PathVariable jobId: UUID,
        request: HttpServletRequest,
    ): ResponseEntity<AiSourceContextResponse> {
        val context = contextService.read(
            jobId,
            principal.toIdentity(),
            AiRequestMetadata(
                requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE).toString(),
                correlationId = request.getAttribute(RequestIdFilter.CORRELATION_ID_ATTRIBUTE).toString(),
                ipAddress = null,
                userAgent = null,
                traceparent = request.getHeader("traceparent")?.take(512),
                tracestate = request.getHeader("tracestate")?.take(512),
            ),
        )
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            AiSourceContextResponse(
                jobId = context.jobId,
                ticketId = context.ticketId,
                ticketNumber = context.ticketNumber,
                ticketVersion = context.ticketVersion,
                feature = context.feature,
                requestRevision = context.requestRevision,
                contextRevision = context.contextRevision,
                contextPolicyVersion = context.contextPolicyVersion,
                aiInputRevision = context.aiInputRevision,
                inputPolicyVersion = context.inputPolicyVersion,
                inputScope = context.inputScope,
                comments = context.comments.map {
                    AiSourceCommentResponse(
                        id = it.id,
                        body = it.body,
                        createdAt = it.createdAt,
                        sequence = it.sequence.takeIf { context.contextPolicyVersion == AI_CONTEXT_POLICY_VERSION },
                        authorRole = it.authorRole.takeIf { context.contextPolicyVersion == AI_CONTEXT_POLICY_VERSION },
                    )
                },
            ),
        )
    }

    @GetMapping("/{jobId}/context-revision")
    fun revision(
        @PathVariable jobId: UUID,
    ): ResponseEntity<AiSourceRevisionResponse> {
        val revision = contextService.revision(jobId)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            AiSourceRevisionResponse(
                jobId = revision.jobId,
                requestRevision = revision.requestRevision,
                contextRevision = revision.contextRevision,
                aiInputRevision = revision.aiInputRevision,
                inputPolicyVersion = revision.inputPolicyVersion,
                authorized = revision.authorized,
                cancelRequested = revision.cancelRequested,
                featureEnabled = revision.featureEnabled,
            ),
        )
    }
}

@RestController
@RequestMapping("/api/v1/internal/ai")
internal class AiPolicyController(private val jdbcTemplate: JdbcTemplate, private val clock: java.time.Clock) {
    @GetMapping("/policy")
    fun policy(): ResponseEntity<AiPolicyResponse> {
        val policy = jdbcTemplate.query(
            """
            select enabled, summary_enabled, triage_enabled, reply_draft_enabled,
                   fast_model_alias, standard_model_alias, version, updated_at
            from ai_settings where singleton = true
            """.trimIndent(),
            { result, _ -> AiPolicyResponse(
                enabled = result.getBoolean("enabled"),
                features = mapOf(
                    "ticket.summary" to result.getBoolean("summary_enabled"),
                    "ticket.triage" to result.getBoolean("triage_enabled"),
                    "ticket.reply_draft" to result.getBoolean("reply_draft_enabled"),
                ),
                fastModelAlias = result.getString("fast_model_alias"),
                standardModelAlias = result.getString("standard_model_alias"),
                version = result.getLong("version"),
                updatedAt = result.getTimestamp("updated_at").toInstant(),
                dataAsOf = Instant.now(clock),
            ) },
        ).single()
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(policy)
    }
}

internal data class AiPolicyResponse(
    val enabled: Boolean,
    val features: Map<String, Boolean>,
    val fastModelAlias: String,
    val standardModelAlias: String,
    val version: Long,
    val updatedAt: Instant,
    val dataAsOf: Instant,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class AiSourceContextResponse(
    val jobId: UUID,
    val ticketId: UUID,
    val ticketNumber: Long,
    val ticketVersion: Long,
    val feature: String,
    val requestRevision: Long,
    val contextRevision: String,
    val contextPolicyVersion: String,
    val aiInputRevision: String? = null,
    val inputPolicyVersion: String? = null,
    val inputScope: String,
    val comments: List<AiSourceCommentResponse>,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class AiSourceCommentResponse(
    val id: UUID,
    val body: String,
    val createdAt: Instant,
    val sequence: Long? = null,
    val authorRole: String? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class AiSourceRevisionResponse(
    val jobId: UUID,
    val requestRevision: Long,
    val contextRevision: String,
    val aiInputRevision: String? = null,
    val inputPolicyVersion: String? = null,
    val authorized: Boolean,
    val cancelRequested: Boolean,
    val featureEnabled: Boolean,
)

@RestControllerAdvice(
    assignableTypes = [
        AiSourceController::class,
        AiKnowledgeSourceController::class,
        AiKnowledgeAuthorizationController::class,
        AiPolicyController::class,
    ],
)
internal class AiSourceExceptionHandler {
    @ExceptionHandler(AiKnowledgeManifestSnapshotUnavailableException::class)
    fun manifestSnapshotUnavailable(request: HttpServletRequest) = problem(
        request, HttpStatus.CONFLICT, "/problems/ai-knowledge-manifest-snapshot-unavailable",
        "AI knowledge manifest snapshot unavailable", "Start a new manifest scan without a snapshot token or cursor.",
    )

    @ExceptionHandler(AiKnowledgeSourceNotFoundException::class)
    fun knowledgeNotFound(request: HttpServletRequest) = problem(
        request, HttpStatus.NOT_FOUND, "/problems/ai-knowledge-source-unavailable",
        "AI knowledge source unavailable", "The exact current PUBLIC article revision is not available.",
    )

    @ExceptionHandler(AiSourceRequestUnavailableException::class)
    fun unavailable(request: HttpServletRequest) = problem(
        request, HttpStatus.NOT_FOUND, "/problems/ai-source-request-unavailable",
        "AI source request unavailable", "The bound request is not available for source retrieval.",
    )

    @ExceptionHandler(AiSourceRequestSupersededException::class)
    fun superseded(request: HttpServletRequest) = problem(
        request, HttpStatus.CONFLICT, "/problems/ai-source-request-superseded",
        "AI source request superseded", "The PUBLIC context changed after this request was accepted.",
    )

    @ExceptionHandler(dev.deskseed.aiassistance.AiAuditUnavailableException::class, org.springframework.dao.DataAccessException::class)
    fun auditUnavailable(request: HttpServletRequest) = problem(
        request, HttpStatus.SERVICE_UNAVAILABLE, "/problems/ai-source-audit-unavailable",
        "AI source audit unavailable", "Required access auditing failed, so no ticket content was returned.",
    )

    private fun problem(
        request: HttpServletRequest,
        status: HttpStatus,
        type: String,
        title: String,
        detail: String,
    ): ResponseEntity<ProblemDetail> {
        val body = ProblemDetail.forStatusAndDetail(status, detail).apply {
            this.type = URI.create(type)
            this.title = title
            this.instance = URI.create(request.requestURI)
            setProperty("requestId", request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE))
        }
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body)
    }
}
