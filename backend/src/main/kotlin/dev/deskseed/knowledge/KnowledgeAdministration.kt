package dev.deskseed.knowledge

import dev.deskseed.foundation.CommandContext
import dev.deskseed.foundation.RequestSource
import java.time.Instant
import java.util.UUID

data class KnowledgeAdminActor(
    val staffId: UUID,
    val displayName: String,
    val context: CommandContext,
) {
    init {
        require(context.source == RequestSource.ADMIN_UI) { "Knowledge administration requires ADMIN_UI source" }
    }
}

enum class KnowledgeArticleLifecycle {
    DRAFT,
    IN_REVIEW,
    PUBLISHED,
    UNPUBLISHED,
    ARCHIVED,
}

enum class KnowledgeLifecycleAction {
    SUBMIT_REVIEW,
    PUBLISH,
    UNPUBLISH,
    ARCHIVE,
}

data class KnowledgeCategoryInput(
    val slug: String,
    val title: String,
    val description: String = "",
    val displayOrder: Int,
)

data class KnowledgeCategoryView(
    val id: UUID,
    val slug: String,
    val title: String,
    val description: String,
    val active: Boolean,
    val displayOrder: Int,
    val version: Long,
)

data class KnowledgeSectionInput(
    val categoryId: UUID,
    val slug: String,
    val title: String,
    val description: String = "",
    val displayOrder: Int,
)

data class KnowledgeSectionView(
    val id: UUID,
    val categoryId: UUID,
    val slug: String,
    val title: String,
    val description: String,
    val active: Boolean,
    val displayOrder: Int,
    val version: Long,
)

data class CreateKnowledgeArticleDraft(
    val sectionId: UUID,
    val slug: String,
    val title: String,
    val summary: String = "",
    val changeNote: String = "",
    val document: CanonicalKnowledgeDocument,
    val audience: KnowledgeAudience,
)

data class KnowledgeRevisionView(
    val id: UUID,
    val revisionNumber: Int,
    val title: String,
    val document: CanonicalKnowledgeDocument,
    val summary: String,
    val changeNote: String,
    val contentChecksum: String,
    val createdAt: Instant,
)

data class KnowledgeArticleView(
    val id: UUID,
    val sectionId: UUID,
    val slug: String,
    val lifecycle: KnowledgeArticleLifecycle,
    val audience: KnowledgeAudience,
    val audienceVersion: Int,
    val currentPublishedRevision: KnowledgeRevisionView?,
    val version: Long,
)

data class KnowledgeArticlePage(
    val items: List<KnowledgeArticleView>,
    val nextCursor: String?,
)

data class KnowledgeArticleListFilter(
    val lifecycle: KnowledgeArticleLifecycle? = null,
    val sectionId: UUID? = null,
    val audience: KnowledgeAudienceType? = null,
)

/** List projection deliberately excludes immutable canonical document bodies. */
data class KnowledgeArticleRevisionSummary(
    val id: UUID,
    val revisionNumber: Int,
    val title: String,
    val summary: String,
    val contentChecksum: String,
    val createdAt: Instant,
)

data class KnowledgeArticleSummary(
    val id: UUID,
    val sectionId: UUID,
    val slug: String,
    val lifecycle: KnowledgeArticleLifecycle,
    val audience: KnowledgeAudience,
    val audienceVersion: Int,
    val currentPublishedRevision: KnowledgeArticleRevisionSummary?,
    val version: Long,
)

data class KnowledgeArticleSummaryPage(
    val items: List<KnowledgeArticleSummary>,
    val nextCursor: String?,
)

enum class KnowledgeSearchIndexState {
    IDLE,
    REBUILDING,
    FAILED,
}

data class KnowledgeSearchIndexStatus(
    val state: KnowledgeSearchIndexState,
    val lastRebuiltAt: Instant?,
    val lagSeconds: Long,
)

interface KnowledgeAdministration {
    fun listCategories(actor: KnowledgeAdminActor): List<KnowledgeCategoryView>

    fun createCategory(input: KnowledgeCategoryInput, actor: KnowledgeAdminActor): KnowledgeCategoryView

    fun updateCategory(
        categoryId: UUID,
        input: KnowledgeCategoryInput,
        active: Boolean,
        expectedVersion: Long,
        actor: KnowledgeAdminActor,
    ): KnowledgeCategoryView

    fun createSection(input: KnowledgeSectionInput, actor: KnowledgeAdminActor): KnowledgeSectionView

    fun updateSection(
        sectionId: UUID,
        input: KnowledgeSectionInput,
        active: Boolean,
        expectedVersion: Long,
        actor: KnowledgeAdminActor,
    ): KnowledgeSectionView

    fun createDraft(input: CreateKnowledgeArticleDraft, actor: KnowledgeAdminActor): KnowledgeArticleView

    fun listArticles(
        cursor: String?,
        filter: KnowledgeArticleListFilter,
        actor: KnowledgeAdminActor,
    ): KnowledgeArticleSummaryPage

    fun getArticle(articleId: UUID, actor: KnowledgeAdminActor): KnowledgeArticleView

    fun updateDraft(
        articleId: UUID,
        input: CreateKnowledgeArticleDraft,
        expectedVersion: Long,
        actor: KnowledgeAdminActor,
    ): KnowledgeArticleView

    fun listRevisions(articleId: UUID, actor: KnowledgeAdminActor): List<KnowledgeRevisionView>

    fun transition(
        articleId: UUID,
        action: KnowledgeLifecycleAction,
        expectedVersion: Long,
        actor: KnowledgeAdminActor,
    ): KnowledgeArticleView

    fun replaceAudience(
        articleId: UUID,
        audience: KnowledgeAudience,
        expectedVersion: Long,
        actor: KnowledgeAdminActor,
    ): KnowledgeArticleView

    fun searchIndexStatus(actor: KnowledgeAdminActor): KnowledgeSearchIndexStatus

    fun rebuildSearchIndex(actor: KnowledgeAdminActor)
}

class KnowledgeNotFoundException(val code: String) : RuntimeException()

class KnowledgeConflictException(val code: String) : RuntimeException()

class KnowledgePreconditionFailedException(val currentVersion: Long) : RuntimeException()
