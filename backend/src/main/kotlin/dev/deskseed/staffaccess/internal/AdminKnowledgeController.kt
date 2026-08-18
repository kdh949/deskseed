package dev.deskseed.staffaccess.internal

import dev.deskseed.foundation.CommandContexts
import dev.deskseed.foundation.EXPECTED_STAFF_ACTOR_HEADER
import dev.deskseed.foundation.RequestSource
import dev.deskseed.knowledge.CanonicalKnowledgeDocumentCodec
import dev.deskseed.knowledge.CreateKnowledgeArticleDraft
import dev.deskseed.knowledge.KnowledgeAdminActor
import dev.deskseed.knowledge.KnowledgeAdministration
import dev.deskseed.knowledge.KnowledgeAudience
import dev.deskseed.knowledge.KnowledgeAudienceType
import dev.deskseed.knowledge.KnowledgeCategoryInput
import dev.deskseed.knowledge.KnowledgeCategoryView
import dev.deskseed.knowledge.KnowledgeArticlePage
import dev.deskseed.knowledge.KnowledgeArticleView
import dev.deskseed.knowledge.KnowledgeLifecycleAction
import dev.deskseed.knowledge.KnowledgeRevisionView
import dev.deskseed.knowledge.KnowledgeSectionInput
import dev.deskseed.knowledge.KnowledgeSectionView
import dev.deskseed.knowledge.KnowledgeSearchIndexStatus
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.PutMapping
import tools.jackson.databind.JsonNode
import java.net.URI
import java.util.UUID

@RestController
@RequestMapping("/api/v1/admin/knowledge")
@Validated
internal class AdminKnowledgeController(
    private val administration: KnowledgeAdministration,
) {
    private val documentCodec = CanonicalKnowledgeDocumentCodec()

    @GetMapping("/categories")
    fun listCategories(
        @RequestHeader(EXPECTED_STAFF_ACTOR_HEADER) expectedActor: UUID,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<List<KnowledgeCategoryView>> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(administration.listCategories(request.actor(principal, expectedActor)))

    @PostMapping("/categories")
    fun createCategory(
        @RequestHeader(EXPECTED_STAFF_ACTOR_HEADER) expectedActor: UUID,
        @AuthenticationPrincipal principal: StaffPrincipal,
        @Valid @RequestBody body: KnowledgeCategoryRequest,
        request: HttpServletRequest,
    ): ResponseEntity<KnowledgeCategoryView> {
        val created = administration.createCategory(body.toInput(), request.actor(principal, expectedActor))
        return ResponseEntity.created(URI.create("/api/v1/admin/knowledge/categories/${created.id}"))
            .cacheControl(CacheControl.noStore())
            .eTag(created.version.toString())
            .body(created)
    }

    @PatchMapping("/categories/{categoryId}")
    fun updateCategory(
        @PathVariable categoryId: UUID,
        @RequestHeader("If-Match") ifMatch: String,
        @RequestHeader(EXPECTED_STAFF_ACTOR_HEADER) expectedActor: UUID,
        @AuthenticationPrincipal principal: StaffPrincipal,
        @Valid @RequestBody body: KnowledgeCategoryPatchRequest,
        request: HttpServletRequest,
    ): ResponseEntity<KnowledgeCategoryView> {
        val result = administration.updateCategory(
            categoryId,
            body.toInput(),
            body.active,
            parseVersion(ifMatch),
            request.actor(principal, expectedActor),
        )
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(result.version.toString()).body(result)
    }

    @PostMapping("/sections")
    fun createSection(
        @RequestHeader(EXPECTED_STAFF_ACTOR_HEADER) expectedActor: UUID,
        @AuthenticationPrincipal principal: StaffPrincipal,
        @Valid @RequestBody body: KnowledgeSectionRequest,
        request: HttpServletRequest,
    ): ResponseEntity<KnowledgeSectionView> {
        val created = administration.createSection(body.toInput(), request.actor(principal, expectedActor))
        return ResponseEntity.created(URI.create("/api/v1/admin/knowledge/sections/${created.id}"))
            .cacheControl(CacheControl.noStore())
            .eTag(created.version.toString())
            .body(created)
    }

    @PatchMapping("/sections/{sectionId}")
    fun updateSection(
        @PathVariable sectionId: UUID,
        @RequestHeader("If-Match") ifMatch: String,
        @RequestHeader(EXPECTED_STAFF_ACTOR_HEADER) expectedActor: UUID,
        @AuthenticationPrincipal principal: StaffPrincipal,
        @Valid @RequestBody body: KnowledgeSectionPatchRequest,
        request: HttpServletRequest,
    ): ResponseEntity<KnowledgeSectionView> {
        val result = administration.updateSection(
            sectionId,
            body.toInput(),
            body.active,
            parseVersion(ifMatch),
            request.actor(principal, expectedActor),
        )
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(result.version.toString()).body(result)
    }

    @PostMapping("/articles")
    fun createArticleDraft(
        @RequestHeader(EXPECTED_STAFF_ACTOR_HEADER) expectedActor: UUID,
        @AuthenticationPrincipal principal: StaffPrincipal,
        @Valid @RequestBody body: KnowledgeArticleDraftRequest,
        request: HttpServletRequest,
    ): ResponseEntity<KnowledgeArticleResponse> {
        val created = administration.createDraft(body.toInput(documentCodec), request.actor(principal, expectedActor))
        return ResponseEntity.created(URI.create("/api/v1/admin/knowledge/articles/${created.id}"))
            .cacheControl(CacheControl.noStore())
            .eTag(created.version.toString())
            .body(created.toResponse(documentCodec))
    }

    @GetMapping("/articles")
    fun listArticles(
        @RequestParam(required = false) cursor: String?,
        @RequestHeader(EXPECTED_STAFF_ACTOR_HEADER) expectedActor: UUID,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<KnowledgeArticlePageResponse> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(administration.listArticles(cursor, request.actor(principal, expectedActor)).toResponse(documentCodec))

    @GetMapping("/articles/{articleId}")
    fun getArticle(
        @PathVariable articleId: UUID,
        @RequestHeader(EXPECTED_STAFF_ACTOR_HEADER) expectedActor: UUID,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<KnowledgeArticleResponse> {
        val article = administration.getArticle(articleId, request.actor(principal, expectedActor))
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(article.version.toString()).body(article.toResponse(documentCodec))
    }

    @PatchMapping("/articles/{articleId}")
    fun updateArticleDraft(
        @PathVariable articleId: UUID,
        @RequestHeader("If-Match") ifMatch: String,
        @RequestHeader(EXPECTED_STAFF_ACTOR_HEADER) expectedActor: UUID,
        @AuthenticationPrincipal principal: StaffPrincipal,
        @Valid @RequestBody body: KnowledgeArticleDraftRequest,
        request: HttpServletRequest,
    ): ResponseEntity<KnowledgeArticleResponse> {
        val article = administration.updateDraft(
            articleId,
            body.toInput(documentCodec),
            parseVersion(ifMatch),
            request.actor(principal, expectedActor),
        )
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(article.version.toString()).body(article.toResponse(documentCodec))
    }

    @GetMapping("/articles/{articleId}/revisions")
    fun listRevisions(
        @PathVariable articleId: UUID,
        @RequestHeader(EXPECTED_STAFF_ACTOR_HEADER) expectedActor: UUID,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<List<KnowledgeRevisionResponse>> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(administration.listRevisions(articleId, request.actor(principal, expectedActor)).map { it.toResponse(documentCodec) })

    @PostMapping("/articles/{articleId}/{action}")
    fun transition(
        @PathVariable articleId: UUID,
        @PathVariable action: String,
        @RequestHeader("If-Match") ifMatch: String,
        @RequestHeader(EXPECTED_STAFF_ACTOR_HEADER) expectedActor: UUID,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<KnowledgeArticleResponse> {
        val result = administration.transition(
            articleId = articleId,
            action = action.toLifecycleAction(),
            expectedVersion = parseVersion(ifMatch),
            actor = request.actor(principal, expectedActor),
        )
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(result.version.toString()).body(result.toResponse(documentCodec))
    }

    @PutMapping("/articles/{articleId}/audience")
    fun replaceAudience(
        @PathVariable articleId: UUID,
        @RequestHeader("If-Match") ifMatch: String,
        @RequestHeader(EXPECTED_STAFF_ACTOR_HEADER) expectedActor: UUID,
        @AuthenticationPrincipal principal: StaffPrincipal,
        @Valid @RequestBody body: KnowledgeAudienceRequest,
        request: HttpServletRequest,
    ): ResponseEntity<KnowledgeArticleResponse> {
        val result = administration.replaceAudience(
            articleId,
            body.toAudience(),
            parseVersion(ifMatch),
            request.actor(principal, expectedActor),
        )
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(result.version.toString()).body(result.toResponse(documentCodec))
    }

    @GetMapping("/search-index")
    fun searchIndexStatus(
        @RequestHeader(EXPECTED_STAFF_ACTOR_HEADER) expectedActor: UUID,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<KnowledgeSearchIndexStatus> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(administration.searchIndexStatus(request.actor(principal, expectedActor)))

    @PostMapping("/search-index")
    fun rebuildSearchIndex(
        @RequestHeader(EXPECTED_STAFF_ACTOR_HEADER) expectedActor: UUID,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<Unit> {
        administration.rebuildSearchIndex(request.actor(principal, expectedActor))
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).build()
    }

    private fun HttpServletRequest.actor(principal: StaffPrincipal, expectedActor: UUID): KnowledgeAdminActor {
        require(expectedActor == principal.id) { "expected staff actor must match the authenticated staff session" }
        return KnowledgeAdminActor(
            staffId = principal.id,
            displayName = principal.displayName,
            context = CommandContexts.from(this, RequestSource.ADMIN_UI),
        )
    }

    private fun String.toLifecycleAction(): KnowledgeLifecycleAction = when (this) {
        "submit-review" -> KnowledgeLifecycleAction.SUBMIT_REVIEW
        "publish" -> KnowledgeLifecycleAction.PUBLISH
        "unpublish" -> KnowledgeLifecycleAction.UNPUBLISH
        "archive" -> KnowledgeLifecycleAction.ARCHIVE
        else -> throw IllegalArgumentException("knowledge lifecycle action is not allowlisted")
    }

    private fun parseVersion(value: String): Long = value.trim().removeSurrounding("\"").toLongOrNull()
        ?.takeIf { it >= 0 }
        ?: throw IllegalArgumentException("If-Match must contain a non-negative resource version")
}

internal data class KnowledgeCategoryRequest(
    @field:NotBlank @field:Size(max = 120)
    val slug: String,
    @field:NotBlank @field:Size(max = 200)
    val title: String,
    @field:Size(max = 1000)
    val description: String = "",
    @field:Min(0) @field:Max(Int.MAX_VALUE.toLong())
    val displayOrder: Int,
) {
    fun toInput() = KnowledgeCategoryInput(slug, title, description, displayOrder)
}

internal data class KnowledgeSectionRequest(
    val categoryId: UUID,
    @field:NotBlank @field:Size(max = 120)
    val slug: String,
    @field:NotBlank @field:Size(max = 200)
    val title: String,
    @field:Size(max = 1000)
    val description: String = "",
    @field:Min(0) @field:Max(Int.MAX_VALUE.toLong())
    val displayOrder: Int,
) {
    fun toInput() = KnowledgeSectionInput(categoryId, slug, title, description, displayOrder)
}

internal data class KnowledgeCategoryPatchRequest(
    @field:NotBlank @field:Size(max = 120)
    val slug: String,
    @field:NotBlank @field:Size(max = 200)
    val title: String,
    @field:Size(max = 1000)
    val description: String = "",
    @field:Min(0) @field:Max(Int.MAX_VALUE.toLong())
    val displayOrder: Int,
    val active: Boolean,
) {
    fun toInput() = KnowledgeCategoryInput(slug, title, description, displayOrder)
}

internal data class KnowledgeSectionPatchRequest(
    val categoryId: UUID,
    @field:NotBlank @field:Size(max = 120)
    val slug: String,
    @field:NotBlank @field:Size(max = 200)
    val title: String,
    @field:Size(max = 1000)
    val description: String = "",
    @field:Min(0) @field:Max(Int.MAX_VALUE.toLong())
    val displayOrder: Int,
    val active: Boolean,
) {
    fun toInput() = KnowledgeSectionInput(categoryId, slug, title, description, displayOrder)
}

internal data class KnowledgeAudienceRequest(
    val type: KnowledgeAudienceType,
    @field:Size(max = 100)
    val groupIds: Set<UUID> = emptySet(),
) {
    fun toAudience(): KnowledgeAudience = when (type) {
        KnowledgeAudienceType.PUBLIC -> KnowledgeAudience.public()
        KnowledgeAudienceType.SIGNED_IN_CUSTOMER -> KnowledgeAudience.signedInCustomer()
        KnowledgeAudienceType.STAFF -> KnowledgeAudience.staff()
        KnowledgeAudienceType.SELECTED_STAFF_GROUPS -> KnowledgeAudience.selectedStaffGroups(groupIds)
    }
}

internal data class KnowledgeArticleDraftRequest(
    val sectionId: UUID,
    @field:NotBlank @field:Size(max = 120)
    val slug: String,
    @field:NotBlank @field:Size(max = 300)
    val title: String,
    @field:Size(max = 1000)
    val summary: String = "",
    @field:Size(max = 1000)
    val changeNote: String = "",
    val document: JsonNode,
    @field:Valid
    val audience: KnowledgeAudienceRequest,
) {
    fun toInput(codec: CanonicalKnowledgeDocumentCodec) = CreateKnowledgeArticleDraft(
        sectionId = sectionId,
        slug = slug,
        title = title,
        summary = summary,
        changeNote = changeNote,
        document = codec.decode(document),
        audience = audience.toAudience(),
    )
}

/** Keep the canonical wire document typed; Kotlin sealed-class JSON has no stable discriminator. */
internal data class KnowledgeAudienceResponse(
    val type: KnowledgeAudienceType,
    val groupIds: Set<UUID>,
)

internal data class KnowledgeRevisionResponse(
    val id: UUID,
    val revisionNumber: Int,
    val title: String,
    val document: Map<String, Any>,
    val summary: String,
    val changeNote: String,
    val contentChecksum: String,
    val createdAt: java.time.Instant,
)

internal data class KnowledgeArticleResponse(
    val id: UUID,
    val sectionId: UUID,
    val slug: String,
    val lifecycle: dev.deskseed.knowledge.KnowledgeArticleLifecycle,
    val audience: KnowledgeAudienceResponse,
    val audienceVersion: Int,
    val currentPublishedRevision: KnowledgeRevisionResponse?,
    val version: Long,
)

internal data class KnowledgeArticlePageResponse(
    val items: List<KnowledgeArticleResponse>,
    val hasMore: Boolean,
    val nextCursor: String?,
)

private fun KnowledgeRevisionView.toResponse(codec: CanonicalKnowledgeDocumentCodec) = KnowledgeRevisionResponse(
    id = id,
    revisionNumber = revisionNumber,
    title = title,
    document = codec.encode(document),
    summary = summary,
    changeNote = changeNote,
    contentChecksum = contentChecksum,
    createdAt = createdAt,
)

private fun KnowledgeArticleView.toResponse(codec: CanonicalKnowledgeDocumentCodec) = KnowledgeArticleResponse(
    id = id,
    sectionId = sectionId,
    slug = slug,
    lifecycle = lifecycle,
    audience = KnowledgeAudienceResponse(audience.type, audience.groupIds),
    audienceVersion = audienceVersion,
    currentPublishedRevision = currentPublishedRevision?.toResponse(codec),
    version = version,
)

private fun KnowledgeArticlePage.toResponse(codec: CanonicalKnowledgeDocumentCodec) = KnowledgeArticlePageResponse(
    items = items.map { it.toResponse(codec) },
    hasMore = nextCursor != null,
    nextCursor = nextCursor,
)
