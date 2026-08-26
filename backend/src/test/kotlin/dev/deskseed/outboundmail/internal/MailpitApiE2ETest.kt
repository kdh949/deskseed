package dev.deskseed.outboundmail.internal

import dev.deskseed.foundation.ActorRef
import dev.deskseed.foundation.ActorType
import dev.deskseed.foundation.CommandContext
import dev.deskseed.foundation.RequestSource
import dev.deskseed.outboundmail.MagicLinkMail
import dev.deskseed.outboundmail.MailRecipient
import dev.deskseed.outboundmail.OutboundMailIntent
import dev.deskseed.outboundmail.OutboundMailPort
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

@dev.deskseed.testsupport.integration.DeskseedSpringIntegrationTest(
    properties = [
        "deskseed.mail.delivery-enabled=true",
        "deskseed.mail.transport=smtp",
        "deskseed.staff-auth.bootstrap.enabled=false",
        "spring.mail.properties[mail.smtp.connectiontimeout]=3000",
        "spring.mail.properties[mail.smtp.timeout]=3000",
        "spring.mail.properties[mail.smtp.writetimeout]=3000",
    ],
)
@Testcontainers
@AutoConfigureMockMvc
@dev.deskseed.testsupport.category.SlowTest
class MailpitApiE2ETest {
    @Autowired private lateinit var mailPort: OutboundMailPort
    @Autowired private lateinit var worker: MailDeliveryWorker
    @Autowired private lateinit var transactionTemplate: TransactionTemplate
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var mockMvc: MockMvc

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    @BeforeEach
    fun clearState() {
        jdbcTemplate.execute(
            """
            truncate table
                outbound_mail_delivery_events, outbound_mail_attempts, outbound_mail_intents,
                customer_sessions, customer_one_time_tokens,
                customer_accounts, admin_security_audit_events, customers
            restart identity cascade
            """.trimIndent(),
        )
        request("DELETE", "/api/v1/messages")
    }

    @Test
    fun `Mailpit API exposes one magic link message with expected recipient subject and link`() {
        val recipient = "mailpit-${UUID.randomUUID()}@example.com"
        val magicLink = "https://deskseed.example/customer/magic/${UUID.randomUUID()}"
        transactionTemplate.execute {
            mailPort.enqueue(
                OutboundMailIntent(
                    idempotencyKey = "magic-link:${UUID.randomUUID()}",
                    recipient = MailRecipient(recipient),
                    content = MagicLinkMail(magicLink),
                    actor = ActorRef(ActorType.SYSTEM, null),
                    context = CommandContext(
                        source = RequestSource.SYSTEM_JOB,
                        requestId = "request-mailpit-e2e",
                        correlationId = "correlation-mailpit-e2e",
                        commandId = "command-mailpit-e2e",
                    ),
                ),
            )
        }

        assertThat(worker.runDueBatch()).isEqualTo(1)
        val summaries = messages()
        assertThat(summaries).hasSize(1)
        val summary = summaries.single()
        assertThat(textField(summary, "Subject", "subject")).contains("로그인")
        assertThat(recipientAddresses(summary)).containsExactly(recipient)

        val id = textField(summary, "ID", "id")
        val detail = objectMapper.readTree(request("GET", "/api/v1/message/$id"))
        assertThat(textField(detail, "Text", "text")).contains(magicLink)

        assertThat(worker.runDueBatch()).isZero()
        assertThat(messages()).hasSize(1)
        assertThat(
            jdbcTemplate.queryForObject("select count(*) from outbound_mail_attempts", Long::class.java),
        ).isEqualTo(1)
    }

    @Test
    fun `customer auth commits an encrypted outbox link that Mailpit delivers and consumes once`() {
        val recipient = "customer-mailpit-${UUID.randomUUID()}@example.com"
        val now = java.sql.Timestamp.from(java.time.Instant.now())
        jdbcTemplate.update(
            """
            insert into customers (id, name, email_normalized, email_display, verified_at, created_at, updated_at)
            values (?, 'Mailpit customer', ?, ?, null, ?, ?)
            """.trimIndent(),
            UUID.randomUUID(),
            recipient,
            recipient,
            now,
            now,
        )

        mockMvc.perform(
            post("/api/v1/customer/auth/magic-link-requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$recipient"}"""),
        ).andExpect(status().isAccepted)

        assertThat(
            jdbcTemplate.queryForMap(
                "select text_body, protected_body_ciphertext is not null as protected from outbound_mail_intents",
            ),
        ).containsEntry("text_body", JpaOutboundMailService.PROTECTED_BODY_PLACEHOLDER)
            .containsEntry("protected", true)
        assertThat(worker.runDueBatch()).isEqualTo(1)

        val summaries = messages()
        assertThat(summaries).hasSize(1)
        val summary = summaries.single()
        assertThat(recipientAddresses(summary)).containsExactly(recipient)
        assertThat(textField(summary, "Subject", "subject")).contains("로그인")
        val detail = objectMapper.readTree(
            request("GET", "/api/v1/message/${textField(summary, "ID", "id")}"),
        )
        val text = textField(detail, "Text", "text")
        val link = Regex("https?://[^\\s]+/customer/sign-in/consume#token=([A-Za-z0-9_-]{43})")
            .find(text) ?: error("delivered magic link is absent")
        val rawToken = link.groupValues[1]
        assertThat(text).doesNotContain("?token=")

        mockMvc.perform(
            post("/api/v1/customer/auth/magic-link-sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"token":"$rawToken"}"""),
        ).andExpect(status().isOk)
        mockMvc.perform(
            post("/api/v1/customer/auth/magic-link-sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"token":"$rawToken"}"""),
        ).andExpect(status().isUnauthorized)

        assertThat(worker.runDueBatch()).isZero()
        assertThat(messages()).hasSize(1)
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from admin_security_audit_events where event_type = 'CUSTOMER_MAGIC_LINK_REPLAYED'",
                Long::class.java,
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `customer PUBLIC follow-up replay delivers one request received message`() {
        val recipient = "follow-up-mailpit-${UUID.randomUUID()}@example.com"
        val session = customerSession(recipient)
        val ticketNumber = insertOwnedTicket(session.customerId)
        val commandId = "mailpit-follow-up-${UUID.randomUUID()}"
        val csrfToken = csrf(session.cookie)
        val payload = objectMapper.writeValueAsString(
            mapOf("body" to "Mailpit으로 확인하는 고객 공개 후속 답변", "clientCommandId" to commandId),
        )

        repeat(2) {
            mockMvc.perform(
                post("/api/v1/customer/requests/{ticketNumber}/comments", ticketNumber)
                    .cookie(session.cookie)
                    .header("X-CSRF-TOKEN", csrfToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload),
            ).andExpect(status().isCreated)
        }

        assertThat(worker.runDueBatch()).isEqualTo(1)
        val summaries = messages()
        assertThat(summaries).hasSize(1)
        val summary = summaries.single()
        assertThat(recipientAddresses(summary)).containsExactly(recipient)
        assertThat(textField(summary, "Subject", "subject")).contains("요청 #$ticketNumber 접수 완료")
        val detail = objectMapper.readTree(
            request("GET", "/api/v1/message/${textField(summary, "ID", "id")}"),
        )
        assertThat(textField(detail, "Text", "text")).contains("/requests/$ticketNumber")

        assertThat(worker.runDueBatch()).isZero()
        assertThat(messages()).hasSize(1)
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from outbound_mail_intents where comment_id is not null",
                Long::class.java,
            ),
        ).isEqualTo(1)
        assertThat(
            jdbcTemplate.queryForObject("select count(*) from outbound_mail_attempts", Long::class.java),
        ).isEqualTo(1)
    }

    private fun csrf(cookie: Cookie): String {
        val response = mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/v1/customer/csrf")
                .cookie(cookie),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readTree(response).get("token").asText()
    }

    private fun customerSession(email: String): CustomerSessionFixture {
        val now = Instant.now()
        val customerId = UUID.randomUUID()
        val accountId = UUID.randomUUID()
        val rawSession = "mailpit-session-${UUID.randomUUID()}"
        jdbcTemplate.update(
            """
            insert into customers (id, name, email_normalized, email_display, verified_at, created_at, updated_at)
            values (?, 'Mailpit follow-up customer', ?, ?, ?, ?, ?)
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
            Timestamp.from(now.plusSeconds(1_800)),
            Timestamp.from(now.plusSeconds(3_600)),
        )
        return CustomerSessionFixture(customerId, Cookie(CUSTOMER_COOKIE, rawSession))
    }

    private fun insertOwnedTicket(customerId: UUID): Long {
        val ticketNumber = jdbcTemplate.queryForObject("select nextval('ticket_number_seq')", Long::class.java)!!
        jdbcTemplate.update(
            """
            insert into tickets
                (id, ticket_number, requester_id, kind, subject, status, priority, group_id,
                 assignee_id, channel, version, created_at, updated_at, solved_at)
            values (?, ?, ?, 'CUSTOMER_REQUEST', 'Mailpit follow-up ticket', 'OPEN', 'NORMAL',
                    null, null, 'WEB', 0, now(), now(), null)
            """.trimIndent(),
            UUID.randomUUID(),
            ticketNumber,
            customerId,
        )
        return ticketNumber
    }

    private fun sha256(value: String): String = java.util.HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()),
    )

    private fun messages(): List<JsonNode> {
        val root = objectMapper.readTree(request("GET", "/api/v1/messages?limit=50"))
        val messages = root.get("messages") ?: root.get("Messages") ?: error("Mailpit messages field is absent")
        return messages.toList()
    }

    private fun recipientAddresses(summary: JsonNode): List<String> {
        val recipients = summary.get("To") ?: summary.get("to") ?: error("Mailpit recipient field is absent")
        return recipients.toList().map { textField(it, "Address", "address") }
    }

    private fun textField(node: JsonNode, vararg names: String): String = names.firstNotNullOfOrNull { name ->
        node.get(name)?.takeUnless { it.isNull }?.asString()
    } ?: error("Mailpit field ${names.joinToString()} is absent")

    private fun request(method: String, path: String): String {
        val builder = HttpRequest.newBuilder(URI("http://${mailpit.host}:${mailpit.getMappedPort(8025)}$path"))
            .timeout(Duration.ofSeconds(5))
        val request = when (method) {
            "GET" -> builder.GET().build()
            "DELETE" -> builder.DELETE().build()
            else -> error("unsupported method")
        }
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) { "Mailpit API returned ${response.statusCode()}" }
        return response.body()
    }

    companion object {
        private const val CUSTOMER_COOKIE = "DESKSEED_CUSTOMER_SESSION"

        @Container
        @JvmStatic
        val mailpit = GenericContainer(DockerImageName.parse("axllent/mailpit:v1.27.4"))
            .withExposedPorts(1025, 8025)
            .waitingFor(Wait.forHttp("/readyz").forPort(8025))

        @Container
        @JvmStatic
        val redis = GenericContainer(DockerImageName.parse("redis:8.2.7-alpine"))
            .withExposedPorts(6379)

        @DynamicPropertySource
        @JvmStatic
        fun mailProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.mail.host", mailpit::getHost)
            registry.add("spring.mail.port") { mailpit.getMappedPort(1025) }
            registry.add("spring.data.redis.host", redis::getHost)
            registry.add("spring.data.redis.port") { redis.getMappedPort(6379) }
        }
    }

    private data class CustomerSessionFixture(val customerId: UUID, val cookie: Cookie)
}
