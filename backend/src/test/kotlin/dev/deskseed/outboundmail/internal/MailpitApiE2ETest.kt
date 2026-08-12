package dev.deskseed.outboundmail.internal

import dev.deskseed.foundation.ActorRef
import dev.deskseed.foundation.ActorType
import dev.deskseed.foundation.CommandContext
import dev.deskseed.foundation.RequestSource
import dev.deskseed.outboundmail.MagicLinkMail
import dev.deskseed.outboundmail.MailRecipient
import dev.deskseed.outboundmail.OutboundMailIntent
import dev.deskseed.outboundmail.OutboundMailPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

@SpringBootTest(
    properties = [
        "deskseed.mail.delivery-enabled=true",
        "deskseed.mail.scheduling-enabled=false",
        "deskseed.mail.transport=smtp",
        "deskseed.staff-auth.bootstrap.enabled=false",
        "spring.mail.properties[mail.smtp.connectiontimeout]=3000",
        "spring.mail.properties[mail.smtp.timeout]=3000",
        "spring.mail.properties[mail.smtp.writetimeout]=3000",
    ],
)
@Testcontainers
class MailpitApiE2ETest {
    @Autowired private lateinit var mailPort: OutboundMailPort
    @Autowired private lateinit var worker: MailDeliveryWorker
    @Autowired private lateinit var transactionTemplate: TransactionTemplate
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate
    @Autowired private lateinit var objectMapper: ObjectMapper

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    @BeforeEach
    fun clearState() {
        jdbcTemplate.execute(
            "truncate table outbound_mail_delivery_events, outbound_mail_attempts, outbound_mail_intents restart identity cascade",
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
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))

        @Container
        @JvmStatic
        val mailpit = GenericContainer(DockerImageName.parse("axllent/mailpit:v1.27.4"))
            .withExposedPorts(1025, 8025)
            .waitingFor(Wait.forHttp("/readyz").forPort(8025))

        @DynamicPropertySource
        @JvmStatic
        fun mailProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.mail.host", mailpit::getHost)
            registry.add("spring.mail.port") { mailpit.getMappedPort(1025) }
        }
    }
}
