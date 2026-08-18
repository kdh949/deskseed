package dev.deskseed.knowledge.internal

import dev.deskseed.audit.AccessAuditProtectionException
import dev.deskseed.audit.AccessAuditSessionFingerprint
import dev.deskseed.audit.SearchQueryProtector
import dev.deskseed.foundation.RequestSource
import dev.deskseed.knowledge.CanonicalKnowledgeDocumentCodec
import dev.deskseed.knowledge.KnowledgeAccessAuditUnavailableException
import dev.deskseed.knowledge.KnowledgeAgentReadContext
import dev.deskseed.knowledge.KnowledgeArticleListing
import dev.deskseed.knowledge.KnowledgeAudience
import dev.deskseed.knowledge.KnowledgeAudienceType
import dev.deskseed.knowledge.KnowledgeNavigationCategory
import dev.deskseed.knowledge.KnowledgeNavigationSection
import dev.deskseed.knowledge.KnowledgeNotFoundException
import dev.deskseed.knowledge.KnowledgeReader
import dev.deskseed.knowledge.KnowledgeReading
import dev.deskseed.knowledge.KnowledgeRevisionView
import dev.deskseed.knowledge.KnowledgeSearchHit
import dev.deskseed.knowledge.KnowledgeSearchPage
import dev.deskseed.knowledge.KnowledgeSearchQuery
import dev.deskseed.knowledge.PublishedKnowledgeArticle
import org.springframework.dao.DataAccessException
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
 * Permission filtering happens in each SQL query before result counts, snippets, or cursors are
 * calculated. In particular, selected-group membership is checked against active memberships in
 * the database rather than trusting a browser-provided group list.
 */
@Service
internal class JdbcKnowledgeReading(
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val cursorCodec: KnowledgeCursorCodec,
    private val queryProtector: SearchQueryProtector,
    private val sessionFingerprint: AccessAuditSessionFingerprint,
    private val clock: Clock,
) : KnowledgeReading {
    private val documentCodec = CanonicalKnowledgeDocumentCodec()

    @Transactional(readOnly = true)
    override fun listCategories(reader: KnowledgeReader): List<KnowledgeNavigationCategory> {
        val audience = audienceSql(reader)
        return jdbc.query(
            """
            select category.id, category.slug, category.title, category.description
              from knowledge_categories category
             where category.status = 'ACTIVE'
               and exists (
                    select 1
                      from knowledge_sections section
                      join knowledge_articles article on article.section_id = section.id
                     where section.category_id = category.id
                       and section.status = 'ACTIVE'
                       and article.lifecycle = 'PUBLISHED'
                       and ${audience.predicate}
               )
             order by category.display_order, category.id
            """.trimIndent(),
            { row, _ -> navigationCategory(row) },
            *audience.args.toTypedArray(),
        )
    }

    @Transactional(readOnly = true)
    override fun getCategory(categorySlug: String, reader: KnowledgeReader): KnowledgeNavigationCategory {
        val slug = slug(categorySlug)
        val audience = audienceSql(reader)
        val category = jdbc.query(
            """
            select category.id, category.slug, category.title, category.description
              from knowledge_categories category
             where category.status = 'ACTIVE' and category.slug = ?
               and exists (
                    select 1
                      from knowledge_sections section
                      join knowledge_articles article on article.section_id = section.id
                     where section.category_id = category.id
                       and section.status = 'ACTIVE'
                       and article.lifecycle = 'PUBLISHED'
                       and ${audience.predicate}
               )
            """.trimIndent(),
            { row, _ -> navigationCategory(row) },
            slug,
            *audience.args.toTypedArray(),
        ).singleOrNull() ?: throw KnowledgeNotFoundException("KNOWLEDGE_CATEGORY_NOT_FOUND")
        return category.copy(sections = visibleSections(category.id, audience))
    }

    @Transactional(readOnly = true)
    override fun getSection(sectionSlug: String, cursor: String?, reader: KnowledgeReader): KnowledgeNavigationSection {
        val slug = slug(sectionSlug)
        val audience = audienceSql(reader)
        val section = jdbc.query(
            """
            select section.id, section.category_id, section.slug, section.title, section.description
              from knowledge_sections section
              join knowledge_categories category on category.id = section.category_id
             where section.status = 'ACTIVE' and category.status = 'ACTIVE' and section.slug = ?
               and exists (
                    select 1 from knowledge_articles article
                     where article.section_id = section.id and article.lifecycle = 'PUBLISHED'
                       and ${audience.predicate}
               )
            """.trimIndent(),
            { row, _ -> navigationSection(row) },
            slug,
            *audience.args.toTypedArray(),
        ).singleOrNull() ?: throw KnowledgeNotFoundException("KNOWLEDGE_SECTION_NOT_FOUND")
        val page = articleListings(section.id, "help-section:${section.slug}:${readerScope(reader)}", cursor, audience)
        return section.copy(articles = page.items, nextCursor = page.nextCursor)
    }

    @Transactional(readOnly = true)
    override fun getArticle(articleSlug: String, reader: KnowledgeReader): PublishedKnowledgeArticle =
        publishedArticle(slug(articleSlug), audienceSql(reader))
            ?: throw KnowledgeNotFoundException("KNOWLEDGE_ARTICLE_NOT_FOUND")

    @Transactional(readOnly = true)
    override fun search(query: KnowledgeSearchQuery, reader: KnowledgeReader): KnowledgeSearchPage {
        val normalized = query.normalized()
        return searchInternal(normalized, audienceSql(reader), queryScope("help-search:${readerScope(reader)}", normalized.query))
    }

    @Transactional
    override fun recordFeedback(articleSlug: String, helpful: Boolean, reader: KnowledgeReader) {
        val article = publishedArticle(slug(articleSlug), audienceSql(reader))
            ?: throw KnowledgeNotFoundException("KNOWLEDGE_ARTICLE_NOT_FOUND")
        val now = Timestamp.from(Instant.now(clock))
        jdbc.update(
            """
            insert into knowledge_article_feedback_totals
                (article_id, helpful_count, not_helpful_count, updated_at)
            values (?, ?, ?, ?)
            on conflict (article_id) do update
              set helpful_count = knowledge_article_feedback_totals.helpful_count + excluded.helpful_count,
                  not_helpful_count = knowledge_article_feedback_totals.not_helpful_count + excluded.not_helpful_count,
                  updated_at = excluded.updated_at
            """.trimIndent(),
            article.id,
            if (helpful) 1 else 0,
            if (helpful) 0 else 1,
            now,
        )
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'AGENT')")
    @Transactional
    override fun searchForAgent(context: KnowledgeAgentReadContext, query: KnowledgeSearchQuery): KnowledgeSearchPage {
        val normalized = query.normalized()
        val page = searchInternal(
            normalized,
            audienceSql(staffReader(context.staffId)),
            queryScope("agent-search:${context.staffId}", normalized.query),
        )
        appendSearchAudit("KNOWLEDGE_SEARCH_EXECUTED", context, normalized.query, page.items.size, null)
        return page
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'AGENT')")
    @Transactional
    override fun getArticleForAgent(context: KnowledgeAgentReadContext, articleSlug: String): PublishedKnowledgeArticle {
        val article = publishedArticle(slug(articleSlug), audienceSql(staffReader(context.staffId)))
            ?: throw KnowledgeNotFoundException("KNOWLEDGE_ARTICLE_NOT_FOUND")
        appendArticleAudit(context, article.id)
        return article
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'AGENT')")
    @Transactional
    override fun suggestionsForAgent(
        context: KnowledgeAgentReadContext,
        ticketNumber: Long,
        boundedPublicQuery: String,
    ): KnowledgeSearchPage {
        require(ticketNumber > 0) { "ticket number must be positive" }
        val normalized = KnowledgeSearchQuery(query = boundedPublicQuery, limit = 20).normalized()
        val page = searchInternal(
            normalized,
            audienceSql(staffReader(context.staffId)),
            queryScope("ticket-suggestions:${context.staffId}:$ticketNumber", normalized.query),
        )
        appendSearchAudit("TICKET_KNOWLEDGE_SUGGESTED", context, normalized.query, page.items.size, ticketNumber)
        return page
    }

    private fun visibleSections(categoryId: UUID, audience: AudienceSql): List<KnowledgeNavigationSection> = jdbc.query(
        """
        select section.id, section.category_id, section.slug, section.title, section.description
          from knowledge_sections section
         where section.category_id = ? and section.status = 'ACTIVE'
           and exists (
                select 1 from knowledge_articles article
                 where article.section_id = section.id and article.lifecycle = 'PUBLISHED'
                   and ${audience.predicate}
           )
         order by section.display_order, section.id
        """.trimIndent(),
        { row, _ -> navigationSection(row) },
        categoryId,
        *audience.args.toTypedArray(),
    )

    private fun articleListings(
        sectionId: UUID,
        scope: String,
        cursor: String?,
        audience: AudienceSql,
    ): ListingPage {
        val decoded = cursorCodec.decode(scope, cursor)
        val args = mutableListOf<Any>(sectionId)
        args.addAll(audience.args)
        val boundary = decoded?.let {
            args += Timestamp.from(it.createdAt)
            args += it.articleId
            "and (revision.created_at, article.id) < (?, ?)"
        }.orEmpty()
        val rows = jdbc.query(
            """
            select article.id, article.slug, article.audience_type, revision.title, revision.summary, revision.created_at
              from knowledge_articles article
              join knowledge_article_revisions revision on revision.id = article.current_published_revision_id
             where article.section_id = ? and article.lifecycle = 'PUBLISHED'
               and ${audience.predicate}
               $boundary
             order by revision.created_at desc, article.id desc
             limit 51
            """.trimIndent(),
            { row, _ -> ListingRow(row) },
            *args.toTypedArray(),
        )
        val visible = rows.take(50)
        return ListingPage(
            items = visible.map { KnowledgeArticleListing(it.slug, it.title, it.summary, it.audience) },
            nextCursor = if (rows.size > visible.size) visible.last().let { cursorCodec.encode(scope, KnowledgeCursor(it.createdAt, it.id)) } else null,
        )
    }

    private fun searchInternal(query: KnowledgeSearchQuery, audience: AudienceSql, scope: String): KnowledgeSearchPage {
        val decoded = cursorCodec.decode(scope, query.cursor)
        val args = mutableListOf<Any>(query.query)
        args.addAll(audience.args)
        val boundary = decoded?.let {
            args += Timestamp.from(it.createdAt)
            args += it.articleId
            "and (revision.created_at, article.id) < (?, ?)"
        }.orEmpty()
        val rows = jdbc.query(
            """
            select article.id, article.slug, article.audience_type, revision.title, revision.plain_text,
                   category.title as category_title, section.title as section_title, revision.created_at
              from knowledge_search_documents document
              join knowledge_articles article on article.id = document.article_id
              join knowledge_article_revisions revision on revision.id = document.revision_id
              join knowledge_sections section on section.id = article.section_id
              join knowledge_categories category on category.id = section.category_id
             where article.lifecycle = 'PUBLISHED'
               and section.status = 'ACTIVE' and category.status = 'ACTIVE'
               and document.search_document @@ websearch_to_tsquery('simple', ?)
               and ${audience.predicate}
               $boundary
             order by revision.created_at desc, article.id desc
             limit ?
            """.trimIndent(),
            { row, _ -> SearchRow(row) },
            *(args + (query.limit + 1)).toTypedArray(),
        )
        val visible = rows.take(query.limit)
        return KnowledgeSearchPage(
            items = visible.map {
                KnowledgeSearchHit(
                    articleSlug = it.slug,
                    title = it.title,
                    excerpt = excerpt(it.plainText),
                    audience = it.audience,
                    categoryTitle = it.categoryTitle,
                    sectionTitle = it.sectionTitle,
                )
            },
            nextCursor = if (rows.size > visible.size) visible.last().let { cursorCodec.encode(scope, KnowledgeCursor(it.createdAt, it.id)) } else null,
        )
    }

    private fun publishedArticle(articleSlug: String, audience: AudienceSql): PublishedKnowledgeArticle? {
        val rows = jdbc.query(
            """
            select article.id, article.section_id, article.slug, article.audience_type, article.audience_version,
                   article.published_at, revision.id as revision_id, revision.revision_number, revision.title,
                   revision.document_json::text as document_json, revision.summary, revision.change_note,
                   revision.content_checksum, revision.created_at
              from knowledge_articles article
              join knowledge_article_revisions revision on revision.id = article.current_published_revision_id
              join knowledge_sections section on section.id = article.section_id
              join knowledge_categories category on category.id = section.category_id
             where article.slug = ? and article.lifecycle = 'PUBLISHED'
               and section.status = 'ACTIVE' and category.status = 'ACTIVE'
               and ${audience.predicate}
            """.trimIndent(),
            { row, _ -> publishedArticle(row) },
            articleSlug,
            *audience.args.toTypedArray(),
        )
        return rows.singleOrNull()
    }

    private fun staffReader(staffId: UUID): KnowledgeReader.Staff = KnowledgeReader.Staff(
        jdbc.query(
            """
            select membership.group_id
              from group_memberships membership
              join support_groups support_group on support_group.id = membership.group_id
             where membership.staff_id = ? and membership.status = 'ACTIVE' and support_group.status = 'ACTIVE'
            """.trimIndent(),
            { row, _ -> row.getObject("group_id", UUID::class.java) },
            staffId,
        ).toSet(),
    )

    private fun appendSearchAudit(
        eventType: String,
        context: KnowledgeAgentReadContext,
        rawQuery: String,
        resultCount: Int,
        ticketNumber: Long?,
    ) {
        try {
            val occurredAt = Instant.now(clock)
            val eventId = UUID.randomUUID()
            val protected = queryProtector.protect(eventId, rawQuery, occurredAt)
            jdbc.update(
                """
                insert into knowledge_access_audit_events
                    (id, event_type, actor_id, actor_display_snapshot, source, session_fingerprint,
                     article_id, ticket_number, query_redacted, query_fingerprint, query_key_version,
                     query_ciphertext, query_expires_at, result_count, request_id, correlation_id, occurred_at)
                values (?, ?, ?, ?, ?, ?, null, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                eventId,
                eventType,
                context.staffId,
                context.staffDisplayName.take(100),
                RequestSource.AGENT_UI.name,
                sessionFingerprint.fingerprint(context.sessionId),
                ticketNumber,
                protected.queryRedacted,
                protected.queryFingerprint,
                protected.keyVersion,
                protected.queryCiphertext,
                Timestamp.from(protected.expiresAt),
                resultCount.toLong(),
                context.commandContext.requestId,
                context.commandContext.correlationId,
                Timestamp.from(occurredAt),
            )
        } catch (exception: DataAccessException) {
            throw KnowledgeAccessAuditUnavailableException(exception)
        } catch (exception: AccessAuditProtectionException) {
            throw KnowledgeAccessAuditUnavailableException(exception)
        }
    }

    private fun appendArticleAudit(context: KnowledgeAgentReadContext, articleId: UUID) {
        try {
            jdbc.update(
                """
                insert into knowledge_access_audit_events
                    (id, event_type, actor_id, actor_display_snapshot, source, session_fingerprint,
                     article_id, ticket_number, query_redacted, query_fingerprint, query_key_version,
                     query_ciphertext, query_expires_at, result_count, request_id, correlation_id, occurred_at)
                values (?, 'KNOWLEDGE_ARTICLE_VIEWED', ?, ?, ?, ?, ?, null, null, null, null, null, null, null, ?, ?, ?)
                """.trimIndent(),
                UUID.randomUUID(),
                context.staffId,
                context.staffDisplayName.take(100),
                RequestSource.AGENT_UI.name,
                sessionFingerprint.fingerprint(context.sessionId),
                articleId,
                context.commandContext.requestId,
                context.commandContext.correlationId,
                Timestamp.from(Instant.now(clock)),
            )
        } catch (exception: DataAccessException) {
            throw KnowledgeAccessAuditUnavailableException(exception)
        }
    }

    private fun audienceSql(reader: KnowledgeReader): AudienceSql = when (reader) {
        KnowledgeReader.AnonymousCustomer -> AudienceSql("article.audience_type = 'PUBLIC'", emptyList())
        KnowledgeReader.SignedInCustomer -> AudienceSql(
            "article.audience_type in ('PUBLIC', 'SIGNED_IN_CUSTOMER')",
            emptyList(),
        )
        is KnowledgeReader.Staff -> audienceSqlForActiveGroups(reader.activeGroupIds)
    }

    private fun audienceSqlForActiveGroups(activeGroupIds: Set<UUID>): AudienceSql {
        val base = "article.audience_type in ('PUBLIC', 'SIGNED_IN_CUSTOMER', 'STAFF')"
        if (activeGroupIds.isEmpty()) return AudienceSql(base, emptyList())
        val placeholders = activeGroupIds.joinToString(",") { "?" }
        return AudienceSql(
            """($base or (article.audience_type = 'SELECTED_STAFF_GROUPS' and exists (
                select 1 from knowledge_article_audience_groups audience_group
                 where audience_group.article_id = article.id and audience_group.group_id in ($placeholders)
            )))""",
            activeGroupIds.sortedBy(UUID::toString),
        )
    }

    private fun readerScope(reader: KnowledgeReader): String = when (reader) {
        KnowledgeReader.AnonymousCustomer -> "anonymous"
        KnowledgeReader.SignedInCustomer -> "customer"
        is KnowledgeReader.Staff -> "staff"
    }

    private fun queryScope(prefix: String, query: String): String = "$prefix:" + java.security.MessageDigest
        .getInstance("SHA-256")
        .digest(query.toByteArray(Charsets.UTF_8))
        .take(12)
        .joinToString("") { "%02x".format(it) }

    private fun navigationCategory(row: ResultSet) = KnowledgeNavigationCategory(
        id = row.getObject("id", UUID::class.java),
        slug = row.getString("slug"),
        title = row.getString("title"),
        description = row.getString("description"),
    )

    private fun navigationSection(row: ResultSet) = KnowledgeNavigationSection(
        id = row.getObject("id", UUID::class.java),
        categoryId = row.getObject("category_id", UUID::class.java),
        slug = row.getString("slug"),
        title = row.getString("title"),
        description = row.getString("description"),
    )

    private fun publishedArticle(row: ResultSet): PublishedKnowledgeArticle {
        val articleId = row.getObject("id", UUID::class.java)
        val audience = audience(
            KnowledgeAudienceType.valueOf(row.getString("audience_type")),
            audienceGroups(articleId),
        )
        return PublishedKnowledgeArticle(
            id = articleId,
            sectionId = row.getObject("section_id", UUID::class.java),
            slug = row.getString("slug"),
            audience = audience,
            audienceVersion = row.getInt("audience_version"),
            revision = KnowledgeRevisionView(
                id = row.getObject("revision_id", UUID::class.java),
                revisionNumber = row.getInt("revision_number"),
                title = row.getString("title"),
                document = documentCodec.decodeJson(row.getString("document_json"), objectMapper),
                summary = row.getString("summary"),
                changeNote = row.getString("change_note"),
                contentChecksum = row.getString("content_checksum"),
                createdAt = row.getTimestamp("created_at").toInstant(),
            ),
            publishedAt = row.getTimestamp("published_at").toInstant(),
        )
    }

    private fun audienceGroups(articleId: UUID): Set<UUID> = jdbc.query(
        "select group_id from knowledge_article_audience_groups where article_id = ? order by group_id",
        { row, _ -> row.getObject("group_id", UUID::class.java) },
        articleId,
    ).toSet()

    private fun audience(type: KnowledgeAudienceType, groups: Set<UUID>): KnowledgeAudience = when (type) {
        KnowledgeAudienceType.PUBLIC -> KnowledgeAudience.public()
        KnowledgeAudienceType.SIGNED_IN_CUSTOMER -> KnowledgeAudience.signedInCustomer()
        KnowledgeAudienceType.STAFF -> KnowledgeAudience.staff()
        KnowledgeAudienceType.SELECTED_STAFF_GROUPS -> KnowledgeAudience.selectedStaffGroups(groups)
    }

    private fun KnowledgeSearchQuery.normalized(): KnowledgeSearchQuery = copy(
        query = query.trim().also {
            require(it.isNotEmpty() && it.length <= 512 && it.none(Char::isISOControl)) { "Knowledge query is invalid" }
        },
    ).also { require(it.limit in 1..50) { "Knowledge search limit is invalid" } }

    private fun slug(value: String): String = value.trim().also {
        require(SLUG.matches(it)) { "Knowledge slug is invalid" }
    }

    private fun excerpt(plainText: String): String = plainText.replace(Regex("\\s+"), " ").trim().take(300)

    private data class AudienceSql(val predicate: String, val args: List<Any>)
    private data class ListingRow(
        val id: UUID,
        val slug: String,
        val audience: KnowledgeAudienceType,
        val title: String,
        val summary: String,
        val createdAt: Instant,
    ) {
        constructor(row: ResultSet) : this(
            row.getObject("id", UUID::class.java),
            row.getString("slug"),
            KnowledgeAudienceType.valueOf(row.getString("audience_type")),
            row.getString("title"),
            row.getString("summary"),
            row.getTimestamp("created_at").toInstant(),
        )
    }

    private data class SearchRow(
        val id: UUID,
        val slug: String,
        val audience: KnowledgeAudienceType,
        val title: String,
        val plainText: String,
        val categoryTitle: String,
        val sectionTitle: String,
        val createdAt: Instant,
    ) {
        constructor(row: ResultSet) : this(
            row.getObject("id", UUID::class.java),
            row.getString("slug"),
            KnowledgeAudienceType.valueOf(row.getString("audience_type")),
            row.getString("title"),
            row.getString("plain_text"),
            row.getString("category_title"),
            row.getString("section_title"),
            row.getTimestamp("created_at").toInstant(),
        )
    }

    private data class ListingPage(val items: List<KnowledgeArticleListing>, val nextCursor: String?)

    private companion object {
        val SLUG = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
    }
}
