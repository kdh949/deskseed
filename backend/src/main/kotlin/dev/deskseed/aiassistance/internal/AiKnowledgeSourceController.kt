package dev.deskseed.aiassistance.internal

import dev.deskseed.aiassistance.AiAuditUnavailableException
import dev.deskseed.aiassistance.AiRequestMetadata
import dev.deskseed.audit.AccessAuditAuthType
import dev.deskseed.audit.AccessAuditContext
import dev.deskseed.audit.AccessAuditOutcome
import dev.deskseed.audit.AccessAuditWriter
import dev.deskseed.audit.AiKnowledgeAccessAudit
import dev.deskseed.foundation.ActorType
import dev.deskseed.foundation.RequestIdFilter
import dev.deskseed.foundation.RequestSource
import dev.deskseed.knowledge.AiKnowledgeProjection
import dev.deskseed.knowledge.AiPublicKnowledgeArticle
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.dao.DataAccessException
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1/internal/ai/kb")
@Validated
internal class AiKnowledgeSourceController(
    private val service: AiKnowledgeSourceService,
) {
    @GetMapping("/manifest")
    fun manifest(
        @RequestParam(required = false) snapshotToken: UUID?,
        @RequestParam(required = false) cursor: UUID?,
        @RequestParam(defaultValue = "1000") @Min(1) @Max(1000) limit: Int,
    ): ResponseEntity<AiKnowledgeManifestResponse> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            service.manifest(snapshotToken, cursor, limit).let { page ->
                AiKnowledgeManifestResponse(
                    snapshotToken = page.snapshotToken,
                    expiresAt = page.expiresAt,
                    nextCursor = page.nextCursor,
                    items = page.items.map {
                        AiKnowledgeManifestItemResponse(
                            it.articleId,
                            it.revisionId,
                            it.sourceVersion,
                            it.publicRevision,
                            it.publishedAt,
                        )
                    },
                )
            },
        )

    @GetMapping("/articles/{articleId}/revisions/{revisionId}")
    fun article(
        @AuthenticationPrincipal principal: AiSourcePrincipal,
        @PathVariable articleId: UUID,
        @PathVariable revisionId: UUID,
        @RequestHeader("X-Deskseed-AI-Index-Event-Id") requestRef: UUID,
        @RequestParam(defaultValue = "INDEX") purpose: AiKnowledgeReadPurpose,
        request: HttpServletRequest,
    ): ResponseEntity<AiKnowledgeArticleResponse> {
        val article = service.article(
            principal,
            articleId,
            revisionId,
            requestRef,
            purpose,
            request.metadata(),
        )
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(article.toResponse())
    }

    private fun HttpServletRequest.metadata() = AiRequestMetadata(
        requestId = getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE).toString(),
        correlationId = getAttribute(RequestIdFilter.CORRELATION_ID_ATTRIBUTE).toString(),
        ipAddress = null,
        userAgent = null,
    )
}

@RestController
@RequestMapping("/api/v1/internal/ai/requests")
@Validated
internal class AiKnowledgeAuthorizationController(
    private val service: AiKnowledgeSourceService,
    private val contextService: dev.deskseed.aiassistance.AiSourceContextService,
) {
    @PostMapping("/{jobId}/kb/authorize")
    fun authorize(
        @AuthenticationPrincipal principal: AiSourcePrincipal,
        @PathVariable jobId: UUID,
        @Valid @RequestBody body: AiKnowledgeAuthorizationRequest,
        request: HttpServletRequest,
    ): ResponseEntity<AiKnowledgeAuthorizationResponse> {
        val revision = contextService.revision(jobId)
        if (!revision.authorized) throw dev.deskseed.aiassistance.AiSourceRequestSupersededException()
        val authorized = service.authorize(principal, jobId, body.candidates, request.metadata())
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(
                AiKnowledgeAuthorizationResponse(
                    authorized.map { (candidate, article) ->
                        AiAuthorizedKnowledgeCandidate(
                            articleId = article.articleId,
                            revisionId = article.revisionId,
                            chunkId = candidate.chunkId,
                            title = article.title,
                            url = "/help/articles/${article.slug}",
                        )
                    },
                ),
            )
    }

    private fun HttpServletRequest.metadata() = AiRequestMetadata(
        requestId = getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE).toString(),
        correlationId = getAttribute(RequestIdFilter.CORRELATION_ID_ATTRIBUTE).toString(),
        ipAddress = null,
        userAgent = null,
    )
}

internal enum class AiKnowledgeReadPurpose { INDEX, RECONCILE, RETRIEVAL, RESULT }

internal data class AiKnowledgeCandidate(
    val articleId: UUID,
    val revisionId: UUID,
    val chunkId: UUID,
)

internal data class AiKnowledgeAuthorizationRequest(
    @field:Size(max = 8)
    val candidates: List<@Valid AiKnowledgeCandidate>,
)

internal data class AiAuthorizedKnowledgeCandidate(
    val articleId: UUID,
    val revisionId: UUID,
    val chunkId: UUID,
    val title: String,
    val url: String,
)

internal data class AiKnowledgeAuthorizationResponse(val items: List<AiAuthorizedKnowledgeCandidate>)

internal data class AiKnowledgeManifestItemResponse(
    val articleId: UUID,
    val revisionId: UUID,
    val sourceVersion: Long,
    val publicRevision: String,
    val publishedAt: Instant,
)

internal data class AiKnowledgeManifestResponse(
    val snapshotToken: UUID,
    val expiresAt: Instant,
    val nextCursor: UUID?,
    val items: List<AiKnowledgeManifestItemResponse>,
)

internal data class AiKnowledgeArticleResponse(
    val articleId: UUID,
    val revisionId: UUID,
    val slug: String,
    val title: String,
    val body: String,
    val sourceVersion: Long,
    val publicRevision: String,
    val publishedAt: Instant,
    val dataClass: String = "PUBLIC_KB_ONLY",
)

@org.springframework.stereotype.Service
internal class AiKnowledgeSourceService(
    private val projection: AiKnowledgeProjection,
    private val auditWriter: AccessAuditWriter,
    private val clock: Clock,
) {
    @Transactional
    fun manifest(snapshotToken: UUID?, cursor: UUID?, limit: Int): dev.deskseed.knowledge.AiPublicKnowledgeManifestPage {
        if (snapshotToken == null && cursor != null) throw AiKnowledgeManifestSnapshotUnavailableException()
        val now = Instant.now(clock)
        val token = snapshotToken ?: projection.createManifestSnapshot(now, now.plus(MANIFEST_SNAPSHOT_TTL))
        return projection.manifest(token, cursor, limit, now)
            ?: throw AiKnowledgeManifestSnapshotUnavailableException()
    }

    @Transactional
    fun article(
        principal: AiSourcePrincipal,
        articleId: UUID,
        revisionId: UUID,
        requestRef: UUID,
        purpose: AiKnowledgeReadPurpose,
        metadata: AiRequestMetadata,
    ): AiPublicKnowledgeArticle {
        val article = projection.findCurrentPublic(articleId, revisionId)
            ?: throw AiKnowledgeSourceNotFoundException()
        try {
            auditWriter.appendAiKnowledgeAccess(
                AiKnowledgeAccessAudit(
                    eventId = UUID.randomUUID(),
                    context = AccessAuditContext(
                        actorType = ActorType.INTEGRATION_CLIENT,
                        actorId = principal.id,
                        actorDisplaySnapshot = principal.displayName,
                        source = RequestSource.AI_SERVICE,
                        sessionFingerprint = null,
                        authType = AccessAuditAuthType.API_KEY,
                        requestId = metadata.requestId,
                        correlationId = metadata.correlationId,
                        ipAddress = null,
                        userAgent = null,
                    ),
                    requestRef = requestRef,
                    articleId = articleId,
                    revisionId = revisionId,
                    purpose = purpose.name,
                    outcome = AccessAuditOutcome.SUCCEEDED,
                    httpStatus = 200,
                    occurredAt = Instant.now(clock),
                ),
            )
        } catch (exception: DataAccessException) {
            throw AiAuditUnavailableException(exception)
        }
        return article
    }

    @Transactional
    fun authorize(
        principal: AiSourcePrincipal,
        jobId: UUID,
        candidates: List<AiKnowledgeCandidate>,
        metadata: AiRequestMetadata,
    ): List<Pair<AiKnowledgeCandidate, AiPublicKnowledgeArticle>> {
        require(candidates.distinctBy { it.articleId to it.revisionId }.size == candidates.size)
        return candidates.map { candidate ->
            val article = projection.findCurrentPublic(candidate.articleId, candidate.revisionId)
                ?: throw AiKnowledgeSourceNotFoundException()
            appendAudit(principal, jobId, article, AiKnowledgeReadPurpose.RESULT, metadata)
            candidate to article
        }
    }

    private fun appendAudit(
        principal: AiSourcePrincipal,
        requestRef: UUID,
        article: AiPublicKnowledgeArticle,
        purpose: AiKnowledgeReadPurpose,
        metadata: AiRequestMetadata,
    ) {
        try {
            auditWriter.appendAiKnowledgeAccess(
                AiKnowledgeAccessAudit(
                    eventId = UUID.randomUUID(),
                    context = AccessAuditContext(
                        actorType = ActorType.INTEGRATION_CLIENT,
                        actorId = principal.id,
                        actorDisplaySnapshot = principal.displayName,
                        source = RequestSource.AI_SERVICE,
                        sessionFingerprint = null,
                        authType = AccessAuditAuthType.API_KEY,
                        requestId = metadata.requestId,
                        correlationId = metadata.correlationId,
                        ipAddress = null,
                        userAgent = null,
                    ),
                    requestRef = requestRef,
                    articleId = article.articleId,
                    revisionId = article.revisionId,
                    purpose = purpose.name,
                    outcome = AccessAuditOutcome.SUCCEEDED,
                    httpStatus = 200,
                    occurredAt = Instant.now(clock),
                ),
            )
        } catch (exception: DataAccessException) {
            throw AiAuditUnavailableException(exception)
        }
    }

    private companion object {
        val MANIFEST_SNAPSHOT_TTL: Duration = Duration.ofHours(24)
    }
}

internal class AiKnowledgeSourceNotFoundException : RuntimeException()
internal class AiKnowledgeManifestSnapshotUnavailableException : RuntimeException()

private fun AiPublicKnowledgeArticle.toResponse() = AiKnowledgeArticleResponse(
    articleId = articleId,
    revisionId = revisionId,
    slug = slug,
    title = title,
    body = body,
    sourceVersion = sourceVersion,
    publicRevision = publicRevision,
    publishedAt = publishedAt,
)
