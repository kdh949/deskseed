package dev.deskseed.staffaccess.internal

import dev.deskseed.foundation.CommandContexts
import dev.deskseed.foundation.EXPECTED_STAFF_ACTOR_HEADER
import dev.deskseed.foundation.RequestSource
import dev.deskseed.knowledge.CanonicalKnowledgeDocumentCodec
import dev.deskseed.knowledge.KnowledgeRevisionView
import dev.deskseed.knowledge.KnowledgeSearchPage
import dev.deskseed.knowledge.KnowledgeSearchQuery
import dev.deskseed.knowledge.PublishedKnowledgeArticle
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/agent")
@Validated
internal class AgentKnowledgeController(
    private val applicationService: AgentKnowledgeApplicationService,
) {
    private val documentCodec = CanonicalKnowledgeDocumentCodec()

    @PostMapping("/knowledge/search")
    fun search(
        @RequestHeader(EXPECTED_STAFF_ACTOR_HEADER) expectedActor: UUID,
        @AuthenticationPrincipal principal: StaffPrincipal,
        @Valid @RequestBody body: AgentKnowledgeSearchRequest,
        request: HttpServletRequest,
    ): ResponseEntity<AgentKnowledgeSearchPageResponse> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(applicationService.search(principal.expected(expectedActor), body.toQuery(), request.context(), request.sessionId()).toResponse())

    @GetMapping("/knowledge/articles/{articleSlug}")
    fun article(
        @PathVariable articleSlug: String,
        @RequestHeader(EXPECTED_STAFF_ACTOR_HEADER) expectedActor: UUID,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<AgentKnowledgeArticleResponse> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(applicationService.article(principal.expected(expectedActor), articleSlug, request.context(), request.sessionId()).toResponse(documentCodec))

    @GetMapping("/tickets/{ticketNumber}/knowledge-suggestions")
    fun suggestions(
        @PathVariable @Positive ticketNumber: Long,
        @RequestHeader(EXPECTED_STAFF_ACTOR_HEADER) expectedActor: UUID,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<AgentKnowledgeSearchPageResponse> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(applicationService.suggestions(principal.expected(expectedActor), ticketNumber, request.context(), request.sessionId()).toResponse())

    private fun StaffPrincipal.expected(expectedActor: UUID): StaffPrincipal = apply {
        require(id == expectedActor) { "expected staff actor must match the authenticated staff session" }
    }

    private fun HttpServletRequest.context() = CommandContexts.from(this, RequestSource.AGENT_UI)

    private fun HttpServletRequest.sessionId(): String = getSession(false)?.id
        ?: throw dev.deskseed.knowledge.KnowledgeAccessAuditUnavailableException(
            IllegalStateException("Authenticated staff session is unavailable"),
        )
}

internal data class AgentKnowledgeSearchRequest(
    @field:NotBlank @field:Size(max = 512)
    val query: String,
    @field:Size(max = 1024)
    val cursor: String? = null,
    @field:Min(1) @field:Max(50)
    val limit: Int = 20,
) {
    fun toQuery() = KnowledgeSearchQuery(query, cursor, limit)
}

internal data class AgentKnowledgeSearchPageResponse(
    val items: List<dev.deskseed.knowledge.KnowledgeSearchHit>,
    val hasMore: Boolean,
    val nextCursor: String?,
)

internal data class AgentKnowledgeArticleResponse(
    val id: UUID,
    val sectionId: UUID,
    val slug: String,
    val lifecycle: String = "PUBLISHED",
    val audience: AgentKnowledgeAudienceResponse,
    val audienceVersion: Int,
    val currentPublishedRevision: AgentKnowledgeRevisionResponse,
)

internal data class AgentKnowledgeAudienceResponse(
    val type: dev.deskseed.knowledge.KnowledgeAudienceType,
    val groupIds: Set<UUID>,
)

internal data class AgentKnowledgeRevisionResponse(
    val id: UUID,
    val revisionNumber: Int,
    val title: String,
    val document: Map<String, Any>,
    val summary: String,
    val contentChecksum: String,
    val createdAt: java.time.Instant,
)

private fun KnowledgeSearchPage.toResponse() = AgentKnowledgeSearchPageResponse(items, hasMore, nextCursor)

private fun PublishedKnowledgeArticle.toResponse(codec: CanonicalKnowledgeDocumentCodec) = AgentKnowledgeArticleResponse(
    id,
    sectionId,
    slug,
    audience = AgentKnowledgeAudienceResponse(audience.type, audience.groupIds),
    audienceVersion = audienceVersion,
    currentPublishedRevision = revision.toResponse(codec),
)

private fun KnowledgeRevisionView.toResponse(codec: CanonicalKnowledgeDocumentCodec) = AgentKnowledgeRevisionResponse(
    id,
    revisionNumber,
    title,
    codec.encode(document),
    summary,
    contentChecksum,
    createdAt,
)
