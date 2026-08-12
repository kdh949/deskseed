package dev.deskseed.portal.internal

import dev.deskseed.foundation.CommandContext
import dev.deskseed.foundation.RequestSource
import dev.deskseed.portal.RequestNotFoundException
import dev.deskseed.settings.AnonymousSubmissionDisabledException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.dao.DataAccessException
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ExtendWith(OutputCaptureExtension::class)
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
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("X-Request-Id", "request-http-123"))
            .andExpect(header().string("X-Correlation-Id", "correlation-http-456"))
            .andExpect(jsonPath("$.status").value("NEW"))
            .andExpect(jsonPath("$.createdAt").isString)

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
    fun `http contract accepts twenty thousand message characters and rejects larger input`() {
        val acceptedBody = "a".repeat(20_000)
        val rejectedBody = "a".repeat(20_001)

        mockMvc.perform(
            post("/api/v1/requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson("accepted-${UUID.randomUUID()}@example.com", acceptedBody)),
        ).andExpect(status().isCreated)

        mockMvc.perform(
            post("/api/v1/requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson("rejected-${UUID.randomUUID()}@example.com", rejectedBody)),
        )
            .andExpect(status().isBadRequest)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.type").value("/problems/validation"))
    }

    @Test
    fun `http contract rejects unknown request fields`() {
        mockMvc.perform(
            post("/api/v1/requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    requestJson("unknown-${UUID.randomUUID()}@example.com", "문의 내용")
                        .replace("\n}", ",\n  \"unexpected\": \"value\"\n}"),
                ),
        )
            .andExpect(status().isBadRequest)
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.type").value("/problems/malformed-json"))
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
    fun `http customer projection contains only public customer safe fields before serialization`() {
        val submitted = submitUniqueRequest("http-public-projection")
        val ticketId = ticketId(submitted.ticketNumber)
        val childSubject = "절대 노출하면 안 되는 하위 티켓"
        val groupId = UUID.randomUUID()
        val assigneeId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            insert into support_groups (id, name, status, created_at, updated_at, version)
            values (?, ?, 'ACTIVE', now(), now(), 0)
            """.trimIndent(),
            groupId,
            "projection-${UUID.randomUUID()}",
        )
        jdbcTemplate.update(
            """
            insert into staff_accounts
                (id, email_normalized, email_display, display_name, role, status,
                 password_hash, created_at, updated_at, version)
            values (?, ?, ?, 'Projection Agent', 'AGENT', 'ACTIVE', 'test-only-hash', now(), now(), 0)
            """.trimIndent(),
            assigneeId,
            "projection-${UUID.randomUUID()}@example.com",
            "projection@example.com",
        )
        jdbcTemplate.update(
            "update tickets set group_id = ?, assignee_id = ? where id = ?",
            groupId,
            assigneeId,
            ticketId,
        )
        jdbcTemplate.update(
            """
            insert into ticket_comments
                (id, ticket_id, author_type, author_id, visibility, body, created_at)
            values (?, ?, 'AGENT', null, 'INTERNAL', ?, ?)
            """.trimIndent(),
            UUID.randomUUID(),
            ticketId,
            "절대 노출하면 안 되는 내부 메모",
            Timestamp.from(Instant.parse("2026-08-10T01:00:00Z")),
        )
        jdbcTemplate.update(
            """
            insert into tickets
                (id, ticket_number, requester_id, kind, subject, status, priority,
                 group_id, assignee_id, channel, version, created_at, updated_at, solved_at)
            select ?, nextval('ticket_number_seq'), requester_id, 'INTERNAL_CHILD', ?,
                   'NEW', 'NORMAL', null, null, 'AGENT', 0, ?, ?, null
            from tickets
            where id = ?
            """.trimIndent(),
            UUID.randomUUID(),
            childSubject,
            Timestamp.from(Instant.parse("2026-08-10T01:01:00Z")),
            Timestamp.from(Instant.parse("2026-08-10T01:01:00Z")),
            ticketId,
        )

        val responseBody = mockMvc.perform(
            get("/api/v1/requests/{ticketNumber}", submitted.ticketNumber)
                .header("X-Request-Access-Token", submitted.accessToken),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.comments.length()").value(1))
            .andExpect(jsonPath("$.comments[0].authorDisplayName").value("고객"))
            .andExpect(jsonPath("$.comments[0].body").value("결제 버튼을 누르면 오류가 납니다."))
            .andExpect(jsonPath("$.comments[0].authorType").doesNotExist())
            .andExpect(jsonPath("$.group").doesNotExist())
            .andExpect(jsonPath("$.assignee").doesNotExist())
            .andExpect(jsonPath("$.children").doesNotExist())
            .andExpect(jsonPath("$.audits").doesNotExist())
            .andReturn()
            .response
            .contentAsString

        assertThat(responseBody).doesNotContain("절대 노출하면 안 되는 내부 메모")
        assertThat(responseBody).doesNotContain(childSubject)
    }

    @Test
    fun `wrong token and nonexistent ticket return the same RFC 9457 problem`() {
        val submitted = submitUniqueRequest("http-non-enumeration")

        fun problem(ticketNumber: Long, token: String): String = mockMvc.perform(
            get("/api/v1/requests/{ticketNumber}", ticketNumber)
                .header("X-Request-Id", "same-request-id")
                .header("X-Request-Access-Token", token),
        )
            .andExpect(status().isNotFound)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.type").value("/problems/request-not-found"))
            .andExpect(jsonPath("$.title").value("Request not found"))
            .andExpect(jsonPath("$.status").value(404))
            .andReturn()
            .response
            .contentAsString

        val wrongToken = problem(submitted.ticketNumber, "x".repeat(43))
        val malformedToken = problem(submitted.ticketNumber, "too-short")
        val nonexistentTicket = problem(submitted.ticketNumber + 10_000, submitted.accessToken)

        assertThat(problemFields(wrongToken)).isEqualTo(problemFields(nonexistentTicket))
        assertThat(problemFields(malformedToken)).isEqualTo(problemFields(nonexistentTicket))
    }

    @Test
    fun `invalid ticket number is a validation error without lookup details`() {
        mockMvc.perform(
            get("/api/v1/requests/{ticketNumber}", -1)
                .header("X-Request-Access-Token", "x".repeat(43)),
        )
            .andExpect(status().isBadRequest)
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.type").value("/problems/validation"))
    }

    @Test
    fun `missing token header and malformed ticket number return RFC 9457 validation problems`() {
        mockMvc.perform(get("/api/v1/requests/{ticketNumber}", 1234))
            .andExpect(status().isBadRequest)
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.type").value("/problems/validation"))
            .andExpect(jsonPath("$.status").value(400))

        mockMvc.perform(
            get("/api/v1/requests/not-a-number")
                .header("X-Request-Access-Token", "not-the-issued-token"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.type").value("/problems/validation"))
            .andExpect(jsonPath("$.status").value(400))
    }

    @Test
    fun `database write failure returns a safe RFC 9457 service problem without partial data`() {
        val marker = UUID.randomUUID().toString()
        val email = "http-failure-$marker@example.com"
        val subject = "http-failure-$marker"
        val table = "ticket_audits"
        val functionName = "test_fail_http_audit_insert"
        val triggerName = "${functionName}_trigger"
        installFailingInsertTrigger(table, functionName, triggerName)
        try {
            mockMvc.perform(
                post("/api/v1/requests")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestJson(email, "민감한 장애 주입 메시지", subject)),
            )
                .andExpect(status().isServiceUnavailable)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("/problems/request-storage-unavailable"))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.detail").value("The request could not be processed safely."))
        } finally {
            jdbcTemplate.execute("drop trigger if exists $triggerName on $table")
            jdbcTemplate.execute("drop function if exists $functionName()")
        }

        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from tickets where subject = ?",
                Long::class.java,
                subject,
            ),
        ).isZero()
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from customers where email_normalized = ?",
                Long::class.java,
                email,
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
            "update request_access_tokens set revoked_at = created_at where ticket_id = ?",
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
    fun `same unverified email creates isolated customers without sharing ticket grants`() {
        val email = "Reuse-${UUID.randomUUID()}@Example.com"
        fun submit(name: String, subject: String) = service.submit(
            SubmitAnonymousRequest(
                name = name,
                email = email,
                subject = subject,
                message = "문의 내용",
                context = CommandContext(
                    source = RequestSource.CUSTOMER_PORTAL,
                    requestId = "request-${UUID.randomUUID()}",
                    correlationId = "correlation-${UUID.randomUUID()}",
                    commandId = UUID.randomUUID().toString(),
                ),
            ),
        )
        val first = submit("첫 이름", "첫 문의")
        val second = submit("두 번째 이름", "두 번째 문의")

        val firstCustomerId = ticketRequesterId(first.ticketNumber)
        val secondCustomerId = ticketRequesterId(second.ticketNumber)
        assertThat(firstCustomerId).isNotEqualTo(secondCustomerId)
        assertThat(customerName(firstCustomerId)).isEqualTo("첫 이름")
        assertThat(customerName(secondCustomerId)).isEqualTo("두 번째 이름")
        assertThat(customerCount(email)).isEqualTo(2)
        assertThat(ticketCount(firstCustomerId)).isEqualTo(1)
        assertThat(ticketCount(secondCustomerId)).isEqualTo(1)
        assertThatThrownBy { service.view(first.ticketNumber, second.accessToken) }
            .isInstanceOf(RequestNotFoundException::class.java)
        assertThatThrownBy { service.view(second.ticketNumber, first.accessToken) }
            .isInstanceOf(RequestNotFoundException::class.java)
    }

    @Test
    fun `anonymous reuse never overwrites a verified customer profile`() {
        val email = "verified-${UUID.randomUUID()}@example.com"
        val first = service.submit(
            SubmitAnonymousRequest(
                name = "초기 이름",
                email = email,
                subject = "첫 문의",
                message = "첫 문의 내용",
                context = commandContext("verified-first"),
            ),
        )
        val customerId = jdbcTemplate.queryForObject(
            "select requester_id from tickets where ticket_number = ?",
            UUID::class.java,
            first.ticketNumber,
        )!!
        jdbcTemplate.update(
            """
            update customers
            set name = ?, email_display = ?, verified_at = now(), updated_at = now()
            where id = ?
            """.trimIndent(),
            "검증된 이름",
            "Verified.Display@example.com",
            customerId,
        )

        val second = service.submit(
            SubmitAnonymousRequest(
                name = "덮어쓰면 안 되는 이름",
                email = email.uppercase(),
                subject = "두 번째 문의",
                message = "두 번째 문의 내용",
                context = commandContext("verified-second"),
            ),
        )

        val customer = jdbcTemplate.queryForMap(
            "select name, email_display from customers where id = ?",
            customerId,
        )
        assertThat(customer["name"]).isEqualTo("검증된 이름")
        assertThat(customer["email_display"]).isEqualTo("Verified.Display@example.com")
        assertThat(ticketRequesterId(second.ticketNumber)).isNotEqualTo(customerId)
        assertThat(customerCount(email)).isEqualTo(2)
    }

    @Test
    fun `verified email remains unique while unverified contacts may repeat`() {
        val email = "verified-unique-${UUID.randomUUID()}@example.com"
        val first = service.submit(
            SubmitAnonymousRequest(
                name = "첫 고객",
                email = email,
                subject = "첫 문의",
                message = "첫 문의 내용",
                context = commandContext("verified-unique-first"),
            ),
        )
        val second = service.submit(
            SubmitAnonymousRequest(
                name = "두 번째 고객",
                email = email,
                subject = "두 번째 문의",
                message = "두 번째 문의 내용",
                context = commandContext("verified-unique-second"),
            ),
        )
        val firstCustomerId = ticketRequesterId(first.ticketNumber)
        val secondCustomerId = ticketRequesterId(second.ticketNumber)

        jdbcTemplate.update(
            "update customers set verified_at = now(), updated_at = now() where id = ?",
            firstCustomerId,
        )
        assertThatThrownBy {
            jdbcTemplate.update(
                "update customers set verified_at = now(), updated_at = now() where id = ?",
                secondCustomerId,
            )
        }.isInstanceOf(DataAccessException::class.java)
        assertThat(customerCount(email)).isEqualTo(2)
    }

    @Test
    fun `concurrent first submissions for one normalized email create isolated customers and both succeed`() {
        val marker = UUID.randomUUID().toString()
        val email = "Concurrent-$marker@Example.com"
        val functionName = "test_delay_customer_insert"
        val triggerName = "${functionName}_trigger"
        val barrier = CyclicBarrier(2)
        val executor = Executors.newFixedThreadPool(2)
        installCustomerInsertDelayTrigger(functionName, triggerName)

        try {
            val responses = executor.invokeAll(
                (1..2).map { requestNumber ->
                    Callable {
                        barrier.await(10, TimeUnit.SECONDS)
                        mockMvc.perform(
                            post("/api/v1/requests")
                                .header("X-Request-Id", "concurrent-$marker-$requestNumber")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                    requestJson(
                                        email = email,
                                        message = "동시 문의 $requestNumber",
                                        subject = "동시 생성 $requestNumber",
                                    ),
                                ),
                        )
                            .andExpect(status().isCreated)
                            .andExpect(header().string("Cache-Control", "no-store"))
                            .andReturn()
                            .response
                            .contentAsString
                    }
                },
            ).map { it.get(15, TimeUnit.SECONDS) }

            assertThat(responses).allMatch { it.contains("\"accessToken\":") }
        } finally {
            executor.shutdownNow()
            jdbcTemplate.execute("drop trigger if exists $triggerName on customers")
            jdbcTemplate.execute("drop function if exists $functionName()")
        }

        val customerIds = jdbcTemplate.query(
            "select id from customers where email_normalized = ? order by id",
            { resultSet, _ -> resultSet.getObject("id", UUID::class.java) },
            email.lowercase(),
        )
        assertThat(customerIds).hasSize(2)
        assertThat(customerIds.map(::ticketCount)).containsExactlyInAnyOrder(1, 1)
    }

    @Test
    fun `database read failure returns a neutral no-store storage problem`() {
        val submitted = submitUniqueRequest("read-storage-failure")

        jdbcTemplate.execute(
            "alter table request_access_tokens rename column token_hash to token_hash_unavailable",
        )
        try {
            mockMvc.perform(
                get("/api/v1/requests/{ticketNumber}", submitted.ticketNumber)
                    .header("X-Request-Access-Token", submitted.accessToken),
            )
                .andExpect(status().isServiceUnavailable)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("/problems/request-storage-unavailable"))
                .andExpect(jsonPath("$.detail").value("The request could not be processed safely."))
        } finally {
            jdbcTemplate.execute(
                "alter table request_access_tokens rename column token_hash_unavailable to token_hash",
            )
        }
    }

    @Test
    fun `anonymous submission disabled problem is never cacheable`() {
        jdbcTemplate.update(
            "update system_settings set customer_access_mode = 'REGISTRATION_REQUIRED', updated_at = now() where id = 1",
        )
        try {
            mockMvc.perform(
                post("/api/v1/requests")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestJson("disabled-${UUID.randomUUID()}@example.com", "문의 내용")),
            )
                .andExpect(status().isForbidden)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        } finally {
            jdbcTemplate.update(
                "update system_settings set customer_access_mode = 'ANONYMOUS_ALLOWED', updated_at = now() where id = 1",
            )
        }
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
        val eventRows = jdbcTemplate.queryForList(
            """
            select event_order, event_type, occurred_at
            from ticket_audit_events
            where audit_id = ?
            order by event_order
            """.trimIndent(),
            auditId,
        )

        assertThat(eventRows.map { it["event_order"] }).containsExactly(1, 2)
        assertThat(eventRows.map { it["event_type"] }).containsExactly("TICKET_CREATED", "COMMENT_CREATED")
        assertThat(eventRows.map { it["occurred_at"] }).doesNotContainNull()
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
        assertThatThrownBy {
            jdbcTemplate.update(
                "delete from ticket_audit_events where audit_id = ?",
                auditId,
            )
        }.isInstanceOf(DataAccessException::class.java)
        assertThatThrownBy {
            jdbcTemplate.update(
                "delete from ticket_audits where id = ?",
                auditId,
            )
        }.isInstanceOf(DataAccessException::class.java)
    }

    @Test
    fun `comment insert failure rolls back customer ticket audit and access grant`() {
        assertCreationRollsBackWhenInsertFails("ticket_comments", "comment-insert-failure")
    }

    @Test
    fun `ticket audit insert failure rolls back customer ticket comment and access grant`() {
        assertCreationRollsBackWhenInsertFails("ticket_audits", "audit-insert-failure")
    }

    @Test
    fun `ticket audit event insert failure rolls back the whole creation command`() {
        assertCreationRollsBackWhenInsertFails("ticket_audit_events", "audit-event-insert-failure")
    }

    @Test
    fun `access grant insert failure rolls back the whole creation command`() {
        assertCreationRollsBackWhenInsertFails("request_access_tokens", "grant-insert-failure")
    }

    @Test
    fun `message and issued token never appear in application logs`(output: CapturedOutput) {
        val marker = UUID.randomUUID().toString()
        val secretMessage = "sensitive-message-$marker"
        val response = mockMvc.perform(
            post("/api/v1/requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson("log-$marker@example.com", secretMessage)),
        )
            .andExpect(status().isCreated)
            .andReturn()
            .response
            .contentAsString
        val rawToken = Regex("\"accessToken\":\"([^\"]+)\"").find(response)!!.groupValues[1]

        assertThat(output.all).doesNotContain(secretMessage)
        assertThat(output.all).doesNotContain(rawToken)
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
            context = commandContext(suffix),
        ),
    )

    private fun commandContext(suffix: String): CommandContext = CommandContext(
        source = RequestSource.CUSTOMER_PORTAL,
        requestId = "request-$suffix",
        correlationId = "correlation-$suffix",
        commandId = UUID.randomUUID().toString(),
    )

    private fun ticketId(ticketNumber: Long): UUID = jdbcTemplate.queryForObject(
        "select id from tickets where ticket_number = ?",
        UUID::class.java,
        ticketNumber,
    )!!

    private fun ticketRequesterId(ticketNumber: Long): UUID = jdbcTemplate.queryForObject(
        "select requester_id from tickets where ticket_number = ?",
        UUID::class.java,
        ticketNumber,
    )!!

    private fun customerName(customerId: UUID): String = jdbcTemplate.queryForObject(
        "select name from customers where id = ?",
        String::class.java,
        customerId,
    )!!

    private fun customerCount(email: String): Long = jdbcTemplate.queryForObject(
        "select count(*) from customers where email_normalized = ?",
        Long::class.java,
        email.lowercase(),
    ) ?: 0

    private fun ticketCount(customerId: UUID): Long = jdbcTemplate.queryForObject(
        "select count(*) from tickets where requester_id = ?",
        Long::class.java,
        customerId,
    ) ?: 0

    private fun requestJson(email: String, message: String, subject: String = "결제 오류"): String =
        """
        {
          "name": "김고객",
          "email": "$email",
          "subject": "$subject",
          "message": "$message",
          "privacyConsent": true
        }
        """.trimIndent()

    private fun problemFields(json: String): List<String> = listOf(
        Regex("\"type\":\"([^\"]+)\"").find(json)!!.groupValues[1],
        Regex("\"title\":\"([^\"]+)\"").find(json)!!.groupValues[1],
        Regex("\"status\":([0-9]+)").find(json)!!.groupValues[1],
        Regex("\"detail\":\"([^\"]+)\"").find(json)!!.groupValues[1],
    )

    private fun assertCreationRollsBackWhenInsertFails(table: String, marker: String) {
        val functionName = "test_fail_${table}_insert"
        val triggerName = "${functionName}_trigger"
        val email = "$marker-${UUID.randomUUID()}@example.com"
        val subject = "atomic-$marker-${UUID.randomUUID()}"
        installFailingInsertTrigger(table, functionName, triggerName)
        try {
            assertThatThrownBy {
                service.submit(
                    SubmitAnonymousRequest(
                        name = "원자성 고객",
                        email = email,
                        subject = subject,
                        message = "원자성 검증 메시지",
                        context = CommandContext(
                            source = RequestSource.CUSTOMER_PORTAL,
                            requestId = "request-$marker",
                            correlationId = "correlation-$marker",
                            commandId = UUID.randomUUID().toString(),
                        ),
                    ),
                )
            }.isInstanceOf(RuntimeException::class.java)
        } finally {
            jdbcTemplate.execute("drop trigger if exists $triggerName on $table")
            jdbcTemplate.execute("drop function if exists $functionName()")
        }

        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from tickets where subject = ?",
                Long::class.java,
                subject,
            ),
        ).isZero()
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from customers where email_normalized = ?",
                Long::class.java,
                email.lowercase(),
            ),
        ).isZero()
        assertThat(
            jdbcTemplate.queryForObject(
                """
                select count(*)
                from ticket_comments comment
                join tickets ticket on ticket.id = comment.ticket_id
                where ticket.subject = ?
                """.trimIndent(),
                Long::class.java,
                subject,
            ),
        ).isZero()
        assertThat(
            jdbcTemplate.queryForObject(
                """
                select count(*)
                from ticket_audits audit
                join tickets ticket on ticket.id = audit.ticket_id
                where ticket.subject = ?
                """.trimIndent(),
                Long::class.java,
                subject,
            ),
        ).isZero()
        assertThat(
            jdbcTemplate.queryForObject(
                """
                select count(*)
                from request_access_tokens grant_record
                join tickets ticket on ticket.id = grant_record.ticket_id
                where ticket.subject = ?
                """.trimIndent(),
                Long::class.java,
                subject,
            ),
        ).isZero()
    }

    private fun installFailingInsertTrigger(table: String, functionName: String, triggerName: String) {
        require(table in setOf("ticket_comments", "ticket_audits", "ticket_audit_events", "request_access_tokens"))
        jdbcTemplate.execute(
            """
            create function $functionName()
            returns trigger
            language plpgsql
            as ${'$'}${'$'}
            begin
                raise exception 'injected insert failure';
            end;
            ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            create trigger $triggerName
            before insert on $table
            for each row execute function $functionName()
            """.trimIndent(),
        )
    }

    private fun installCustomerInsertDelayTrigger(functionName: String, triggerName: String) {
        jdbcTemplate.execute(
            """
            create function $functionName()
            returns trigger
            language plpgsql
            as ${'$'}${'$'}
            begin
                perform pg_sleep(0.5);
                return new;
            end;
            ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            create trigger $triggerName
            before insert on customers
            for each row execute function $functionName()
            """.trimIndent(),
        )
    }

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
    }
}
