package dev.deskseed.knowledge.internal

import dev.deskseed.knowledge.AiKnowledgeProjection
import dev.deskseed.knowledge.AiPublicKnowledgeArticle
import dev.deskseed.knowledge.AiPublicKnowledgeManifestItem
import dev.deskseed.knowledge.AiPublicKnowledgeManifestPage
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
internal class JdbcAiKnowledgeProjection(
    private val jdbcTemplate: JdbcTemplate,
) : AiKnowledgeProjection {
    override fun createManifestSnapshot(now: Instant, expiresAt: Instant): UUID {
        require(expiresAt > now) { "manifest snapshot must expire after creation" }
        jdbcTemplate.update("delete from ai_knowledge_manifest_snapshots where expires_at <= ?", Timestamp.from(now))
        val snapshotToken = UUID.randomUUID()
        val corpusRevision = jdbcTemplate.queryForObject(
            "select revision from ai_public_knowledge_corpus_state where singleton = true for share",
            Long::class.java,
        ) ?: error("PUBLIC knowledge corpus revision is unavailable")
        jdbcTemplate.update(
            """
            insert into ai_knowledge_manifest_snapshots (
                snapshot_token, created_at, expires_at, canonical_public_corpus_revision
            )
            values (?, ?, ?, ?)
            """.trimIndent(),
            snapshotToken,
            Timestamp.from(now),
            Timestamp.from(expiresAt),
            corpusRevision,
        )
        jdbcTemplate.update(
            """
            insert into ai_knowledge_manifest_snapshot_items (
                snapshot_token, article_id, revision_id, source_version, public_revision, published_at
            )
            select ?, article.id, revision.id, article.version, revision.content_checksum, article.published_at
            from knowledge_articles article
            join knowledge_sections section on section.id = article.section_id and section.status = 'ACTIVE'
            join knowledge_categories category on category.id = section.category_id and category.status = 'ACTIVE'
            join knowledge_article_revisions revision on revision.id = article.current_published_revision_id
            where article.lifecycle = 'PUBLISHED' and article.audience_type = 'PUBLIC'
            """.trimIndent(),
            snapshotToken,
        )
        return snapshotToken
    }

    override fun manifest(
        snapshotToken: UUID,
        cursor: UUID?,
        limit: Int,
        now: Instant,
    ): AiPublicKnowledgeManifestPage? {
        require(limit in 1..1_000) { "manifest limit must be between 1 and 1000" }
        val snapshot = jdbcTemplate.query(
            """
            select expires_at, canonical_public_corpus_revision
            from ai_knowledge_manifest_snapshots where snapshot_token = ? and expires_at > ?
            """.trimIndent(),
            { result, _ ->
                result.getTimestamp("expires_at").toInstant() to
                    result.getLong("canonical_public_corpus_revision")
            },
            snapshotToken,
            Timestamp.from(now),
        ).singleOrNull() ?: return null
        val rows = jdbcTemplate.query(
            """
            select article_id, revision_id, source_version, public_revision, published_at
            from ai_knowledge_manifest_snapshot_items
            where snapshot_token = ? and (?::uuid is null or article_id > ?::uuid)
            order by article_id
            limit ?
            """.trimIndent(),
            { result, _ ->
                AiPublicKnowledgeManifestItem(
                    articleId = result.getObject("article_id", UUID::class.java),
                    revisionId = result.getObject("revision_id", UUID::class.java),
                    sourceVersion = result.getLong("source_version"),
                    publicRevision = result.getString("public_revision"),
                    publishedAt = result.getTimestamp("published_at").toInstant(),
                )
            },
            snapshotToken,
            cursor,
            cursor,
            limit + 1,
        )
        val hasMore = rows.size > limit
        val items = if (hasMore) rows.take(limit) else rows
        return AiPublicKnowledgeManifestPage(
            snapshotToken = snapshotToken,
            expiresAt = snapshot.first,
            canonicalPublicCorpusRevision = snapshot.second,
            items = items,
            nextCursor = if (hasMore) items.last().articleId else null,
        )
    }

    override fun findCurrentPublic(articleId: UUID, revisionId: UUID): AiPublicKnowledgeArticle? = jdbcTemplate.query(
        """
        select article.id as article_id, revision.id as revision_id, article.slug,
               revision.title, revision.plain_text, article.version as source_version,
               revision.content_checksum as public_revision,
               article.published_at
        from knowledge_articles article
        join knowledge_sections section on section.id = article.section_id and section.status = 'ACTIVE'
        join knowledge_categories category on category.id = section.category_id and category.status = 'ACTIVE'
        join knowledge_article_revisions revision on revision.id = article.current_published_revision_id
        where article.id = ? and revision.id = ?
          and article.lifecycle = 'PUBLISHED' and article.audience_type = 'PUBLIC'
        """.trimIndent(),
        { result, _ ->
            AiPublicKnowledgeArticle(
                articleId = result.getObject("article_id", UUID::class.java),
                revisionId = result.getObject("revision_id", UUID::class.java),
                slug = result.getString("slug"),
                title = result.getString("title"),
                body = result.getString("plain_text"),
                sourceVersion = result.getLong("source_version"),
                publicRevision = result.getString("public_revision"),
                publishedAt = result.getTimestamp("published_at").toInstant(),
            )
        },
        articleId,
        revisionId,
    ).singleOrNull()
}
