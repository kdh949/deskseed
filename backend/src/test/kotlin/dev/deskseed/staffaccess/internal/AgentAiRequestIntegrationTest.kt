package dev.deskseed.staffaccess.internal

import dev.deskseed.aiassistance.AiBackendRequestStatus
import dev.deskseed.aiassistance.AiExecutionStatusReader
import dev.deskseed.aiassistance.AiGenerationProvenance
import dev.deskseed.aiassistance.AiJobReceipt
import dev.deskseed.aiassistance.AiSummaryResult
import dev.deskseed.aiassistance.internal.LEGACY_AI_CONTEXT_POLICY_VERSION
import dev.deskseed.aiassistance.internal.computeAiContextRevision
import dev.deskseed.ticketing.StaffTicketReadStore
import dev.deskseed.testsupport.integration.DeskseedSpringIntegrationTest
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.mockito.Mockito
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@DeskseedSpringIntegrationTest(
    properties = [
        "deskseed.staff-auth.bootstrap.enabled=false",
        "deskseed.ai.source-auth.enabled=true",
        "deskseed.ai.source-auth.key-id=test-ai-key",
        "deskseed.ai.source-auth.secret-sha256=a695c5b0e55ed3f90a405d33c529adf48c231f5bfac981f639dbd647d83c70eb",
        "deskseed.ai.source-auth.principal-id=10000000-0000-0000-0000-000000000001",
    ],
)
@AutoConfigureMockMvc
@dev.deskseed.testsupport.category.IntegrationTest
class AgentAiRequestIntegrationTest {
    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate
    @Autowired private lateinit var ticketStore: StaffTicketReadStore
    @Autowired private lateinit var databaseCleaner: dev.deskseed.testsupport.integration.StaffTicketTestDatabaseCleaner
    @MockitoBean private lateinit var executionStatusReader: AiExecutionStatusReader

    @BeforeEach
    fun clearState() = databaseCleaner.resetMutableStaffTicketState()

    @Test
    fun `create is exact-idempotent and appends body-free outbox and nonsemantic access audit`() {
        val fixture = fixture(9101)
        val session = login(fixture.email, PASSWORD)
        val key = "ai-idempotency-key-0001"
        val first = create(session, fixture.staffId, fixture.number, key, requestBody("ticket.summary"))
            .andExpect(status().isAccepted)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.status").value("ACCEPTED"))
            .andExpect(jsonPath("$.inputScope").value("PUBLIC_ONLY"))
            .andReturn().response.contentAsString
        val second = create(session, fixture.staffId, fixture.number, key, requestBody("ticket.summary"))
            .andExpect(status().isAccepted)
            .andReturn().response.contentAsString
        assertThat(uuidField(second, "jobId")).isEqualTo(uuidField(first, "jobId"))

        create(session, fixture.staffId, fixture.number, key, requestBody("ticket.triage"))
            .andExpect(status().isConflict)

        assertThat(count("select count(*) from ai_requests")).isEqualTo(1)
        assertThat(count("select count(*) from ai_integration_outbox where event_type = 'JOB_REQUESTED'"))
            .isEqualTo(1)
        val payload = jdbcTemplate.queryForObject(
            "select payload_json::text from ai_integration_outbox where event_type = 'JOB_REQUESTED'",
            String::class.java,
        )!!
        assertThat(payload).doesNotContain(PUBLIC_BODY, INTERNAL_BODY, fixture.subject, "@example.com")
        assertThat(payload).contains("\"contextPolicyVersion\": \"public-comments-v2\"")
            .contains("\"language\": \"ko\"")
        assertThat(count("select count(*) from access_audit_events where action = 'TICKET_VIEWED'"))
            .isZero()
        assertThat(count("select count(*) from access_audit_events where action = 'API_RESOURCE_READ' and actor_type = 'STAFF'"))
            .isEqualTo(1)
    }

    @Test
    fun `source returns only ordered PUBLIC comments and records fixed service plus requester attribution`() {
        val fixture = fixture(9102)
        val session = login(fixture.email, PASSWORD)
        val sameTimestamp = Timestamp.from(Instant.parse("2026-09-16T00:00:05Z"))
        jdbcTemplate.update(
            """
            insert into ticket_comments (id, ticket_id, author_type, author_id, visibility, body, created_at)
            values (?, ?, 'AGENT', ?, 'PUBLIC', '상담사 공개 안내', ?)
            """.trimIndent(),
            UUID.randomUUID(), fixture.ticketId, fixture.staffId, sameTimestamp,
        )
        jdbcTemplate.update(
            """
            insert into ticket_comments (id, ticket_id, author_type, author_id, visibility, body, created_at)
            values (?, ?, 'AUTOMATION', null, 'PUBLIC', '자동화 공개 안내', ?)
            """.trimIndent(),
            UUID.randomUUID(), fixture.ticketId, sameTimestamp,
        )
        val created = create(
            session,
            fixture.staffId,
            fixture.number,
            "ai-idempotency-key-0002",
            requestBody("ticket.reply_draft", """{"language":"ko","tone":"calm"}"""),
        ).andExpect(status().isAccepted).andReturn().response.contentAsString
        val jobId = uuidField(created, "jobId")

        val response = source(jobId)
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.inputScope").value("PUBLIC_ONLY"))
            .andExpect(jsonPath("$.contextPolicyVersion").value("public-comments-v2"))
            .andExpect(jsonPath("$.comments.length()").value(3))
            .andExpect(jsonPath("$.comments[0].body").value(PUBLIC_BODY))
            .andExpect(jsonPath("$.comments[0].sequence").value(1))
            .andExpect(jsonPath("$.comments[0].authorRole").value("CUSTOMER"))
            .andExpect(jsonPath("$.comments[1].sequence").value(2))
            .andExpect(jsonPath("$.comments[1].authorRole").value("STAFF"))
            .andExpect(jsonPath("$.comments[2].sequence").value(3))
            .andExpect(jsonPath("$.comments[2].authorRole").value("SYSTEM"))
            .andReturn().response.contentAsString
        assertThat(response).doesNotContain(INTERNAL_BODY, fixture.subject, fixture.email)

        assertThat(
            count(
                """
                select count(*)
                from ai_context_access_audit_details detail
                join access_audit_events event on event.id = detail.access_event_id
                where detail.job_id = '$jobId'
                  and detail.requester_staff_id = '${fixture.staffId}'
                  and event.actor_id = '10000000-0000-0000-0000-000000000001'
                  and event.actor_type = 'INTEGRATION_CLIENT'
                  and event.source = 'AI_SERVICE'
                """.trimIndent(),
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `source preserves legacy v1 revision and response shape for an in-flight binding`() {
        val fixture = fixture(9110)
        val session = login(fixture.email, PASSWORD)
        val created = create(
            session,
            fixture.staffId,
            fixture.number,
            "ai-idempotency-key-legacy-v1",
            requestBody("ticket.summary"),
        ).andExpect(status().isAccepted).andReturn().response.contentAsString
        val jobId = uuidField(created, "jobId")
        val context = ticketStore.findAiPublicContext(fixture.number, fixture.staffId)!!
        val legacyRevision = computeAiContextRevision(context, LEGACY_AI_CONTEXT_POLICY_VERSION)
        jdbcTemplate.update(
            "update ai_requests set context_policy_version = ?, context_revision = ? where job_id = ?",
            LEGACY_AI_CONTEXT_POLICY_VERSION,
            legacyRevision,
            jobId,
        )

        source(jobId)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.contextPolicyVersion").value(LEGACY_AI_CONTEXT_POLICY_VERSION))
            .andExpect(jsonPath("$.contextRevision").value(legacyRevision))
            .andExpect(jsonPath("$.comments[0].body").value(PUBLIC_BODY))
            .andExpect(jsonPath("$.comments[0].sequence").doesNotExist())
            .andExpect(jsonPath("$.comments[0].authorRole").doesNotExist())
    }

    @Test
    fun `source fails closed after PUBLIC revision change or requester deactivation`() {
        val fixture = fixture(9103)
        val session = login(fixture.email, PASSWORD)
        val firstJob = uuidField(
            create(session, fixture.staffId, fixture.number, "ai-idempotency-key-0003", requestBody("ticket.summary"))
                .andExpect(status().isAccepted).andReturn().response.contentAsString,
            "jobId",
        )
        insertComment(fixture.ticketId, "PUBLIC", fixture.customerId, "후속 공개 메시지")
        source(firstJob).andExpect(status().isConflict).andExpect(jsonPath("$.comments").doesNotExist())
        assertThat(
            jdbcTemplate.queryForObject("select status from ai_requests where job_id = ?", String::class.java, firstJob),
        ).isEqualTo("SUPERSEDED")

        val secondJob = uuidField(
            create(session, fixture.staffId, fixture.number, "ai-idempotency-key-0004", requestBody("ticket.triage"))
                .andExpect(status().isAccepted).andReturn().response.contentAsString,
            "jobId",
        )
        jdbcTemplate.update("update staff_accounts set status = 'DISABLED' where id = ?", fixture.staffId)
        source(secondJob).andExpect(status().isNotFound).andExpect(jsonPath("$.comments").doesNotExist())
    }

    @Test
    fun `cancel increments request revision once and appends one cancellation outbox event`() {
        val fixture = fixture(9104)
        val session = login(fixture.email, PASSWORD)
        val jobId = uuidField(
            create(session, fixture.staffId, fixture.number, "ai-idempotency-key-0005", requestBody("ticket.summary"))
                .andExpect(status().isAccepted).andReturn().response.contentAsString,
            "jobId",
        )
        repeat(2) {
            mockMvc.perform(
                post("/api/v1/agent/tickets/{ticketNumber}/ai/jobs/{jobId}/cancel", fixture.number, jobId)
                    .session(session)
                    .header("X-CSRF-TOKEN", csrf(session))
                    .header("X-Deskseed-Expected-Staff-Id", fixture.staffId.toString()),
            )
                .andExpect(status().isAccepted)
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.requestRevision").value(2))
        }
        assertThat(count("select count(*) from ai_integration_outbox where event_type = 'JOB_CANCELLED'"))
            .isEqualTo(1)
        source(jobId).andExpect(status().isNotFound)
    }

    @Test
    fun `source authentication is direction-specific and fails closed`() {
        source(UUID.randomUUID(), secret = "wrong").andExpect(status().isUnauthorized)
        mockMvc.perform(
            get("/api/v1/internal/ai/requests/{jobId}/context", UUID.randomUUID())
                .header("Authorization", "Bearer test-ai-source-secret")
                .header("X-Deskseed-AI-Key-Id", "wrong-key"),
        ).andExpect(status().isUnauthorized)
        mockMvc.perform(
            get("/api/v1/internal/ai/kb/manifest")
                .header("Authorization", "Bearer test-ai-source-secret")
                .header("X-Deskseed-AI-Key-Id", "test-ai-key"),
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `new request rejects a stale ticket version and feature off fails closed`() {
        val fixture = fixture(9105)
        val session = login(fixture.email, PASSWORD)
        create(
            session,
            fixture.staffId,
            fixture.number,
            "ai-idempotency-key-0006",
            """{"feature":"ticket.summary","expectedTicketVersion":99,"options":{}}""",
        ).andExpect(status().isConflict)
        assertThat(count("select count(*) from ai_requests")).isZero()

        jdbcTemplate.update("update ai_settings set enabled = false where singleton = true")
        create(session, fixture.staffId, fixture.number, "ai-idempotency-key-0007", requestBody("ticket.summary"))
            .andExpect(status().isServiceUnavailable)
        assertThat(count("select count(*) from ai_requests")).isZero()
    }

    @Test
    fun `unsupported AI options fail before request outbox and audit mutation`() {
        val fixture = fixture(9109)
        val session = login(fixture.email, PASSWORD)

        create(
            session,
            fixture.staffId,
            fixture.number,
            "ai-idempotency-key-unsupported-language",
            requestBody("ticket.summary", """{"language":"en"}"""),
        ).andExpect(status().isBadRequest)
        create(
            session,
            fixture.staffId,
            fixture.number,
            "ai-idempotency-key-unsupported-tone",
            requestBody("ticket.summary", """{"tone":"calm"}"""),
        ).andExpect(status().isBadRequest)

        assertThat(count("select count(*) from ai_requests")).isZero()
        assertThat(count("select count(*) from ai_integration_outbox")).isZero()
        assertThat(count("select count(*) from access_audit_events where action = 'API_RESOURCE_READ'")).isZero()
    }

    @Test
    fun `actor admission limit applies backpressure before creating a sixth job`() {
        val fixture = fixture(9108)
        val session = login(fixture.email, PASSWORD)
        repeat(5) { index ->
            create(
                session,
                fixture.staffId,
                fixture.number,
                "ai-rate-limit-key-${index.toString().padStart(8, '0')}",
                requestBody("ticket.summary"),
            ).andExpect(status().isAccepted)
        }

        create(
            session,
            fixture.staffId,
            fixture.number,
            "ai-rate-limit-key-00000005",
            requestBody("ticket.summary"),
        )
            .andExpect(status().isTooManyRequests)
            .andExpect(header().string("Retry-After", "60"))
        assertThat(count("select count(*) from ai_requests")).isEqualTo(5)
        assertThat(count("select count(*) from ai_integration_outbox where event_type = 'JOB_REQUESTED'"))
            .isEqualTo(5)
    }

    @Test
    fun `feedback is exact-idempotent revisioned audited and body-free`() {
        val fixture = fixture(9106)
        val session = login(fixture.email, PASSWORD)
        val jobId = uuidField(
            create(session, fixture.staffId, fixture.number, "ai-idempotency-key-0008", requestBody("ticket.summary"))
                .andExpect(status().isAccepted).andReturn().response.contentAsString,
            "jobId",
        )
        repeat(2) {
            mockMvc.perform(
                post("/api/v1/agent/tickets/{ticketNumber}/ai/jobs/{jobId}/feedback", fixture.number, jobId)
                    .session(session)
                    .header("X-CSRF-TOKEN", csrf(session))
                    .header("X-Deskseed-Expected-Staff-Id", fixture.staffId.toString())
                    .header("Idempotency-Key", "ai-feedback-key-00000001")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"type":"helpful","reasonCode":"accurate"}"""),
            )
                .andExpect(status().isAccepted)
                .andExpect(jsonPath("$.sourceRevision").value(1))
        }
        mockMvc.perform(
            post("/api/v1/agent/tickets/{ticketNumber}/ai/jobs/{jobId}/feedback", fixture.number, jobId)
                .session(session)
                .header("X-CSRF-TOKEN", csrf(session))
                .header("X-Deskseed-Expected-Staff-Id", fixture.staffId.toString())
                .header("Idempotency-Key", "ai-feedback-key-00000001")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"type":"unhelpful","reasonCode":"inaccurate"}"""),
        ).andExpect(status().isConflict)

        assertThat(count("select count(*) from ai_request_feedback")).isEqualTo(1)
        assertThat(count("select count(*) from ai_feedback_idempotency")).isEqualTo(1)
        assertThat(count("select count(*) from ai_activity_events where action = 'AI_FEEDBACK_RECORDED'")).isEqualTo(1)
        assertThat(count("select count(*) from ai_integration_outbox where event_type = 'JOB_FEEDBACK'")).isEqualTo(1)
        val payload = jdbcTemplate.queryForObject(
            "select payload_json::text from ai_integration_outbox where event_type = 'JOB_FEEDBACK'",
            String::class.java,
        )!!
        assertThat(payload).doesNotContain(PUBLIC_BODY, INTERNAL_BODY, fixture.subject, fixture.email)
    }

    @Test
    fun `result body is audited and hidden when PUBLIC context becomes stale`() {
        val fixture = fixture(9107)
        val session = login(fixture.email, PASSWORD)
        val created = create(
            session,
            fixture.staffId,
            fixture.number,
            "ai-idempotency-key-0009",
            requestBody("ticket.summary"),
        ).andExpect(status().isAccepted).andReturn().response.contentAsString
        val jobId = uuidField(created, "jobId")
        val contextRevision = stringField(created, "contextRevision")
        val now = Instant.now()
        Mockito.`when`(executionStatusReader.read(jobId, false)).thenReturn(
            AiJobReceipt(
                jobId = jobId,
                feature = "ticket.summary",
                status = AiBackendRequestStatus.SUCCEEDED,
                requestRevision = 1,
                createdAt = now.minusSeconds(1),
                deadlineAt = now.plusSeconds(60),
                pollAfterMs = 750,
                cancelRequested = false,
                contextRevision = contextRevision,
                contextPolicyVersion = "public-comments-v2",
                phase = "COMPLETE",
                completedAt = now,
                resultExpiresAt = now.plusSeconds(3600),
                costMicrousd = 10,
            ),
        )
        Mockito.`when`(executionStatusReader.read(jobId, true)).thenReturn(
            AiJobReceipt(
                jobId = jobId,
                feature = "ticket.summary",
                status = AiBackendRequestStatus.SUCCEEDED,
                requestRevision = 1,
                createdAt = now.minusSeconds(1),
                deadlineAt = now.plusSeconds(60),
                pollAfterMs = 750,
                cancelRequested = false,
                contextRevision = contextRevision,
                contextPolicyVersion = "public-comments-v2",
                phase = "COMPLETE",
                completedAt = now,
                resultExpiresAt = now.plusSeconds(3600),
                result = AiSummaryResult(problem = "공개 문의", attemptedActions = emptyList(), unresolvedItems = emptyList(), nextChecks = emptyList()),
                provenance = AiGenerationProvenance(
                    modelAlias = "openai/gpt-5.6-luna",
                    actualModel = "openai/gpt-5.6-luna",
                    promptVersion = "ai-v1.2-p1",
                    configVersion = "2026-09-16",
                    generatedAt = now,
                    publicCommentIds = emptyList(),
                    contextRevision = contextRevision,
                ),
                costMicrousd = 10,
            ),
        )
        mockMvc.perform(
            get("/api/v1/agent/tickets/{ticketNumber}/ai/jobs/{jobId}", fixture.number, jobId).session(session),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").isEmpty)
        assertThat(count("select count(*) from ai_result_access_audit_details where job_id = '$jobId'"))
            .isZero()

        mockMvc.perform(
            get("/api/v1/agent/tickets/{ticketNumber}/ai/jobs/{jobId}?includeResult=true", fixture.number, jobId).session(session),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result.problem").value("공개 문의"))
        assertThat(count("select count(*) from ai_result_access_audit_details where job_id = '$jobId'"))
            .isEqualTo(1)

        insertComment(fixture.ticketId, "PUBLIC", fixture.customerId, "새 공개 댓글")
        mockMvc.perform(
            get("/api/v1/agent/tickets/{ticketNumber}/ai/jobs/{jobId}?includeResult=true", fixture.number, jobId).session(session),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.stale").value(true))
            .andExpect(jsonPath("$.canInsert").value(false))
            .andExpect(jsonPath("$.result").isEmpty)
        assertThat(count("select count(*) from ai_result_access_audit_details where job_id = '$jobId'"))
            .isEqualTo(1)
    }

    private fun create(
        session: MockHttpSession,
        actorId: UUID,
        ticketNumber: Long,
        key: String,
        body: String,
    ) = mockMvc.perform(
        post("/api/v1/agent/tickets/{ticketNumber}/ai/jobs", ticketNumber)
            .session(session)
            .header("X-CSRF-TOKEN", csrf(session))
            .header("X-Deskseed-Expected-Staff-Id", actorId.toString())
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body),
    )

    private fun source(jobId: UUID, secret: String = "test-ai-source-secret") = mockMvc.perform(
        get("/api/v1/internal/ai/requests/{jobId}/context", jobId)
            .header("Authorization", "Bearer $secret")
            .header("X-Deskseed-AI-Key-Id", "test-ai-key"),
    )

    private fun requestBody(feature: String, options: String = "{}") =
        """{"feature":"$feature","expectedTicketVersion":0,"options":$options}"""

    private fun fixture(number: Long): Fixture {
        val email = "ai-agent-$number@example.com"
        val staffId = insertStaff(email)
        jdbcTemplate.update(
            """
            update ai_settings set enabled = true, summary_enabled = true, triage_enabled = true,
                reply_draft_enabled = true, updated_at = clock_timestamp()
            where singleton = true
            """.trimIndent(),
        )
        jdbcTemplate.update(
            """
            insert into ai_feature_staff_allowlist (staff_id, added_by_staff_id, added_at)
            values (?, ?, clock_timestamp())
            """.trimIndent(),
            staffId,
            staffId,
        )
        val customerId = UUID.randomUUID()
        val ticketId = UUID.randomUUID()
        val now = Instant.parse("2026-09-16T00:00:00Z")
        jdbcTemplate.update(
            """
            insert into customers (id, name, email_normalized, email_display, created_at, updated_at)
            values (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            customerId, "고객 $number", "customer-$number@example.com", "customer-$number@example.com",
            Timestamp.from(now), Timestamp.from(now),
        )
        val subject = "비공개로 취급할 제목 $number"
        jdbcTemplate.update(
            """
            insert into tickets (
                id, ticket_number, requester_id, kind, subject, status, priority,
                group_id, assignee_id, channel, version, created_at, updated_at
            ) values (?, ?, ?, 'CUSTOMER_REQUEST', ?, 'OPEN', 'NORMAL', null, null, 'WEB', 0, ?, ?)
            """.trimIndent(),
            ticketId, number, customerId, subject, Timestamp.from(now), Timestamp.from(now),
        )
        insertComment(ticketId, "PUBLIC", customerId, PUBLIC_BODY)
        jdbcTemplate.update(
            """
            insert into ticket_comments (id, ticket_id, author_type, author_id, visibility, body, created_at)
            values (?, ?, 'AGENT', ?, 'INTERNAL', ?, ?)
            """.trimIndent(),
            UUID.randomUUID(), ticketId, staffId, INTERNAL_BODY, Timestamp.from(now.plusSeconds(1)),
        )
        return Fixture(staffId, customerId, ticketId, number, email, subject)
    }

    private fun insertComment(ticketId: UUID, visibility: String, customerId: UUID, body: String) {
        jdbcTemplate.update(
            """
            insert into ticket_comments (id, ticket_id, author_type, author_id, visibility, body, created_at)
            values (?, ?, 'CUSTOMER', ?, ?, ?, clock_timestamp())
            """.trimIndent(),
            UUID.randomUUID(), ticketId, customerId, visibility, body,
        )
    }

    private fun insertStaff(email: String): UUID = UUID.randomUUID().also { id ->
        jdbcTemplate.update(
            """
            insert into staff_accounts (
                id, email_normalized, email_display, display_name, role, status,
                password_hash, created_at, updated_at, version
            ) values (?, ?, ?, 'AI 상담사', 'AGENT', 'ACTIVE', ?, now(), now(), 0)
            """.trimIndent(),
            id, email.lowercase(), email, BCryptPasswordEncoder(4).encode(PASSWORD),
        )
    }

    private fun login(email: String, password: String): MockHttpSession {
        val csrfResult = mockMvc.perform(get("/api/v1/agent/csrf")).andExpect(status().isOk).andReturn()
        val token = Regex("\"token\":\"([^\"]+)\"").find(csrfResult.response.contentAsString)!!.groupValues[1]
        val session = csrfResult.request.session as MockHttpSession
        return mockMvc.perform(
            post("/api/v1/agent/session")
                .session(session)
                .header("X-CSRF-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email","password":"$password"}"""),
        ).andExpect(status().isNoContent).andReturn().request.session as MockHttpSession
    }

    private fun csrf(session: MockHttpSession): String = mockMvc.perform(
        get("/api/v1/agent/csrf").session(session),
    ).andExpect(status().isOk).andReturn().response.contentAsString.let { body ->
        Regex("\"token\":\"([^\"]+)\"").find(body)!!.groupValues[1]
    }

    private fun uuidField(json: String, field: String): UUID = UUID.fromString(
        Regex("\"$field\":\"([^\"]+)\"").find(json)!!.groupValues[1],
    )

    private fun stringField(json: String, field: String): String =
        Regex("\"$field\":\"([^\"]+)\"").find(json)!!.groupValues[1]

    private fun count(sql: String): Long = jdbcTemplate.queryForObject(sql, Long::class.java)!!

    private data class Fixture(
        val staffId: UUID,
        val customerId: UUID,
        val ticketId: UUID,
        val number: Long,
        val email: String,
        val subject: String,
    )

    private companion object {
        const val PASSWORD = "Agent password 42"
        const val PUBLIC_BODY = "공개 문의 본문"
        const val INTERNAL_BODY = "절대 AI로 보내면 안 되는 내부 메모"
    }
}
