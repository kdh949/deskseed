package dev.deskseed.portal.internal

import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
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
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest(
    properties = [
        "deskseed.staff-auth.bootstrap.enabled=false",
        "deskseed.customer-portal.claim-grant-ttl=15m",
        "deskseed.customer-portal.claim-signing-key=BAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQ=",
        "deskseed.customer-portal.claim-fingerprint-key=BQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQU=",
        "deskseed.attachments.cleanup-initial-delay=1d",
    ],
)
@AutoConfigureMockMvc
@Testcontainers
@ExtendWith(OutputCaptureExtension::class)
class CustomerRequestPortalIntegrationTest {
    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate
    @Autowired private lateinit var objectMapper: ObjectMapper

    @BeforeEach
    fun clearState() {
        jdbcTemplate.execute(
            """
            truncate table
                access_audit_events,
                ticket_comment_attachments,
                attachment_objects,
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
        jdbcTemplate.update(
            "update system_settings set customer_access_mode = 'ANONYMOUS_ALLOWED', version = 0, updated_at = now() where id = 1",
        )
    }

    @Test
    fun `customer access modes enforce anonymous and authenticated submission while preserving token view`() {
        val existing = submitAnonymous("existing-token@example.com", "기존 토큰 문의")
        val session = customerSession("required-account@example.com")
        jdbcTemplate.update(
            "update system_settings set customer_access_mode = 'REGISTRATION_REQUIRED', version = version + 1, updated_at = now()",
        )

        mockMvc.perform(
            post("/api/v1/requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson("anonymous-required@example.com", "익명 거부 문의")),
        ).andExpect(status().isForbidden)

        mockMvc.perform(
            post("/api/v1/requests")
                .cookie(session.cookie)
                .header("X-CSRF-TOKEN", csrf(session.cookie))
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson("spoofed@example.com", "인증 고객 문의")),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.ticketNumber").isNumber)

        assertThat(
            jdbcTemplate.queryForObject(
                "select requester_id from tickets where subject = '인증 고객 문의'",
                UUID::class.java,
            ),
        ).isEqualTo(session.customerId)

        mockMvc.perform(
            get("/api/v1/requests/{ticketNumber}", existing.ticketNumber)
                .header("X-Request-Access-Token", existing.accessToken),
        ).andExpect(status().isOk)

        jdbcTemplate.update(
            "update system_settings set customer_access_mode = 'REGISTRATION_OPTIONAL', version = version + 1, updated_at = now()",
        )
        mockMvc.perform(
            post("/api/v1/requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson("optional@example.com", "선택 가입 익명 문의")),
        ).andExpect(status().isCreated)

        val staleCookie = Cookie(session.cookie.name, UUID.randomUUID().toString() + "-stale")
        mockMvc.perform(
            post("/api/v1/requests")
                .cookie(staleCookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson("stale-cookie@example.com", "만료 쿠키 익명 문의")),
        ).andExpect(status().isCreated)
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
    fun `authenticated customer uploads links and downloads own PUBLIC attachment with session audit`(output: CapturedOutput) {
        val session = customerSession("authenticated-attachment@example.com")
        val request = submitAuthenticated(session, "인증 고객 첨부 문의")
        val csrfToken = csrf(session.cookie)

        mockMvc.perform(
            multipart("/api/v1/customer/requests/{ticketNumber}/attachments/uploads", request.ticketNumber)
                .file(MockMultipartFile("file", "evidence.pdf", "application/pdf", PDF_BYTES))
                .cookie(session.cookie),
        ).andExpect(status().isForbidden)
        assertThat(attachmentObjectCount()).isZero()

        val uploadResponse = uploadAttachment(session, request.ticketNumber, csrfToken)
            .andExpect(status().isCreated)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.scanStatus").value("CLEAN"))
            .andExpect(jsonPath("$.objectKey").doesNotExist())
            .andExpect(jsonPath("$.checksum").doesNotExist())
            .andReturn().response.contentAsString
        val attachmentId = UUID.fromString(objectMapper.readTree(uploadResponse).path("id").asText())

        val stored = jdbcTemplate.queryForMap(
            """
            select uploaded_actor_type, uploaded_actor_id, bound_ticket_id, allowed_visibility,
                   storage_key, scan_status
            from attachment_objects where id = ?
            """.trimIndent(),
            attachmentId,
        )
        assertThat(stored["uploaded_actor_type"]).isEqualTo("CUSTOMER")
        assertThat(stored["uploaded_actor_id"]).isEqualTo(session.customerId)
        assertThat(stored["bound_ticket_id"]).isEqualTo(ticketId(request.ticketNumber))
        assertThat(stored["allowed_visibility"]).isEqualTo("PUBLIC")
        assertThat(stored["scan_status"]).isEqualTo("CLEAN")

        addFollowUp(
            session = session,
            ticketNumber = request.ticketNumber,
            commandId = UUID.randomUUID().toString(),
            body = "PUBLIC 첨부를 추가합니다.",
            attachmentIds = listOf(attachmentId),
            csrfToken = csrfToken,
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.attachments[0].id").value(attachmentId.toString()))

        mockMvc.perform(
            get("/api/v1/customer/requests/{ticketNumber}", request.ticketNumber).cookie(session.cookie),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.comments[1].attachments[0].id").value(attachmentId.toString()))
            .andExpect(jsonPath("$.comments[1].attachments[0].objectKey").doesNotExist())

        val download = downloadAttachment(session, request.ticketNumber, attachmentId)
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("Content-Type", MediaType.APPLICATION_OCTET_STREAM_VALUE))
            .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("evidence.pdf")))
            .andReturn().response
        assertThat(download.contentAsByteArray).isEqualTo(PDF_BYTES)

        val audit = jdbcTemplate.queryForMap(
            """
            select actor_type, actor_id, source, action, auth_type, session_fingerprint
            from access_audit_events
            where resource_id = ? and action = 'ATTACHMENT_DOWNLOADED'
            """.trimIndent(),
            attachmentId,
        )
        val fingerprint = audit["session_fingerprint"].toString()
        assertThat(audit["actor_type"]).isEqualTo("CUSTOMER")
        assertThat(audit["actor_id"]).isEqualTo(session.customerId)
        assertThat(audit["source"]).isEqualTo("CUSTOMER_PORTAL")
        assertThat(audit["auth_type"]).isEqualTo("CUSTOMER_SESSION")
        assertThat(fingerprint).matches("v1:[A-Za-z0-9_-]{43}")
        assertThat(fingerprint).doesNotContain(session.rawSession, session.sessionId.toString())
        assertThat(uploadResponse).doesNotContain(stored["storage_key"].toString())
        assertThat(output.all).doesNotContain(
            session.rawSession,
            csrfToken,
            stored["storage_key"].toString(),
        )
    }

    @Test
    fun `authenticated download is not found safe across customers tickets visibility and unsafe states`() {
        val owner = customerSession("attachment-owner@example.com")
        val other = customerSession("attachment-other@example.com")
        val request = submitAuthenticated(owner, "다운로드 격리 문의")
        val otherOwnerRequest = submitAuthenticated(owner, "같은 고객의 다른 문의")
        val csrfToken = csrf(owner.cookie)
        val attachmentId = uploadedAttachmentId(owner, request.ticketNumber, csrfToken)
        addFollowUp(
            owner,
            request.ticketNumber,
            UUID.randomUUID().toString(),
            "격리 조건 검증 첨부",
            listOf(attachmentId),
            csrfToken,
        ).andExpect(status().isCreated)

        downloadAttachment(other, request.ticketNumber, attachmentId).andExpect(status().isNotFound)
        downloadAttachment(owner, otherOwnerRequest.ticketNumber, attachmentId).andExpect(status().isNotFound)
        downloadAttachment(owner, request.ticketNumber, UUID.randomUUID()).andExpect(status().isNotFound)

        listOf("QUARANTINED", "INFECTED", "FAILED", "DELETED", "EXPIRED").forEach { unsafeStatus ->
            jdbcTemplate.update("update attachment_objects set scan_status = ? where id = ?", unsafeStatus, attachmentId)
            downloadAttachment(owner, request.ticketNumber, attachmentId).andExpect(status().isNotFound)
        }
        jdbcTemplate.update("update attachment_objects set scan_status = 'CLEAN' where id = ?", attachmentId)

        jdbcTemplate.update(
            "update ticket_comment_attachments set visibility = 'INTERNAL' where attachment_id = ?",
            attachmentId,
        )
        downloadAttachment(owner, request.ticketNumber, attachmentId).andExpect(status().isNotFound)
        jdbcTemplate.update(
            "update ticket_comment_attachments set visibility = 'PUBLIC' where attachment_id = ?",
            attachmentId,
        )

        jdbcTemplate.update("update attachment_objects set linked_at = null where id = ?", attachmentId)
        downloadAttachment(owner, request.ticketNumber, attachmentId).andExpect(status().isNotFound)
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from access_audit_events where resource_id = ?",
                Long::class.java,
                attachmentId,
            ),
        ).isZero()
    }

    @Test
    fun `required customer session download audit failure withholds attachment bytes`() {
        val session = customerSession("attachment-audit-failure@example.com")
        val request = submitAuthenticated(session, "감사 실패 첨부 문의")
        val csrfToken = csrf(session.cookie)
        val attachmentId = uploadedAttachmentId(session, request.ticketNumber, csrfToken)
        addFollowUp(
            session,
            request.ticketNumber,
            UUID.randomUUID().toString(),
            "감사 실패 검증 첨부",
            listOf(attachmentId),
            csrfToken,
        ).andExpect(status().isCreated)

        jdbcTemplate.execute(
            """
            create or replace function fail_customer_session_attachment_audit() returns trigger as ${'$'}${'$'}
            begin
                if new.action = 'ATTACHMENT_DOWNLOADED' and new.auth_type = 'CUSTOMER_SESSION' then
                    raise exception 'forced customer attachment audit failure';
                end if;
                return new;
            end;
            ${'$'}${'$'} language plpgsql
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            create trigger fail_customer_session_attachment_audit
            before insert on access_audit_events
            for each row execute function fail_customer_session_attachment_audit()
            """.trimIndent(),
        )
        try {
            val response = downloadAttachment(session, request.ticketNumber, attachmentId)
                .andExpect(status().isServiceUnavailable)
                .andReturn().response
            assertThat(response.contentAsByteArray).isNotEqualTo(PDF_BYTES)
            assertThat(
                jdbcTemplate.queryForObject(
                    "select count(*) from access_audit_events where resource_id = ?",
                    Long::class.java,
                    attachmentId,
                ),
            ).isZero()
        } finally {
            jdbcTemplate.execute("drop trigger if exists fail_customer_session_attachment_audit on access_audit_events")
            jdbcTemplate.execute("drop function if exists fail_customer_session_attachment_audit()")
        }
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
    fun `concurrent signed grants produce one claim and preserve the losing grant and denial audit`() {
        val session = customerSession("grant-race@example.com")
        val request = submitAnonymous("grant-race@example.com", "동시 claim 문의")
        val tokens = listOf(
            issueClaimGrant(request.ticketNumber, request.accessToken),
            issueClaimGrant(request.ticketNumber, request.accessToken),
        )
        val csrfToken = csrf(session.cookie)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = tokens.map { token ->
                executor.submit(
                    Callable {
                        start.await(5, TimeUnit.SECONDS)
                        claimWithSignedGrant(session, request.ticketNumber, token, csrfToken)
                            .andReturn().response.status
                    },
                )
            }
            start.countDown()
            val statuses = futures.map { it.get(10, TimeUnit.SECONDS) }

            assertThat(statuses).containsExactlyInAnyOrder(204, 404)
        } finally {
            executor.shutdownNow()
        }

        assertThat(requesterId(request.ticketNumber)).isEqualTo(session.customerId)
        assertThat(ticketAuditEventCount(request.ticketNumber, "REQUESTER_CHANGED")).isEqualTo(1)
        assertThat(securityEventCount("CUSTOMER_REQUEST_CLAIMED")).isEqualTo(1)
        assertThat(securityEventCount("CUSTOMER_REQUEST_CLAIM_DENIED")).isEqualTo(1)
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from customer_request_claim_grants where consumed_at is not null",
                Long::class.java,
            ),
        ).isEqualTo(1)
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from customer_request_claim_grants where consumed_at is null",
                Long::class.java,
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `expired signed claim grant is rejected without storing or logging bearer plaintext`(output: CapturedOutput) {
        val session = customerSession("grant-expired@example.com")
        val request = submitAnonymous("grant-expired@example.com", "만료 claim 문의")
        val originalRequester = requesterId(request.ticketNumber)
        val claimToken = issueClaimGrant(request.ticketNumber, request.accessToken)
        jdbcTemplate.update(
            """
            update customer_request_claim_grants
            set created_at = now() - interval '2 minutes', expires_at = now() - interval '1 minute'
            """.trimIndent(),
        )

        claimWithSignedGrant(session, request.ticketNumber, claimToken)
            .andExpect(status().isNotFound)

        assertThat(requesterId(request.ticketNumber)).isEqualTo(originalRequester)
        assertThat(
            jdbcTemplate.queryForObject(
                "select string_agg(token_digest || email_fingerprint, '') from customer_request_claim_grants",
                String::class.java,
            ),
        ).doesNotContain(claimToken, "grant-expired@example.com")
        assertThat(output.all).doesNotContain(claimToken, "grant-expired@example.com")
        assertThat(ticketAuditEventCount(request.ticketNumber, "REQUESTER_CHANGED")).isZero()
    }

    @Test
    fun `public follow-up replay creates one customer comment one audit and one mail intent`() {
        val session = customerSession("follow-up@example.com")
        val request = submitAnonymous("follow-up@example.com", "후속 답변 문의")
        claimOwnershipForFixture(request.ticketNumber, session.customerId)
        val commandId = UUID.randomUUID().toString()
        val attachmentId = uploadedAttachmentId(session, request.ticketNumber, csrf(session.cookie))

        val first = addFollowUp(
            session,
            request.ticketNumber,
            commandId,
            "추가 정보를 남깁니다.",
            listOf(attachmentId),
        )
            .andExpect(status().isCreated)
            .andReturn().response.contentAsString
        val replay = addFollowUp(
            session,
            request.ticketNumber,
            commandId,
            "추가 정보를 남깁니다.",
            listOf(attachmentId),
        )
            .andExpect(status().isCreated)
            .andReturn().response.contentAsString
        addFollowUp(session, request.ticketNumber, commandId, "추가 정보를 남깁니다.")
            .andExpect(status().isConflict)

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

    @Test
    fun `authenticated and token follow-ups share one lock order and replay a command without a deadlock`() {
        val session = customerSession("follow-up-lock-order@example.com")
        val request = submitAuthenticated(session, "동일 명령 동시 후속 답변")
        val ticketId = ticketId(request.ticketNumber)
        val commandId = UUID.randomUUID().toString()
        val csrfToken = csrf(session.cookie)
        val attachmentId = uploadedAttachmentId(session, request.ticketNumber, csrfToken)
        val executor = Executors.newFixedThreadPool(2)
        val ticketLock = requireNotNull(jdbcTemplate.dataSource).connection

        try {
            ticketLock.autoCommit = false
            ticketLock.prepareStatement("select id from tickets where id = ? for update").use { statement ->
                statement.setObject(1, ticketId)
                statement.executeQuery().use { resultSet -> assertThat(resultSet.next()).isTrue() }
            }

            val anonymous = executor.submit(
                Callable {
                    addAnonymousFollowUp(
                        ticketNumber = request.ticketNumber,
                        accessToken = request.accessToken,
                        commandId = commandId,
                        body = "동시에 저장되는 공개 후속 답변",
                        attachmentIds = listOf(attachmentId),
                    ).andReturn().response.status
                },
            )
            awaitTicketLockWaiters(1)

            val authenticated = executor.submit(
                Callable {
                    addFollowUp(
                        session = session,
                        ticketNumber = request.ticketNumber,
                        commandId = commandId,
                        body = "동시에 저장되는 공개 후속 답변",
                        attachmentIds = listOf(attachmentId),
                        csrfToken = csrfToken,
                    ).andReturn().response.status
                },
            )
            awaitTicketLockWaiters(2)
            ticketLock.commit()

            assertThat(listOf(anonymous.get(20, TimeUnit.SECONDS), authenticated.get(20, TimeUnit.SECONDS)))
                .containsOnly(201)
        } finally {
            runCatching(ticketLock::rollback)
            ticketLock.close()
            executor.shutdownNow()
        }

        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from ticket_comments where ticket_id = ?",
                Long::class.java,
                ticketId,
            ),
        ).isEqualTo(2)
        assertThat(ticketAuditEventCount(request.ticketNumber, "COMMENT_CREATED")).isEqualTo(2)
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from ticket_comment_attachments where attachment_id = ?",
                Long::class.java,
                attachmentId,
            ),
        ).isEqualTo(1)
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from outbound_mail_intents where ticket_id = ?",
                Long::class.java,
                ticketId,
            ),
        ).isEqualTo(2)
    }

    private fun claimWithAccessToken(session: CustomerSessionFixture, ticketNumber: Long, token: String) =
        mockMvc.perform(
            post("/api/v1/customer/requests/{ticketNumber}/claim", ticketNumber)
                .cookie(session.cookie)
                .header("X-CSRF-TOKEN", csrf(session.cookie))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("requestAccessToken" to token))),
        )

    private fun claimWithSignedGrant(
        session: CustomerSessionFixture,
        ticketNumber: Long,
        token: String,
        csrfToken: String = csrf(session.cookie),
    ) =
        mockMvc.perform(
            post("/api/v1/customer/requests/{ticketNumber}/claim", ticketNumber)
                .cookie(session.cookie)
                .header("X-CSRF-TOKEN", csrfToken)
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
        attachmentIds: List<UUID> = emptyList(),
        csrfToken: String = csrf(session.cookie),
    ) = mockMvc.perform(
        post("/api/v1/customer/requests/{ticketNumber}/comments", ticketNumber)
            .cookie(session.cookie)
            .header("X-CSRF-TOKEN", csrfToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                objectMapper.writeValueAsString(
                    mapOf(
                        "body" to body,
                        "attachmentIds" to attachmentIds,
                        "clientCommandId" to commandId,
                    ),
                ),
            ),
    )

    private fun addAnonymousFollowUp(
        ticketNumber: Long,
        accessToken: String,
        commandId: String,
        body: String,
        attachmentIds: List<UUID> = emptyList(),
    ) = mockMvc.perform(
        post("/api/v1/requests/{ticketNumber}/comments", ticketNumber)
            .header("X-Request-Access-Token", accessToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                objectMapper.writeValueAsString(
                    mapOf(
                        "body" to body,
                        "attachmentIds" to attachmentIds,
                        "clientCommandId" to commandId,
                    ),
                ),
            ),
    )

    private fun uploadAttachment(
        session: CustomerSessionFixture,
        ticketNumber: Long,
        csrfToken: String = csrf(session.cookie),
    ) = mockMvc.perform(
        multipart("/api/v1/customer/requests/{ticketNumber}/attachments/uploads", ticketNumber)
            .file(MockMultipartFile("file", "evidence.pdf", "application/pdf", PDF_BYTES))
            .cookie(session.cookie)
            .header("X-CSRF-TOKEN", csrfToken),
    )

    private fun uploadedAttachmentId(
        session: CustomerSessionFixture,
        ticketNumber: Long,
        csrfToken: String = csrf(session.cookie),
    ): UUID {
        val body = uploadAttachment(session, ticketNumber, csrfToken)
            .andExpect(status().isCreated)
            .andReturn().response.contentAsString
        return UUID.fromString(objectMapper.readTree(body).path("id").asText())
    }

    private fun downloadAttachment(
        session: CustomerSessionFixture,
        ticketNumber: Long,
        attachmentId: UUID,
    ) = mockMvc.perform(
        get(
            "/api/v1/customer/requests/{ticketNumber}/attachments/{attachmentId}/download",
            ticketNumber,
            attachmentId,
        ).cookie(session.cookie),
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
        val sessionId = UUID.randomUUID()
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
            sessionId,
            accountId,
            sha256(rawSession),
            Timestamp.from(now),
            Timestamp.from(now),
            Timestamp.from(now.plusSeconds(1800)),
            Timestamp.from(now.plusSeconds(3600)),
        )
        return CustomerSessionFixture(customerId, sessionId, rawSession, Cookie(CUSTOMER_COOKIE, rawSession))
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

    private fun submitAuthenticated(session: CustomerSessionFixture, subject: String): AnonymousRequestFixture {
        val response = mockMvc.perform(
            post("/api/v1/requests")
                .cookie(session.cookie)
                .header("X-CSRF-TOKEN", csrf(session.cookie))
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson("spoofed@example.com", subject)),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val json = objectMapper.readTree(response)
        return AnonymousRequestFixture(json.get("ticketNumber").asLong(), json.get("accessToken").asText())
    }

    private fun requestJson(email: String, subject: String): String = objectMapper.writeValueAsString(
        mapOf(
            "name" to "익명 고객",
            "email" to email,
            "subject" to subject,
            "message" to "최초 공개 문의입니다.",
        ),
    )

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

    private fun attachmentObjectCount(): Long = jdbcTemplate.queryForObject(
        "select count(*) from attachment_objects",
        Long::class.java,
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

    private fun awaitTicketLockWaiters(expectedCount: Int) {
        repeat(200) {
            val waiters = jdbcTemplate.queryForObject(
                """
                select count(*)
                from pg_stat_activity
                where datname = current_database()
                  and wait_event_type = 'Lock'
                  and query ilike '%tickets%'
                """.trimIndent(),
                Long::class.java,
            ) ?: 0L
            if (waiters >= expectedCount) return
            Thread.sleep(25)
        }
        error("Timed out waiting for $expectedCount ticket lock waiter(s)")
    }

    private fun tamper(token: String): String = token.dropLast(1) + if (token.last() == 'A') 'B' else 'A'

    private fun sha256(value: String): String = java.util.HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()),
    )

    private data class CustomerSessionFixture(
        val customerId: UUID,
        val sessionId: UUID,
        val rawSession: String,
        val cookie: Cookie,
    )
    private data class AnonymousRequestFixture(val ticketNumber: Long, val accessToken: String)

    companion object {
        private const val CUSTOMER_COOKIE = "DESKSEED_CUSTOMER_SESSION"
        private val PDF_BYTES = "%PDF-1.4\nclean authenticated customer attachment".toByteArray()

        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
    }
}
