package dev.deskseed.knowledge

import java.time.Instant
import java.util.UUID

data class AiPublicKnowledgeManifestItem(
    val articleId: UUID,
    val revisionId: UUID,
    val sourceVersion: Long,
    val publicRevision: String,
    val publishedAt: Instant,
)

data class AiPublicKnowledgeManifestPage(
    val snapshotToken: UUID,
    val expiresAt: Instant,
    val canonicalPublicCorpusRevision: Long,
    val items: List<AiPublicKnowledgeManifestItem>,
    val nextCursor: UUID?,
)

data class AiPublicKnowledgeArticle(
    val articleId: UUID,
    val revisionId: UUID,
    val slug: String,
    val title: String,
    val body: String,
    val sourceVersion: Long,
    val publicRevision: String,
    val publishedAt: Instant,
)

interface AiKnowledgeProjection {
    fun createManifestSnapshot(now: Instant, expiresAt: Instant): UUID
    fun manifest(snapshotToken: UUID, cursor: UUID?, limit: Int, now: Instant): AiPublicKnowledgeManifestPage?
    fun findCurrentPublic(articleId: UUID, revisionId: UUID): AiPublicKnowledgeArticle?
}
