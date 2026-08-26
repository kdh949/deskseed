package dev.deskseed.staffaccess.internal

import dev.deskseed.audit.internal.SearchQueryCiphertextRetentionJob
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.charset.StandardCharsets
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

@dev.deskseed.testsupport.integration.DeskseedSpringIntegrationTest(
    properties = ["deskseed.test.context-group=agent-ticket-search"],
)
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension::class)
@dev.deskseed.testsupport.category.IntegrationTest
class AgentTicketSearchIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var ciphertextRetentionJob: SearchQueryCiphertextRetentionJob

    @BeforeEach
    fun clearState() {
        if (tableExists("search_audit_query_ciphertexts")) {
            jdbcTemplate.execute(
                "truncate table macro_preview_audit_details, search_audit_query_ciphertexts, " +
                    "search_audit_customer_result_items, " +
                    "search_audit_result_items, search_audit_details, access_audit_events",
            )
        } else if (tableExists("access_audit_events")) {
            jdbcTemplate.execute("truncate table access_audit_events")
        }
        jdbcTemplate.execute("truncate table admin_security_audit_events")
        jdbcTemplate.execute(
            "truncate table customer_registration_intent_consents, customer_registration_intents, " +
                "customer_consent_acceptances, customer_consent_policy_versions, customer_consent_policies cascade",
        )
        jdbcTemplate.update("delete from request_access_tokens")
        jdbcTemplate.update("delete from ticket_audit_events")
        jdbcTemplate.update("delete from ticket_audits")
        jdbcTemplate.update("delete from ticket_comments")
        jdbcTemplate.update("delete from tickets")
        jdbcTemplate.update("delete from customers")
        jdbcTemplate.update("delete from group_memberships")
        jdbcTemplate.update("delete from support_groups")
        jdbcTemplate.update("delete from staff_login_throttles")
        jdbcTemplate.update("delete from staff_accounts")
    }

    @Test
    fun `search returns authorized PostgreSQL results and commits protected query filters count and context`() {
        val agent = insertStaff("search@example.com", "Agent password 42", "검색 상담사")
        val ownGroup = insertGroup("검색 그룹", agent)
        val otherAgent = insertStaff("other@example.com", "Agent password 42", "다른 상담사")
        val otherGroup = insertGroup("다른 그룹", otherAgent)
        insertTicket(8101, "결제 오류 확인", "OPEN", "HIGH", ownGroup, agent)
        insertTicket(8102, "결제 오류 종료", "SOLVED", "HIGH", ownGroup, agent)
        insertTicket(8103, "결제 오류 타 그룹", "OPEN", "HIGH", otherGroup, otherAgent)
        insertTicket(8104, "배송 문의", "OPEN", "NORMAL", ownGroup, agent)
        val browser = login("search@example.com", "Agent password 42")
        val interactionId = UUID.randomUUID()

        val response = mockMvc.perform(
            search(
                browser,
                interactionId,
                """{
                  "query":"결제 오류",
                  "filters":{"status":"OPEN","priority":"HIGH"},
                  "sort":"updatedAt:desc,ticketNumber:desc",
                  "limit":25
                }""".trimIndent(),
            ),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.searchInteractionId").value(interactionId.toString()))
            .andExpect(jsonPath("$.resultCount").value(2))
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].ticketNumber").value(8103))
            .andExpect(jsonPath("$.items[1].ticketNumber").value(8101))
            .andReturn().response.contentAsString

        val searchEventId = UUID.fromString(stringField(response, "searchEventId"))
        val event = jdbcTemplate.queryForMap(
            """
            select actor_id, source, action, resource_type, interaction_id, session_fingerprint,
                   auth_type, request_id, correlation_id, outcome, http_status
            from access_audit_events where id = ?
            """.trimIndent(),
            searchEventId,
        )
        assertThat(event["actor_id"]).isEqualTo(agent)
        assertThat(event["source"]).isEqualTo("AGENT_UI")
        assertThat(event["action"]).isEqualTo("SEARCH_EXECUTED")
        assertThat(event["resource_type"]).isEqualTo("SEARCH")
        assertThat(event["interaction_id"]).isEqualTo(interactionId)
        assertThat(event["session_fingerprint"].toString())
            .matches("v1:[A-Za-z0-9_-]{43}")
            .isNotEqualTo(browser.id)
        assertThat(event["auth_type"]).isEqualTo("STAFF_SESSION")
        assertThat(event["request_id"]).isEqualTo("search-request")
        assertThat(event["correlation_id"]).isEqualTo("search-correlation")
        assertThat(event["outcome"]).isEqualTo("SUCCEEDED")
        assertThat(event["http_status"]).isEqualTo(200)

        val detail = jdbcTemplate.queryForMap(
            """
            select query_redacted, query_fingerprint, query_key_version,
                   normalized_filters::text as filters, sort, result_count
            from search_audit_details where access_event_id = ?
            """.trimIndent(),
            searchEventId,
        )
        assertThat(detail["query_redacted"]).isEqualTo("[PROTECTED]")
        assertThat(detail["query_fingerprint"].toString()).isNotBlank().isNotEqualTo("결제 오류")
        assertThat(detail["query_key_version"]).isEqualTo("local-v1")
        assertThat(detail["filters"].toString()).contains("\"status\": \"OPEN\"")
        assertThat(detail["filters"].toString()).contains("\"priority\": \"HIGH\"")
        assertThat(detail["sort"]).isEqualTo("updatedAt:desc,ticketNumber:desc")
        assertThat(detail["result_count"]).isEqualTo(2L)
        assertThat(
            jdbcTemplate.queryForList(
                """
                select ticket_number from search_audit_result_items
                where access_event_id = ? order by result_ordinal
                """.trimIndent(),
                Long::class.java,
                searchEventId,
            ),
        ).containsExactly(8103L, 8101L)

        val ciphertext = jdbcTemplate.queryForMap(
            """
            select query_ciphertext, key_version, created_at, expires_at
            from search_audit_query_ciphertexts where access_event_id = ?
            """.trimIndent(),
            searchEventId,
        )
        assertThat(String(ciphertext["query_ciphertext"] as ByteArray, StandardCharsets.UTF_8))
            .doesNotContain("결제 오류")
        assertThat(ciphertext["key_version"]).isEqualTo("local-v1")
        val createdAt = (ciphertext["created_at"] as Timestamp).toInstant()
        val expiresAt = (ciphertext["expires_at"] as Timestamp).toInstant()
        assertThat(Duration.between(createdAt, expiresAt)).isEqualTo(Duration.ofDays(30))
        assertThat(
            jdbcTemplate.queryForList(
                """
                select column_name from information_schema.columns
                where table_schema = 'public'
                  and table_name like 'search_audit%'
                  and column_name in ('query', 'raw_query', 'query_plaintext', 'query_original')
                """.trimIndent(),
                String::class.java,
            ),
        ).isEmpty()
    }

    @Test
    fun `opening a search result links one semantic view and one result-open event to its search`() {
        val agent = insertStaff("link@example.com", "Agent password 42", "연결 상담사")
        val group = insertGroup("연결 그룹", agent)
        insertTicket(8201, "환불 연결 대상", "OPEN", "NORMAL", group, agent)
        val browser = login("link@example.com", "Agent password 42")
        val searchInteractionId = UUID.randomUUID()
        val searchResponse = mockMvc.perform(
            search(
                browser,
                searchInteractionId,
                """{"query":"환불","filters":{},"sort":"updatedAt:desc,ticketNumber:desc","limit":25}""",
            ),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val searchEventId = UUID.fromString(stringField(searchResponse, "searchEventId"))
        val ticketInteractionId = UUID.randomUUID()

        mockMvc.perform(ticketDetail(8201, browser, ticketInteractionId, searchEventId))
            .andExpect(status().isOk)
        mockMvc.perform(ticketDetail(8201, browser, ticketInteractionId, searchEventId))
            .andExpect(status().isOk)

        val linked = jdbcTemplate.queryForList(
            """
            select action, origin_search_event_id from access_audit_events
            where actor_id = ? and ticket_number = 8201
              and action in ('TICKET_VIEWED', 'SEARCH_RESULT_OPENED')
            order by action
            """.trimIndent(),
            agent,
        )
        assertThat(linked).hasSize(2)
        assertThat(linked.map { it["origin_search_event_id"] }).containsOnly(searchEventId)

        mockMvc.perform(ticketDetail(8201, browser, UUID.randomUUID(), null))
            .andExpect(status().isOk)
        assertThat(
            jdbcTemplate.queryForObject(
                """
                select count(*) from access_audit_events
                where actor_id = ? and ticket_number = 8201 and action = 'TICKET_VIEWED'
                  and origin_search_event_id is null
                """.trimIndent(),
                Long::class.java,
                agent,
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `invalid search origin fails before protected ticket detail is returned`() {
        val agent = insertStaff("invalid-origin@example.com", "Agent password 42", "원점 상담사")
        val group = insertGroup("원점 그룹", agent)
        insertTicket(8301, "원점 검증 대상", "OPEN", "NORMAL", group, agent)
        insertTicket(8302, "다른 검색 결과", "OPEN", "NORMAL", group, agent)
        val browser = login("invalid-origin@example.com", "Agent password 42")

        mockMvc.perform(ticketDetail(8301, browser, UUID.randomUUID(), UUID.randomUUID()))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.type").value("/problems/invalid-search-origin"))
            .andExpect(jsonPath("$.ticket").doesNotExist())

        val unrelatedSearch = mockMvc.perform(
            search(
                browser,
                UUID.randomUUID(),
                """{"query":"다른 검색 결과","filters":{},"sort":"updatedAt:desc,ticketNumber:desc","limit":25}""",
            ),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val unrelatedSearchEventId = UUID.fromString(stringField(unrelatedSearch, "searchEventId"))
        mockMvc.perform(ticketDetail(8301, browser, UUID.randomUUID(), unrelatedSearchEventId))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.type").value("/problems/invalid-search-origin"))
            .andExpect(jsonPath("$.ticket").doesNotExist())
    }

    @Test
    fun `search audit insert failure blocks results and agents cannot query the access ledger`() {
        val agent = insertStaff("strict@example.com", "Agent password 42", "Strict 상담사")
        val group = insertGroup("Strict 그룹", agent)
        insertTicket(8401, "audit-failure-protected-search-result", "OPEN", "NORMAL", group, agent)
        val browser = login("strict@example.com", "Agent password 42")

        jdbcTemplate.execute(
            """
            create or replace function fail_search_audit_insert() returns trigger language plpgsql as ${'$'}${'$'}
            begin raise exception 'injected search audit failure'; end; ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            "create trigger fail_search_audit before insert on access_audit_events for each row execute function fail_search_audit_insert()",
        )
        try {
            val response = mockMvc.perform(
                search(
                    browser,
                    UUID.randomUUID(),
                    """{"query":"audit-failure-protected","filters":{},"sort":"updatedAt:desc,ticketNumber:desc","limit":25}""",
                ),
            )
                .andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.type").value("/problems/audit-write-unavailable"))
                .andExpect(jsonPath("$.items").doesNotExist())
                .andExpect(jsonPath("$.resultCount").doesNotExist())
                .andReturn().response.contentAsString
            assertThat(response).doesNotContain("audit-failure-protected-search-result")
        } finally {
            jdbcTemplate.execute("drop trigger if exists fail_search_audit on access_audit_events")
            jdbcTemplate.execute("drop function if exists fail_search_audit_insert()")
        }

        mockMvc.perform(get("/api/v1/audit/activities").session(browser))
            .andExpect(status().isForbidden)

        val anonymousCsrf = mockMvc.perform(get("/api/v1/agent/csrf"))
            .andExpect(status().isOk)
            .andReturn()
        mockMvc.perform(
            post("/api/v1/agent/search")
                .session(anonymousCsrf.request.session as MockHttpSession)
                .header("X-CSRF-TOKEN", stringField(anonymousCsrf.response.contentAsString, "token"))
                .header("X-Interaction-Id", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query":"감사 장애","filters":{},"sort":"updatedAt:desc,ticketNumber:desc","limit":25}"""),
        ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `raw query and secrets never appear in captured application output`(output: CapturedOutput) {
        val agent = insertStaff("logs@example.com", "Agent password 42", "로그 상담사")
        val group = insertGroup("로그 그룹", agent)
        insertTicket(8501, "로그 무관 대상", "OPEN", "NORMAL", group, agent)
        val browser = login("logs@example.com", "Agent password 42")
        val rawQuery = "token=raw-secret-value-that-must-not-leak"

        mockMvc.perform(
            search(
                browser,
                UUID.randomUUID(),
                """{"query":"$rawQuery","filters":{},"sort":"updatedAt:desc,ticketNumber:desc","limit":25}""",
            ),
        ).andExpect(status().isOk)

        assertThat(output.out).doesNotContain(rawQuery).doesNotContain("raw-secret-value-that-must-not-leak")
        assertThat(output.err).doesNotContain(rawQuery).doesNotContain("raw-secret-value-that-must-not-leak")
        val redacted = jdbcTemplate.queryForObject(
            "select query_redacted from search_audit_details order by access_event_id desc limit 1",
            String::class.java,
        )
        assertThat(redacted).isEqualTo("[PROTECTED]")
    }

    @Test
    fun `ciphertext retention deletes only expired rows and rolls back when its execution audit fails`() {
        val now = Instant.parse("2026-08-12T00:00:00Z")
        val expiredEvent = insertSearchAuditCiphertext(now.minusSeconds(60), now.minusSeconds(1))
        val retainedEvent = insertSearchAuditCiphertext(now.minusSeconds(60), now.plusSeconds(3600))

        val first = ciphertextRetentionJob.purgeExpired(now)
        val second = ciphertextRetentionJob.purgeExpired(now)

        assertThat(first).isEqualTo(1)
        assertThat(second).isZero()
        assertThat(
            jdbcTemplate.queryForList(
                "select access_event_id from search_audit_query_ciphertexts order by access_event_id",
                UUID::class.java,
            ),
        ).containsExactly(retainedEvent)
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from access_audit_events where id in (?, ?)",
                Long::class.java,
                expiredEvent,
                retainedEvent,
            ),
        ).isEqualTo(2)
        val execution = jdbcTemplate.queryForMap(
            """
            select actor_type, source, outcome, metadata_json
            from admin_security_audit_events
            where event_type = 'RETENTION_JOB_EXECUTED'
              and metadata_json::jsonb ->> 'deletedCount' = '1'
            limit 1
            """.trimIndent(),
        )
        assertThat(execution["actor_type"]).isEqualTo("SYSTEM")
        assertThat(execution["source"]).isEqualTo("SYSTEM_JOB")
        assertThat(execution["outcome"]).isEqualTo("SUCCEEDED")
        assertThat(execution["metadata_json"].toString())
            .contains("search-query-ciphertext-v1")
            .contains("\"deletedCount\":\"1\"")

        val rollbackEvent = insertSearchAuditCiphertext(now.minusSeconds(60), now.minusSeconds(1))
        jdbcTemplate.execute(
            """
            create or replace function fail_retention_audit_insert() returns trigger language plpgsql as ${'$'}${'$'}
            begin raise exception 'injected retention audit failure'; end; ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            "create trigger fail_retention_audit before insert on admin_security_audit_events for each row execute function fail_retention_audit_insert()",
        )
        try {
            assertThatThrownBy { ciphertextRetentionJob.purgeExpired(now) }
                .hasMessageContaining("injected retention audit failure")
        } finally {
            jdbcTemplate.execute("drop trigger if exists fail_retention_audit on admin_security_audit_events")
            jdbcTemplate.execute("drop function if exists fail_retention_audit_insert()")
        }
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from search_audit_query_ciphertexts where access_event_id = ?",
                Long::class.java,
                rollbackEvent,
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `score cursor keeps a stable snapshot binds query and SLA filter and returns exact count`() {
        val agent = insertStaff("stable-cursor@example.com", "Agent password 42", "Cursor 상담사")
        val group = insertGroup("Cursor 그룹", agent)
        (1L..4L).forEach { offset ->
            insertTicket(
                8600 + offset,
                "stable cursor result $offset",
                "OPEN",
                "NORMAL",
                group,
                agent,
            )
        }
        val browser = login("stable-cursor@example.com", "Agent password 42")
        val first = mockMvc.perform(
            search(
                browser,
                UUID.randomUUID(),
                """
                {"query":"stable cursor","filters":{"slaState":"NO_POLICY"},"sort":"score:desc,ticketNumber:desc","limit":2}
                """.trimIndent(),
            ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.resultCount").value(4))
            .andExpect(jsonPath("$.items[0].ticketNumber").value(8604))
            .andExpect(jsonPath("$.items[1].ticketNumber").value(8603))
            .andReturn().response.contentAsString
        val cursor = stringField(first, "nextCursor")
        assertThat(cursor).doesNotContain("stable cursor")

        // This ticket matches the words but was created after the signed first-page snapshot.
        insertTicket(
            8700,
            "stable cursor late result",
            "OPEN",
            "NORMAL",
            group,
            agent,
            updatedAt = Instant.now().plusSeconds(60),
        )
        mockMvc.perform(
            search(
                browser,
                UUID.randomUUID(),
                """
                {"query":"stable cursor","filters":{"slaState":"NO_POLICY"},"sort":"score:desc,ticketNumber:desc","cursor":"$cursor","limit":2}
                """.trimIndent(),
            ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.resultCount").value(4))
            .andExpect(jsonPath("$.items[0].ticketNumber").value(8602))
            .andExpect(jsonPath("$.items[1].ticketNumber").value(8601))
            .andExpect(jsonPath("$.nextCursor").isEmpty)

        mockMvc.perform(
            search(
                browser,
                UUID.randomUUID(),
                """
                {"query":"other query","filters":{"slaState":"NO_POLICY"},"sort":"score:desc,ticketNumber:desc","cursor":"$cursor","limit":2}
                """.trimIndent(),
            ),
        ).andExpect(status().isBadRequest)
        mockMvc.perform(
            search(
                browser,
                UUID.randomUUID(),
                """
                {"query":"stable cursor","filters":{"slaState":"BREACHED"},"sort":"score:desc,ticketNumber:desc","cursor":"$cursor","limit":2}
                """.trimIndent(),
            ),
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `projection quality corpus preserves internal exact rank and literal wildcard behavior`() {
        val agent = insertStaff("projection-corpus@example.com", "Agent password 42", "Corpus 상담사")
        val group = insertGroup("Corpus 그룹", agent)
        val commentTicket = insertTicket(8801, "한국어 공개 제목", "OPEN", "NORMAL", group, agent)
        insertTicket(8802, "정확 번호 대상", "OPEN", "NORMAL", group, agent)
        insertTicket(8803, "8802 reference in subject", "OPEN", "NORMAL", group, agent)
        insertTicket(8804, "literal 100%_done", "OPEN", "NORMAL", group, agent)
        insertTicket(8805, "literal 100xxdone", "OPEN", "NORMAL", group, agent)
        insertTicket(8806, "payment refund", "OPEN", "NORMAL", group, agent)
        insertTicket(8807, "요청자 이메일 대상", "OPEN", "NORMAL", group, agent)
        jdbcTemplate.update(
            """
            insert into ticket_comments
                (id, ticket_id, author_type, author_id, visibility, body, created_at)
            values (?, ?, 'AGENT', ?, 'INTERNAL', '내부전용 corpus-marker', now())
            """.trimIndent(),
            UUID.randomUUID(),
            commentTicket,
            agent,
        )
        val browser = login("projection-corpus@example.com", "Agent password 42")

        mockMvc.perform(
            search(
                browser,
                UUID.randomUUID(),
                """{"query":"내부전용 corpus-marker","filters":{},"sort":"score:desc,ticketNumber:desc","limit":25}""",
            ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.resultCount").value(1))
            .andExpect(jsonPath("$.items[0].ticketNumber").value(8801))

        mockMvc.perform(
            search(
                browser,
                UUID.randomUUID(),
                """{"query":"8802","filters":{},"sort":"score:desc,ticketNumber:desc","limit":25}""",
            ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.resultCount").value(2))
            .andExpect(jsonPath("$.items[0].ticketNumber").value(8802))
            .andExpect(jsonPath("$.items[1].ticketNumber").value(8803))

        mockMvc.perform(
            search(
                browser,
                UUID.randomUUID(),
                """{"query":"100%_","filters":{},"sort":"score:desc,ticketNumber:desc","limit":25}""",
            ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.resultCount").value(1))
            .andExpect(jsonPath("$.items[0].ticketNumber").value(8804))

        mockMvc.perform(
            search(
                browser,
                UUID.randomUUID(),
                """{"query":"customer-8807@example.com","filters":{},"sort":"score:desc,ticketNumber:desc","limit":25}""",
            ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.resultCount").value(1))
            .andExpect(jsonPath("$.items[0].ticketNumber").value(8807))

        // V35 deliberately preserves literal substring semantics; fuzzy typo correction is not claimed.
        mockMvc.perform(
            search(
                browser,
                UUID.randomUUID(),
                """{"query":"paymant","filters":{},"sort":"score:desc,ticketNumber:desc","limit":25}""",
            ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.resultCount").value(0))
            .andExpect(jsonPath("$.items").isEmpty)
    }

    private fun search(session: MockHttpSession, interactionId: UUID, body: String) =
        post("/api/v1/agent/search")
            .session(session)
            .header("X-CSRF-TOKEN", csrf(session))
            .header("X-Interaction-Id", interactionId)
            .header("X-Request-Id", "search-request")
            .header("X-Correlation-Id", "search-correlation")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body)

    private fun ticketDetail(
        number: Long,
        session: MockHttpSession,
        interactionId: UUID,
        originSearchEventId: UUID?,
    ) = get("/api/v1/agent/tickets/{ticketNumber}", number)
        .session(session)
        .header("X-Interaction-Id", interactionId)
        .header("X-Deskseed-Read-Intent", "NAVIGATION")
        .header("X-Request-Id", "result-open-request")
        .apply { originSearchEventId?.let { header("X-Origin-Search-Event-Id", it) } }

    private fun csrf(session: MockHttpSession): String {
        val result = mockMvc.perform(get("/api/v1/agent/csrf").session(session))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        return stringField(result, "token")
    }

    private fun login(email: String, password: String): MockHttpSession {
        val csrfResult = mockMvc.perform(get("/api/v1/agent/csrf")).andExpect(status().isOk).andReturn()
        val token = stringField(csrfResult.response.contentAsString, "token")
        val session = csrfResult.request.session as MockHttpSession
        return mockMvc.perform(
            post("/api/v1/agent/session")
                .session(session)
                .header("X-CSRF-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email","password":"$password"}"""),
        ).andExpect(status().isNoContent).andReturn().request.session as MockHttpSession
    }

    private fun insertStaff(email: String, password: String, displayName: String): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            """
            insert into staff_accounts
                (id, email_normalized, email_display, display_name, role, status,
                 password_hash, created_at, updated_at, version)
            values (?, ?, ?, ?, 'AGENT', 'ACTIVE', ?, now(), now(), 0)
            """.trimIndent(),
            id,
            email.lowercase(),
            email,
            displayName,
            BCryptPasswordEncoder(4).encode(password),
        )
        return id
    }

    private fun insertGroup(name: String, staffId: UUID): UUID {
        val groupId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            insert into support_groups (id, name, status, created_at, updated_at, version)
            values (?, ?, 'ACTIVE', now(), now(), 0)
            """.trimIndent(),
            groupId,
            name,
        )
        jdbcTemplate.update(
            """
            insert into group_memberships (id, group_id, staff_id, status, created_at, updated_at)
            values (?, ?, ?, 'ACTIVE', now(), now())
            """.trimIndent(),
            UUID.randomUUID(),
            groupId,
            staffId,
        )
        return groupId
    }

    private fun insertTicket(
        number: Long,
        subject: String,
        status: String,
        priority: String,
        groupId: UUID,
        assigneeId: UUID,
        updatedAt: Instant = Instant.parse("2026-08-11T00:00:00Z").plusSeconds(number),
    ): UUID {
        val customerId = UUID.randomUUID()
        val ticketId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            insert into customers (id, name, email_normalized, email_display, created_at, updated_at)
            values (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            customerId,
            "고객 $number",
            "customer-$number@example.com",
            "customer-$number@example.com",
            Timestamp.from(updatedAt.minusSeconds(60)),
            Timestamp.from(updatedAt),
        )
        jdbcTemplate.update(
            """
            insert into tickets
                (id, ticket_number, requester_id, kind, subject, status, priority,
                 group_id, assignee_id, channel, version, created_at, updated_at)
            values (?, ?, ?, 'CUSTOMER_REQUEST', ?, ?, ?, ?, ?, 'WEB', 0, ?, ?)
            """.trimIndent(),
            ticketId,
            number,
            customerId,
            subject,
            status,
            priority,
            groupId,
            assigneeId,
            Timestamp.from(updatedAt.minusSeconds(60)),
            Timestamp.from(updatedAt),
        )
        return ticketId
    }

    private fun insertSearchAuditCiphertext(createdAt: Instant, expiresAt: Instant): UUID {
        val eventId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            insert into access_audit_events (
                id, occurred_at, actor_type, actor_id, actor_display_snapshot,
                source, action, resource_type, resource_id, ticket_number,
                interaction_id, request_id, correlation_id, outcome, http_status
            ) values (?, ?, 'STAFF', ?, 'retention-test', 'AGENT_UI', 'SEARCH_EXECUTED',
                      'SEARCH', null, null, ?, ?, ?, 'SUCCEEDED', 200)
            """.trimIndent(),
            eventId,
            Timestamp.from(createdAt),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "retention-test-$eventId",
            "retention-test-$eventId",
        )
        jdbcTemplate.update(
            """
            insert into search_audit_query_ciphertexts
                (access_event_id, key_version, query_ciphertext, created_at, expires_at)
            values (?, 'local-v1', ?, ?, ?)
            """.trimIndent(),
            eventId,
            ByteArray(29) { 1 },
            Timestamp.from(createdAt),
            Timestamp.from(expiresAt),
        )
        return eventId
    }

    private fun tableExists(name: String): Boolean = jdbcTemplate.queryForObject(
        "select to_regclass(?) is not null",
        Boolean::class.java,
        name,
    ) == true

    private fun stringField(json: String, field: String): String =
        Regex("\"$field\":\"([^\"]+)\"").find(json)!!.groupValues[1]

}
