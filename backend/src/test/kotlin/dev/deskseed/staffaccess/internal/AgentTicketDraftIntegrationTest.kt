package dev.deskseed.staffaccess.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
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
class AgentTicketDraftIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun clearState() {
        jdbc.execute(
            """
            truncate table
                ticket_drafts,
                access_audit_events,
                admin_security_audit_events,
                ticket_audit_events,
                ticket_audits,
                ticket_comments,
                request_access_tokens,
                tickets,
                customers,
                group_memberships,
                support_groups,
                staff_login_throttles,
                staff_accounts
            restart identity cascade
            """.trimIndent(),
        )
        jdbc.update("update ticket_draft_cleanup_lease set lease_owner = null, lease_expires_at = null")
    }

    @Test
    fun `owner-only drafts preserve channel separation optimistic conflict and ticket audit invariants`() {
        val owner = insertStaff("draft-owner@example.com", "Draft owner")
        insertStaff("draft-other@example.com", "Draft other")
        val ticket = insertTicket(7101, "Cross-device recovery", "OPEN")
        val ownerBrowser = login("draft-owner@example.com")
        val ticketUpdatedAt = jdbc.queryForObject(
            "select updated_at from tickets where id = ?",
            Timestamp::class.java,
            ticket.id,
        )!!.toInstant()

        mockMvc.perform(
            saveDraft(ownerBrowser, ticket.number, "PUBLIC_REPLY", 0, "공개 답변 초안"),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.channel").value("PUBLIC_REPLY"))
            .andExpect(jsonPath("$.body").value("공개 답변 초안"))
            .andExpect(jsonPath("$.draftVersion").value(1))

        mockMvc.perform(get(draftPath(ticket.number, "PUBLIC_REPLY")).session(ownerBrowser.session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.body").value("공개 답변 초안"))

        val otherBrowser = login("draft-other@example.com")
        mockMvc.perform(get(draftPath(ticket.number, "PUBLIC_REPLY")).session(otherBrowser.session))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.type").value("/problems/ticket-draft-not-found"))

        mockMvc.perform(
            saveDraft(ownerBrowser, ticket.number, "PUBLIC_REPLY", 0, "silent overwrite"),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.type").value("/problems/ticket-draft-conflict"))
            .andExpect(jsonPath("$.currentVersion").value(1))

        mockMvc.perform(
            saveDraft(ownerBrowser, ticket.number, "PUBLIC_REPLY", 1, "공개 답변 갱신"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.draftVersion").value(2))
        mockMvc.perform(
            saveDraft(ownerBrowser, ticket.number, "INTERNAL_NOTE", 0, "내부 메모 초안"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.draftVersion").value(1))

        mockMvc.perform(
            delete(draftPath(ticket.number, "PUBLIC_REPLY"))
                .session(ownerBrowser.session)
                .header("X-CSRF-TOKEN", ownerBrowser.csrfToken)
                .queryParam("expectedDraftVersion", "1"),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.currentVersion").value(2))
        mockMvc.perform(
            delete(draftPath(ticket.number, "PUBLIC_REPLY"))
                .session(ownerBrowser.session)
                .header("X-CSRF-TOKEN", ownerBrowser.csrfToken)
                .queryParam("expectedDraftVersion", "2"),
        ).andExpect(status().isNoContent)

        mockMvc.perform(get(draftPath(ticket.number, "PUBLIC_REPLY")).session(ownerBrowser.session))
            .andExpect(status().isNotFound)
        mockMvc.perform(get(draftPath(ticket.number, "INTERNAL_NOTE")).session(ownerBrowser.session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.body").value("내부 메모 초안"))
        mockMvc.perform(get("/api/v1/agent/drafts/recoverable").session(ownerBrowser.session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].channel").value("INTERNAL_NOTE"))

        assertThat(
            jdbc.queryForObject("select updated_at from tickets where id = ?", Timestamp::class.java, ticket.id)!!.toInstant(),
        ).isEqualTo(ticketUpdatedAt)
        assertThat(jdbc.queryForObject("select count(*) from ticket_audits where ticket_id = ?", Long::class.java, ticket.id))
            .isZero()
        assertThat(jdbc.queryForObject("select count(*) from access_audit_events", Long::class.java)).isZero()
    }

    @Test
    fun `CLOSED ticket allows an existing owner draft to be read and cleared but rejects save`() {
        val owner = insertStaff("closed-draft-owner@example.com", "Closed draft owner")
        val ticket = insertTicket(7102, "Closed recovery", "CLOSED")
        jdbc.update(
            """
            insert into ticket_drafts (
                owner_staff_id, ticket_id, composer_channel, body, attachment_ids, client_device_id,
                base_ticket_version, draft_version, created_at, updated_at, expires_at
            ) values (?, ?, 'PUBLIC_REPLY', 'existing draft', '{}', ?, 0, 1, clock_timestamp(), clock_timestamp(), clock_timestamp() + interval '30 days')
            """.trimIndent(),
            owner,
            ticket.id,
            UUID.randomUUID(),
        )
        val browser = login("closed-draft-owner@example.com")

        mockMvc.perform(saveDraft(browser, ticket.number, "PUBLIC_REPLY", 1, "new closed draft"))
            .andExpect(status().isUnprocessableContent)
            .andExpect(jsonPath("$.type").value("/problems/ticket-draft-closed"))
        mockMvc.perform(get(draftPath(ticket.number, "PUBLIC_REPLY")).session(browser.session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.body").value("existing draft"))
        mockMvc.perform(
            delete(draftPath(ticket.number, "PUBLIC_REPLY"))
                .session(browser.session)
                .header("X-CSRF-TOKEN", browser.csrfToken)
                .queryParam("expectedDraftVersion", "1"),
        ).andExpect(status().isNoContent)
    }

    @Test
    fun `draft rejects another staff member's pending attachment reference`() {
        val owner = insertStaff("draft-attachment-owner@example.com", "Draft attachment owner")
        val other = insertStaff("draft-attachment-other@example.com", "Draft attachment other")
        val ticket = insertTicket(7103, "Attachment ownership", "OPEN")
        val attachmentId = insertCleanStaffAttachment(other)
        val browser = login("draft-attachment-owner@example.com")

        mockMvc.perform(
            put(draftPath(ticket.number, "PUBLIC_REPLY"))
                .session(browser.session)
                .header("X-CSRF-TOKEN", browser.csrfToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "body": "attachment draft",
                      "attachmentIds": ["$attachmentId"],
                      "clientDeviceId": "018f7c2c-7348-7a32-a971-4c9a845b3312",
                      "baseTicketVersion": 0,
                      "expectedDraftVersion": 0
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.type").value("/problems/validation"))
        assertThat(jdbc.queryForObject("select count(*) from ticket_drafts", Long::class.java)).isZero()
        assertThat(jdbc.queryForObject("select count(*) from ticket_audits", Long::class.java)).isZero()
        assertThat(owner).isNotEqualTo(other)
    }

    private fun saveDraft(
        browser: Browser,
        ticketNumber: Long,
        channel: String,
        expectedDraftVersion: Long,
        body: String,
    ) = put(draftPath(ticketNumber, channel))
        .session(browser.session)
        .header("X-CSRF-TOKEN", browser.csrfToken)
        .header("X-Request-Id", "draft-request-$expectedDraftVersion")
        .header("X-Correlation-Id", "draft-correlation-$expectedDraftVersion")
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            """
            {
              "body": "$body",
              "attachmentIds": [],
              "clientDeviceId": "018f7c2c-7348-7a32-a971-4c9a845b3311",
              "baseTicketVersion": 0,
              "expectedDraftVersion": $expectedDraftVersion
            }
            """.trimIndent(),
        )

    private fun draftPath(ticketNumber: Long, channel: String) =
        "/api/v1/agent/tickets/$ticketNumber/drafts/$channel"

    private fun login(email: String): Browser {
        val csrfResult = mockMvc.perform(get("/api/v1/agent/csrf")).andExpect(status().isOk).andReturn()
        val csrfToken = Regex("\\\"token\\\":\\\"([^\\\"]+)\\\"").find(csrfResult.response.contentAsString)!!.groupValues[1]
        val session = csrfResult.request.session as MockHttpSession
        val login = mockMvc.perform(
            post("/api/v1/agent/session")
                .session(session)
                .header("X-CSRF-TOKEN", csrfToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email","password":"$PASSWORD"}"""),
        ).andExpect(status().isNoContent).andReturn()
        return Browser(login.request.session as MockHttpSession, csrfToken)
    }

    private fun insertStaff(email: String, displayName: String): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            """
            insert into staff_accounts (id, email_normalized, email_display, display_name, role, status, password_hash, created_at, updated_at, version)
            values (?, ?, ?, ?, 'AGENT', 'ACTIVE', ?, clock_timestamp(), clock_timestamp(), 0)
            """.trimIndent(),
            id,
            email,
            email,
            displayName,
            BCryptPasswordEncoder(4).encode(PASSWORD),
        )
        return id
    }

    private fun insertTicket(number: Long, subject: String, status: String): TicketFixture {
        val customerId = UUID.randomUUID()
        val ticketId = UUID.randomUUID()
        jdbc.update(
            "insert into customers (id, name, email_normalized, email_display, created_at, updated_at) values (?, 'Draft customer', ?, ?, clock_timestamp(), clock_timestamp())",
            customerId,
            "draft-customer-$number@example.test",
            "draft-customer-$number@example.test",
        )
        jdbc.update(
            """
            insert into tickets (id, ticket_number, requester_id, kind, subject, status, priority, channel, version, created_at, updated_at)
            values (?, ?, ?, 'CUSTOMER_REQUEST', ?, ?, 'NORMAL', 'WEB', 0, clock_timestamp(), clock_timestamp())
            """.trimIndent(),
            ticketId,
            number,
            customerId,
            subject,
            status,
        )
        return TicketFixture(ticketId, number)
    }

    private fun insertCleanStaffAttachment(ownerStaffId: UUID): UUID {
        val id = UUID.randomUUID()
        val now = Instant.now()
        jdbc.update(
            """
            insert into attachment_objects (
                id, storage_key, uploaded_actor_type, uploaded_actor_id, bound_ticket_id, allowed_visibility,
                initial_public_submission, file_name, declared_content_type, detected_content_type, content_type,
                size_bytes, sha256, scan_status, created_at, scanned_at, linked_at, expires_at
            ) values (?, ?, 'STAFF', ?, null, null, false, 'draft.txt', 'text/plain', 'text/plain', 'text/plain',
                      1, 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', 'CLEAN', ?, ?, null, ?)
            """.trimIndent(),
            id,
            "attachments/quarantine/$id",
            ownerStaffId,
            Timestamp.from(now),
            Timestamp.from(now),
            Timestamp.from(now.plusSeconds(3600)),
        )
        return id
    }

    private data class Browser(val session: MockHttpSession, val csrfToken: String)

    private data class TicketFixture(val id: UUID, val number: Long)

    private companion object {
        const val PASSWORD = "Agent password 42!"

        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
    }
}
