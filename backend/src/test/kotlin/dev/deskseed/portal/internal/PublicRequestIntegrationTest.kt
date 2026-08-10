package dev.deskseed.portal.internal

import dev.deskseed.foundation.CommandContext
import dev.deskseed.foundation.RequestSource
import dev.deskseed.portal.RequestNotFoundException
import dev.deskseed.settings.AnonymousSubmissionDisabledException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.dao.DataAccessException
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PublicRequestIntegrationTest {
    @Autowired
    private lateinit var service: PublicRequestApplicationService

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var tokenCodec: RequestAccessTokenCodec

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `http submission persists trusted customer actor and accepted command context`() {
        val spoofedActorId = UUID.randomUUID().toString()

        mockMvc.perform(
            post("/api/v1/requests")
                .header("X-Request-Id", "request-http-123")
                .header("X-Correlation-Id", "correlation-http-456")
                .header("X-Actor-Id", spoofedActorId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name": "김고객",
                      "email": "http-context-${UUID.randomUUID()}@example.com",
                      "subject": "결제 오류",
                      "message": "결제 버튼을 누르면 오류가 납니다."
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isCreated)
            .andExpect(header().string("X-Request-Id", "request-http-123"))
            .andExpect(header().string("X-Correlation-Id", "correlation-http-456"))

        val auditContext = jdbcTemplate.queryForMap(
            """
            select actor_type, actor_id::text, source, request_id, correlation_id, command_id
            from ticket_audits
            where request_id = 'request-http-123'
            """.trimIndent(),
        )

        assertThat(auditContext["actor_type"]).isEqualTo("CUSTOMER")
        assertThat(auditContext["actor_id"]).isNotEqualTo(spoofedActorId)
        assertThat(auditContext["source"]).isEqualTo("CUSTOMER_PORTAL")
        assertThat(auditContext["request_id"]).isEqualTo("request-http-123")
        assertThat(auditContext["correlation_id"]).isEqualTo("correlation-http-456")
        assertThat(auditContext["command_id"].toString()).matches("[A-Za-z0-9._:-]{1,100}")
    }

    @Test
    fun `anonymous request creates first public comment and customer projection excludes internal comments`() {
        val submitted = submitUniqueRequest("projection")
        val ticketId = ticketId(submitted.ticketNumber)

        jdbcTemplate.update(
            """
            insert into ticket_comments
                (id, ticket_id, author_type, author_id, visibility, body, created_at)
            values (?, ?, 'AGENT', null, 'INTERNAL', ?, ?)
            """.trimIndent(),
            UUID.randomUUID(),
            ticketId,
            "고객에게 노출되면 안 되는 내부 메모",
            Timestamp.from(Instant.parse("2026-08-10T01:00:00Z")),
        )

        val visible = service.view(submitted.ticketNumber, submitted.accessToken)

        assertThat(visible.comments).hasSize(1)
        assertThat(visible.comments.single().body).isEqualTo("결제 버튼을 누르면 오류가 납니다.")
        assertThat(
            jdbcTemplate.queryForObject(
                """
                select count(*)
                from information_schema.columns
                where table_schema = 'public'
                  and table_name = 'tickets'
                  and column_name = 'description'
                """.trimIndent(),
                Long::class.java,
            ),
        ).isZero()
    }

    @Test
    fun `request lookup requires both the matching number and opaque token`() {
        val submitted = submitUniqueRequest("lookup")

        assertThat(service.view(submitted.ticketNumber, submitted.accessToken).ticketNumber)
            .isEqualTo(submitted.ticketNumber)

        assertThatThrownBy {
            service.view(submitted.ticketNumber + 1, submitted.accessToken)
        }.isInstanceOf(RequestNotFoundException::class.java)

        assertThatThrownBy {
            service.view(submitted.ticketNumber, "not-the-issued-token")
        }.isInstanceOf(RequestNotFoundException::class.java)
    }

    @Test
    fun `raw request access token is never persisted`() {
        val submitted = submitUniqueRequest("token")
        val storedHashCount = jdbcTemplate.queryForObject(
            "select count(*) from request_access_tokens where token_hash = ?",
            Long::class.java,
            tokenCodec.hash(submitted.accessToken),
        )
        val rawTokenCount = jdbcTemplate.queryForObject(
            "select count(*) from request_access_tokens where token_hash = ?",
            Long::class.java,
            submitted.accessToken,
        )

        assertThat(storedHashCount).isEqualTo(1)
        assertThat(rawTokenCount).isZero()
    }

    @Test
    fun `request access grant stores a mandatory thirty day expiry and revocation metadata`() {
        val submitted = submitUniqueRequest("token-lifecycle")
        val lifecycle = jdbcTemplate.queryForMap(
            """
            select created_at, expires_at, revoked_at
            from request_access_tokens
            where ticket_id = ?
            """.trimIndent(),
            ticketId(submitted.ticketNumber),
        )

        val createdAt = (lifecycle["created_at"] as Timestamp).toInstant()
        val expiresAt = (lifecycle["expires_at"] as Timestamp).toInstant()
        assertThat(Duration.between(createdAt, expiresAt)).isEqualTo(Duration.ofDays(30))
        assertThat(lifecycle["revoked_at"]).isNull()
    }

    @Test
    fun `expired and revoked grants use the same not found result as invalid credentials`() {
        val expired = submitUniqueRequest("expired-token")
        val revoked = submitUniqueRequest("revoked-token")
        jdbcTemplate.update(
            """
            update request_access_tokens
            set created_at = now() - interval '31 days',
                expires_at = now() - interval '1 day'
            where ticket_id = ?
            """.trimIndent(),
            ticketId(expired.ticketNumber),
        )
        jdbcTemplate.update(
            "update request_access_tokens set revoked_at = now() where ticket_id = ?",
            ticketId(revoked.ticketNumber),
        )

        assertThatThrownBy { service.view(expired.ticketNumber, expired.accessToken) }
            .isInstanceOf(RequestNotFoundException::class.java)
        assertThatThrownBy { service.view(revoked.ticketNumber, revoked.accessToken) }
            .isInstanceOf(RequestNotFoundException::class.java)
        assertThatThrownBy { service.view(expired.ticketNumber, "invalid-token") }
            .isInstanceOf(RequestNotFoundException::class.java)
    }

    @Test
    fun `one submission creates one audit with ordered creation events`() {
        val submitted = submitUniqueRequest("audit")
        val ticketId = ticketId(submitted.ticketNumber)
        val auditId = jdbcTemplate.queryForObject(
            "select id from ticket_audits where ticket_id = ?",
            UUID::class.java,
            ticketId,
        )!!
        val eventTypes = jdbcTemplate.queryForList(
            """
            select event_type
            from ticket_audit_events
            where audit_id = ?
            order by event_order
            """.trimIndent(),
            String::class.java,
            auditId,
        )

        assertThat(eventTypes).containsExactly("TICKET_CREATED", "COMMENT_CREATED")
        val auditContext = jdbcTemplate.queryForMap(
            """
            select actor_type, source, request_id, correlation_id, command_id
            from ticket_audits
            where id = ?
            """.trimIndent(),
            auditId,
        )
        assertThat(auditContext["actor_type"]).isEqualTo("CUSTOMER")
        assertThat(auditContext["source"]).isEqualTo("CUSTOMER_PORTAL")
        assertThat(auditContext["request_id"].toString()).matches("[A-Za-z0-9._:-]{1,100}")
        assertThat(auditContext["correlation_id"].toString()).matches("[A-Za-z0-9._:-]{1,100}")
        assertThat(auditContext["command_id"].toString()).matches("[A-Za-z0-9._:-]{1,100}")
        assertThatThrownBy {
            jdbcTemplate.update(
                "update ticket_audits set source = 'MUTATED' where id = ?",
                auditId,
            )
        }.isInstanceOf(DataAccessException::class.java)
    }

    @Test
    fun `registration required mode blocks anonymous submission`() {
        jdbcTemplate.update(
            "update system_settings set customer_access_mode = 'REGISTRATION_REQUIRED', updated_at = now() where id = 1",
        )
        try {
            assertThatThrownBy {
                submitUniqueRequest("blocked")
            }.isInstanceOf(AnonymousSubmissionDisabledException::class.java)
        } finally {
            jdbcTemplate.update(
                "update system_settings set customer_access_mode = 'ANONYMOUS_ALLOWED', updated_at = now() where id = 1",
            )
        }
    }

    private fun submitUniqueRequest(suffix: String): AnonymousRequestSubmitted = service.submit(
        SubmitAnonymousRequest(
            name = "김고객",
            email = "customer-$suffix-${UUID.randomUUID()}@example.com",
            subject = "결제 오류",
            message = "결제 버튼을 누르면 오류가 납니다.",
            context = CommandContext(
                source = RequestSource.CUSTOMER_PORTAL,
                requestId = "request-$suffix",
                correlationId = "correlation-$suffix",
                commandId = UUID.randomUUID().toString(),
            ),
        ),
    )

    private fun ticketId(ticketNumber: Long): UUID = jdbcTemplate.queryForObject(
        "select id from tickets where ticket_number = ?",
        UUID::class.java,
        ticketNumber,
    )!!

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
    }
}
