package dev.deskseed.knowledge

import dev.deskseed.foundation.CommandContext
import java.time.Instant
import java.util.UUID

/**
 * Read projections deliberately expose canonical blocks instead of HTML. HTTP adapters turn the
 * document into the typed wire representation, and clients render only the allowlisted blocks.
 */
data class KnowledgeNavigationCategory(
    val id: UUID,
    val slug: String,
    val title: String,
    val description: String,
    val sections: List<KnowledgeNavigationSection> = emptyList(),
)

data class KnowledgeNavigationSection(
    val id: UUID,
    val categoryId: UUID,
    val slug: String,
    val title: String,
    val description: String,
    val articles: List<KnowledgeArticleListing> = emptyList(),
    val nextCursor: String? = null,
)

data class KnowledgeArticleListing(
    val slug: String,
    val title: String,
    val summary: String,
    val audience: KnowledgeAudienceType,
)

data class PublishedKnowledgeArticle(
    val id: UUID,
    val sectionId: UUID,
    val slug: String,
    val audience: KnowledgeAudience,
    val audienceVersion: Int,
    val revision: KnowledgeRevisionView,
    val publishedAt: Instant,
)

data class KnowledgeSearchHit(
    val articleSlug: String,
    val title: String,
    val excerpt: String,
    val audience: KnowledgeAudienceType,
    val categoryTitle: String,
    val sectionTitle: String,
)

data class KnowledgeSearchPage(
    val items: List<KnowledgeSearchHit>,
    val nextCursor: String?,
) {
    val hasMore: Boolean get() = nextCursor != null
}

data class KnowledgeSearchQuery(
    val query: String,
    val cursor: String? = null,
    val limit: Int = 20,
)

data class KnowledgeAgentReadContext(
    val staffId: UUID,
    val staffDisplayName: String,
    val sessionId: String,
    val commandContext: CommandContext,
)

interface KnowledgeReading {
    fun listCategories(reader: KnowledgeReader): List<KnowledgeNavigationCategory>

    fun getCategory(categorySlug: String, reader: KnowledgeReader): KnowledgeNavigationCategory

    fun getSection(sectionSlug: String, cursor: String?, reader: KnowledgeReader): KnowledgeNavigationSection

    fun getArticle(articleSlug: String, reader: KnowledgeReader): PublishedKnowledgeArticle

    fun search(query: KnowledgeSearchQuery, reader: KnowledgeReader): KnowledgeSearchPage

    fun recordFeedback(articleSlug: String, helpful: Boolean, reader: KnowledgeReader)

    /** Agent search/detail reads append their own required access audit before returning success. */
    fun searchForAgent(context: KnowledgeAgentReadContext, query: KnowledgeSearchQuery): KnowledgeSearchPage

    fun getArticleForAgent(context: KnowledgeAgentReadContext, articleSlug: String): PublishedKnowledgeArticle

    fun suggestionsForAgent(
        context: KnowledgeAgentReadContext,
        ticketNumber: Long,
        boundedPublicQuery: String,
    ): KnowledgeSearchPage
}

class KnowledgeAccessAuditUnavailableException(cause: Throwable) : RuntimeException(cause)
