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
import dev.deskseed.knowledge.KnowledgeArticleView
import dev.deskseed.knowledge.KnowledgeLifecycleAction
import dev.deskseed.knowledge.KnowledgeRevisionView
import dev.deskseed.knowledge.KnowledgeSectionInput
import dev.deskseed.knowledge.KnowledgeSectionView
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
    ): ResponseEntity<KnowledgeArticleView> {
        val created = administration.createDraft(body.toInput(documentCodec), request.actor(principal, expectedActor))
        return ResponseEntity.created(URI.create("/api/v1/admin/knowledge/articles/${created.id}"))
            .cacheControl(CacheControl.noStore())
            .eTag(created.version.toString())
            .body(created)
    }

    @GetMapping("/articles/{articleId}")
    fun getArticle(
        @PathVariable articleId: UUID,
        @RequestHeader(EXPECTED_STAFF_ACTOR_HEADER) expectedActor: UUID,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<KnowledgeArticleView> {
        val article = administration.getArticle(articleId, request.actor(principal, expectedActor))
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(article.version.toString()).body(article)
    }

    @GetMapping("/articles/{articleId}/revisions")
    fun listRevisions(
        @PathVariable articleId: UUID,
        @RequestHeader(EXPECTED_STAFF_ACTOR_HEADER) expectedActor: UUID,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<List<KnowledgeRevisionView>> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(administration.listRevisions(articleId, request.actor(principal, expectedActor)))

    @PostMapping("/articles/{articleId}/{action}")
    fun transition(
        @PathVariable articleId: UUID,
        @PathVariable action: String,
        @RequestHeader("If-Match") ifMatch: String,
        @RequestHeader(EXPECTED_STAFF_ACTOR_HEADER) expectedActor: UUID,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<KnowledgeArticleView> {
        val result = administration.transition(
            articleId = articleId,
            action = action.toLifecycleAction(),
            expectedVersion = parseVersion(ifMatch),
            actor = request.actor(principal, expectedActor),
        )
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(result.version.toString()).body(result)
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
