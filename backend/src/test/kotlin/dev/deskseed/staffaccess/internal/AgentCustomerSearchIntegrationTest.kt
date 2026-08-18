package dev.deskseed.staffaccess.internal

import org.assertj.core.api.Assertions.assertThat
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
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@dev.deskseed.testsupport.integration.DeskseedSpringIntegrationTest(
    properties = ["deskseed.test.context-group=agent-customer-search"],
)
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension::class)
@dev.deskseed.testsupport.category.IntegrationTest
class AgentCustomerSearchIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun clearState() {
        if (tableExists("search_audit_customer_result_items")) {
            jdbcTemplate.execute(
                "truncate table search_audit_customer_result_items, search_audit_query_ciphertexts, " +
                    "search_audit_result_items, search_audit_details, access_audit_events",
            )
        } else if (tableExists("access_audit_events")) {
            jdbcTemplate.execute("truncate table access_audit_events")
        }
        jdbcTemplate.update("delete from group_memberships")
        jdbcTemplate.update("delete from support_groups")
        jdbcTemplate.update("delete from customers")
        jdbcTemplate.update("delete from staff_login_throttles")
        jdbcTemplate.update("delete from staff_accounts")
    }

    @Test
    fun `search matches customer by name or email substring and commits protected query and result audit`() {
        val agent = insertStaff("customer-search@example.com", "Agent password 42", "검색 상담사")
        val minA = insertCustomer("김민아", "mina.kim@example.test")
        insertCustomer("박서준", "seojun.park@example.test")
        val minB = insertCustomer("최민준", "minjun.choi@example.test")
        val browser = login("customer-search@example.com", "Agent password 42")
        val interactionId = UUID.randomUUID()

        val response = mockMvc.perform(search(browser, interactionId, """{"query":"민","limit":10}"""))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.searchInteractionId").value(interactionId.toString()))
            .andExpect(jsonPath("$.resultCount").value(2))
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].name").value("김민아"))
            .andExpect(jsonPath("$.items[0].verified").value(false))
            .andExpect(jsonPath("$.items[1].name").value("최민준"))
            .andReturn().response.contentAsString

        val searchEventId = UUID.fromString(stringField(response, "searchEventId"))
        val event = jdbcTemplate.queryForMap(
            """
            select actor_id, source, action, resource_type, interaction_id, session_fingerprint,
                   auth_type, outcome, http_status
            from access_audit_events where id = ?
            """.trimIndent(),
            searchEventId,
        )
        assertThat(event["actor_id"]).isEqualTo(agent)
        assertThat(event["action"]).isEqualTo("CUSTOMER_SEARCH_EXECUTED")
        assertThat(event["resource_type"]).isEqualTo("SEARCH")
        assertThat(event["interaction_id"]).isEqualTo(interactionId)
        assertThat(event["outcome"]).isEqualTo("SUCCEEDED")
        assertThat(event["http_status"]).isEqualTo(200)

        val detail = jdbcTemplate.queryForMap(
            "select query_redacted, result_count from search_audit_details where access_event_id = ?",
            searchEventId,
        )
        assertThat(detail["query_redacted"]).isEqualTo("[PROTECTED]")
        assertThat(detail["result_count"]).isEqualTo(2L)
        assertThat(
            jdbcTemplate.queryForList(
                """
                select customer_id from search_audit_customer_result_items
                where access_event_id = ? order by result_ordinal
                """.trimIndent(),
                UUID::class.java,
                searchEventId,
            ),
        ).containsExactly(minA, minB)
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from search_audit_query_ciphertexts where access_event_id = ?",
                Long::class.java,
                searchEventId,
            ),
        ).isEqualTo(1L)
    }

    @Test
    fun `no matches still records an audited search execution with an empty result set`() {
        val agent = insertStaff("empty-search@example.com", "Agent password 42", "빈검색 상담사")
        insertCustomer("이서연", "seoyeon.lee@example.test")
        val browser = login("empty-search@example.com", "Agent password 42")

        val response = mockMvc.perform(search(browser, UUID.randomUUID(), """{"query":"존재하지않음","limit":10}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.resultCount").value(0))
            .andExpect(jsonPath("$.items.length()").value(0))
            .andReturn().response.contentAsString

        val searchEventId = UUID.fromString(stringField(response, "searchEventId"))
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from access_audit_events where id = ? and action = 'CUSTOMER_SEARCH_EXECUTED'",
                Long::class.java,
                searchEventId,
            ),
        ).isEqualTo(1L)
    }

    @Test
    fun `resultCount reflects the true total match count, not just the returned page size`() {
        val agent = insertStaff("paged-search@example.com", "Agent password 42", "페이지 상담사")
        repeat(3) { index -> insertCustomer("페이지고객$index", "paged-$index@example.test") }
        val browser = login("paged-search@example.com", "Agent password 42")

        mockMvc.perform(search(browser, UUID.randomUUID(), """{"query":"페이지고객","limit":2}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.resultCount").value(3))
    }

    @Test
    fun `blank query and out-of-range limit are rejected before any search executes`() {
        val agent = insertStaff("invalid-search@example.com", "Agent password 42", "검증 상담사")
        val browser = login("invalid-search@example.com", "Agent password 42")

        mockMvc.perform(search(browser, UUID.randomUUID(), """{"query":"","limit":10}"""))
            .andExpect(status().isBadRequest)
        mockMvc.perform(search(browser, UUID.randomUUID(), """{"query":"홍길동","limit":0}"""))
            .andExpect(status().isBadRequest)
        mockMvc.perform(search(browser, UUID.randomUUID(), """{"query":"홍길동","limit":26}"""))
            .andExpect(status().isBadRequest)

        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from access_audit_events where actor_id = ?",
                Long::class.java,
                agent,
            ),
        ).isZero()
    }

    @Test
    fun `unauthenticated search is rejected`() {
        val anonymousCsrf = mockMvc.perform(get("/api/v1/agent/csrf"))
            .andExpect(status().isOk)
            .andReturn()
        mockMvc.perform(
            post("/api/v1/agent/customers/search")
                .session(anonymousCsrf.request.session as MockHttpSession)
                .header("X-CSRF-TOKEN", stringField(anonymousCsrf.response.contentAsString, "token"))
                .header("X-Interaction-Id", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query":"홍길동","limit":10}"""),
        ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `search audit insert failure fails closed and leaks no customer data`() {
        val agent = insertStaff("strict-customer@example.com", "Agent password 42", "Strict 상담사")
        insertCustomer("정하은", "haeun-strict@example.test")
        val browser = login("strict-customer@example.com", "Agent password 42")

        jdbcTemplate.execute(
            """
            create or replace function fail_customer_search_audit_insert() returns trigger language plpgsql as ${'$'}${'$'}
            begin raise exception 'injected customer search audit failure'; end; ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            "create trigger fail_customer_search_audit before insert on access_audit_events " +
                "for each row execute function fail_customer_search_audit_insert()",
        )
        try {
            val response = mockMvc.perform(search(browser, UUID.randomUUID(), """{"query":"정하은","limit":10}"""))
                .andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.type").value("/problems/audit-write-unavailable"))
                .andExpect(jsonPath("$.items").doesNotExist())
                .andReturn().response.contentAsString
            assertThat(response).doesNotContain("정하은").doesNotContain("haeun-strict")
        } finally {
            jdbcTemplate.execute("drop trigger if exists fail_customer_search_audit on access_audit_events")
            jdbcTemplate.execute("drop function if exists fail_customer_search_audit_insert()")
        }
    }

    @Test
    fun `raw query never appears in captured application output`(output: CapturedOutput) {
        val browser = login("logs-customer@example.com", "Agent password 42", createStaff = true)
        val rawQuery = "token=raw-secret-value-that-must-not-leak"

        mockMvc.perform(search(browser, UUID.randomUUID(), """{"query":"$rawQuery","limit":10}"""))
            .andExpect(status().isOk)

        assertThat(output.out).doesNotContain(rawQuery).doesNotContain("raw-secret-value-that-must-not-leak")
        assertThat(output.err).doesNotContain(rawQuery).doesNotContain("raw-secret-value-that-must-not-leak")
    }

    @Test
    fun `customer search result audit rows are append-only`() {
        val agent = insertStaff("immutable-customer@example.com", "Agent password 42", "불변 상담사")
        insertCustomer("한지민", "jimin.han@example.test")
        val browser = login("immutable-customer@example.com", "Agent password 42")
        val response = mockMvc.perform(search(browser, UUID.randomUUID(), """{"query":"한지민","limit":10}"""))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        val searchEventId = UUID.fromString(stringField(response, "searchEventId"))

        assertThat(
            org.assertj.core.api.Assertions.catchThrowable {
                jdbcTemplate.update(
                    "update search_audit_customer_result_items set result_ordinal = result_ordinal + 1 where access_event_id = ?",
                    searchEventId,
                )
            },
        ).isNotNull()
    }

    private fun search(session: MockHttpSession, interactionId: UUID, body: String) =
        post("/api/v1/agent/customers/search")
            .session(session)
            .header("X-CSRF-TOKEN", csrf(session))
            .header("X-Interaction-Id", interactionId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body)

    private fun csrf(session: MockHttpSession): String {
        val result = mockMvc.perform(get("/api/v1/agent/csrf").session(session))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        return stringField(result, "token")
    }

    private fun login(email: String, password: String, createStaff: Boolean = false): MockHttpSession {
        if (createStaff) {
            insertStaff(email, password, "임시 상담사")
        }
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

    private fun insertCustomer(name: String, email: String): UUID {
        val id = UUID.randomUUID()
        val now = Instant.parse("2026-08-11T00:00:00Z")
        jdbcTemplate.update(
            """
            insert into customers (id, name, email_normalized, email_display, created_at, updated_at)
            values (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            id,
            name,
            email.lowercase(),
            email,
            Timestamp.from(now),
            Timestamp.from(now),
        )
        return id
    }

    private fun tableExists(name: String): Boolean = jdbcTemplate.queryForObject(
        "select to_regclass(?) is not null",
        Boolean::class.java,
        name,
    ) == true

    private fun stringField(json: String, field: String): String =
        Regex("\"$field\":\"([^\"]+)\"").find(json)!!.groupValues[1]

}
