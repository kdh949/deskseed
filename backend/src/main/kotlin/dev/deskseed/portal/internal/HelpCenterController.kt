package dev.deskseed.portal.internal

import dev.deskseed.customerauth.CustomerPrincipal
import dev.deskseed.knowledge.CanonicalKnowledgeDocumentCodec
import dev.deskseed.knowledge.KnowledgeArticleListing
import dev.deskseed.knowledge.KnowledgeAudience
import dev.deskseed.knowledge.KnowledgeNavigationCategory
import dev.deskseed.knowledge.KnowledgeNavigationSection
import dev.deskseed.knowledge.KnowledgeReader
import dev.deskseed.knowledge.KnowledgeReading
import dev.deskseed.knowledge.KnowledgeRevisionView
import dev.deskseed.knowledge.KnowledgeSearchPage
import dev.deskseed.knowledge.KnowledgeSearchQuery
import dev.deskseed.knowledge.PublishedKnowledgeArticle
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration

@RestController
@RequestMapping("/api/v1/help")
@Validated
internal class HelpCenterController(
    private val knowledge: KnowledgeReading,
) {
    private val documentCodec = CanonicalKnowledgeDocumentCodec()

    @GetMapping("/categories")
    fun listCategories(
        @AuthenticationPrincipal principal: CustomerPrincipal?,
    ): ResponseEntity<List<HelpCategoryResponse>> = ResponseEntity.ok()
        .cacheControl(navigationCache(principal))
        .body(knowledge.listCategories(principal.reader()).map(::categoryResponse))

    @GetMapping("/categories/{categorySlug}")
    fun getCategory(
        @PathVariable categorySlug: String,
        @AuthenticationPrincipal principal: CustomerPrincipal?,
    ): ResponseEntity<HelpCategoryResponse> = ResponseEntity.ok()
        .cacheControl(navigationCache(principal))
        .body(categoryResponse(knowledge.getCategory(categorySlug, principal.reader())))

    @GetMapping("/sections/{sectionSlug}")
    fun getSection(
        @PathVariable sectionSlug: String,
        @RequestParam(required = false) cursor: String?,
        @AuthenticationPrincipal principal: CustomerPrincipal?,
    ): ResponseEntity<HelpSectionResponse> = ResponseEntity.ok()
        .cacheControl(navigationCache(principal))
        .body(sectionResponse(knowledge.getSection(sectionSlug, cursor, principal.reader())))

    @GetMapping("/articles/{articleSlug}")
    fun getArticle(
        @PathVariable articleSlug: String,
        @RequestHeader(HttpHeaders.IF_NONE_MATCH, required = false) ifNoneMatch: String?,
        @AuthenticationPrincipal principal: CustomerPrincipal?,
    ): ResponseEntity<HelpArticleResponse> {
        val article = knowledge.getArticle(articleSlug, principal.reader())
        val etag = article.etag()
        val cache = articleCache(principal, article.audience)
        if (ifNoneMatch?.split(',')?.map(String::trim)?.contains(etag) == true) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                .eTag(etag)
                .lastModified(article.revision.createdAt.toEpochMilli())
                .cacheControl(cache)
                .build()
        }
        return ResponseEntity.ok()
            .eTag(etag)
            .lastModified(article.revision.createdAt.toEpochMilli())
            .cacheControl(cache)
            .body(article.toResponse(documentCodec))
    }

    @PostMapping("/search")
    fun search(
        @Valid @RequestBody body: HelpKnowledgeSearchRequest,
        @AuthenticationPrincipal principal: CustomerPrincipal?,
    ): ResponseEntity<HelpKnowledgeSearchPageResponse> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(knowledge.search(body.toQuery(), principal.reader()).toResponse())

    @PostMapping("/articles/{articleSlug}/feedback")
    fun feedback(
        @PathVariable articleSlug: String,
        @Valid @RequestBody body: HelpKnowledgeFeedbackRequest,
        @AuthenticationPrincipal principal: CustomerPrincipal?,
    ): ResponseEntity<Unit> {
        knowledge.recordFeedback(articleSlug, body.helpful, principal.reader())
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
    }

    private fun CustomerPrincipal?.reader(): KnowledgeReader =
        if (this == null) KnowledgeReader.AnonymousCustomer else KnowledgeReader.SignedInCustomer

    private fun navigationCache(principal: CustomerPrincipal?): CacheControl =
        if (principal == null) CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic() else CacheControl.noStore()

    private fun articleCache(principal: CustomerPrincipal?, audience: KnowledgeAudience): CacheControl =
        if (principal == null && audience.type.name == "PUBLIC") CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic()
        else CacheControl.noStore()
}

internal data class HelpKnowledgeSearchRequest(
    @field:NotBlank @field:Size(max = 512)
    val query: String,
    @field:Size(max = 1024)
    val cursor: String? = null,
    @field:Min(1) @field:Max(50)
    val limit: Int = 20,
) {
    fun toQuery() = KnowledgeSearchQuery(query, cursor, limit)
}

internal data class HelpKnowledgeFeedbackRequest(val helpful: Boolean)

internal data class HelpCategoryResponse(
    val id: java.util.UUID,
    val slug: String,
    val title: String,
    val description: String,
    val sections: List<HelpSectionResponse> = emptyList(),
)

internal data class HelpSectionResponse(
    val id: java.util.UUID,
    val categoryId: java.util.UUID,
    val slug: String,
    val title: String,
    val description: String,
    val articles: List<HelpArticleListingResponse> = emptyList(),
    val hasMore: Boolean = false,
    val nextCursor: String? = null,
)

internal data class HelpArticleListingResponse(
    val slug: String,
    val title: String,
    val summary: String,
    val audience: dev.deskseed.knowledge.KnowledgeAudienceType,
)

internal data class HelpRevisionResponse(
    val id: java.util.UUID,
    val revisionNumber: Int,
    val title: String,
    val document: Map<String, Any>,
    val summary: String,
    val contentChecksum: String,
    val createdAt: java.time.Instant,
)

internal data class HelpArticleResponse(
    val id: java.util.UUID,
    val sectionId: java.util.UUID,
    val slug: String,
    val lifecycle: String = "PUBLISHED",
    val audience: HelpAudienceResponse,
    val audienceVersion: Int,
    val currentPublishedRevision: HelpRevisionResponse,
)

internal data class HelpAudienceResponse(
    val type: dev.deskseed.knowledge.KnowledgeAudienceType,
    val groupIds: Set<java.util.UUID>,
)

internal data class HelpKnowledgeSearchPageResponse(
    val items: List<dev.deskseed.knowledge.KnowledgeSearchHit>,
    val hasMore: Boolean,
    val nextCursor: String?,
)

private fun categoryResponse(category: KnowledgeNavigationCategory): HelpCategoryResponse = HelpCategoryResponse(
    category.id,
    category.slug,
    category.title,
    category.description,
    category.sections.map(::sectionResponse),
)

private fun sectionResponse(section: KnowledgeNavigationSection): HelpSectionResponse = HelpSectionResponse(
    section.id,
    section.categoryId,
    section.slug,
    section.title,
    section.description,
    section.articles.map(::listingResponse),
    section.nextCursor != null,
    section.nextCursor,
)

private fun listingResponse(item: KnowledgeArticleListing) = HelpArticleListingResponse(
    item.slug,
    item.title,
    item.summary,
    item.audience,
)

private fun PublishedKnowledgeArticle.toResponse(codec: CanonicalKnowledgeDocumentCodec) = HelpArticleResponse(
    id,
    sectionId,
    slug,
    audience = HelpAudienceResponse(audience.type, audience.groupIds),
    audienceVersion = audienceVersion,
    currentPublishedRevision = revision.toResponse(codec),
)

private fun KnowledgeRevisionView.toResponse(codec: CanonicalKnowledgeDocumentCodec) = HelpRevisionResponse(
    id,
    revisionNumber,
    title,
    codec.encode(document),
    summary,
    contentChecksum,
    createdAt,
)

private fun KnowledgeSearchPage.toResponse() = HelpKnowledgeSearchPageResponse(items, hasMore, nextCursor)

private fun PublishedKnowledgeArticle.etag(): String = "\"" + MessageDigest.getInstance("SHA-256")
    .digest("${revision.id}:${audienceVersion}".toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { "%02x".format(it) } + "\""
