package dev.deskseed.knowledge.internal

import dev.deskseed.audit.AdminSecurityAudit
import dev.deskseed.audit.AdminSecurityAuditWriter
import dev.deskseed.audit.AdminSecurityOutcome
import dev.deskseed.eventpublication.DomainEventAppend
import dev.deskseed.eventpublication.DomainEventEnvelope
import dev.deskseed.eventpublication.DomainEventVisibility
import dev.deskseed.eventpublication.EventPublicationPort
import dev.deskseed.foundation.ActorType
import dev.deskseed.knowledge.CanonicalKnowledgeDocumentCodec
import dev.deskseed.knowledge.CanonicalKnowledgeDocumentValidator
import dev.deskseed.knowledge.CreateKnowledgeArticleDraft
import dev.deskseed.knowledge.KnowledgeAdminActor
import dev.deskseed.knowledge.KnowledgeAdministration
import dev.deskseed.knowledge.KnowledgeArticleLifecycle
import dev.deskseed.knowledge.KnowledgeArticleListFilter
import dev.deskseed.knowledge.KnowledgeArticleRevisionSummary
import dev.deskseed.knowledge.KnowledgeArticleSummary
import dev.deskseed.knowledge.KnowledgeArticleSummaryPage
import dev.deskseed.knowledge.KnowledgeArticleView
import dev.deskseed.knowledge.KnowledgeAudience
import dev.deskseed.knowledge.KnowledgeAudienceType
import dev.deskseed.knowledge.KnowledgeCategoryInput
import dev.deskseed.knowledge.KnowledgeCategoryView
import dev.deskseed.knowledge.KnowledgeConflictException
import dev.deskseed.knowledge.KnowledgeNotFoundException
import dev.deskseed.knowledge.KnowledgeLifecycleAction
import dev.deskseed.knowledge.KnowledgePreconditionFailedException
import dev.deskseed.knowledge.KnowledgeRevisionView
import dev.deskseed.knowledge.KnowledgeSearchIndexState
import dev.deskseed.knowledge.KnowledgeSearchIndexStatus
import dev.deskseed.knowledge.KnowledgeSectionInput
import dev.deskseed.knowledge.KnowledgeSectionView
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * The first write slice intentionally uses JDBC rather than exposing persistence entities.
 * Mutations, security audit, and durable event intent share the same transaction so a failed
 * required audit/event cannot leave a category, section, or draft behind.
 */
@Service
internal class JdbcKnowledgeAdministration(
    private val jdbc: JdbcTemplate,
    private val auditWriter: AdminSecurityAuditWriter,
    private val eventPublication: EventPublicationPort,
    private val objectMapper: ObjectMapper,
    private val cursorCodec: KnowledgeCursorCodec,
    private val clock: Clock,
) : KnowledgeAdministration {
    private val documentValidator = CanonicalKnowledgeDocumentValidator()
    private val documentCodec = CanonicalKnowledgeDocumentCodec()

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    override fun listCategories(actor: KnowledgeAdminActor): List<KnowledgeCategoryView> {
        val categories = jdbc.query(
            """
            select id, slug, title, description, status, display_order, version
              from knowledge_categories
             order by display_order, id
            """.trimIndent(),
            ::categoryView,
        )
        audit(
            eventType = "KNOWLEDGE_CATEGORY_LISTED",
            actor = actor,
            targetType = "KNOWLEDGE_CATEGORY_COLLECTION",
            targetId = null,
            metadata = mapOf("count" to categories.size.toString()),
        )
        return categories
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    override fun createCategory(input: KnowledgeCategoryInput, actor: KnowledgeAdminActor): KnowledgeCategoryView {
        val normalized = input.validated()
        val id = UUID.randomUUID()
        val now = Instant.now(clock)
        try {
            jdbc.update(
                """
                insert into knowledge_categories
                    (id, slug, title, description, status, display_order, version, created_at, updated_at, archived_at)
                values (?, ?, ?, ?, 'ACTIVE', ?, 0, ?, ?, null)
                """.trimIndent(),
                id,
                normalized.slug,
                normalized.title,
                normalized.description,
                normalized.displayOrder,
                Timestamp.from(now),
                Timestamp.from(now),
            )
        } catch (_: DuplicateKeyException) {
            throw KnowledgeConflictException("DUPLICATE_CATEGORY_SLUG_OR_ORDER")
        }
        audit(
            eventType = "KNOWLEDGE_CATEGORY_CREATED",
            actor = actor,
            targetType = "KNOWLEDGE_CATEGORY",
            targetId = id,
            metadata = mapOf("slug" to normalized.slug, "displayOrder" to normalized.displayOrder.toString()),
        )
        publish(
            type = "knowledge.category.created",
            subject = "knowledge-category:$id",
            actor = actor,
            data = mapOf("categoryId" to id.toString()),
            occurredAt = now,
        )
        return category(id)
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    override fun updateCategory(
        categoryId: UUID,
        input: KnowledgeCategoryInput,
        active: Boolean,
        expectedVersion: Long,
        actor: KnowledgeAdminActor,
    ): KnowledgeCategoryView {
        val current = lockedCategory(categoryId)
        if (current.version != expectedVersion) throw KnowledgePreconditionFailedException(current.version)
        val normalized = input.validated()
        val now = Instant.now(clock)
        try {
            jdbc.update(
                """
                update knowledge_categories
                   set slug = ?, title = ?, description = ?, status = ?, display_order = ?,
                       archived_at = ?, version = version + 1, updated_at = ?
                 where id = ? and version = ?
                """.trimIndent(),
                normalized.slug,
                normalized.title,
                normalized.description,
                if (active) "ACTIVE" else "ARCHIVED",
                normalized.displayOrder,
                if (active) null else Timestamp.from(now),
                Timestamp.from(now),
                categoryId,
                expectedVersion,
            ).also { changed -> if (changed != 1) throw KnowledgePreconditionFailedException(current.version) }
        } catch (_: DuplicateKeyException) {
            throw KnowledgeConflictException("DUPLICATE_CATEGORY_SLUG_OR_ORDER")
        }
        audit(
            "KNOWLEDGE_CATEGORY_UPDATED",
            actor,
            "KNOWLEDGE_CATEGORY",
            categoryId,
            mapOf("active" to active.toString()),
        )
        publish(
            "knowledge.category.updated",
            "knowledge-category:$categoryId",
            actor,
            mapOf("categoryId" to categoryId.toString(), "active" to active.toString()),
            now,
        )
        return category(categoryId)
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    override fun createSection(input: KnowledgeSectionInput, actor: KnowledgeAdminActor): KnowledgeSectionView {
        val normalized = input.validated()
        requireActiveCategory(normalized.categoryId)
        val id = UUID.randomUUID()
        val now = Instant.now(clock)
        try {
            jdbc.update(
                """
                insert into knowledge_sections
                    (id, category_id, slug, title, description, status, display_order, version, created_at, updated_at, archived_at)
                values (?, ?, ?, ?, ?, 'ACTIVE', ?, 0, ?, ?, null)
                """.trimIndent(),
                id,
                normalized.categoryId,
                normalized.slug,
                normalized.title,
                normalized.description,
                normalized.displayOrder,
                Timestamp.from(now),
                Timestamp.from(now),
            )
        } catch (_: DuplicateKeyException) {
            throw KnowledgeConflictException("DUPLICATE_SECTION_SLUG_OR_ORDER")
        }
        audit(
            eventType = "KNOWLEDGE_SECTION_CREATED",
            actor = actor,
            targetType = "KNOWLEDGE_SECTION",
            targetId = id,
            metadata = mapOf("categoryId" to normalized.categoryId.toString(), "slug" to normalized.slug),
        )
        publish(
            type = "knowledge.section.created",
            subject = "knowledge-section:$id",
            actor = actor,
            data = mapOf("sectionId" to id.toString(), "categoryId" to normalized.categoryId.toString()),
            occurredAt = now,
        )
        return section(id)
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    override fun updateSection(
        sectionId: UUID,
        input: KnowledgeSectionInput,
        active: Boolean,
        expectedVersion: Long,
        actor: KnowledgeAdminActor,
    ): KnowledgeSectionView {
        val current = lockedSection(sectionId)
        if (current.version != expectedVersion) throw KnowledgePreconditionFailedException(current.version)
        val normalized = input.validated()
        if (active) requireActiveCategory(normalized.categoryId)
        val now = Instant.now(clock)
        try {
            jdbc.update(
                """
                update knowledge_sections
                   set category_id = ?, slug = ?, title = ?, description = ?, status = ?, display_order = ?,
                       archived_at = ?, version = version + 1, updated_at = ?
                 where id = ? and version = ?
                """.trimIndent(),
                normalized.categoryId,
                normalized.slug,
                normalized.title,
                normalized.description,
                if (active) "ACTIVE" else "ARCHIVED",
                normalized.displayOrder,
                if (active) null else Timestamp.from(now),
                Timestamp.from(now),
                sectionId,
                expectedVersion,
            ).also { changed -> if (changed != 1) throw KnowledgePreconditionFailedException(current.version) }
        } catch (_: DuplicateKeyException) {
            throw KnowledgeConflictException("DUPLICATE_SECTION_SLUG_OR_ORDER")
        }
        audit(
            "KNOWLEDGE_SECTION_UPDATED",
            actor,
            "KNOWLEDGE_SECTION",
            sectionId,
            mapOf("active" to active.toString(), "categoryId" to normalized.categoryId.toString()),
        )
        publish(
            "knowledge.section.updated",
            "knowledge-section:$sectionId",
            actor,
            mapOf("sectionId" to sectionId.toString(), "active" to active.toString()),
            now,
        )
        return section(sectionId)
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    override fun createDraft(input: CreateKnowledgeArticleDraft, actor: KnowledgeAdminActor): KnowledgeArticleView {
        val normalized = input.validated()
        requireActiveSection(normalized.sectionId)
        requireAudienceGroupsActive(normalized.audience)
        val validatedDocument = documentValidator.validate(normalized.document, normalized.audience)
        val articleId = UUID.randomUUID()
        val revisionId = UUID.randomUUID()
        val now = Instant.now(clock)
        try {
            jdbc.update(
                """
                insert into knowledge_articles
                    (id, section_id, slug, lifecycle, audience_type, audience_version, current_published_revision_id,
                     author_id, reviewer_id, published_at, archived_at, version, created_at, updated_at)
                values (?, ?, ?, 'DRAFT', ?, 1, null, ?, null, null, null, 0, ?, ?)
                """.trimIndent(),
                articleId,
                normalized.sectionId,
                normalized.slug,
                normalized.audience.type.name,
                actor.staffId,
                Timestamp.from(now),
                Timestamp.from(now),
            )
            normalized.audience.groupIds.sortedBy(UUID::toString).forEach { groupId ->
                jdbc.update(
                    "insert into knowledge_article_audience_groups (article_id, group_id) values (?, ?)",
                    articleId,
                    groupId,
                )
            }
            jdbc.update(
                """
                insert into knowledge_article_revisions
                    (id, article_id, revision_number, title, document_json, plain_text, summary, change_note,
                     content_checksum, created_by_staff_id, created_at)
                values (?, ?, 1, ?, cast(? as jsonb), ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                revisionId,
                articleId,
                normalized.title,
                objectMapper.writeValueAsString(documentCodec.encode(validatedDocument.document)),
                validatedDocument.plainText,
                normalized.summary,
                normalized.changeNote,
                validatedDocument.checksumSha256,
                actor.staffId,
                Timestamp.from(now),
            )
        } catch (_: DuplicateKeyException) {
            throw KnowledgeConflictException("DUPLICATE_ARTICLE_SLUG")
        }
        audit(
            eventType = "KNOWLEDGE_ARTICLE_DRAFT_CREATED",
            actor = actor,
            targetType = "KNOWLEDGE_ARTICLE",
            targetId = articleId,
            metadata = mapOf(
                "sectionId" to normalized.sectionId.toString(),
                "audience" to normalized.audience.type.name,
                "revision" to "1",
            ),
        )
        publish(
            type = "knowledge.article.draft-created",
            subject = "knowledge-article:$articleId",
            actor = actor,
            data = mapOf(
                "articleId" to articleId.toString(),
                "lifecycle" to KnowledgeArticleLifecycle.DRAFT.name,
                "audience" to normalized.audience.type.name,
            ),
            occurredAt = now,
        )
        return article(articleId)
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    override fun listArticles(
        cursor: String?,
        filter: KnowledgeArticleListFilter,
        actor: KnowledgeAdminActor,
    ): KnowledgeArticleSummaryPage {
        val conditions = mutableListOf<String>()
        val arguments = mutableListOf<Any>()
        filter.lifecycle?.let {
            conditions += "article.lifecycle = ?"
            arguments += it.name
        }
        filter.sectionId?.let {
            conditions += "article.section_id = ?"
            arguments += it
        }
        filter.audience?.let {
            conditions += "article.audience_type = ?"
            arguments += it.name
        }
        val scope = if (filter.lifecycle == null && filter.sectionId == null && filter.audience == null) {
            "admin-articles"
        } else {
            listOf(
                "admin-articles",
                filter.lifecycle?.name ?: "all-lifecycles",
                filter.sectionId?.toString() ?: "all-sections",
                filter.audience?.name ?: "all-audiences",
            ).joinToString(":")
        }
        cursorCodec.decode(scope, cursor)?.let {
            conditions += "(article.created_at, article.id) < (?, ?)"
            arguments += Timestamp.from(it.createdAt)
            arguments += it.articleId
        }
        val whereClause = conditions.takeIf(List<String>::isNotEmpty)?.joinToString(" and ", prefix = "where ").orEmpty()
        val rows = jdbc.query(
            """
            select article.id, article.section_id, article.slug, article.lifecycle, article.audience_type,
                   article.audience_version, article.version, article.created_at,
                   revision.id as revision_id, revision.revision_number, revision.title, revision.summary,
                   revision.content_checksum, revision.created_at as revision_created_at,
                   array_agg(audience_group.group_id order by audience_group.group_id)
                       filter (where audience_group.group_id is not null) as audience_group_ids
              from knowledge_articles article
              left join knowledge_article_audience_groups audience_group on audience_group.article_id = article.id
              left join knowledge_article_revisions revision on revision.id = article.current_published_revision_id
              $whereClause
             group by article.id, article.section_id, article.slug, article.lifecycle, article.audience_type,
                      article.audience_version, article.version, article.created_at,
                      revision.id, revision.revision_number, revision.title, revision.summary,
                      revision.content_checksum, revision.created_at
             order by article.created_at desc, article.id desc
             limit 51
            """.trimIndent(),
            { row, _ ->
                val groupIds = row.getArray("audience_group_ids")?.array
                    ?.let { values -> (values as Array<*>).map { it as UUID }.toSet() }
                    .orEmpty()
                val revisionId = row.getObject("revision_id", UUID::class.java)
                AdminArticleListRow(
                    cursor = KnowledgeCursor(row.getTimestamp("created_at").toInstant(), row.getObject("id", UUID::class.java)),
                    summary = KnowledgeArticleSummary(
                        id = row.getObject("id", UUID::class.java),
                        sectionId = row.getObject("section_id", UUID::class.java),
                        slug = row.getString("slug"),
                        lifecycle = KnowledgeArticleLifecycle.valueOf(row.getString("lifecycle")),
                        audience = KnowledgeAudienceFactory.fromDatabase(
                            KnowledgeAudienceType.valueOf(row.getString("audience_type")),
                            groupIds,
                        ),
                        audienceVersion = row.getInt("audience_version"),
                        currentPublishedRevision = revisionId?.let {
                            KnowledgeArticleRevisionSummary(
                                id = it,
                                revisionNumber = row.getInt("revision_number"),
                                title = row.getString("title"),
                                summary = row.getString("summary"),
                                contentChecksum = row.getString("content_checksum"),
                                createdAt = checkNotNull(row.getTimestamp("revision_created_at")).toInstant(),
                            )
                        },
                        version = row.getLong("version"),
                    ),
                )
            },
            *arguments.toTypedArray(),
        )
        val items = rows.take(50).map(AdminArticleListRow::summary)
        audit(
            eventType = "KNOWLEDGE_ARTICLE_LISTED",
            actor = actor,
            targetType = "KNOWLEDGE_ARTICLE_COLLECTION",
            targetId = null,
            metadata = mapOf(
                "count" to items.size.toString(),
                "lifecycle" to (filter.lifecycle?.name ?: "ALL"),
                "sectionId" to (filter.sectionId?.toString() ?: "ALL"),
                "audience" to (filter.audience?.name ?: "ALL"),
            ),
        )
        return KnowledgeArticleSummaryPage(
            items = items,
            nextCursor = if (rows.size > items.size) {
                cursorCodec.encode(scope, rows[items.size - 1].cursor)
            } else null,
        )
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    override fun getArticle(articleId: UUID, actor: KnowledgeAdminActor): KnowledgeArticleView {
        val view = article(articleId)
        audit(
            eventType = "KNOWLEDGE_ARTICLE_VIEWED",
            actor = actor,
            targetType = "KNOWLEDGE_ARTICLE",
            targetId = articleId,
            metadata = emptyMap(),
        )
        return view
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    override fun updateDraft(
        articleId: UUID,
        input: CreateKnowledgeArticleDraft,
        expectedVersion: Long,
        actor: KnowledgeAdminActor,
    ): KnowledgeArticleView {
        val root = lockedArticle(articleId)
        if (root.version != expectedVersion) throw KnowledgePreconditionFailedException(root.version)
        require(root.lifecycle == KnowledgeArticleLifecycle.DRAFT) { "Only DRAFT articles can receive a new draft revision" }
        val normalized = input.validated()
        requireActiveSection(normalized.sectionId)
        requireAudienceGroupsActive(normalized.audience)
        val validatedDocument = documentValidator.validate(normalized.document, normalized.audience)
        val now = Instant.now(clock)
        val nextRevision = jdbc.queryForObject(
            "select coalesce(max(revision_number), 0) + 1 from knowledge_article_revisions where article_id = ?",
            Int::class.java,
            articleId,
        )!!
        val audienceChanged = root.audienceType != normalized.audience.type || audienceGroups(articleId) != normalized.audience.groupIds
        jdbc.update("delete from knowledge_article_audience_groups where article_id = ?", articleId)
        normalized.audience.groupIds.sortedBy(UUID::toString).forEach { groupId ->
            jdbc.update(
                "insert into knowledge_article_audience_groups (article_id, group_id) values (?, ?)",
                articleId,
                groupId,
            )
        }
        try {
            jdbc.update(
                """
                update knowledge_articles
                   set section_id = ?, slug = ?, audience_type = ?,
                       audience_version = audience_version + ?, version = version + 1, updated_at = ?
                 where id = ? and version = ?
                """.trimIndent(),
                normalized.sectionId,
                normalized.slug,
                normalized.audience.type.name,
                if (audienceChanged) 1 else 0,
                Timestamp.from(now),
                articleId,
                expectedVersion,
            ).also { changed -> if (changed != 1) throw KnowledgePreconditionFailedException(root.version) }
            jdbc.update(
                """
                insert into knowledge_article_revisions
                    (id, article_id, revision_number, title, document_json, plain_text, summary, change_note,
                     content_checksum, created_by_staff_id, created_at)
                values (?, ?, ?, ?, cast(? as jsonb), ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                UUID.randomUUID(),
                articleId,
                nextRevision,
                normalized.title,
                objectMapper.writeValueAsString(documentCodec.encode(validatedDocument.document)),
                validatedDocument.plainText,
                normalized.summary,
                normalized.changeNote,
                validatedDocument.checksumSha256,
                actor.staffId,
                Timestamp.from(now),
            )
        } catch (_: DuplicateKeyException) {
            throw KnowledgeConflictException("DUPLICATE_ARTICLE_SLUG")
        }
        audit(
            "KNOWLEDGE_ARTICLE_DRAFT_REVISION_CREATED",
            actor,
            "KNOWLEDGE_ARTICLE",
            articleId,
            mapOf("revision" to nextRevision.toString(), "audienceChanged" to audienceChanged.toString()),
        )
        publish(
            "knowledge.article.draft-revision-created",
            "knowledge-article:$articleId",
            actor,
            mapOf("articleId" to articleId.toString(), "revision" to nextRevision.toString()),
            now,
        )
        return article(articleId)
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    override fun listRevisions(articleId: UUID, actor: KnowledgeAdminActor): List<KnowledgeRevisionView> {
        article(articleId)
        val revisions = jdbc.query(
            """
            select id, revision_number, title, document_json::text as document_json, summary, change_note,
                   content_checksum, created_at
              from knowledge_article_revisions
             where article_id = ?
             order by revision_number desc
            """.trimIndent(),
            ::revisionView,
            articleId,
        )
        audit(
            eventType = "KNOWLEDGE_ARTICLE_REVISIONS_LISTED",
            actor = actor,
            targetType = "KNOWLEDGE_ARTICLE",
            targetId = articleId,
            metadata = mapOf("count" to revisions.size.toString()),
        )
        return revisions
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    override fun transition(
        articleId: UUID,
        action: KnowledgeLifecycleAction,
        expectedVersion: Long,
        actor: KnowledgeAdminActor,
    ): KnowledgeArticleView {
        val root = lockedArticle(articleId)
        if (root.version != expectedVersion) throw KnowledgePreconditionFailedException(root.version)
        val now = Instant.now(clock)
        val nextLifecycle = when (action) {
            KnowledgeLifecycleAction.SUBMIT_REVIEW -> {
                require(root.lifecycle == KnowledgeArticleLifecycle.DRAFT) { "Only DRAFT articles can enter review" }
                KnowledgeArticleLifecycle.IN_REVIEW
            }
            KnowledgeLifecycleAction.PUBLISH -> {
                require(root.lifecycle == KnowledgeArticleLifecycle.IN_REVIEW) { "Only IN_REVIEW articles can publish" }
                KnowledgeArticleLifecycle.PUBLISHED
            }
            KnowledgeLifecycleAction.UNPUBLISH -> {
                require(root.lifecycle == KnowledgeArticleLifecycle.PUBLISHED) { "Only PUBLISHED articles can unpublish" }
                KnowledgeArticleLifecycle.UNPUBLISHED
            }
            KnowledgeLifecycleAction.ARCHIVE -> {
                require(root.lifecycle != KnowledgeArticleLifecycle.ARCHIVED) { "ARCHIVED article cannot transition" }
                KnowledgeArticleLifecycle.ARCHIVED
            }
        }
        val publishedRevisionId = if (nextLifecycle == KnowledgeArticleLifecycle.PUBLISHED) {
            latestRevisionId(articleId)
        } else {
            null
        }
        jdbc.update(
            """
            update knowledge_articles
               set lifecycle = ?, current_published_revision_id = ?, reviewer_id = ?,
                   published_at = ?, archived_at = ?, version = version + 1, updated_at = ?
             where id = ? and version = ?
            """.trimIndent(),
            nextLifecycle.name,
            publishedRevisionId,
            if (action == KnowledgeLifecycleAction.PUBLISH) actor.staffId else root.reviewerId,
            if (nextLifecycle == KnowledgeArticleLifecycle.PUBLISHED) Timestamp.from(now) else null,
            if (nextLifecycle == KnowledgeArticleLifecycle.ARCHIVED) Timestamp.from(now) else null,
            Timestamp.from(now),
            articleId,
            expectedVersion,
        ).also { changed -> if (changed != 1) throw KnowledgePreconditionFailedException(root.version) }
        audit(
            eventType = "KNOWLEDGE_ARTICLE_LIFECYCLE_CHANGED",
            actor = actor,
            targetType = "KNOWLEDGE_ARTICLE",
            targetId = articleId,
            metadata = mapOf("action" to action.name, "lifecycle" to nextLifecycle.name),
        )
        publish(
            type = "knowledge.article.lifecycle-changed",
            subject = "knowledge-article:$articleId",
            actor = actor,
            data = mapOf("articleId" to articleId.toString(), "lifecycle" to nextLifecycle.name),
            occurredAt = now,
        )
        return article(articleId)
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    override fun replaceAudience(
        articleId: UUID,
        audience: KnowledgeAudience,
        expectedVersion: Long,
        actor: KnowledgeAdminActor,
    ): KnowledgeArticleView {
        val root = lockedArticle(articleId)
        if (root.version != expectedVersion) throw KnowledgePreconditionFailedException(root.version)
        requireAudienceGroupsActive(audience)
        val revisionId = if (root.lifecycle == KnowledgeArticleLifecycle.PUBLISHED) {
            root.currentPublishedRevisionId
                ?: throw KnowledgeNotFoundException("PUBLISHED_ARTICLE_REVISION_NOT_FOUND")
        } else {
            latestRevisionId(articleId)
        }
        documentValidator.validate(revision(revisionId).document, audience)
        val now = Instant.now(clock)
        jdbc.update("delete from knowledge_article_audience_groups where article_id = ?", articleId)
        audience.groupIds.sortedBy(UUID::toString).forEach { groupId ->
            jdbc.update(
                "insert into knowledge_article_audience_groups (article_id, group_id) values (?, ?)",
                articleId,
                groupId,
            )
        }
        jdbc.update(
            """
            update knowledge_articles
               set audience_type = ?, audience_version = audience_version + 1,
                   version = version + 1, updated_at = ?
             where id = ? and version = ?
            """.trimIndent(),
            audience.type.name,
            Timestamp.from(now),
            articleId,
            expectedVersion,
        ).also { changed -> if (changed != 1) throw KnowledgePreconditionFailedException(root.version) }
        audit(
            "KNOWLEDGE_ARTICLE_AUDIENCE_REPLACED",
            actor,
            "KNOWLEDGE_ARTICLE",
            articleId,
            mapOf("audience" to audience.type.name, "groupCount" to audience.groupIds.size.toString()),
        )
        publish(
            "knowledge.article.audience-replaced",
            "knowledge-article:$articleId",
            actor,
            mapOf("articleId" to articleId.toString(), "audience" to audience.type.name),
            now,
        )
        return article(articleId)
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    override fun searchIndexStatus(actor: KnowledgeAdminActor): KnowledgeSearchIndexStatus {
        val result = jdbc.query(
            """
            select status.state, status.last_rebuilt_at,
                   greatest(0, extract(epoch from now() - coalesce(max(document.indexed_at), status.updated_at)))::bigint as lag_seconds
              from knowledge_search_index_status status
              left join knowledge_search_documents document on true
             where status.singleton = true
             group by status.state, status.last_rebuilt_at, status.updated_at
            """.trimIndent(),
            { row, _ ->
                KnowledgeSearchIndexStatus(
                    state = KnowledgeSearchIndexState.valueOf(row.getString("state")),
                    lastRebuiltAt = row.getTimestamp("last_rebuilt_at")?.toInstant(),
                    lagSeconds = row.getLong("lag_seconds"),
                )
            },
        ).single()
        audit(
            "KNOWLEDGE_SEARCH_INDEX_STATUS_VIEWED",
            actor,
            "KNOWLEDGE_SEARCH_INDEX",
            null,
            mapOf("state" to result.state.name),
        )
        return result
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    override fun rebuildSearchIndex(actor: KnowledgeAdminActor) {
        val now = Instant.now(clock)
        jdbc.update(
            """
            insert into knowledge_search_documents (article_id, revision_id, search_document, indexed_at)
            select article.id, revision.id,
                   setweight(to_tsvector('simple', revision.title), 'A') ||
                       setweight(to_tsvector('simple', coalesce(revision.summary, '')), 'B') ||
                       setweight(to_tsvector('simple', revision.plain_text), 'C'),
                   ?
              from knowledge_articles article
              join knowledge_article_revisions revision on revision.id = article.current_published_revision_id
             where article.lifecycle = 'PUBLISHED'
            on conflict (article_id) do update
                set revision_id = excluded.revision_id,
                    search_document = excluded.search_document,
                    indexed_at = excluded.indexed_at
            """.trimIndent(),
            Timestamp.from(now),
        )
        jdbc.update(
            """
            delete from knowledge_search_documents document
             where not exists (
                select 1 from knowledge_articles article
                 where article.id = document.article_id and article.lifecycle = 'PUBLISHED'
                   and article.current_published_revision_id = document.revision_id
             )
            """.trimIndent(),
        )
        jdbc.update(
            """
            update knowledge_search_index_status
               set state = 'IDLE', last_rebuilt_at = ?, updated_at = ?
             where singleton = true
            """.trimIndent(),
            Timestamp.from(now),
            Timestamp.from(now),
        )
        audit(
            "KNOWLEDGE_SEARCH_INDEX_REBUILT",
            actor,
            "KNOWLEDGE_SEARCH_INDEX",
            null,
            emptyMap(),
        )
        publish(
            "knowledge.search-index.rebuilt",
            "knowledge-search-index:primary",
            actor,
            mapOf("rebuiltAt" to now.toString()),
            now,
        )
    }

    private fun requireActiveCategory(categoryId: UUID) {
        val active = jdbc.queryForObject(
            "select count(*) from knowledge_categories where id = ? and status = 'ACTIVE'",
            Long::class.java,
            categoryId,
        ) == 1L
        if (!active) throw KnowledgeNotFoundException("ACTIVE_CATEGORY_NOT_FOUND")
    }

    private fun requireActiveSection(sectionId: UUID) {
        val active = jdbc.queryForObject(
            """
            select count(*)
              from knowledge_sections section
              join knowledge_categories category on category.id = section.category_id
             where section.id = ? and section.status = 'ACTIVE' and category.status = 'ACTIVE'
            """.trimIndent(),
            Long::class.java,
            sectionId,
        ) == 1L
        if (!active) throw KnowledgeNotFoundException("ACTIVE_SECTION_NOT_FOUND")
    }

    private fun requireAudienceGroupsActive(audience: KnowledgeAudience) {
        audience.groupIds.forEach { groupId ->
            val active = jdbc.queryForObject(
                "select count(*) from support_groups where id = ? and status = 'ACTIVE'",
                Long::class.java,
                groupId,
            ) == 1L
            if (!active) throw KnowledgeNotFoundException("ACTIVE_AUDIENCE_GROUP_NOT_FOUND")
        }
    }

    private fun category(id: UUID): KnowledgeCategoryView = jdbc.query(
        """
        select id, slug, title, description, status, display_order, version
          from knowledge_categories where id = ?
        """.trimIndent(),
        ::categoryView,
        id,
    ).singleOrNull() ?: throw KnowledgeNotFoundException("CATEGORY_NOT_FOUND")

    private fun section(id: UUID): KnowledgeSectionView = jdbc.query(
        """
        select id, category_id, slug, title, description, status, display_order, version
          from knowledge_sections where id = ?
        """.trimIndent(),
        ::sectionView,
        id,
    ).singleOrNull() ?: throw KnowledgeNotFoundException("SECTION_NOT_FOUND")

    private fun article(id: UUID): KnowledgeArticleView = jdbc.query(
        """
        select id, section_id, slug, lifecycle, audience_type, audience_version, current_published_revision_id, version
          from knowledge_articles where id = ?
        """.trimIndent(),
        { row, _ ->
            KnowledgeArticleView(
                id = row.getObject("id", UUID::class.java),
                sectionId = row.getObject("section_id", UUID::class.java),
                slug = row.getString("slug"),
                lifecycle = KnowledgeArticleLifecycle.valueOf(row.getString("lifecycle")),
                audience = KnowledgeAudienceFactory.fromDatabase(
                    KnowledgeAudienceType.valueOf(row.getString("audience_type")),
                    audienceGroups(row.getObject("id", UUID::class.java)),
                ),
                audienceVersion = row.getInt("audience_version"),
                currentPublishedRevision = row.getObject("current_published_revision_id", UUID::class.java)
                    ?.let(::revision),
                version = row.getLong("version"),
            )
        },
        id,
    ).singleOrNull() ?: throw KnowledgeNotFoundException("ARTICLE_NOT_FOUND")

    private fun audienceGroups(articleId: UUID): Set<UUID> = jdbc.query(
        "select group_id from knowledge_article_audience_groups where article_id = ? order by group_id",
        { row, _ -> row.getObject("group_id", UUID::class.java) },
        articleId,
    ).toSet()

    private fun revision(id: UUID): KnowledgeRevisionView = jdbc.query(
        """
        select id, revision_number, title, document_json::text as document_json, summary, change_note,
               content_checksum, created_at
          from knowledge_article_revisions where id = ?
        """.trimIndent(),
        ::revisionView,
        id,
    ).singleOrNull() ?: throw KnowledgeNotFoundException("REVISION_NOT_FOUND")

    private fun revisionView(row: ResultSet, @Suppress("UNUSED_PARAMETER") index: Int) = KnowledgeRevisionView(
        id = row.getObject("id", UUID::class.java),
        revisionNumber = row.getInt("revision_number"),
        title = row.getString("title"),
        document = documentCodec.decodeJson(row.getString("document_json"), objectMapper),
        summary = row.getString("summary"),
        changeNote = row.getString("change_note"),
        contentChecksum = row.getString("content_checksum"),
        createdAt = row.getTimestamp("created_at").toInstant(),
    )

    private fun latestRevisionId(articleId: UUID): UUID = jdbc.query(
        """
        select id from knowledge_article_revisions
         where article_id = ?
         order by revision_number desc
         limit 1
        """.trimIndent(),
        { row, _ -> row.getObject("id", UUID::class.java) },
        articleId,
    ).singleOrNull() ?: throw KnowledgeNotFoundException("ARTICLE_REVISION_NOT_FOUND")

    private fun lockedArticle(articleId: UUID): LockedArticle = jdbc.query(
        """
        select id, lifecycle, audience_type, current_published_revision_id, version, reviewer_id
          from knowledge_articles
         where id = ?
         for update
        """.trimIndent(),
        { row, _ ->
            LockedArticle(
                id = row.getObject("id", UUID::class.java),
                lifecycle = KnowledgeArticleLifecycle.valueOf(row.getString("lifecycle")),
                audienceType = KnowledgeAudienceType.valueOf(row.getString("audience_type")),
                currentPublishedRevisionId = row.getObject("current_published_revision_id", UUID::class.java),
                version = row.getLong("version"),
                reviewerId = row.getObject("reviewer_id", UUID::class.java),
            )
        },
        articleId,
    ).singleOrNull() ?: throw KnowledgeNotFoundException("ARTICLE_NOT_FOUND")

    private fun lockedCategory(categoryId: UUID): LockedVersion = jdbc.query(
        "select version from knowledge_categories where id = ? for update",
        { row, _ -> LockedVersion(row.getLong("version")) },
        categoryId,
    ).singleOrNull() ?: throw KnowledgeNotFoundException("CATEGORY_NOT_FOUND")

    private fun lockedSection(sectionId: UUID): LockedVersion = jdbc.query(
        "select version from knowledge_sections where id = ? for update",
        { row, _ -> LockedVersion(row.getLong("version")) },
        sectionId,
    ).singleOrNull() ?: throw KnowledgeNotFoundException("SECTION_NOT_FOUND")

    private fun categoryView(row: ResultSet, @Suppress("UNUSED_PARAMETER") index: Int) = KnowledgeCategoryView(
        id = row.getObject("id", UUID::class.java),
        slug = row.getString("slug"),
        title = row.getString("title"),
        description = row.getString("description"),
        active = row.getString("status") == "ACTIVE",
        displayOrder = row.getInt("display_order"),
        version = row.getLong("version"),
    )

    private fun sectionView(row: ResultSet, @Suppress("UNUSED_PARAMETER") index: Int) = KnowledgeSectionView(
        id = row.getObject("id", UUID::class.java),
        categoryId = row.getObject("category_id", UUID::class.java),
        slug = row.getString("slug"),
        title = row.getString("title"),
        description = row.getString("description"),
        active = row.getString("status") == "ACTIVE",
        displayOrder = row.getInt("display_order"),
        version = row.getLong("version"),
    )

    private fun audit(
        eventType: String,
        actor: KnowledgeAdminActor,
        targetType: String,
        targetId: UUID?,
        metadata: Map<String, String>,
    ) {
        auditWriter.append(
            AdminSecurityAudit(
                eventType = eventType,
                actorType = ActorType.STAFF,
                actorId = actor.staffId,
                actorDisplaySnapshot = actor.displayName,
                source = actor.context.source,
                targetType = targetType,
                targetId = targetId,
                outcome = AdminSecurityOutcome.SUCCEEDED,
                requestId = actor.context.requestId,
                correlationId = actor.context.correlationId,
                metadata = metadata,
                occurredAt = Instant.now(clock),
            ),
        )
    }

    private fun publish(
        type: String,
        subject: String,
        actor: KnowledgeAdminActor,
        data: Map<String, String>,
        occurredAt: Instant,
    ) {
        eventPublication.append(
            DomainEventAppend(
                envelope = DomainEventEnvelope(
                    id = UUID.randomUUID(),
                    type = type,
                    version = 1,
                    occurredAt = occurredAt,
                    subject = subject,
                    sequence = null,
                    correlationId = actor.context.correlationId,
                    actorType = ActorType.STAFF,
                    actorId = actor.staffId,
                    source = actor.context.source,
                    requestId = actor.context.requestId,
                    commandId = actor.context.commandId,
                    data = data,
                ),
                visibility = DomainEventVisibility.INTERNAL,
            ),
        )
    }

    private fun KnowledgeCategoryInput.validated(): KnowledgeCategoryInput = copy(
        slug = slug.validSlug(),
        title = title.requiredText(200, "category title"),
        description = description.optionalText(1000, "category description"),
    ).also { require(it.displayOrder >= 0) { "category display order must not be negative" } }

    private fun KnowledgeSectionInput.validated(): KnowledgeSectionInput = copy(
        slug = slug.validSlug(),
        title = title.requiredText(200, "section title"),
        description = description.optionalText(1000, "section description"),
    ).also { require(it.displayOrder >= 0) { "section display order must not be negative" } }

    private fun CreateKnowledgeArticleDraft.validated(): CreateKnowledgeArticleDraft = copy(
        slug = slug.validSlug(),
        title = title.requiredText(300, "article title"),
        summary = summary.optionalText(1000, "article summary"),
        changeNote = changeNote.optionalText(1000, "article change note"),
    )

    private fun String.validSlug(): String = trim().also {
        require(this == it && SLUG.matches(it)) { "knowledge slug is invalid" }
    }

    private fun String.requiredText(max: Int, field: String): String = trim().also {
        require(it.isNotEmpty() && it.length <= max && it.none(Char::isISOControl)) { "$field is invalid" }
    }

    private fun String.optionalText(max: Int, field: String): String = trim().also {
        require(it.length <= max && it.none(Char::isISOControl)) { "$field is invalid" }
    }

    private companion object {
        val SLUG = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
    }

    private data class LockedArticle(
        val id: UUID,
        val lifecycle: KnowledgeArticleLifecycle,
        val audienceType: KnowledgeAudienceType,
        val currentPublishedRevisionId: UUID?,
        val version: Long,
        val reviewerId: UUID?,
    )

    private data class AdminArticleListRow(
        val cursor: KnowledgeCursor,
        val summary: KnowledgeArticleSummary,
    )

    private data class LockedVersion(val version: Long)
}

private object KnowledgeAudienceFactory {
    fun fromDatabase(type: KnowledgeAudienceType, groupIds: Set<UUID>): KnowledgeAudience = when (type) {
        KnowledgeAudienceType.PUBLIC -> KnowledgeAudience.public()
        KnowledgeAudienceType.SIGNED_IN_CUSTOMER -> KnowledgeAudience.signedInCustomer()
        KnowledgeAudienceType.STAFF -> KnowledgeAudience.staff()
        KnowledgeAudienceType.SELECTED_STAFF_GROUPS -> KnowledgeAudience.selectedStaffGroups(groupIds)
    }
}
