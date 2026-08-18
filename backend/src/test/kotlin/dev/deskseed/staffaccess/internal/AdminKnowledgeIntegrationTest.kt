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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
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
        // Revisions and access rows are append-only in the production application role.
        // Test isolation therefore uses one PostgreSQL TRUNCATE over the FK-connected tables.
        jdbc.execute(
            """
            truncate table knowledge_access_audit_events, knowledge_article_feedback_totals,
                knowledge_search_documents, knowledge_article_audience_groups,
                knowledge_article_revisions, knowledge_articles, knowledge_sections, knowledge_categories
            """.trimIndent(),
        )
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

        mockMvc.perform(
            put("/api/v1/admin/knowledge/articles/{articleId}/audience", articleId)
                .session(browser.session)
                .header("X-Deskseed-Expected-Staff-Id", adminId.toString())
                .header("X-CSRF-TOKEN", browser.csrfToken)
                .header("If-Match", "\"2\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"type":"STAFF"}"""),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.audience.type").value("STAFF"))
            .andExpect(jsonPath("$.audienceVersion").value(2))
            .andExpect(jsonPath("$.version").value(3))

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
            "KNOWLEDGE_ARTICLE_AUDIENCE_REPLACED",
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

    @Test
    fun `published Help article is audience filtered searchable and agent reads fail closed on audit`() {
        val adminId = insertStaff("knowledge-reader@example.com", "Knowledge reader password 42")
        val browser = login("knowledge-reader@example.com", "Knowledge reader password 42")
        val categoryId = postJson(
            browser,
            adminId,
            "/api/v1/admin/knowledge/categories",
            """{"slug":"access","title":"접근","displayOrder":1}""",
        ).andExpect(status().isCreated).andReturn().response.contentAsString.uuidField("id")
        val sectionId = postJson(
            browser,
            adminId,
            "/api/v1/admin/knowledge/sections",
            """{"categoryId":"$categoryId","slug":"login","title":"로그인","displayOrder":1}""",
        ).andExpect(status().isCreated).andReturn().response.contentAsString.uuidField("id")
        val articleId = postJson(
            browser,
            adminId,
            "/api/v1/admin/knowledge/articles",
            """
            {"sectionId":"$sectionId","slug":"reset-password","title":"비밀번호 재설정",
             "summary":"로그인 문제 해결","document":{"schemaVersion":1,"blocks":[{"type":"paragraph","text":"비밀번호를 재설정하려면 이메일을 확인하세요."}]},"audience":{"type":"PUBLIC"}}
            """.trimIndent(),
        ).andExpect(status().isCreated).andReturn().response.contentAsString.uuidField("id")
        mockMvc.perform(
            patch("/api/v1/admin/knowledge/articles/{articleId}", articleId)
                .session(browser.session).header("X-Deskseed-Expected-Staff-Id", adminId)
                .header("X-CSRF-TOKEN", browser.csrfToken).header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"sectionId":"$sectionId","slug":"reset-password","title":"비밀번호 재설정",
                     "summary":"로그인 문제 해결","changeNote":"문구 보강",
                     "document":{"schemaVersion":1,"blocks":[{"type":"paragraph","text":"비밀번호를 재설정하려면 이메일을 확인하고 다시 로그인하세요."}]},"audience":{"type":"PUBLIC"}}
                    """.trimIndent(),
                ),
        ).andExpect(status().isOk).andExpect(jsonPath("$.version").value(1))
        mockMvc.perform(
            get("/api/v1/admin/knowledge/articles")
                .session(browser.session).header("X-Deskseed-Expected-Staff-Id", adminId),
        ).andExpect(status().isOk).andExpect(jsonPath("$.items[0].id").value(articleId.toString()))
        mockMvc.perform(
            get("/api/v1/admin/knowledge/articles/{articleId}/revisions", articleId)
                .session(browser.session).header("X-Deskseed-Expected-Staff-Id", adminId),
        ).andExpect(status().isOk).andExpect(jsonPath("$.length()").value(2))
        mockMvc.perform(
            post("/api/v1/admin/knowledge/articles/{articleId}/submit-review", articleId)
                .session(browser.session).header("X-Deskseed-Expected-Staff-Id", adminId)
                .header("X-CSRF-TOKEN", browser.csrfToken).header("If-Match", "\"1\""),
        ).andExpect(status().isOk)
        mockMvc.perform(
            post("/api/v1/admin/knowledge/articles/{articleId}/publish", articleId)
                .session(browser.session).header("X-Deskseed-Expected-Staff-Id", adminId)
                .header("X-CSRF-TOKEN", browser.csrfToken).header("If-Match", "\"2\""),
        ).andExpect(status().isOk)
        mockMvc.perform(
            get("/api/v1/admin/knowledge/search-index")
                .session(browser.session).header("X-Deskseed-Expected-Staff-Id", adminId),
        ).andExpect(status().isOk).andExpect(jsonPath("$.state").value("IDLE"))
        mockMvc.perform(
            post("/api/v1/admin/knowledge/search-index")
                .session(browser.session).header("X-Deskseed-Expected-Staff-Id", adminId)
                .header("X-CSRF-TOKEN", browser.csrfToken),
        ).andExpect(status().isAccepted)

        val publicArticle = mockMvc.perform(get("/api/v1/help/articles/reset-password"))
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.currentPublishedRevision.document.blocks[0].type").value("paragraph"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", org.hamcrest.Matchers.containsString("public")))
            .andReturn()
        mockMvc.perform(
            get("/api/v1/help/articles/reset-password")
                .header("If-None-Match", checkNotNull(publicArticle.response.getHeader("ETag"))),
        ).andExpect(status().isNotModified)
        mockMvc.perform(
            post("/api/v1/help/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query":"비밀번호 재설정"}"""),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].articleSlug").value("reset-password"))
        mockMvc.perform(
            post("/api/v1/help/articles/reset-password/feedback")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"helpful":true}"""),
        ).andExpect(status().isNoContent)
        assertThat(jdbc.queryForObject("select helpful_count from knowledge_article_feedback_totals where article_id = ?", Long::class.java, articleId))
            .isEqualTo(1)

        mockMvc.perform(
            get("/api/v1/agent/knowledge/articles/reset-password")
                .session(browser.session).header("X-Deskseed-Expected-Staff-Id", adminId),
        ).andExpect(status().isOk)
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store"))
        mockMvc.perform(
            post("/api/v1/agent/knowledge/search")
                .session(browser.session).header("X-Deskseed-Expected-Staff-Id", adminId)
                .header("X-CSRF-TOKEN", browser.csrfToken).contentType(MediaType.APPLICATION_JSON)
                .content("""{"query":"비밀번호"}"""),
        ).andExpect(status().isOk)
        assertThat(jdbc.queryForObject("select count(*) from knowledge_access_audit_events", Long::class.java)).isEqualTo(2)
        jdbc.execute(
            """
            create or replace function fail_knowledge_access_audit()
            returns trigger language plpgsql as ${'$'}${'$'}
            begin raise exception 'injected knowledge access audit failure'; end;
            ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbc.execute(
            "create trigger fail_knowledge_access_audit before insert on knowledge_access_audit_events for each row execute function fail_knowledge_access_audit()",
        )
        try {
            mockMvc.perform(
                get("/api/v1/agent/knowledge/articles/reset-password")
                    .session(browser.session).header("X-Deskseed-Expected-Staff-Id", adminId),
            ).andExpect(status().isServiceUnavailable)
        } finally {
            jdbc.execute("drop trigger if exists fail_knowledge_access_audit on knowledge_access_audit_events")
            jdbc.execute("drop function if exists fail_knowledge_access_audit()")
        }
        assertThat(jdbc.queryForObject("select count(*) from knowledge_access_audit_events", Long::class.java)).isEqualTo(2)
        assertThat(jdbc.queryForObject("select count(*) from knowledge_search_documents where article_id = ?", Long::class.java, articleId))
            .isEqualTo(1)
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
