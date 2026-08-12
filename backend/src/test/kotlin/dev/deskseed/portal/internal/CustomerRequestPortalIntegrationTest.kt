package dev.deskseed.portal.internal

import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@SpringBootTest(
    properties = [
        "deskseed.staff-auth.bootstrap.enabled=false",
        "deskseed.customer-portal.claim-grant-ttl=15m",
        "deskseed.customer-portal.claim-signing-key=BAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQ=",
        "deskseed.customer-portal.claim-fingerprint-key=BQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQU=",
    ],
)
@AutoConfigureMockMvc
@Testcontainers
class CustomerRequestPortalIntegrationTest {
    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate
    @Autowired private lateinit var objectMapper: ObjectMapper

    @BeforeEach
    fun clearState() {
        jdbcTemplate.execute(
            """
            truncate table
                outbound_mail_delivery_events,
                outbound_mail_attempts,
                outbound_mail_intents,
                customer_sessions,
                customer_magic_link_request_limits,
                customer_magic_link_tokens,
                customer_accounts,
                admin_security_audit_events,
                ticket_audit_events,
                ticket_audits,
                ticket_comments,
                request_access_tokens,
                tickets,
                customers
            restart identity cascade
            """.trimIndent(),
        )
    }

    @Test
    fun `owned list and detail isolate customers and never expose internal child or audit data`() {
        val first = customerSession("first-portal@example.com")
        val second = customerSession("second-portal@example.com")
        val firstRequest = submitAnonymous("first-portal@example.com", "첫 번째 고객 문의")
        val secondRequest = submitAnonymous("second-portal@example.com", "두 번째 고객 문의")
        claimOwnershipForFixture(firstRequest.ticketNumber, first.customerId)
        claimOwnershipForFixture(secondRequest.ticketNumber, second.customerId)
        val firstTicketId = ticketId(firstRequest.ticketNumber)
        insertInternalComment(firstTicketId, "절대 노출하면 안 되는 내부 메모")
        val childNumber = insertChildTicket(firstTicketId, first.customerId)

        val listBody = mockMvc.perform(get("/api/v1/customer/requests").cookie(first.cookie))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].ticketNumber").value(firstRequest.ticketNumber))
            .andExpect(jsonPath("$.items[0].comments").doesNotExist())
            .andReturn().response.contentAsString

        assertThat(listBody)
            .doesNotContain("절대 노출하면 안 되는 내부 메모")
            .doesNotContain(childNumber.toString())
            .doesNotContain("audit", "assignee", "group")

        val detailBody = mockMvc.perform(
            get("/api/v1/customer/requests/{ticketNumber}", firstRequest.ticketNumber).cookie(first.cookie),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.comments.length()").value(1))
            .andReturn().response.contentAsString
        assertThat(detailBody).doesNotContain("절대 노출하면 안 되는 내부 메모", childNumber.toString(), "audit")

        mockMvc.perform(
            get("/api/v1/customer/requests/{ticketNumber}", firstRequest.ticketNumber).cookie(second.cookie),
        ).andExpect(status().isNotFound)
        mockMvc.perform(
            get("/api/v1/customer/requests/{ticketNumber}", firstRequest.ticketNumber + 99_999).cookie(first.cookie),
        ).andExpect(status().isNotFound)
    }

    @Test
    fun `request access token claims matching anonymous request once and different email is denied`() {
        val matching = customerSession("claim-match@example.com")
        val other = customerSession("claim-other@example.com")
        val request = submitAnonymous("claim-match@example.com", "명시적 연결 문의")

        claimWithAccessToken(matching, request.ticketNumber, request.accessToken)
            .andExpect(status().isNoContent)

        assertThat(requesterId(request.ticketNumber)).isEqualTo(matching.customerId)
        assertThat(activeAccessTokenCount(request.ticketNumber)).isZero()
        assertThat(ticketAuditEventCount(request.ticketNumber, "REQUESTER_CHANGED")).isEqualTo(1)
        assertThat(securityEventCount("CUSTOMER_REQUEST_CLAIMED")).isEqualTo(1)

        claimWithAccessToken(matching, request.ticketNumber, request.accessToken)
            .andExpect(status().isNotFound)

        val otherRequest = submitAnonymous("claim-match@example.com", "다른 계정 거부 문의")
        val before = requesterId(otherRequest.ticketNumber)
        claimWithAccessToken(other, otherRequest.ticketNumber, otherRequest.accessToken)
            .andExpect(status().isForbidden)
        assertThat(requesterId(otherRequest.ticketNumber)).isEqualTo(before)
        assertThat(ticketAuditEventCount(otherRequest.ticketNumber, "REQUESTER_CHANGED")).isZero()
    }

    @Test
    fun `signed claim grant succeeds once while tamper replay and different email fail`() {
        val matching = customerSession("grant-match@example.com")
        val other = customerSession("grant-other@example.com")
        val request = submitAnonymous("grant-match@example.com", "서명 claim 문의")
        val claimToken = issueClaimGrant(request.ticketNumber, request.accessToken)

        claimWithSignedGrant(matching, request.ticketNumber, tamper(claimToken))
            .andExpect(status().isNotFound)
        claimWithSignedGrant(other, request.ticketNumber, claimToken)
            .andExpect(status().isForbidden)
        claimWithSignedGrant(matching, request.ticketNumber, claimToken)
            .andExpect(status().isNoContent)
        claimWithSignedGrant(matching, request.ticketNumber, claimToken)
            .andExpect(status().isNotFound)

        assertThat(requesterId(request.ticketNumber)).isEqualTo(matching.customerId)
        assertThat(ticketAuditEventCount(request.ticketNumber, "REQUESTER_CHANGED")).isEqualTo(1)
        assertThat(securityEventCount("CUSTOMER_REQUEST_CLAIMED")).isEqualTo(1)
    }

    @Test
    fun `public follow-up replay creates one customer comment one audit and one mail intent`() {
        val session = customerSession("follow-up@example.com")
        val request = submitAnonymous("follow-up@example.com", "후속 답변 문의")
        claimOwnershipForFixture(request.ticketNumber, session.customerId)
        val commandId = UUID.randomUUID().toString()

        val first = addFollowUp(session, request.ticketNumber, commandId, "추가 정보를 남깁니다.")
            .andExpect(status().isCreated)
            .andReturn().response.contentAsString
        val replay = addFollowUp(session, request.ticketNumber, commandId, "추가 정보를 남깁니다.")
            .andExpect(status().isCreated)
            .andReturn().response.contentAsString

        assertThat(replay).isEqualTo(first)
        val ticketId = ticketId(request.ticketNumber)
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from ticket_comments where ticket_id = ? and author_type = 'CUSTOMER'",
                Long::class.java,
                ticketId,
            ),
        ).isEqualTo(2)
        assertThat(ticketAuditEventCount(request.ticketNumber, "COMMENT_CREATED")).isEqualTo(2)
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from outbound_mail_intents where comment_id in (select id from ticket_comments where ticket_id = ? and body = ?)",
                Long::class.java,
                ticketId,
                "추가 정보를 남깁니다.",
            ),
        ).isEqualTo(1)

        mockMvc.perform(get("/api/v1/customer/requests/{ticketNumber}", request.ticketNumber).cookie(session.cookie))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.comments.length()").value(2))
            .andExpect(jsonPath("$.comments[1].body").value("추가 정보를 남깁니다."))
    }

    private fun claimWithAccessToken(session: CustomerSessionFixture, ticketNumber: Long, token: String) =
        mockMvc.perform(
            post("/api/v1/customer/requests/{ticketNumber}/claim", ticketNumber)
                .cookie(session.cookie)
                .header("X-CSRF-TOKEN", csrf(session.cookie))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("requestAccessToken" to token))),
        )

    private fun claimWithSignedGrant(session: CustomerSessionFixture, ticketNumber: Long, token: String) =
        mockMvc.perform(
            post("/api/v1/customer/requests/{ticketNumber}/claim", ticketNumber)
                .cookie(session.cookie)
                .header("X-CSRF-TOKEN", csrf(session.cookie))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("claimToken" to token))),
        )

    private fun issueClaimGrant(ticketNumber: Long, accessToken: String): String {
        val response = mockMvc.perform(
            post("/api/v1/requests/{ticketNumber}/claim-grants", ticketNumber)
                .header("X-Request-Access-Token", accessToken),
        )
            .andExpect(status().isCreated)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andReturn().response.contentAsString
        return objectMapper.readTree(response).get("claimToken").asText()
    }

    private fun addFollowUp(
        session: CustomerSessionFixture,
        ticketNumber: Long,
        commandId: String,
        body: String,
    ) = mockMvc.perform(
        post("/api/v1/customer/requests/{ticketNumber}/comments", ticketNumber)
            .cookie(session.cookie)
            .header("X-CSRF-TOKEN", csrf(session.cookie))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(mapOf("body" to body, "clientCommandId" to commandId))),
    )

    private fun csrf(cookie: Cookie): String {
        val response = mockMvc.perform(get("/api/v1/customer/csrf").cookie(cookie))
            .andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readTree(response).get("token").asText()
    }

    private fun customerSession(email: String): CustomerSessionFixture {
        val now = Instant.now()
        val customerId = UUID.randomUUID()
        val accountId = UUID.randomUUID()
        val rawSession = UUID.randomUUID().toString() + "-token"
        jdbcTemplate.update(
            """
            insert into customers (id, name, email_normalized, email_display, verified_at, created_at, updated_at)
            values (?, 'Portal Customer', ?, ?, ?, ?, ?)
            """.trimIndent(),
            customerId,
            email,
            email,
            Timestamp.from(now),
            Timestamp.from(now),
            Timestamp.from(now),
        )
        jdbcTemplate.update(
            """
            insert into customer_accounts
                (id, customer_id, email_normalized, status, verified_at, last_login_at,
                 created_at, updated_at, version)
            values (?, ?, ?, 'ACTIVE', ?, ?, ?, ?, 0)
            """.trimIndent(),
            accountId,
            customerId,
            email,
            Timestamp.from(now),
            Timestamp.from(now),
            Timestamp.from(now),
            Timestamp.from(now),
        )
        jdbcTemplate.update(
            """
            insert into customer_sessions
                (id, account_id, session_token_digest, created_at, last_activity_at,
                 expires_at, absolute_expires_at, revoked_at)
            values (?, ?, ?, ?, ?, ?, ?, null)
            """.trimIndent(),
            UUID.randomUUID(),
            accountId,
            sha256(rawSession),
            Timestamp.from(now),
            Timestamp.from(now),
            Timestamp.from(now.plusSeconds(1800)),
            Timestamp.from(now.plusSeconds(3600)),
        )
        return CustomerSessionFixture(customerId, Cookie(CUSTOMER_COOKIE, rawSession))
    }

    private fun submitAnonymous(email: String, subject: String): AnonymousRequestFixture {
        val response = mockMvc.perform(
            post("/api/v1/requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf(
                            "name" to "익명 고객",
                            "email" to email,
                            "subject" to subject,
                            "message" to "최초 공개 문의입니다.",
                        ),
                    ),
                ),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val json = objectMapper.readTree(response)
        return AnonymousRequestFixture(json.get("ticketNumber").asLong(), json.get("accessToken").asText())
    }

    private fun claimOwnershipForFixture(ticketNumber: Long, customerId: UUID) {
        jdbcTemplate.update("update tickets set requester_id = ? where ticket_number = ?", customerId, ticketNumber)
    }

    private fun insertInternalComment(ticketId: UUID, body: String) {
        jdbcTemplate.update(
            """
            insert into ticket_comments (id, ticket_id, author_type, author_id, visibility, body, created_at)
            values (?, ?, 'AGENT', null, 'INTERNAL', ?, now())
            """.trimIndent(),
            UUID.randomUUID(),
            ticketId,
            body,
        )
    }

    private fun insertChildTicket(parentId: UUID, requesterId: UUID): Long {
        val childId = UUID.randomUUID()
        val childNumber = jdbcTemplate.queryForObject("select nextval('ticket_number_seq')", Long::class.java)!!
        jdbcTemplate.update(
            """
            insert into tickets
                (id, ticket_number, requester_id, kind, subject, status, priority, group_id, assignee_id,
                 channel, version, created_at, updated_at, solved_at)
            values (?, ?, ?, 'INTERNAL_CHILD', '숨겨진 하위 티켓', 'NEW', 'NORMAL', null, null,
                    'AGENT', 0, now(), now(), null)
            """.trimIndent(),
            childId,
            childNumber,
            requesterId,
        )
        jdbcTemplate.update(
            """
            insert into ticket_relations
                (id, source_ticket_id, target_ticket_id, relation_type, created_by_actor_type,
                 created_by_actor_id, created_at)
            values (?, ?, ?, 'PARENT_CHILD', 'STAFF', null, now())
            """.trimIndent(),
            UUID.randomUUID(),
            parentId,
            childId,
        )
        return childNumber
    }

    private fun ticketId(ticketNumber: Long): UUID = jdbcTemplate.queryForObject(
        "select id from tickets where ticket_number = ?",
        UUID::class.java,
        ticketNumber,
    )!!

    private fun requesterId(ticketNumber: Long): UUID = jdbcTemplate.queryForObject(
        "select requester_id from tickets where ticket_number = ?",
        UUID::class.java,
        ticketNumber,
    )!!

    private fun activeAccessTokenCount(ticketNumber: Long): Long = jdbcTemplate.queryForObject(
        """
        select count(*) from request_access_tokens token
        join tickets ticket on ticket.id = token.ticket_id
        where ticket.ticket_number = ? and token.revoked_at is null
        """.trimIndent(),
        Long::class.java,
        ticketNumber,
    )!!

    private fun ticketAuditEventCount(ticketNumber: Long, eventType: String): Long = jdbcTemplate.queryForObject(
        """
        select count(*) from ticket_audit_events event
        join ticket_audits audit on audit.id = event.audit_id
        join tickets ticket on ticket.id = audit.ticket_id
        where ticket.ticket_number = ? and event.event_type = ?
        """.trimIndent(),
        Long::class.java,
        ticketNumber,
        eventType,
    )!!

    private fun securityEventCount(eventType: String): Long = jdbcTemplate.queryForObject(
        "select count(*) from admin_security_audit_events where event_type = ?",
        Long::class.java,
        eventType,
    )!!

    private fun tamper(token: String): String = token.dropLast(1) + if (token.last() == 'A') 'B' else 'A'

    private fun sha256(value: String): String = java.util.HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()),
    )

    private data class CustomerSessionFixture(val customerId: UUID, val cookie: Cookie)
    private data class AnonymousRequestFixture(val ticketNumber: Long, val accessToken: String)

    companion object {
        private const val CUSTOMER_COOKIE = "DESKSEED_CUSTOMER_SESSION"

        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
    }
}
