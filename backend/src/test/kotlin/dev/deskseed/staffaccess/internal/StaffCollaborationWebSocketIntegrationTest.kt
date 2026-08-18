package dev.deskseed.staffaccess.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.handler.TextWebSocketHandler
import org.springframework.web.socket.WebSocketHttpHeaders
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "deskseed.staff-auth.bootstrap.enabled=false",
        "deskseed.collaboration.websocket.allowed-origins=http://localhost:5173",
    ],
)
@Testcontainers
class StaffCollaborationWebSocketIntegrationTest {
    @Autowired private lateinit var jdbc: JdbcTemplate
    @LocalServerPort private var port = 0
    private val http = HttpClient.newHttpClient()

    @BeforeEach
    fun clearState() {
        jdbc.execute(
            """
            truncate table
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
    }

    @Test
    fun `staff session origin and ticket authorization gate presence while logout revokes the socket`() {
        insertStaff("presence-agent@example.com")
        insertTicket(9101)
        val browser = login("presence-agent@example.com")
        val handler = RecordingWebSocketHandler()
        val session = connect(browser.sessionCookie, handler)

        try {
            session.sendMessage(TextMessage("""{"version":1,"type":"subscribe","ticketNumber":9101}"""))

            val snapshot = handler.messages.poll(5, TimeUnit.SECONDS)
            val delta = handler.messages.poll(5, TimeUnit.SECONDS)
            assertThat(listOfNotNull(snapshot, delta)).anySatisfy { message ->
                assertThat(message).contains("\"type\":\"presence.snapshot\"")
                assertThat(message).contains("\"ticketNumber\":9101")
            }
            assertThat(
                jdbc.queryForObject("select count(*) from access_audit_events", Long::class.java),
            ).isZero()

            val logout = http.send(
                HttpRequest.newBuilder(URI(url("/api/v1/agent/session")))
                    .header(HttpHeaders.COOKIE, browser.sessionCookie)
                    .header("X-CSRF-TOKEN", browser.csrfToken)
                    .DELETE()
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertThat(logout.statusCode()).isEqualTo(204)
            assertThat(handler.closed.await(5, TimeUnit.SECONDS)).isTrue()
        } finally {
            if (session.isOpen) session.close(CloseStatus.NORMAL)
        }
    }

    @Test
    fun `deactivated staff receives an unauthorized protocol error before socket closure`() {
        insertStaff("revoked-presence-agent@example.com")
        val browser = login("revoked-presence-agent@example.com")
        val handler = RecordingWebSocketHandler()
        val session = connect(browser.sessionCookie, handler)

        try {
            jdbc.update(
                "update staff_accounts set status = 'DISABLED' where email_normalized = ?",
                "revoked-presence-agent@example.com",
            )
            session.sendMessage(TextMessage("""{"version":1,"type":"heartbeat"}"""))

            val error = handler.messages.poll(5, TimeUnit.SECONDS)
            assertThat(error).contains("\"type\":\"error\"")
            assertThat(error).contains("\"code\":\"UNAUTHORIZED\"")
            assertThat(error).contains("\"retryable\":false")
            assertThat(handler.closed.await(5, TimeUnit.SECONDS)).isTrue()
        } finally {
            if (session.isOpen) session.close(CloseStatus.NORMAL)
        }
    }

    private fun connect(sessionCookie: String, handler: RecordingWebSocketHandler): WebSocketSession {
        val headers = WebSocketHttpHeaders().apply {
            add(HttpHeaders.COOKIE, sessionCookie)
            add(HttpHeaders.ORIGIN, "http://localhost:5173")
        }
        return StandardWebSocketClient()
            .execute(handler, headers, URI("ws://localhost:$port/ws/agent/collaboration"))
            .get(10, TimeUnit.SECONDS)
    }

    private fun login(email: String): BrowserSession {
        val csrf = http.send(
            HttpRequest.newBuilder(URI(url("/api/v1/agent/csrf"))).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        val csrfToken = Regex("\\\"token\\\":\\\"([^\\\"]+)\\\"")
            .find(csrf.body())!!.groupValues[1]
        val csrfCookie = csrf.headers().firstValue(HttpHeaders.SET_COOKIE)
            .orElseThrow().substringBefore(';')
        val login = http.send(
            HttpRequest.newBuilder(URI(url("/api/v1/agent/session")))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header(HttpHeaders.COOKIE, csrfCookie)
                .header("X-CSRF-TOKEN", csrfToken)
                .POST(HttpRequest.BodyPublishers.ofString("""{"email":"$email","password":"$PASSWORD"}"""))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertThat(login.statusCode()).isEqualTo(204)
        return BrowserSession(
            csrfToken = csrfToken,
            sessionCookie = login.headers().firstValue(HttpHeaders.SET_COOKIE)
                .map { it.substringBefore(';') }
                .orElse(csrfCookie),
        )
    }

    private fun insertStaff(email: String) {
        jdbc.update(
            """
            insert into staff_accounts (id, email_normalized, email_display, display_name, role, status, password_hash, created_at, updated_at, version)
            values (gen_random_uuid(), ?, ?, 'Presence agent', 'AGENT', 'ACTIVE', ?, clock_timestamp(), clock_timestamp(), 0)
            """.trimIndent(),
            email,
            email,
            BCryptPasswordEncoder(4).encode(PASSWORD),
        )
    }

    private fun insertTicket(ticketNumber: Long) {
        jdbc.update(
            """
            with customer as (
                insert into customers (id, name, email_normalized, email_display, created_at, updated_at)
                values (gen_random_uuid(), 'Presence customer', 'presence-customer@example.test', 'presence-customer@example.test', clock_timestamp(), clock_timestamp())
                returning id
            )
            insert into tickets (id, ticket_number, requester_id, kind, subject, status, priority, channel, version, created_at, updated_at)
            select gen_random_uuid(), ?, customer.id, 'CUSTOMER_REQUEST', 'Presence handshake', 'OPEN', 'NORMAL', 'WEB', 0, clock_timestamp(), clock_timestamp()
            from customer
            """.trimIndent(),
            ticketNumber,
        )
    }

    private fun url(path: String) = "http://localhost:$port$path"

    private data class BrowserSession(
        val csrfToken: String,
        val sessionCookie: String,
    )

    private class RecordingWebSocketHandler : TextWebSocketHandler() {
        val closed = CountDownLatch(1)
        val messages = LinkedBlockingQueue<String>()

        override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
            messages.offer(message.payload)
        }

        override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
            closed.countDown()
        }
    }

    private companion object {
        const val PASSWORD = "Presence password 42!"

        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
    }
}
