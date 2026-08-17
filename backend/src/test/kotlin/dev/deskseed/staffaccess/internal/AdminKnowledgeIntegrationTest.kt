package dev.deskseed.staffaccess.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@SpringBootTest(properties = ["deskseed.staff-auth.bootstrap.enabled=false"])
@AutoConfigureMockMvc
@Testcontainers
class AdminKnowledgeIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun clearState() {
        jdbc.execute("delete from knowledge_article_audience_groups")
        jdbc.execute("delete from knowledge_article_revisions")
        jdbc.execute("delete from knowledge_articles")
        jdbc.execute("delete from knowledge_sections")
        jdbc.execute("delete from knowledge_categories")
        jdbc.execute("delete from domain_event_outbox")
        // Canonical audit rows are append-only; test isolation must use PostgreSQL TRUNCATE,
        // never an application-role DELETE that production correctly rejects.
        jdbc.execute("truncate table admin_security_audit_events")
        jdbc.execute("delete from group_memberships")
        jdbc.execute("delete from support_groups")
        jdbc.execute("delete from staff_login_throttles")
        jdbc.execute("delete from staff_accounts")
    }

    @Test
    fun `admin creates hierarchy and immutable draft with audit and durable event intent`() {
        val adminId = insertStaff("knowledge-admin@example.com", "Knowledge admin password 42")
        val browser = login("knowledge-admin@example.com", "Knowledge admin password 42")

        mockMvc.perform(get("/api/v1/admin/knowledge/categories").session(browser.session))
            .andExpect(status().isBadRequest)
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.type").value("/problems/knowledge-validation"))

        val categoryId = postJson(
            browser,
            adminId,
            "/api/v1/admin/knowledge/categories",
            """{"slug":"billing","title":"결제","description":"결제 도움말","displayOrder":10}""",
        ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.slug").value("billing"))
            .andReturn().response.contentAsString.uuidField("id")

        mockMvc.perform(
            patch("/api/v1/admin/knowledge/categories/{categoryId}", categoryId)
                .session(browser.session)
                .header("X-Deskseed-Expected-Staff-Id", adminId.toString())
                .header("X-CSRF-TOKEN", browser.csrfToken)
                .header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"slug":"billing","title":"결제와 청구","description":"결제 도움말","displayOrder":10,"active":true}"""),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.version").value(1))

        mockMvc.perform(
            patch("/api/v1/admin/knowledge/categories/{categoryId}", categoryId)
                .session(browser.session)
                .header("X-Deskseed-Expected-Staff-Id", adminId.toString())
                .header("X-CSRF-TOKEN", browser.csrfToken)
                .header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"slug":"billing","title":"stale","description":"","displayOrder":10,"active":true}"""),
        ).andExpect(status().isPreconditionFailed)

        val sectionId = postJson(
            browser,
            adminId,
            "/api/v1/admin/knowledge/sections",
            """{"categoryId":"$categoryId","slug":"payment-errors","title":"결제 오류","displayOrder":10}""",
        ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.categoryId").value(categoryId.toString()))
            .andReturn().response.contentAsString.uuidField("id")

        val articleId = postJson(
            browser,
            adminId,
            "/api/v1/admin/knowledge/articles",
            """
            {"sectionId":"$sectionId","slug":"card-declined","title":"카드 결제가 거절될 때",
             "summary":"카드 승인 오류 해결","changeNote":"초기 초안",
             "document":{"schemaVersion":1,"blocks":[{"type":"heading","level":2,"text":"카드 결제"},{"type":"paragraph","text":"카드 정보를 다시 확인하세요."}]},
             "audience":{"type":"PUBLIC"}}
            """.trimIndent(),
        ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.lifecycle").value("DRAFT"))
            .andExpect(jsonPath("$.audience.type").value("PUBLIC"))
            .andReturn().response.contentAsString.uuidField("id")

        mockMvc.perform(
            post("/api/v1/admin/knowledge/articles/{articleId}/submit-review", articleId)
                .session(browser.session)
                .header("X-Deskseed-Expected-Staff-Id", adminId.toString())
                .header("X-CSRF-TOKEN", browser.csrfToken)
                .header("If-Match", "\"0\""),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.lifecycle").value("IN_REVIEW"))
            .andExpect(jsonPath("$.version").value(1))

        mockMvc.perform(
            post("/api/v1/admin/knowledge/articles/{articleId}/publish", articleId)
                .session(browser.session)
                .header("X-Deskseed-Expected-Staff-Id", adminId.toString())
                .header("X-CSRF-TOKEN", browser.csrfToken)
                .header("If-Match", "\"1\""),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.lifecycle").value("PUBLISHED"))
            .andExpect(jsonPath("$.currentPublishedRevision.revisionNumber").value(1))
            .andExpect(jsonPath("$.version").value(2))

        assertThat(
            jdbc.queryForObject(
                "select count(*) from knowledge_article_revisions where article_id = ? and revision_number = 1",
                Long::class.java,
                articleId,
            ),
        ).isEqualTo(1)
        assertThat(
            jdbc.queryForList(
                "select event_type from admin_security_audit_events where target_id = ? order by occurred_at",
                String::class.java,
                articleId,
            ),
        ).containsExactlyInAnyOrder(
            "KNOWLEDGE_ARTICLE_DRAFT_CREATED",
            "KNOWLEDGE_ARTICLE_LIFECYCLE_CHANGED",
            "KNOWLEDGE_ARTICLE_LIFECYCLE_CHANGED",
        )
        assertThat(
            jdbc.queryForList(
                "select event_type, visibility, data_json::text as data from domain_event_outbox where subject = ?",
                "knowledge-article:$articleId",
            ),
        ).allSatisfy { event ->
            assertThat(event).containsEntry("visibility", "INTERNAL")
                .doesNotContainValue("카드 결제가 거절될 때")
        }
    }

    @Test
    fun `required audit failure rolls category mutation and event intent back`() {
        val adminId = insertStaff("rollback-admin@example.com", "Rollback admin password 42")
        val browser = login("rollback-admin@example.com", "Rollback admin password 42")
        jdbc.execute(
            """
            create or replace function fail_knowledge_audit()
            returns trigger language plpgsql as ${'$'}${'$'}
            begin raise exception 'injected knowledge audit failure'; end;
            ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbc.execute(
            "create trigger fail_knowledge_audit before insert on admin_security_audit_events for each row execute function fail_knowledge_audit()",
        )
        try {
            postJson(
                browser,
                adminId,
                "/api/v1/admin/knowledge/categories",
                """{"slug":"rollback","title":"Rollback","displayOrder":0}""",
            ).andExpect(status().isServiceUnavailable)
        } finally {
            jdbc.execute("drop trigger if exists fail_knowledge_audit on admin_security_audit_events")
            jdbc.execute("drop function if exists fail_knowledge_audit()")
        }

        assertThat(jdbc.queryForObject("select count(*) from knowledge_categories", Long::class.java)).isZero()
        assertThat(jdbc.queryForObject("select count(*) from domain_event_outbox", Long::class.java)).isZero()
    }

    private fun postJson(
        browser: Browser,
        actorId: UUID,
        path: String,
        body: String,
    ) = mockMvc.perform(
        post(path)
            .session(browser.session)
            .header("X-Deskseed-Expected-Staff-Id", actorId.toString())
            .header("X-CSRF-TOKEN", browser.csrfToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body),
    )

    private fun login(email: String, password: String): Browser {
        val csrfResult = mockMvc.perform(get("/api/v1/agent/csrf")).andExpect(status().isOk).andReturn()
        val csrfToken = Regex("\\\"token\\\":\\\"([^\\\"]+)\\\"")
            .find(csrfResult.response.contentAsString)!!.groupValues[1]
        val session = csrfResult.request.session as MockHttpSession
        val login = mockMvc.perform(
            post("/api/v1/agent/session")
                .session(session)
                .header("X-CSRF-TOKEN", csrfToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email","password":"$password"}"""),
        ).andExpect(status().isNoContent).andReturn()
        return Browser(login.request.session as MockHttpSession, csrfToken)
    }

    private fun insertStaff(email: String, password: String): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            """
            insert into staff_accounts
                (id, email_normalized, email_display, display_name, role, status,
                 password_hash, created_at, updated_at, version)
            values (?, ?, ?, '지식 관리자', 'ADMIN', 'ACTIVE', ?, ?, ?, 0)
            """.trimIndent(),
            id,
            email.lowercase(),
            email,
            BCryptPasswordEncoder(4).encode(password),
            Timestamp.from(Instant.parse("2026-08-10T00:00:00Z")),
            Timestamp.from(Instant.parse("2026-08-10T00:00:00Z")),
        )
        return id
    }

    private fun String.uuidField(name: String): UUID = UUID.fromString(
        Regex("\\\"$name\\\":\\\"([^\\\"]+)\\\"").find(this)!!.groupValues[1],
    )

    private data class Browser(val session: MockHttpSession, val csrfToken: String)

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
    }
}
