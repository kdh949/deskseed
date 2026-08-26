package dev.deskseed.staffaccess.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@dev.deskseed.testsupport.integration.DeskseedSpringIntegrationTest(
    properties = ["deskseed.test.context-group=admin-webhook"],
)
@AutoConfigureMockMvc
@dev.deskseed.testsupport.category.IntegrationTest
class AdminWebhookIntegrationTest {
    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun clearState() {
        jdbcTemplate.execute(
            "truncate table webhook_delivery_attempts, webhook_deliveries, webhook_subscriptions, webhook_endpoint_secrets, " +
                "webhook_endpoints, staff_authority_grants, admin_security_audit_events, audit_activity_projection cascade",
        )
        jdbcTemplate.update("delete from staff_login_throttles")
        jdbcTemplate.execute("truncate table customer_consent_acceptances, customer_consent_policy_versions, customer_consent_policies cascade")
        jdbcTemplate.update("delete from staff_accounts")
    }

    @Test
    fun `admin creates rotates tests and replays endpoint without persisting raw secret`() {
        insertStaff("admin@example.com", "Admin password 42", "ADMIN")
        val browser = login("admin@example.com", "Admin password 42")
        val create = mockMvc.perform(
            post("/api/v1/admin/integrations/webhooks")
                .session(browser.session).csrf(browser)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"name":"orders","url":"https://203.0.113.10/hooks","subscriptions":[{"eventType":"ticket.created","version":1,"payloadPolicy":"METADATA_ONLY"}]}""",
                ),
        )
            .andExpect(status().isCreated)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.endpoint.targetClass").value("PUBLIC"))
            .andExpect(jsonPath("$.secret").isNotEmpty)
            .andReturn().response.contentAsString
        val endpointId = UUID.fromString(Regex("\\\"id\\\":\\\"([^\\\"]+)\\\"").find(create)!!.groupValues[1])
        val secret = Regex("\\\"secret\\\":\\\"([^\\\"]+)\\\"").find(create)!!.groupValues[1]

        val stored = jdbcTemplate.queryForObject("select encode(ciphertext, 'base64') from webhook_endpoint_secrets where endpoint_id = ?", String::class.java, endpointId)!!
        assertThat(stored).doesNotContain(secret)
        mockMvc.perform(get("/api/v1/admin/integrations/webhooks/{id}", endpointId).session(browser.session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.secret").doesNotExist())
            .andExpect(jsonPath("$.deliverySummary.totalDeliveries").value(0))

        mockMvc.perform(
            post("/api/v1/admin/integrations/webhooks/{id}/rotate-secret", endpointId)
                .session(browser.session).csrf(browser).contentType(MediaType.APPLICATION_JSON)
                .content("""{"overlapSeconds":0,"reason":"scheduled rotation"}"""),
        ).andExpect(status().isOk).andExpect(jsonPath("$.secret").isNotEmpty)

        val delivery = mockMvc.perform(
            post("/api/v1/admin/integrations/webhooks/{id}/test-deliveries", endpointId)
                .session(browser.session).csrf(browser).contentType(MediaType.APPLICATION_JSON)
                .content("""{"reason":"test receiver"}"""),
        ).andExpect(status().isAccepted).andExpect(jsonPath("$.status").value("PENDING")).andReturn().response.contentAsString
        val deliveryId = UUID.fromString(Regex("\\\"id\\\":\\\"([^\\\"]+)\\\"").find(delivery)!!.groupValues[1])
        jdbcTemplate.update(
            """
            insert into webhook_delivery_attempts
                (id, delivery_id, attempt_number, request_timestamp, response_status, response_headers_json,
                 response_summary, latency_millis, error_category, completed_at)
            values (?, ?, 1, now(), 503, cast(? as jsonb), ?, 42, 'HTTP_503', now())
            """.trimIndent(),
            UUID.randomUUID(), deliveryId, """{"authorization":"must-not-leak"}""", "receiver body must not leak",
        )
        val detail = mockMvc.perform(
            get("/api/v1/admin/integrations/webhooks/{endpointId}/deliveries/{deliveryId}", endpointId, deliveryId)
                .session(browser.session),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.delivery.id").value(deliveryId.toString()))
            .andExpect(jsonPath("$.attempts[0].attemptNumber").value(1))
            .andExpect(jsonPath("$.attempts[0].responseStatus").value(503))
            .andExpect(jsonPath("$.attempts[0].latencyMillis").value(42))
            .andExpect(jsonPath("$.attempts[0].responseSummary").doesNotExist())
            .andExpect(jsonPath("$.attempts[0].responseHeaders").doesNotExist())
            .andReturn().response.contentAsString
        assertThat(detail).doesNotContain("must-not-leak", "receiver body must not leak")
        jdbcTemplate.update(
            "update webhook_deliveries set status = 'DEAD_LETTERED', next_attempt_at = null, completed_at = now(), updated_at = now(), error_category = 'HTTP_503' where id = ?",
            deliveryId,
        )
        mockMvc.perform(get("/api/v1/admin/integrations/webhooks/{id}", endpointId).session(browser.session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.deliverySummary.totalDeliveries").value(1))
            .andExpect(jsonPath("$.deliverySummary.deadLetteredDeliveries").value(1))
            .andExpect(jsonPath("$.deliverySummary.lastFailureAt").isNotEmpty)
            .andExpect(jsonPath("$.deliverySummary.lastFailureCategory").value("HTTP_503"))
        mockMvc.perform(
            post("/api/v1/admin/integrations/webhooks/{endpointId}/deliveries/{deliveryId}/replay", endpointId, deliveryId)
                .session(browser.session).csrf(browser).contentType(MediaType.APPLICATION_JSON)
                .content("""{"reason":"receiver recovered"}"""),
        ).andExpect(status().isAccepted).andExpect(jsonPath("$.status").value("PENDING"))

        val events = jdbcTemplate.queryForList(
            "select event_type from admin_security_audit_events where target_id = ? order by occurred_at, id",
            String::class.java, endpointId,
        )
        assertThat(events).contains("WEBHOOK_ENDPOINT_CREATED", "WEBHOOK_SECRET_ROTATED", "WEBHOOK_TEST_DELIVERY_REQUESTED", "WEBHOOK_DELIVERY_REPLAY_REQUESTED")
    }

    @Test
    fun `agent cannot access webhook administration`() {
        insertStaff("agent@example.com", "Agent password 42", "AGENT")
        val browser = login("agent@example.com", "Agent password 42")
        mockMvc.perform(get("/api/v1/admin/integrations/webhooks").session(browser.session))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `admin archives only an inactive endpoint and cancels pending history without requeueing`() {
        insertStaff("archive-admin@example.com", "Admin password 42", "ADMIN")
        val browser = login("archive-admin@example.com", "Admin password 42")
        val created = mockMvc.perform(
            post("/api/v1/admin/integrations/webhooks").session(browser.session).csrf(browser)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"name":"archive-target","url":"https://203.0.113.20/hooks","subscriptions":[{"eventType":"ticket.created","version":1,"payloadPolicy":"METADATA_ONLY"}]}""",
                ),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val endpointId = UUID.fromString(Regex("\\\"id\\\":\\\"([^\\\"]+)\\\"").find(created)!!.groupValues[1])

        mockMvc.perform(
            post("/api/v1/admin/integrations/webhooks/{id}/archive", endpointId).session(browser.session).csrf(browser)
                .contentType(MediaType.APPLICATION_JSON).content("""{"reason":"retire after migration"}"""),
        ).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("WEBHOOK_ENDPOINT_MUST_BE_DEACTIVATED"))

        val delivery = mockMvc.perform(
            post("/api/v1/admin/integrations/webhooks/{id}/test-deliveries", endpointId).session(browser.session).csrf(browser)
                .contentType(MediaType.APPLICATION_JSON).content("""{"reason":"queue before archive"}"""),
        ).andExpect(status().isAccepted).andReturn().response.contentAsString
        val deliveryId = UUID.fromString(Regex("\\\"id\\\":\\\"([^\\\"]+)\\\"").find(delivery)!!.groupValues[1])
        val currentEtag = mockMvc.perform(get("/api/v1/admin/integrations/webhooks/{id}", endpointId).session(browser.session))
            .andExpect(status().isOk).andReturn().response.getHeader("ETag")!!
        mockMvc.perform(
            patch("/api/v1/admin/integrations/webhooks/{id}", endpointId).session(browser.session).csrf(browser)
                .header("If-Match", currentEtag).contentType(MediaType.APPLICATION_JSON).content("""{"enabled":false}"""),
        ).andExpect(status().isOk)
        val archiveEtag = mockMvc.perform(
            post("/api/v1/admin/integrations/webhooks/{id}/archive", endpointId).session(browser.session).csrf(browser)
                .contentType(MediaType.APPLICATION_JSON).content("""{"reason":"retire after migration"}"""),
        ).andExpect(status().isOk).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.archivedAt").isNotEmpty).andReturn().response.getHeader("ETag")!!

        val list = mockMvc.perform(get("/api/v1/admin/integrations/webhooks").session(browser.session))
            .andExpect(status().isOk).andReturn().response.contentAsString
        assertThat(list).doesNotContain(endpointId.toString())
        mockMvc.perform(get("/api/v1/admin/integrations/webhooks/{id}", endpointId).session(browser.session))
            .andExpect(status().isOk).andExpect(jsonPath("$.archivedAt").isNotEmpty)
        mockMvc.perform(get("/api/v1/admin/integrations/webhooks/{id}/deliveries", endpointId).session(browser.session))
            .andExpect(status().isOk).andExpect(jsonPath("$[0].id").value(deliveryId.toString()))
            .andExpect(jsonPath("$[0].status").value("CANCELLED"))
        mockMvc.perform(get("/api/v1/admin/integrations/webhooks/{id}", endpointId).session(browser.session))
            .andExpect(status().isOk).andExpect(jsonPath("$.deliverySummary.totalDeliveries").value(1))
            .andExpect(jsonPath("$.deliverySummary.cancelledDeliveries").value(1))
            .andExpect(jsonPath("$.deliverySummary.lastFailureCategory").doesNotExist())
        mockMvc.perform(
            post("/api/v1/admin/integrations/webhooks/{id}/test-deliveries", endpointId).session(browser.session).csrf(browser)
                .contentType(MediaType.APPLICATION_JSON).content("""{"reason":"must not queue"}"""),
        ).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("WEBHOOK_ENDPOINT_ARCHIVED"))
        assertThat(archiveEtag).startsWith("\"webhook-v")
        assertThat(jdbcTemplate.queryForList("select event_type from admin_security_audit_events where target_id = ?", String::class.java, endpointId))
            .contains("WEBHOOK_ENDPOINT_UPDATED", "WEBHOOK_ENDPOINT_ARCHIVED")
    }

    private fun login(email: String, password: String): Browser {
        val csrf = mockMvc.perform(get("/api/v1/agent/csrf")).andExpect(status().isOk).andReturn()
        val token = Regex("\\\"token\\\":\\\"([^\\\"]+)\\\"").find(csrf.response.contentAsString)!!.groupValues[1]
        val session = csrf.request.session as MockHttpSession
        val result = mockMvc.perform(
            post("/api/v1/agent/session").session(session).header("X-CSRF-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON).content("""{"email":"$email","password":"$password"}"""),
        ).andExpect(status().isNoContent).andReturn()
        return Browser(result.request.session as MockHttpSession, token)
    }

    private fun insertStaff(email: String, password: String, role: String) {
        val now = Timestamp.from(Instant.parse("2026-08-18T00:00:00Z"))
        jdbcTemplate.update(
            """insert into staff_accounts (id, email_normalized, email_display, display_name, role, status, password_hash, created_at, updated_at, version)
               values (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, 0)""",
            UUID.randomUUID(), email.lowercase(), email, if (role == "ADMIN") "관리자" else "상담사", role,
            BCryptPasswordEncoder(4).encode(password), now, now,
        )
    }

    private fun org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder.csrf(browser: Browser) =
        header("X-CSRF-TOKEN", browser.csrfToken)

    private data class Browser(val session: MockHttpSession, val csrfToken: String)

}
