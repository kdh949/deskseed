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
import dev.deskseed.knowledge.KnowledgeArticleView
import dev.deskseed.knowledge.KnowledgeAudience
import dev.deskseed.knowledge.KnowledgeAudienceType
import dev.deskseed.knowledge.KnowledgeCategoryInput
import dev.deskseed.knowledge.KnowledgeCategoryView
import dev.deskseed.knowledge.KnowledgeConflictException
import dev.deskseed.knowledge.KnowledgeNotFoundException
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
        select id, section_id, slug, lifecycle, audience_type, audience_version, version
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
                currentPublishedRevision = null,
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
}

private object KnowledgeAudienceFactory {
    fun fromDatabase(type: KnowledgeAudienceType, groupIds: Set<UUID>): KnowledgeAudience = when (type) {
        KnowledgeAudienceType.PUBLIC -> KnowledgeAudience.public()
        KnowledgeAudienceType.SIGNED_IN_CUSTOMER -> KnowledgeAudience.signedInCustomer()
        KnowledgeAudienceType.STAFF -> KnowledgeAudience.staff()
        KnowledgeAudienceType.SELECTED_STAFF_GROUPS -> KnowledgeAudience.selectedStaffGroups(groupIds)
    }
}
