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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@dev.deskseed.testsupport.integration.DeskseedSpringIntegrationTest
@AutoConfigureMockMvc
@dev.deskseed.testsupport.category.IntegrationTest
class AgentTicketCollaborationIntegrationTest {
    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var databaseCleaner: dev.deskseed.testsupport.integration.StaffTicketTestDatabaseCleaner
    private val objectMapper = ObjectMapper()

    @BeforeEach
    fun clearState() {
        databaseCleaner.resetMutableStaffTicketState()
    }

    @Test
    fun `collaboration note atomically creates audit mention and body-free notification with replay semantics`() {
        val writer = insertStaff("collaboration-writer@example.com", "Alex Rivera")
        val mentioned = insertStaff("collaboration-mentioned@example.com", "Sam Lee")
        val group = insertGroup("Support", writer, mentioned)
        val ticket = insertAssignedTicket(8_219, group, writer)
        val writerBrowser = login("collaboration-writer@example.com")
        val commandId = UUID.randomUUID()

        val created = mockMvc.perform(createNote(writerBrowser, ticket.number, commandId, "  확인 부탁드립니다.  ", mentioned))
            .andExpect(status().isCreated)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.replayed").value(false))
            .andExpect(jsonPath("$.note.body").value("확인 부탁드립니다."))
            .andExpect(jsonPath("$.note.mentionedStaff[0].id").value(mentioned.toString()))
            .andReturn().response.contentAsString

        val noteId = UUID.fromString(field(created, "id"))
        val auditId = UUID.fromString(field(created, "auditId"))
        assertThat(count("ticket_collaboration_notes")).isEqualTo(1)
        assertThat(count("ticket_collaboration_note_mentions")).isEqualTo(1)
        assertThat(count("staff_notifications")).isEqualTo(1)
        assertThat(count("ticket_audits")).isEqualTo(1)
        assertThat(
            jdbc.queryForObject(
                "select event_type from ticket_audit_events where audit_id = ?",
                String::class.java,
                auditId,
            ),
        ).isEqualTo("COLLABORATION_NOTE_CREATED")
        val event = jdbc.queryForMap(
            "select new_value_json::text as value, metadata_json::text as metadata from ticket_audit_events where audit_id = ?",
            auditId,
        )
        assertThat(event["value"].toString()).contains(noteId.toString()).doesNotContain("확인 부탁드립니다")
        assertThat(event["metadata"].toString())
            .contains("contentLength", "contentSha256", "mentionCount")
            .doesNotContain("확인 부탁드립니다")
        assertThat(
            jdbc.queryForObject("select version from tickets where id = ?", Long::class.java, ticket.id),
        ).isZero()

        mockMvc.perform(createNote(writerBrowser, ticket.number, commandId, "확인 부탁드립니다.", mentioned))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.replayed").value(true))
            .andExpect(jsonPath("$.note.id").value(noteId.toString()))
        assertThat(count("ticket_collaboration_notes")).isEqualTo(1)
        assertThat(count("staff_notifications")).isEqualTo(1)
        assertThat(count("ticket_audits")).isEqualTo(1)

        mockMvc.perform(createNote(writerBrowser, ticket.number, commandId, "다른 본문", mentioned))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.type").value("/problems/client-command-id-reused"))

        mockMvc.perform(
            get("/api/v1/agent/tickets/{ticketNumber}/collaboration-notes", ticket.number)
                .session(writerBrowser.session)
                .header("X-Interaction-Id", UUID.randomUUID()),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.items[0].id").value(noteId.toString()))
            .andExpect(jsonPath("$.items[0].body").value("확인 부탁드립니다."))
        assertThat(count("access_audit_events")).isEqualTo(1)

        val mentionedBrowser = login("collaboration-mentioned@example.com")
        val notifications = mockMvc.perform(
            get("/api/v1/agent/notifications").session(mentionedBrowser.session),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.unreadCount").value(1))
            .andExpect(jsonPath("$.items[0].ticketNumber").value(ticket.number))
            .andExpect(jsonPath("$.items[0].noteId").value(noteId.toString()))
            .andExpect(jsonPath("$.items[0].body").doesNotExist())
            .andReturn().response.contentAsString
        val notificationId = UUID.fromString(field(notifications, "id"))

        mockMvc.perform(
            put("/api/v1/agent/notifications/{notificationId}/read", notificationId)
                .session(mentionedBrowser.session)
                .header("X-CSRF-TOKEN", mentionedBrowser.csrfToken),
        ).andExpect(status().isNoContent)
        mockMvc.perform(get("/api/v1/agent/notifications").session(mentionedBrowser.session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.unreadCount").value(0))
            .andExpect(jsonPath("$.items[0].readAt").isNotEmpty)
    }

    @Test
    fun `collaboration note rejects inactive or duplicate mention without partial audit`() {
        val writer = insertStaff("collaboration-policy-writer@example.com", "Policy writer")
        val inactive = insertStaff("collaboration-inactive@example.com", "Inactive staff", status = "DISABLED")
        val active = insertStaff("collaboration-active@example.com", "Active staff")
        val group = insertGroup("Policy support", writer)
        val ticket = insertAssignedTicket(8_220, group, writer)
        val browser = login("collaboration-policy-writer@example.com")

        mockMvc.perform(createNote(browser, ticket.number, UUID.randomUUID(), "비활성 멘션", inactive))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.type").value("/problems/validation"))
        mockMvc.perform(createNote(browser, ticket.number, UUID.randomUUID(), "중복 멘션", active, active))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.type").value("/problems/validation"))

        assertThat(count("ticket_collaboration_notes")).isZero()
        assertThat(count("ticket_collaboration_note_mentions")).isZero()
        assertThat(count("staff_notifications")).isZero()
        assertThat(count("ticket_audits")).isZero()
    }

    @Test
    fun `collaboration note pagination returns the look-ahead boundary row on the next page`() {
        val writer = insertStaff("collaboration-pagination-writer@example.com", "Pagination writer")
        val group = insertGroup("Pagination support", writer)
        val ticket = insertAssignedTicket(8_221, group, writer)
        val browser = login("collaboration-pagination-writer@example.com")
        val createdNoteIds = (1..21).map { index ->
            val response = mockMvc.perform(
                createNote(browser, ticket.number, UUID.randomUUID(), "pagination note $index"),
            )
                .andExpect(status().isCreated)
                .andReturn().response.contentAsString
            UUID.fromString(objectMapper.readTree(response).path("note").path("id").asText())
        }

        val first = mockMvc.perform(
            get("/api/v1/agent/tickets/{ticketNumber}/collaboration-notes", ticket.number)
                .session(browser.session)
                .header("X-Interaction-Id", UUID.randomUUID())
                .param("limit", "20"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(20))
            .andExpect(jsonPath("$.nextCursor").isNotEmpty)
            .andReturn().response.contentAsString
        val firstPage = objectMapper.readTree(first)
        val cursor = firstPage.path("nextCursor").asText()
        val second = mockMvc.perform(
            get("/api/v1/agent/tickets/{ticketNumber}/collaboration-notes", ticket.number)
                .session(browser.session)
                .header("X-Interaction-Id", UUID.randomUUID())
                .param("limit", "20")
                .param("before", cursor),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.nextCursor").isEmpty)
            .andReturn().response.contentAsString

        val pagedNoteIds = noteIds(first) + noteIds(second)
        assertThat(pagedNoteIds).hasSize(21).doesNotHaveDuplicates()
        assertThat(pagedNoteIds).containsExactlyInAnyOrderElementsOf(createdNoteIds)
    }

    @Test
    fun `notification pagination returns the look-ahead boundary row on the next page`() {
        val writer = insertStaff("notification-pagination-writer@example.com", "Notification writer")
        val mentioned = insertStaff("notification-pagination-mentioned@example.com", "Notification recipient")
        val group = insertGroup("Notification support", writer, mentioned)
        val ticket = insertAssignedTicket(8_222, group, writer)
        val writerBrowser = login("notification-pagination-writer@example.com")
        val mentionedBrowser = login("notification-pagination-mentioned@example.com")
        val createdNoteIds = (1..21).map { index ->
            val response = mockMvc.perform(
                createNote(writerBrowser, ticket.number, UUID.randomUUID(), "notification note $index", mentioned),
            )
                .andExpect(status().isCreated)
                .andReturn().response.contentAsString
            UUID.fromString(objectMapper.readTree(response).path("note").path("id").asText())
        }

        val first = mockMvc.perform(
            get("/api/v1/agent/notifications")
                .session(mentionedBrowser.session)
                .param("limit", "20"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(20))
            .andExpect(jsonPath("$.unreadCount").value(21))
            .andExpect(jsonPath("$.nextCursor").isNotEmpty)
            .andReturn().response.contentAsString
        val cursor = objectMapper.readTree(first).path("nextCursor").asText()
        val second = mockMvc.perform(
            get("/api/v1/agent/notifications")
                .session(mentionedBrowser.session)
                .param("limit", "20")
                .param("before", cursor),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.nextCursor").isEmpty)
            .andReturn().response.contentAsString

        val pagedNoteIds = notificationNoteIds(first) + notificationNoteIds(second)
        assertThat(pagedNoteIds).hasSize(21).doesNotHaveDuplicates()
        assertThat(pagedNoteIds).containsExactlyInAnyOrderElementsOf(createdNoteIds)
    }

    private fun createNote(browser: Browser, ticketNumber: Long, commandId: UUID, body: String, vararg mentions: UUID) =
        post("/api/v1/agent/tickets/{ticketNumber}/collaboration-notes", ticketNumber)
            .session(browser.session)
            .header("X-CSRF-TOKEN", browser.csrfToken)
            .header("X-Request-Id", "collaboration-$commandId")
            .header("X-Correlation-Id", "collaboration-test")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {
                  "body": "${body.replace("\\", "\\\\").replace("\"", "\\\"")}",
                  "mentionedStaffIds": [${mentions.joinToString(",") { "\"$it\"" }}],
                  "clientCommandId": "$commandId"
                }
                """.trimIndent(),
            )

    private fun login(email: String): Browser {
        val csrf = mockMvc.perform(get("/api/v1/agent/csrf")).andExpect(status().isOk).andReturn()
        val token = Regex("\\\"token\\\":\\\"([^\\\"]+)\\\"").find(csrf.response.contentAsString)!!.groupValues[1]
        val session = csrf.request.session as MockHttpSession
        val login = mockMvc.perform(
            post("/api/v1/agent/session")
                .session(session)
                .header("X-CSRF-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email","password":"$PASSWORD"}"""),
        ).andExpect(status().isNoContent).andReturn()
        return Browser(login.request.session as MockHttpSession, token)
    }

    private fun insertStaff(email: String, displayName: String, status: String = "ACTIVE"): UUID =
        UUID.randomUUID().also { id ->
            jdbc.update(
                """
                insert into staff_accounts
                    (id, email_normalized, email_display, display_name, role, status,
                     password_hash, created_at, updated_at, version)
                values (?, ?, ?, ?, 'AGENT', ?, ?, now(), now(), 0)
                """.trimIndent(),
                id,
                email,
                email,
                displayName,
                status,
                BCryptPasswordEncoder(4).encode(PASSWORD),
            )
        }

    private fun insertGroup(name: String, vararg members: UUID): UUID = UUID.randomUUID().also { groupId ->
        jdbc.update(
            "insert into support_groups (id, name, status, created_at, updated_at, version) values (?, ?, 'ACTIVE', now(), now(), 0)",
            groupId,
            "$name-${UUID.randomUUID()}",
        )
        members.forEach { staffId ->
            jdbc.update(
                """
                insert into group_memberships (id, group_id, staff_id, status, created_at, updated_at, version)
                values (?, ?, ?, 'ACTIVE', now(), now(), 0)
                """.trimIndent(),
                UUID.randomUUID(),
                groupId,
                staffId,
            )
        }
    }

    private fun insertAssignedTicket(number: Long, groupId: UUID, assigneeId: UUID): Ticket =
        Ticket(UUID.randomUUID(), number).also { ticket ->
            val customerId = UUID.randomUUID()
            jdbc.update(
                """
                insert into customers (id, name, email_normalized, email_display, created_at, updated_at)
                values (?, 'Jennifer Ward', ?, ?, now(), now())
                """.trimIndent(),
                customerId,
                "customer-$number@example.test",
                "customer-$number@example.test",
            )
            jdbc.update(
                """
                insert into tickets
                    (id, ticket_number, requester_id, kind, subject, status, priority,
                     group_id, assignee_id, channel, version, created_at, updated_at)
                values (?, ?, ?, 'CUSTOMER_REQUEST', 'Password reset', 'OPEN', 'HIGH',
                        ?, ?, 'EMAIL', 0, now(), now())
                """.trimIndent(),
                ticket.id,
                ticket.number,
                customerId,
                groupId,
                assigneeId,
            )
            jdbc.update(
                """
                insert into ticket_comments (id, ticket_id, author_type, author_id, visibility, body, created_at)
                values (?, ?, 'CUSTOMER', ?, 'PUBLIC', 'Cannot sign in', now())
                """.trimIndent(),
                UUID.randomUUID(),
                ticket.id,
                customerId,
            )
        }

    private fun count(table: String): Long {
        require(table in setOf(
            "ticket_collaboration_notes",
            "ticket_collaboration_note_mentions",
            "staff_notifications",
            "ticket_audits",
            "access_audit_events",
        ))
        return jdbc.queryForObject("select count(*) from $table", Long::class.java)!!
    }

    private fun field(json: String, name: String): String =
        Regex("\\\"$name\\\":\\\"([^\\\"]+)\\\"").find(json)!!.groupValues[1]

    private fun noteIds(json: String): List<UUID> = objectMapper.readTree(json).path("items").values()
        .asSequence()
        .map { UUID.fromString(it.path("id").asText()) }
        .toList()

    private fun notificationNoteIds(json: String): List<UUID> = objectMapper.readTree(json).path("items").values()
        .asSequence()
        .map { UUID.fromString(it.path("noteId").asText()) }
        .toList()

    private data class Browser(val session: MockHttpSession, val csrfToken: String)
    private data class Ticket(val id: UUID, val number: Long)

    private companion object {
        const val PASSWORD = "Collaboration password 42!"
    }
}
