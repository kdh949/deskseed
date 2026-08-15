package dev.deskseed.outboundmail.internal

import dev.deskseed.foundation.ActorRef
import dev.deskseed.foundation.ActorType
import dev.deskseed.foundation.CommandContext
import dev.deskseed.foundation.RequestSource
import dev.deskseed.outboundmail.MagicLinkMail
import dev.deskseed.outboundmail.MailRecipient
import dev.deskseed.outboundmail.ManualMailRetryCommand
import dev.deskseed.outboundmail.OutboundMailIntent
import dev.deskseed.outboundmail.OutboundMailIntentConflictException
import dev.deskseed.outboundmail.OutboundMailOperations
import dev.deskseed.outboundmail.OutboundMailPort
import dev.deskseed.portal.internal.PublicRequestApplicationService
import dev.deskseed.portal.internal.SubmitAnonymousRequest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest(
    properties = [
        "deskseed.mail.delivery-enabled=true",
        "deskseed.mail.scheduling-enabled=false",
        "deskseed.mail.transport=fake",
        "deskseed.mail.retry-backoff[0]=0s",
        "deskseed.mail.retry-backoff[1]=1h",
        "deskseed.staff-auth.bootstrap.enabled=false",
    ],
)
@Import(FakeMailTransportConfiguration::class)
@Testcontainers
@ExtendWith(OutputCaptureExtension::class)
class OutboundMailDeliveryIntegrationTest {
    @Autowired private lateinit var worker: MailDeliveryWorker
    @Autowired private lateinit var transport: FakeMailTransport
    @Autowired private lateinit var mailPort: OutboundMailPort
    @Autowired private lateinit var operations: OutboundMailOperations
    @Autowired private lateinit var publicRequestService: PublicRequestApplicationService
    @Autowired private lateinit var transactionTemplate: TransactionTemplate
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun clearState() {
        jdbcTemplate.execute(
            """
            truncate table
                outbound_mail_delivery_events,
                outbound_mail_attempts,
                outbound_mail_intents,
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
        transport.reset()
    }

    @Test
    fun `delivery failure preserves business commit then retry sends once with stable message id`() {
        val submitted = submitRequest("delivery-boundary")
        val ticketId = ticketId(submitted.ticketNumber)
        transport.failNext(MailTransportException(true, "SMTP_TEMPORARY_FAILURE"))

        assertThat(worker.runDueBatch()).isEqualTo(1)
        assertThat(intentStatus(ticketId)).isEqualTo("RETRY_WAIT")
        assertThat(ticketCount(ticketId)).isEqualTo(1)
        assertThat(commentCount(ticketId)).isEqualTo(1)
        assertThat(transport.deliveredMessages).isEmpty()
        jdbcTemplate.update(
            "update outbound_mail_intents set next_attempt_at = now() where ticket_id = ?",
            ticketId,
        )

        assertThat(worker.runDueBatch()).isEqualTo(1)
        assertThat(intentStatus(ticketId)).isEqualTo("SENT")
        assertThat(worker.runDueBatch()).isZero()
        assertThat(transport.attemptedMessages).hasSize(2)
        assertThat(transport.deliveredMessages).hasSize(1)
        assertThat(transport.attemptedMessages.map { it.stableMessageId }.distinct()).hasSize(1)
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from outbound_mail_attempts where intent_id = (select id from outbound_mail_intents where ticket_id = ?)",
                Long::class.java,
                ticketId,
            ),
        ).isEqualTo(2)
    }

    @Test
    fun `terminal failure manual retry reuses intent and business comment`() {
        val submitted = submitRequest("manual-retry")
        val ticketId = ticketId(submitted.ticketNumber)
        val intentId = intentId(ticketId)
        transport.failNext(MailTransportException(true, "SMTP_TEMPORARY_FAILURE"))
        worker.runDueBatch()
        jdbcTemplate.update("update outbound_mail_intents set next_attempt_at = now() where id = ?", intentId)
        transport.failNext(MailTransportException(false, "RECIPIENT_REJECTED"))
        worker.runDueBatch()

        assertThat(intentStatus(ticketId)).isEqualTo("FAILED")
        asAdmin {
            operations.retryTerminal(
                ManualMailRetryCommand(
                    intentId = intentId,
                    actor = ActorRef(ActorType.STAFF, UUID.randomUUID()),
                    actorDisplayName = "메일 관리자",
                    context = context("manual-retry-command", RequestSource.ADMIN_UI),
                    reason = "주소 확인 후 운영자 재시도",
                ),
            )
        }
        assertThat(worker.runDueBatch()).isEqualTo(1)

        assertThat(intentStatus(ticketId)).isEqualTo("SENT")
        assertThat(
            jdbcTemplate.queryForObject("select count(*) from outbound_mail_intents where id = ?", Long::class.java, intentId),
        ).isEqualTo(1)
        assertThat(commentCount(ticketId)).isEqualTo(1)
        assertThat(
            jdbcTemplate.queryForMap(
                "select retry_cycle, manual_retry_count, attempt_count from outbound_mail_intents where id = ?",
                intentId,
            ),
        ).containsEntry("retry_cycle", 1).containsEntry("manual_retry_count", 1).containsEntry("attempt_count", 3)
        assertThat(
            jdbcTemplate.queryForMap(
                "select actor_type, source, reason_text from outbound_mail_delivery_events where intent_id = ? and event_type = 'MAIL_MANUAL_RETRY_REQUESTED'",
                intentId,
            ),
        ).containsEntry("actor_type", "STAFF")
            .containsEntry("source", "ADMIN_UI")
            .containsEntry("reason_text", "주소 확인 후 운영자 재시도")
        assertThat(
            jdbcTemplate.queryForMap(
                """
                select actor_type, source, target_type, metadata_json
                from admin_security_audit_events
                where event_type = 'OUTBOUND_MAIL_MANUAL_RETRY_REQUESTED'
                """.trimIndent(),
            ),
        ).containsEntry("actor_type", "STAFF")
            .containsEntry("source", "ADMIN_UI")
            .containsEntry("target_type", "OUTBOUND_MAIL_INTENT")
            .doesNotContainValue("주소 확인 후 운영자 재시도")
    }

    @Test
    fun `concurrent manual retries requeue one failed intent and write one security audit`() {
        val intentId = terminalFailedIntent("retry-race")
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = executor.invokeAll(
                (1..2).map { index ->
                    Callable {
                        runCatching {
                            asAdmin {
                                operations.retryTerminal(
                                    ManualMailRetryCommand(
                                        intentId = intentId,
                                        actor = ActorRef(ActorType.STAFF, UUID.randomUUID()),
                                        actorDisplayName = "동시 관리자 $index",
                                        context = context("retry-race-$index", RequestSource.ADMIN_UI),
                                        reason = "동시 재시도 검증",
                                    ),
                                )
                            }
                        }
                    }
                },
            ).map { it.get(10, TimeUnit.SECONDS) }

            assertThat(results.count { it.isSuccess }).isEqualTo(1)
            assertThat(
                results.count { it.exceptionOrNull() is dev.deskseed.outboundmail.OutboundMailRetryInvalidException },
            ).isEqualTo(1)
        } finally {
            executor.shutdownNow()
        }

        assertThat(
            jdbcTemplate.queryForMap(
                "select status, retry_cycle, manual_retry_count from outbound_mail_intents where id = ?",
                intentId,
            ),
        ).containsEntry("status", "QUEUED")
            .containsEntry("retry_cycle", 1)
            .containsEntry("manual_retry_count", 1)
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from admin_security_audit_events where event_type = 'OUTBOUND_MAIL_MANUAL_RETRY_REQUESTED' and target_id = ?",
                Long::class.java,
                intentId,
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `manual retry rolls back state and delivery event when security audit persistence fails`() {
        val intentId = terminalFailedIntent("retry-audit-rollback")
        jdbcTemplate.execute(
            """
            create or replace function fail_outbound_mail_retry_security_audit()
            returns trigger language plpgsql as ${'$'}${'$'}
            begin raise exception 'injected outbound mail retry audit failure'; end;
            ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            "create trigger fail_outbound_mail_retry_security_audit before insert on admin_security_audit_events for each row execute function fail_outbound_mail_retry_security_audit()",
        )
        try {
            assertThatThrownBy {
                asAdmin {
                    operations.retryTerminal(
                        ManualMailRetryCommand(
                            intentId = intentId,
                            actor = ActorRef(ActorType.STAFF, UUID.randomUUID()),
                            actorDisplayName = "실패 관리자",
                            context = context("retry-audit-rollback", RequestSource.ADMIN_UI),
                            reason = "감사 실패 롤백 검증",
                        ),
                    )
                }
            }.isInstanceOf(RuntimeException::class.java)
        } finally {
            jdbcTemplate.execute("drop trigger if exists fail_outbound_mail_retry_security_audit on admin_security_audit_events")
            jdbcTemplate.execute("drop function if exists fail_outbound_mail_retry_security_audit()")
        }

        assertThat(
            jdbcTemplate.queryForObject("select status from outbound_mail_intents where id = ?", String::class.java, intentId),
        ).isEqualTo("FAILED")
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from outbound_mail_delivery_events where intent_id = ? and event_type = 'MAIL_MANUAL_RETRY_REQUESTED'",
                Long::class.java,
                intentId,
            ),
        ).isZero()
    }

    @Test
    fun `outbox insert failure rolls back the whole request and sends nothing`() {
        jdbcTemplate.execute(
            """
            create or replace function fail_test_mail_intent_insert()
            returns trigger language plpgsql as ${'$'}${'$'}
            begin raise exception 'injected mail intent failure'; end;
            ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            "create trigger fail_test_mail_intent_insert before insert on outbound_mail_intents for each row execute function fail_test_mail_intent_insert()",
        )
        try {
            assertThatThrownBy { submitRequest("outbox-rollback") }.isInstanceOf(RuntimeException::class.java)
        } finally {
            jdbcTemplate.execute("drop trigger if exists fail_test_mail_intent_insert on outbound_mail_intents")
            jdbcTemplate.execute("drop function if exists fail_test_mail_intent_insert()")
        }

        assertThat(jdbcTemplate.queryForObject("select count(*) from customers", Long::class.java)).isZero()
        assertThat(jdbcTemplate.queryForObject("select count(*) from tickets", Long::class.java)).isZero()
        assertThat(jdbcTemplate.queryForObject("select count(*) from ticket_comments", Long::class.java)).isZero()
        assertThat(jdbcTemplate.queryForObject("select count(*) from outbound_mail_intents", Long::class.java)).isZero()
        assertThat(transport.attemptedMessages).isEmpty()
    }

    @Test
    fun `same idempotency key replays one intent and rejects a different payload`() {
        val intent = magicIntent("magic-link:stable-token", "customer@example.com")
        val ids = transactionTemplate.execute {
            listOf(mailPort.enqueue(intent), mailPort.enqueue(intent))
        }

        assertThat(ids.distinct()).hasSize(1)
        assertThat(jdbcTemplate.queryForObject("select count(*) from outbound_mail_intents", Long::class.java)).isEqualTo(1)
        assertThatThrownBy {
            transactionTemplate.execute {
                mailPort.enqueue(magicIntent("magic-link:stable-token", "other@example.com"))
            }
        }.isInstanceOf(OutboundMailIntentConflictException::class.java)
        assertThat(jdbcTemplate.queryForObject("select count(*) from outbound_mail_intents", Long::class.java)).isEqualTo(1)
    }

    @Test
    fun `concurrent workers claim one intent and produce one delivery`() {
        transactionTemplate.execute { mailPort.enqueue(magicIntent("magic-link:concurrent", "customer@example.com")) }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = executor.invokeAll(
                listOf(Callable { worker.runDueBatch() }, Callable { worker.runDueBatch() }),
            ).map { it.get(10, TimeUnit.SECONDS) }
            assertThat(results.sum()).isEqualTo(1)
        } finally {
            executor.shutdownNow()
        }

        assertThat(transport.attemptedMessages).hasSize(1)
        assertThat(transport.deliveredMessages).hasSize(1)
        assertThat(jdbcTemplate.queryForObject("select count(*) from outbound_mail_attempts", Long::class.java)).isEqualTo(1)
    }

    @Test
    fun `delivery failure logs omit magic link recipient subject and body`(output: CapturedOutput) {
        val secretLink = "https://deskseed.example/customer/magic/do-not-log-${UUID.randomUUID()}"
        transactionTemplate.execute {
            mailPort.enqueue(
                magicIntent("magic-link:log-safety-${UUID.randomUUID()}", "private-recipient@example.com", secretLink),
            )
        }
        transport.failNext(MailTransportException(true, "leak-$secretLink"))

        worker.runDueBatch()

        assertThat(output.all).doesNotContain(secretLink)
        assertThat(output.all).doesNotContain("private-recipient@example.com")
        assertThat(output.all).doesNotContain("[Deskseed] 로그인 링크")
        assertThat(output.all).contains("code=MAIL_DELIVERY_FAILURE")
    }

    @Test
    fun `unreadable protected content becomes terminal and does not block the next due mail`(output: CapturedOutput) {
        val secretLink = "https://deskseed.example/magic/unreadable-${UUID.randomUUID()}"
        val intentIds = transactionTemplate.execute {
            listOf(
                mailPort.enqueue(
                    magicIntent("magic-link:unreadable-${UUID.randomUUID()}", "first@example.com", secretLink),
                ),
                mailPort.enqueue(
                    magicIntent("magic-link:healthy-${UUID.randomUUID()}", "second@example.com"),
                ),
            )
        }
        jdbcTemplate.update(
            "update outbound_mail_intents set protected_body_ciphertext = ?, next_attempt_at = now() - interval '2 seconds' where id = ?",
            byteArrayOf(1, 2, 3),
            intentIds[0],
        )
        jdbcTemplate.update(
            "update outbound_mail_intents set next_attempt_at = now() - interval '1 second' where id = ?",
            intentIds[1],
        )

        assertThat(worker.runDueBatch()).isEqualTo(2)

        assertThat(
            jdbcTemplate.queryForMap(
                "select status, last_error_code from outbound_mail_intents where id = ?",
                intentIds[0],
            ),
        ).containsEntry("status", "FAILED")
            .containsEntry("last_error_code", "PROTECTED_CONTENT_UNREADABLE")
        assertThat(
            jdbcTemplate.queryForMap(
                "select status, failure_class, failure_code from outbound_mail_attempts where intent_id = ?",
                intentIds[0],
            ),
        ).containsEntry("status", "PERMANENT_FAILED")
            .containsEntry("failure_class", "PROTECTED_CONTENT")
            .containsEntry("failure_code", "PROTECTED_CONTENT_UNREADABLE")
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from outbound_mail_delivery_events where intent_id = ? and event_type = 'MAIL_TERMINAL_FAILED'",
                Long::class.java,
                intentIds[0],
            ),
        ).isEqualTo(1)
        assertThat(transport.deliveredMessages.map { it.intentId }).containsExactly(intentIds[1])
        assertThat(output.all).contains("code=PROTECTED_CONTENT_UNREADABLE")
        assertThat(output.all).doesNotContain(secretLink)
        assertThat(worker.runDueBatch()).isZero()
    }

    @Test
    fun `delivery events are append only and carry business correlation`() {
        val submitted = submitRequest("delivery-event-ledger")
        val intentId = intentId(ticketId(submitted.ticketNumber))
        val queuedEvent = jdbcTemplate.queryForMap(
            "select id, actor_type, source, request_id, correlation_id from outbound_mail_delivery_events where intent_id = ?",
            intentId,
        )

        assertThat(queuedEvent).containsEntry("actor_type", "CUSTOMER")
            .containsEntry("source", "CUSTOMER_PORTAL")
            .containsEntry("request_id", "request-delivery-event-ledger")
            .containsEntry("correlation_id", "correlation-delivery-event-ledger")
        val eventId = queuedEvent["id"] as UUID
        assertThatThrownBy {
            jdbcTemplate.update("update outbound_mail_delivery_events set reason_code = 'MUTATED' where id = ?", eventId)
        }.isInstanceOf(DataAccessException::class.java)
        assertThatThrownBy {
            jdbcTemplate.update("delete from outbound_mail_delivery_events where id = ?", eventId)
        }.isInstanceOf(DataAccessException::class.java)
    }

    private fun submitRequest(suffix: String) = publicRequestService.submit(
        SubmitAnonymousRequest(
            name = "메일 고객",
            email = "$suffix-${UUID.randomUUID()}@example.com",
            subject = "메일 경계 검증",
            message = "메일 장애와 무관하게 보존되는 문의 본문",
            context = context(suffix, RequestSource.CUSTOMER_PORTAL),
        ),
    )

    private fun magicIntent(key: String, recipient: String, link: String = "https://deskseed.example/magic/token") =
        OutboundMailIntent(
            idempotencyKey = key,
            recipient = MailRecipient(recipient),
            content = MagicLinkMail(link),
            actor = ActorRef(ActorType.SYSTEM, null),
            context = context("magic-${UUID.randomUUID()}", RequestSource.SYSTEM_JOB),
        )

    private fun context(suffix: String, source: RequestSource) = CommandContext(
        source = source,
        requestId = "request-$suffix",
        correlationId = "correlation-$suffix",
        commandId = "command-$suffix",
    )

    private fun terminalFailedIntent(suffix: String): UUID {
        val submitted = submitRequest(suffix)
        val ticketId = ticketId(submitted.ticketNumber)
        val intentId = intentId(ticketId)
        transport.failNext(MailTransportException(false, "RECIPIENT_REJECTED"))
        worker.runDueBatch()
        assertThat(intentStatus(ticketId)).isEqualTo("FAILED")
        return intentId
    }

    private fun <T> asAdmin(block: () -> T): T {
        val previous = SecurityContextHolder.getContext().authentication
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken.authenticated(
            "outbound-mail-admin",
            null,
            listOf(SimpleGrantedAuthority("ROLE_ADMIN")),
        )
        return try {
            block()
        } finally {
            if (previous == null) SecurityContextHolder.clearContext()
            else SecurityContextHolder.getContext().authentication = previous
        }
    }

    private fun ticketId(ticketNumber: Long): UUID = jdbcTemplate.queryForObject(
        "select id from tickets where ticket_number = ?",
        UUID::class.java,
        ticketNumber,
    )!!

    private fun intentId(ticketId: UUID): UUID = jdbcTemplate.queryForObject(
        "select id from outbound_mail_intents where ticket_id = ?",
        UUID::class.java,
        ticketId,
    )!!

    private fun intentStatus(ticketId: UUID): String = jdbcTemplate.queryForObject(
        "select status from outbound_mail_intents where ticket_id = ?",
        String::class.java,
        ticketId,
    )!!

    private fun ticketCount(ticketId: UUID): Long = jdbcTemplate.queryForObject(
        "select count(*) from tickets where id = ?",
        Long::class.java,
        ticketId,
    )!!

    private fun commentCount(ticketId: UUID): Long = jdbcTemplate.queryForObject(
        "select count(*) from ticket_comments where ticket_id = ?",
        Long::class.java,
        ticketId,
    )!!

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
    }
}

@TestConfiguration(proxyBeanMethods = false)
internal class FakeMailTransportConfiguration {
    @Bean
    @Primary
    fun fakeMailTransport() = FakeMailTransport()
}

internal class FakeMailTransport : MailTransport {
    private val failures = ArrayDeque<MailTransportException>()
    val attemptedMessages = mutableListOf<MailTransportMessage>()
    val deliveredMessages = mutableListOf<MailTransportMessage>()

    @Synchronized
    override fun send(message: MailTransportMessage): MailTransportReceipt {
        attemptedMessages += message
        if (failures.isNotEmpty()) throw failures.removeFirst()
        deliveredMessages += message
        return MailTransportReceipt("FAKE", message.stableMessageId)
    }

    @Synchronized
    fun failNext(failure: MailTransportException) {
        failures += failure
    }

    @Synchronized
    fun reset() {
        failures.clear()
        attemptedMessages.clear()
        deliveredMessages.clear()
    }
}
