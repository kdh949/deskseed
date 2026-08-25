package dev.deskseed.staffaccess.internal

import dev.deskseed.outboundmail.OutboundMailOperations
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@dev.deskseed.testsupport.integration.DeskseedSpringIntegrationTest
@AutoConfigureMockMvc
@dev.deskseed.testsupport.category.IntegrationTest
class AdminOutboundMailIntegrationTest {
    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate
    @Autowired private lateinit var operations: OutboundMailOperations

    @BeforeEach
    fun clearState() {
        jdbcTemplate.execute(
            """
            truncate table
                outbound_mail_delivery_events,
                outbound_mail_attempts,
                outbound_mail_intents,
                admin_security_audit_events,
                staff_authority_grants,
                staff_login_throttles,
                staff_accounts
            restart identity cascade
            """.trimIndent(),
        )
    }

    @Test
    fun `admin mail operations expose only masked fields signed cursors and auditable same intent retry`() {
        val adminId = insertStaff("mail-admin@example.com", "Mail admin password 42", "ADMIN")
        val browser = login("mail-admin@example.com", "Mail admin password 42")
        val rawRecipient = "private-recipient@example.com"
        val rawSubject = "private outbound subject"
        val rawBody = "private magic link and request token"
        val firstIntentId = insertIntent(rawRecipient, rawSubject, rawBody, Instant.parse("2026-08-15T00:00:00Z"))
        insertAttempt(firstIntentId, "provider-response-private", "PROVIDER_PRIVATE")
        val rawProviderFailure = "provider response private detail"
        jdbcTemplate.update("update outbound_mail_intents set last_error_code = ? where id = ?", rawProviderFailure, firstIntentId)
        insertIntent("second-recipient@example.com", "second subject", "second body", Instant.parse("2026-08-14T00:00:00Z"))

        val firstPage = mockMvc.perform(get("/api/v1/admin/mail/intents?limit=1").session(browser.session))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].recipientMasked").value("***@example.com"))
            .andExpect(jsonPath("$.items[0].lastErrorCode").value("MAIL_DELIVERY_FAILURE"))
            .andExpect(jsonPath("$.items[0].attempts.length()").value(0))
            .andExpect(jsonPath("$.nextCursor").isNotEmpty)
            .andReturn()
        val firstPageBody = firstPage.response.contentAsString
        assertThat(firstPageBody)
            .doesNotContain(rawRecipient, rawSubject, rawBody, rawProviderFailure, "provider-response-private", "protectedBodyCiphertext")
        val cursor = Regex("\\\"nextCursor\\\":\\\"([^\\\"]+)\\\"").find(firstPageBody)!!.groupValues[1]

        mockMvc.perform(get("/api/v1/admin/mail/intents?limit=1&cursor=$cursor").session(browser.session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(1))
        mockMvc.perform(get("/api/v1/admin/mail/intents?limit=1&cursor=${cursor}tampered").session(browser.session))
            .andExpect(status().isBadRequest)

        val detail = mockMvc.perform(get("/api/v1/admin/mail/intents/{intentId}", firstIntentId).session(browser.session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.recipientMasked").value("***@example.com"))
            .andExpect(jsonPath("$.lastErrorCode").value("MAIL_DELIVERY_FAILURE"))
            .andExpect(jsonPath("$.attempts[0].failureCode").value("PROVIDER_PRIVATE"))
            .andReturn()
        assertThat(detail.response.contentAsString)
            .doesNotContain(rawRecipient, rawSubject, rawBody, rawProviderFailure, "provider-response-private")

        mockMvc.perform(get("/api/v1/admin/mail/summary").session(browser.session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.failedCount").value(2))
            .andExpect(jsonPath("$.transport").value("DISABLED"))

        mockMvc.perform(
            post("/api/v1/admin/mail/intents/{intentId}/retry", firstIntentId)
                .session(browser.session)
                .header("X-CSRF-TOKEN", browser.csrfToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"reason":"SMTP 수신 거부 원인을 확인해 재시도"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(firstIntentId.toString()))
            .andExpect(jsonPath("$.status").value("QUEUED"))
            .andExpect(jsonPath("$.recipientMasked").value("***@example.com"))

        assertThat(
            jdbcTemplate.queryForMap(
                """
                select actor_id, actor_display_snapshot, source, target_type, target_id, metadata_json
                from admin_security_audit_events
                where event_type = 'OUTBOUND_MAIL_MANUAL_RETRY_REQUESTED'
                """.trimIndent(),
            ),
        ).containsEntry("actor_id", adminId)
            .containsEntry("actor_display_snapshot", "관리자")
            .containsEntry("source", "ADMIN_UI")
            .containsEntry("target_type", "OUTBOUND_MAIL_INTENT")
            .containsEntry("target_id", firstIntentId)
            .doesNotContainValue("SMTP 수신 거부 원인을 확인해 재시도")
    }

    @Test
    fun `admin mail operations require an admin session and csrf for retry`() {
        insertIntent("locked@example.com", "locked", "locked", Instant.parse("2026-08-15T00:00:00Z"))
        mockMvc.perform(get("/api/v1/admin/mail/summary"))
            .andExpect(status().isUnauthorized)
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))

        insertStaff("mail-agent@example.com", "Mail agent password 42", "AGENT")
        val agent = login("mail-agent@example.com", "Mail agent password 42")
        mockMvc.perform(get("/api/v1/admin/mail/summary").session(agent.session))
            .andExpect(status().isForbidden)
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))

        val intentId = insertIntent("retry@example.com", "retry", "retry", Instant.parse("2026-08-14T00:00:00Z"))
        insertStaff("csrf-admin@example.com", "CSRF admin password 42", "ADMIN")
        val admin = login("csrf-admin@example.com", "CSRF admin password 42")
        mockMvc.perform(
            post("/api/v1/admin/mail/intents/{intentId}/retry", intentId)
                .session(admin.session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"reason":"CSRF 누락"}"""),
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `agent cannot bypass outbound mail operation method authorization`() {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken.authenticated(
            "mail-agent",
            null,
            listOf(SimpleGrantedAuthority("ROLE_AGENT")),
        )
        try {
            assertThatThrownBy { operations.summary() }.isInstanceOf(AccessDeniedException::class.java)
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    private fun insertIntent(recipient: String, subject: String, body: String, queuedAt: Instant): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            """
            insert into outbound_mail_intents (
                id, idempotency_key, stable_message_id, template_key, template_version,
                sender_address, recipient_address, subject, text_body,
                actor_type, source, request_id, correlation_id, command_id,
                status, attempt_count, cycle_attempt_count, max_attempts, retry_cycle, manual_retry_count,
                next_attempt_at, lease_expires_at, last_error_code, queued_at, sent_at, failed_at, version
            ) values (?, ?, ?, 'REQUEST_RECEIVED', 1, ?, ?, ?, ?,
                'STAFF', 'ADMIN_UI', 'mail-admin-request', 'mail-admin-correlation', ?,
                'FAILED', 1, 1, 2, 0, 0,
                null, null, 'RECIPIENT_REJECTED', ?, null, ?, 0)
            """.trimIndent(),
            id,
            "admin-mail:$id",
            "<admin-mail-$id@deskseed.example>",
            "no-reply@deskseed.example",
            recipient,
            subject,
            body,
            "mail-admin-command-$id",
            Timestamp.from(queuedAt),
            Timestamp.from(queuedAt.plusSeconds(60)),
        )
        return id
    }

    private fun insertAttempt(intentId: UUID, providerMessageId: String, failureCode: String) {
        jdbcTemplate.update(
            """
            insert into outbound_mail_attempts (
                id, intent_id, attempt_number, retry_cycle, cycle_attempt_number, provider,
                status, provider_message_id, failure_class, failure_code, started_at, finished_at, next_retry_at
            ) values (?, ?, 1, 0, 1, 'SMTP', 'PERMANENT_FAILED', ?, 'PERMANENT', ?, now(), now(), null)
            """.trimIndent(),
            UUID.randomUUID(),
            intentId,
            providerMessageId,
            failureCode,
        )
    }

    private fun login(email: String, password: String): Browser {
        val csrfResult = mockMvc.perform(get("/api/v1/agent/csrf")).andExpect(status().isOk).andReturn()
        val token = Regex("\\\"token\\\":\\\"([^\\\"]+)\\\"").find(csrfResult.response.contentAsString)!!.groupValues[1]
        val session = csrfResult.request.session as MockHttpSession
        val loginResult = mockMvc.perform(
            post("/api/v1/agent/session")
                .session(session)
                .header("X-CSRF-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email","password":"$password"}"""),
        ).andExpect(status().isNoContent).andReturn()
        return Browser(loginResult.request.session as MockHttpSession, token)
    }

    private fun insertStaff(email: String, password: String, role: String): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            """
            insert into staff_accounts
                (id, email_normalized, email_display, display_name, role, status,
                 password_hash, created_at, updated_at, version)
            values (?, ?, ?, ?, ?, 'ACTIVE', ?, now(), now(), 0)
            """.trimIndent(),
            id,
            email.lowercase(),
            email,
            if (role == "ADMIN") "관리자" else "상담사",
            role,
            BCryptPasswordEncoder(4).encode(password),
        )
        return id
    }

    private data class Browser(val session: MockHttpSession, val csrfToken: String)

}
