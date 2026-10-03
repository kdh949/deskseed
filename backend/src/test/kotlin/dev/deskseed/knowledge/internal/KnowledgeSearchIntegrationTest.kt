package dev.deskseed.knowledge.internal

import dev.deskseed.knowledge.KnowledgeReader
import dev.deskseed.knowledge.KnowledgeReading
import dev.deskseed.knowledge.KnowledgeSearchQuery
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@dev.deskseed.testsupport.integration.DeskseedSpringIntegrationTest
@AutoConfigureMockMvc
@dev.deskseed.testsupport.category.IntegrationTest
class KnowledgeSearchIntegrationTest {
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var mapper: ObjectMapper
    @Autowired private lateinit var reading: KnowledgeReading
    @Autowired private lateinit var cursorCodec: KnowledgeCursorCodec
    private val staffId = UUID.fromString("10000000-0000-0000-0000-000000000011")
    private val categoryId = UUID.fromString("20000000-0000-0000-0000-000000000011")
    private val sectionId = UUID.fromString("30000000-0000-0000-0000-000000000011")
    private val createdAt = Instant.parse("2026-09-01T00:00:00Z")

    @BeforeEach
    fun prepareCorpus() {
        jdbc.execute("truncate table knowledge_categories, staff_accounts cascade")
        jdbc.update("""
            insert into staff_accounts (id, email_normalized, email_display, display_name, role, status,
                password_hash, created_at, updated_at, version)
            values (?, 'kb-fixture@example.test', 'kb-fixture@example.test', 'KB fixture', 'ADMIN', 'ACTIVE',
                'unused-test-hash', clock_timestamp(), clock_timestamp(), 0)
        """.trimIndent(), staffId)
        jdbc.update("""
            insert into knowledge_categories (id, slug, title, status, display_order, created_at, updated_at)
            values (?, 'account', '계정', 'ACTIVE', 1, clock_timestamp(), clock_timestamp())
        """.trimIndent(), categoryId)
        jdbc.update("""
            insert into knowledge_sections (id, category_id, slug, title, status, display_order, created_at, updated_at)
            values (?, ?, 'login', '로그인', 'ACTIVE', 1, clock_timestamp(), clock_timestamp())
        """.trimIndent(), sectionId, categoryId)
    }

    @Test
    fun `weighted title and summary outrank recent body boilerplate with contextual plain excerpts`() {
        article(1, "title-match", "비밀번호 재설정", "계정 복구", "이메일로 재설정합니다.")
        article(2, "summary-match", "계정 복구", "비밀번호 복구 안내", "이메일을 확인합니다.")
        article(3, "body-match", "배송 도움말", "배송 안내", "안내 사항 ".repeat(90) + "비밀번호 원문은 보내지 않습니다. 문의를 접수하세요.", createdAt.plusSeconds(60))
        val result = search("비밀번호")
        assertThat(slugs(result))
            .containsExactly("title-match", "summary-match", "body-match")
        val excerpt = result["items"][2]["excerpt"].asText()
        assertThat(excerpt).contains("비밀번호").doesNotContain("<b>", "</b>")
        assertThat(excerpt.codePointCount(0, excerpt.length)).isLessThanOrEqualTo(240)
        assertThat(result["items"][0].has("rank")).isFalse()
    }

    @Test
    fun `rank cursor traverses tied rows and excludes hidden audience from every page`() {
        (1..5).forEach { article(it, "visible-$it", "password reset", "계정 안내", "email reset instructions") }
        article(6, "staff-hidden", "password password reset", "private sentinel", "private sentinel", audience = "STAFF")
        article(7, "group-hidden", "password password reset", "group sentinel", "group sentinel", audience = "SELECTED_STAFF_GROUPS")
        article(8, "customer-hidden", "password password reset", "customer sentinel", "customer sentinel", audience = "SIGNED_IN_CUSTOMER")
        val visited = mutableListOf<String>()
        var cursor: String? = null
        do {
            val page = search("password", cursor, 2)
            assertThat(page.toString()).doesNotContain("sentinel", "hidden")
            visited += slugs(page)
            cursor = page["nextCursor"]?.takeUnless { it.isNull }?.asText()
            assertThat(page["hasMore"].asBoolean()).isEqualTo(cursor != null)
        } while (cursor != null)
        assertThat(visited).containsExactly("visible-5", "visible-4", "visible-3", "visible-2", "visible-1")
        val first = search("password", limit = 1)["nextCursor"].asText()
        searchRejected("reset", first)
        val fingerprint = java.security.MessageDigest.getInstance("SHA-256").digest("password".toByteArray())
            .take(12).joinToString("") { "%02x".format(it) }
        val legacy = cursorCodec.encode("help-search:anonymous:$fingerprint", KnowledgeCursor(createdAt, UUID(0, 5)))
        searchRejected("password", legacy)
        val parts = first.split('.')
        val changed = parts[2].replaceRange(0, 1, if (parts[2].first() == 'A') "B" else "A")
        searchRejected("password", "${parts[0]}.${parts[1]}.$changed")
        assertThatThrownBy { reading.search(KnowledgeSearchQuery("password", first), KnowledgeReader.SignedInCustomer) }
            .isInstanceOf(IllegalArgumentException::class.java)
        jdbc.update("update knowledge_articles set audience_type = 'STAFF' where slug = 'visible-4'")
        assertThat(slugs(search("password", first)))
            .containsExactly("visible-3", "visible-2", "visible-1")
    }

    @Test
    fun `English phrase OR and identifier matching retain simple websearch semantics`() {
        article(1, "payment", "payment authorization", "ERR-204", "authorization failed, retry payment")
        article(2, "shipment", "shipment tracking", "parcel", "track delivery")
        assertThat(slugs(search("\"payment authorization\""))).containsExactly("payment")
        assertThat(slugs(search("payment OR shipment"))).containsExactlyInAnyOrder("payment", "shipment")
        assertThat(slugs(search("ERR-204"))).containsExactly("payment")
        assertThat(search("unmatched")["items"].size()).isZero()
    }

    private fun slugs(page: JsonNode): List<String> = (0 until page["items"].size()).map { page["items"][it]["articleSlug"].asText() }

    private fun search(query: String, cursor: String? = null, limit: Int = 20): JsonNode = mapper.readTree(
        mockMvc.perform(post("/api/v1/help/search").contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(mapOf("query" to query, "limit" to limit) + (cursor?.let { mapOf("cursor" to it) } ?: emptyMap()))))
            .andExpect(status().isOk).andReturn().response.contentAsString,
    )

    private fun searchRejected(query: String, cursor: String) {
        mockMvc.perform(post("/api/v1/help/search").contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(mapOf("query" to query, "cursor" to cursor))))
            .andExpect(status().isBadRequest)
    }

    private fun article(number: Int, slug: String, title: String, summary: String, body: String,
                        timestamp: Instant = createdAt, audience: String = "PUBLIC") {
        val id = UUID(0, number.toLong())
        val revision = UUID.randomUUID()
        val time = Timestamp.from(timestamp)
        jdbc.update("""
            insert into knowledge_articles (id, section_id, slug, lifecycle, audience_type, author_id, created_at, updated_at)
            values (?, ?, ?, 'DRAFT', ?, ?, ?, ?)
        """.trimIndent(), id, sectionId, slug, audience, staffId, time, time)
        jdbc.update("""
            insert into knowledge_article_revisions (id, article_id, revision_number, title, summary, document_json,
                plain_text, content_checksum, created_by_staff_id, created_at)
            values (?, ?, 1, ?, ?, ?::jsonb, ?, repeat('a',64), ?, ?)
        """.trimIndent(), revision, id, title, summary,
            mapper.writeValueAsString(mapOf("schemaVersion" to 1, "blocks" to listOf(mapOf("type" to "paragraph", "text" to body)))),
            body, staffId, time)
        jdbc.update("update knowledge_articles set lifecycle = 'PUBLISHED', current_published_revision_id = ?, published_at = ? where id = ?", revision, time, id)
    }
}
