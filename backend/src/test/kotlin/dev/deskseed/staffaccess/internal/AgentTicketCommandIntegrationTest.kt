package dev.deskseed.staffaccess.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest(properties = ["deskseed.staff-auth.bootstrap.enabled=false"])
@AutoConfigureMockMvc
@Testcontainers
class AgentTicketCommandIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun clearState() {
        jdbcTemplate.execute(
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
    fun `agent creation stores explicit first visibility and one ordered audit atomically`() {
        val agentId = insertStaff("creator@example.com", "상담사 생성", "AGENT")
        val groupId = insertGroup("생성 그룹", agentId)
        val browser = login("creator@example.com")

        val response = mockMvc.perform(
            createTicketRequest(
                browser = browser,
                requestId = "request-agent-create",
                correlationId = "correlation-agent-create",
                body =
                    """
                    {
                      "requester": {"name": "직접 생성 고객", "email": "agent-created@example.com"},
                      "subject": "상담사가 만든 티켓",
                      "firstComment": {"visibility": "INTERNAL", "body": "고객 문의 없이 시작한 내부 조사"},
                      "priority": "HIGH",
                      "groupId": "$groupId",
                      "assigneeId": "$agentId"
                    }
                    """.trimIndent(),
            ),
        )
            .andExpect(status().isCreated)
            .andExpect(header().exists("Location"))
            .andExpect(header().string("ETag", "\"0\""))
            .andExpect(jsonPath("$.version").value(0))
            .andExpect(jsonPath("$.auditId").isString)
            .andReturn().response.contentAsString

        val ticketNumber = longField(response, "ticketNumber")
        val auditId = uuidField(response, "auditId")
        val ticket = jdbcTemplate.queryForMap(
            """
            select kind, channel, status, priority, group_id::text, assignee_id::text, version
            from tickets where ticket_number = ?
            """.trimIndent(),
            ticketNumber,
        )
        assertThat(ticket).containsEntry("kind", "AGENT_CREATED")
        assertThat(ticket).containsEntry("channel", "AGENT")
        assertThat(ticket).containsEntry("status", "NEW")
        assertThat(ticket).containsEntry("priority", "HIGH")
        assertThat(ticket["group_id"]).isEqualTo(groupId.toString())
        assertThat(ticket["assignee_id"]).isEqualTo(agentId.toString())
        assertThat(ticket["version"]).isEqualTo(0L)

        assertThat(
            jdbcTemplate.queryForObject(
                "select visibility from ticket_comments where ticket_id = (select id from tickets where ticket_number = ?)",
                String::class.java,
                ticketNumber,
            ),
        ).isEqualTo("INTERNAL")

        val audit = jdbcTemplate.queryForMap(
            """
            select actor_type, actor_id::text, source, request_id, correlation_id,
                   expected_version, ticket_version
            from ticket_audits where id = ?
            """.trimIndent(),
            auditId,
        )
        assertThat(audit).containsEntry("actor_type", "STAFF")
        assertThat(audit["actor_id"]).isEqualTo(agentId.toString())
        assertThat(audit).containsEntry("source", "AGENT_UI")
        assertThat(audit).containsEntry("request_id", "request-agent-create")
        assertThat(audit).containsEntry("correlation_id", "correlation-agent-create")
        assertThat(audit).containsEntry("expected_version", 0L)
        assertThat(audit).containsEntry("ticket_version", 0L)
        assertThat(eventTypes(auditId)).containsExactly("TICKET_CREATED", "COMMENT_CREATED")
    }

    @Test
    fun `agent creation reuses an existing customer by id instead of minting a duplicate unverified customer`() {
        val agentId = insertStaff("reuse-creator@example.com", "재사용 상담사", "AGENT")
        val existingCustomerId = insertCustomer("기존 고객", "existing-customer@example.com")
        val browser = login("reuse-creator@example.com")

        val response = mockMvc.perform(
            createTicketRequest(
                browser = browser,
                requestId = "request-agent-create-existing",
                correlationId = "correlation-agent-create-existing",
                body =
                    """
                    {
                      "requester": {"customerId": "$existingCustomerId"},
                      "subject": "기존 고객 재사용 티켓",
                      "firstComment": {"visibility": "INTERNAL", "body": "검색으로 찾은 고객으로 생성"},
                      "priority": "NORMAL"
                    }
                    """.trimIndent(),
            ),
        )
            .andExpect(status().isCreated)
            .andReturn().response.contentAsString

        val ticketNumber = longField(response, "ticketNumber")
        val ticket = jdbcTemplate.queryForMap(
            "select requester_id::text from tickets where ticket_number = ?",
            ticketNumber,
        )
        assertThat(ticket["requester_id"]).isEqualTo(existingCustomerId.toString())
        assertThat(
            jdbcTemplate.queryForObject("select count(*) from customers", Long::class.java),
        ).isEqualTo(1L)
    }

    @Test
    fun `agent creation with an unknown customerId is rejected without creating a ticket`() {
        val agentId = insertStaff("missing-customer-creator@example.com", "미존재 상담사", "AGENT")
        val browser = login("missing-customer-creator@example.com")

        mockMvc.perform(
            createTicketRequest(
                browser = browser,
                requestId = "request-agent-create-missing",
                correlationId = "correlation-agent-create-missing",
                body =
                    """
                    {
                      "requester": {"customerId": "${UUID.randomUUID()}"},
                      "subject": "존재하지 않는 고객",
                      "firstComment": {"visibility": "INTERNAL", "body": "본문"},
                      "priority": "NORMAL"
                    }
                    """.trimIndent(),
            ),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.type").value("/problems/agent-ticket-requester-not-found"))

        assertThat(
            jdbcTemplate.queryForObject("select count(*) from tickets", Long::class.java),
        ).isZero()
    }

    @Test
    fun `agent creation rejects a requester that mixes customerId with name or email`() {
        val agentId = insertStaff("mixed-requester@example.com", "혼합 상담사", "AGENT")
        val existingCustomerId = insertCustomer("혼합 고객", "mixed-customer@example.com")
        val browser = login("mixed-requester@example.com")

        mockMvc.perform(
            createTicketRequest(
                browser = browser,
                requestId = "request-agent-create-mixed",
                correlationId = "correlation-agent-create-mixed",
                body =
                    """
                    {
                      "requester": {"customerId": "$existingCustomerId", "name": "다른 이름", "email": "other@example.com"},
                      "subject": "혼합 요청",
                      "firstComment": {"visibility": "INTERNAL", "body": "본문"},
                      "priority": "NORMAL"
                    }
                    """.trimIndent(),
            ),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.type").value("/problems/validation"))
    }

    @Test
    fun `comment field combined and no-op saves create one audit with ordered effects or receipt`() {
        val agentId = insertStaff("writer@example.com", "상담사 저장", "AGENT")
        val groupId = insertGroup("저장 그룹", agentId)
        val browser = login("writer@example.com")
        val created = createAssignedTicket(browser, agentId, groupId, "command-cases@example.com")

        val commentOnly = performCommand(
            browser,
            created.ticketNumber,
            "request-comment-only",
            """
            {
              "expectedVersion": 0,
              "changedFields": [],
              "comment": {"visibility": "PUBLIC", "body": "고객에게 보내는 답변"}
            }
            """.trimIndent(),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.version").value(1))
            .andReturn().response.contentAsString
        assertThat(eventTypes(uuidField(commentOnly, "auditId"))).containsExactly("COMMENT_CREATED")

        val fieldOnly = performCommand(
            browser,
            created.ticketNumber,
            "request-field-only",
            """
            {
              "expectedVersion": 1,
              "changedFields": ["priority"],
              "priority": "HIGH"
            }
            """.trimIndent(),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.version").value(2))
            .andReturn().response.contentAsString
        assertThat(eventRows(uuidField(fieldOnly, "auditId")))
            .containsExactly(AuditEventRow(1, "PRIORITY_CHANGED", "priority", "\"NORMAL\"", "\"HIGH\""))

        val combined = performCommand(
            browser,
            created.ticketNumber,
            "request-combined",
            """
            {
              "expectedVersion": 2,
              "changedFields": ["status", "priority"],
              "status": "OPEN",
              "priority": "URGENT",
              "comment": {"visibility": "INTERNAL", "body": "우선순위를 올리고 처리 시작"}
            }
            """.trimIndent(),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.version").value(3))
            .andReturn().response.contentAsString
        assertThat(eventTypes(uuidField(combined, "auditId")))
            .containsExactly("COMMENT_CREATED", "STATUS_CHANGED", "PRIORITY_CHANGED")
        val combinedAuditId = uuidField(combined, "auditId")
        val combinedAudit = jdbcTemplate.queryForMap(
            """
            select actor_type, actor_id::text, source, request_id, correlation_id,
                   command_id, expected_version, ticket_version
            from ticket_audits where id = ?
            """.trimIndent(),
            combinedAuditId,
        )
        assertThat(combinedAudit).containsEntry("actor_type", "STAFF")
        assertThat(combinedAudit["actor_id"]).isEqualTo(agentId.toString())
        assertThat(combinedAudit).containsEntry("source", "AGENT_UI")
        assertThat(combinedAudit).containsEntry("request_id", "request-combined")
        assertThat(combinedAudit).containsEntry("correlation_id", "correlation-ticket-command")
        assertThat(combinedAudit["command_id"].toString()).matches("[A-Za-z0-9._:-]{1,100}")
        assertThat(combinedAudit).containsEntry("expected_version", 2L)
        assertThat(combinedAudit).containsEntry("ticket_version", 3L)
        val combinedMetadata = jdbcTemplate.queryForList(
            "select metadata_json from ticket_audit_events where audit_id = ? order by event_order",
            String::class.java,
            combinedAuditId,
        ).filterNotNull()
        assertThat(combinedMetadata.first()).contains(
            "contentSha256",
            "contentLength",
            "INTERNAL",
            "commandOperation",
            "UPDATE_TICKET",
            "commandRequestDescriptor",
        )
        assertThat(combinedMetadata.joinToString()).doesNotContain("우선순위를 올리고 처리 시작")

        val noOpCommandId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
        val noOpBody =
            """
            {
              "expectedVersion": 3,
              "changedFields": ["status", "priority"],
              "status": "OPEN",
              "priority": "URGENT",
              "clientCommandId": "$noOpCommandId"
            }
            """.trimIndent()
        val noOp = performCommand(
            browser,
            created.ticketNumber,
            "request-no-op",
            noOpBody,
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.version").value(3))
            .andReturn().response.contentAsString
        assertThat(eventTypes(uuidField(noOp, "auditId"))).containsExactly("UPDATE_COMMAND_RECEIVED")
        val noOpReplay = performCommand(
            browser,
            created.ticketNumber,
            "request-no-op-replay",
            noOpBody,
        )
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        assertThat(noOpReplay).isEqualTo(noOp)
        performCommand(
            browser,
            created.ticketNumber,
            "request-no-op-misuse",
            """
            {
              "expectedVersion": 3,
              "changedFields": ["status"],
              "status": "OPEN",
              "clientCommandId": "$noOpCommandId"
            }
            """.trimIndent(),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.type").value("/problems/client-command-id-reused"))

        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from ticket_audits where ticket_id = ?",
                Long::class.java,
                created.ticketId,
            ),
        ).isEqualTo(5)
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from ticket_comments where ticket_id = ?",
                Long::class.java,
                created.ticketId,
            ),
        ).isEqualTo(3)
    }

    @Test
    fun `same staff client command replay returns original comment result without duplicate mutation`() {
        val agentId = insertStaff("idempotent-replay@example.com", "재시도 상담사", "AGENT")
        val groupId = insertGroup("재시도 그룹", agentId)
        val browser = login("idempotent-replay@example.com")
        val created = createAssignedTicket(browser, agentId, groupId, "idempotent-replay-customer@example.com")
        val clientCommandId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val body =
            """
            {
              "expectedVersion": 0,
              "changedFields": [],
              "comment": {"visibility": "PUBLIC", "body": "응답 유실 후 같은 명령 재시도"},
              "clientCommandId": "$clientCommandId"
            }
            """.trimIndent()

        val first = commandResponse(browser, created.ticketNumber, "request-idempotent-first", body)
        val replay = commandResponse(browser, created.ticketNumber, "request-idempotent-replay", body)

        assertThat(first.status).isEqualTo(200)
        assertThat(replay.status).isEqualTo(200)
        assertThat(replay.body).isEqualTo(first.body)
        assertThat(commentCount(created.ticketId)).isEqualTo(2)
        assertThat(auditCount(created.ticketId)).isEqualTo(2)
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from ticket_audits where actor_id = ? and command_id = ?",
                Long::class.java,
                agentId,
                clientCommandId,
            ),
        ).isEqualTo(1)
        assertThat(
            jdbcTemplate.queryForObject(
                """
                select string_agg(event.metadata_json, ' ' order by event.event_order)
                from ticket_audit_events event
                join ticket_audits audit on audit.id = event.audit_id
                where audit.actor_id = ? and audit.command_id = ?
                """.trimIndent(),
                String::class.java,
                agentId,
                clientCommandId,
            ),
        )
            .contains("commandRequestDescriptor", "contentSha256", "UPDATE_TICKET")
            .doesNotContain("응답 유실 후 같은 명령 재시도")
    }

    @Test
    fun `client command id replays original on same ticket and rejects reuse on another ticket`() {
        val agentId = insertStaff("idempotent-misuse@example.com", "명령 재사용 상담사", "AGENT")
        val groupId = insertGroup("명령 재사용 그룹", agentId)
        val browser = login("idempotent-misuse@example.com")
        val firstTicket = createAssignedTicket(browser, agentId, groupId, "idempotent-misuse-one@example.com")
        val secondTicket = createAssignedTicket(browser, agentId, groupId, "idempotent-misuse-two@example.com")
        val clientCommandId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val firstBody =
            """
            {
              "expectedVersion": 0,
              "changedFields": [],
              "comment": {"visibility": "INTERNAL", "body": "원래 메모"},
              "clientCommandId": "$clientCommandId"
            }
            """.trimIndent()

        val original = performCommand(browser, firstTicket.ticketNumber, "request-idempotent-original", firstBody)
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        val exactReplay = performCommand(
            browser,
            firstTicket.ticketNumber,
            "request-idempotent-exact-replay",
            firstBody,
        )
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        assertThat(exactReplay).isEqualTo(original)

        performCommand(
            browser,
            firstTicket.ticketNumber,
            "request-idempotent-payload-misuse",
            firstBody.replace("원래 메모", "다른 메모"),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.type").value("/problems/client-command-id-reused"))

        performCommand(
            browser,
            secondTicket.ticketNumber,
            "request-idempotent-ticket-misuse",
            firstBody,
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.type").value("/problems/client-command-id-reused"))

        assertThat(commentCount(firstTicket.ticketId)).isEqualTo(2)
        assertThat(auditCount(firstTicket.ticketId)).isEqualTo(2)
        assertThat(commentCount(secondTicket.ticketId)).isEqualTo(1)
        assertThat(auditCount(secondTicket.ticketId)).isEqualTo(1)
    }

    @Test
    fun `client command id used by another staff ticket operation fails closed`() {
        val agentId = insertStaff("idempotent-operation@example.com", "작업 재사용 상담사", "AGENT")
        val groupId = insertGroup("작업 재사용 그룹", agentId)
        val browser = login("idempotent-operation@example.com")
        val clientCommandId = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
        val createdResponse = mockMvc.perform(
            createTicketRequest(
                browser,
                "request-idempotent-operation-create",
                "correlation-idempotent-operation",
                """
                {
                  "requester": {"name": "작업 재사용 고객", "email": "idempotent-operation-customer@example.com"},
                  "subject": "작업 ID 재사용 방지",
                  "firstComment": {"visibility": "PUBLIC", "body": "최초 공개 문의"},
                  "priority": "NORMAL",
                  "groupId": "$groupId",
                  "assigneeId": "$agentId",
                  "clientCommandId": "$clientCommandId"
                }
                """.trimIndent(),
            ),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val ticketNumber = longField(createdResponse, "ticketNumber")
        val ticketId = jdbcTemplate.queryForObject(
            "select id from tickets where ticket_number = ?",
            UUID::class.java,
            ticketNumber,
        )!!

        performCommand(
            browser,
            ticketNumber,
            "request-idempotent-operation-update",
            """
            {
              "expectedVersion": 0,
              "changedFields": [],
              "comment": {"visibility": "INTERNAL", "body": "다른 작업으로 재사용되면 안 됨"},
              "clientCommandId": "$clientCommandId"
            }
            """.trimIndent(),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.type").value("/problems/client-command-id-reused"))

        assertThat(commentCount(ticketId)).isEqualTo(1)
        assertThat(auditCount(ticketId)).isEqualTo(1)
    }

    @Test
    fun `concurrent duplicate client command commits one comment and replays one result`() {
        val agentId = insertStaff("idempotent-concurrent@example.com", "동시 재시도 상담사", "AGENT")
        val groupId = insertGroup("동시 재시도 그룹", agentId)
        val browserA = login("idempotent-concurrent@example.com")
        val browserB = login("idempotent-concurrent@example.com")
        val created = createAssignedTicket(
            browserA,
            agentId,
            groupId,
            "idempotent-concurrent-customer@example.com",
        )
        val body =
            """
            {
              "expectedVersion": 0,
              "changedFields": [],
              "comment": {"visibility": "PUBLIC", "body": "동시 재시도는 한 번만 저장"},
              "clientCommandId": "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
            }
            """.trimIndent()

        val responses = concurrently(
            Callable { commandResponse(browserA, created.ticketNumber, "request-idempotent-a", body) },
            Callable { commandResponse(browserB, created.ticketNumber, "request-idempotent-b", body) },
        )

        assertThat(responses.map(HttpResult::status)).containsExactlyInAnyOrder(200, 200)
        assertThat(responses.map(HttpResult::body).distinct()).hasSize(1)
        assertThat(commentCount(created.ticketId)).isEqualTo(2)
        assertThat(auditCount(created.ticketId)).isEqualTo(2)
    }

    @Test
    fun `closed ticket rejects comment-only and field-only commands without mutation`() {
        val agentId = insertStaff("closed-writer@example.com", "종료 티켓 상담사", "AGENT")
        val groupId = insertGroup("종료 티켓 그룹", agentId)
        val browser = login("closed-writer@example.com")
        val created = createAssignedTicket(browser, agentId, groupId, "closed-command@example.com")
        jdbcTemplate.update("update tickets set status = 'CLOSED' where id = ?", created.ticketId)
        val auditCountBefore = auditCount(created.ticketId)
        val commentCountBefore = commentCount(created.ticketId)

        performCommand(
            browser,
            created.ticketNumber,
            "request-closed-comment-only",
            """
            {
              "expectedVersion": 0,
              "changedFields": [],
              "comment": {"visibility": "INTERNAL", "body": "종료 티켓에 남으면 안 되는 메모"}
            }
            """.trimIndent(),
        )
            .andExpect(status().isUnprocessableContent)
            .andExpect(jsonPath("$.type").value("/problems/ticket-status-transition-invalid"))

        performCommand(
            browser,
            created.ticketNumber,
            "request-closed-field-only",
            """
            {
              "expectedVersion": 0,
              "changedFields": ["priority"],
              "priority": "URGENT"
            }
            """.trimIndent(),
        )
            .andExpect(status().isUnprocessableContent)
            .andExpect(jsonPath("$.type").value("/problems/ticket-status-transition-invalid"))

        assertThat(
            jdbcTemplate.queryForMap(
                "select status, priority, version from tickets where id = ?",
                created.ticketId,
            ),
        )
            .containsEntry("status", "CLOSED")
            .containsEntry("priority", "NORMAL")
            .containsEntry("version", 0L)
        assertThat(commentCount(created.ticketId)).isEqualTo(commentCountBefore)
        assertThat(auditCount(created.ticketId)).isEqualTo(auditCountBefore)
    }

    @Test
    fun `global read does not grant cross-group write and assignment requires active target membership`() {
        val agentA = insertStaff("agent-a@example.com", "상담사 A", "AGENT")
        val agentB = insertStaff("agent-b@example.com", "상담사 B", "AGENT")
        val groupA = insertGroup("그룹 A", agentA)
        val groupB = insertGroup("그룹 B", agentB)
        val browserA = login("agent-a@example.com")
        val browserB = login("agent-b@example.com")
        val created = createAssignedTicket(browserB, agentB, groupB, "write-policy@example.com")

        mockMvc.perform(
            get("/api/v1/agent/tickets/{ticketNumber}", created.ticketNumber)
                .session(browserA.session)
                .header("X-Interaction-Id", UUID.randomUUID())
                .header("X-Deskseed-Read-Intent", "BACKGROUND"),
        ).andExpect(status().isOk)

        performCommand(
            browserA,
            created.ticketNumber,
            "request-cross-group-denied",
            """
            {
              "expectedVersion": 0,
              "changedFields": ["priority"],
              "priority": "HIGH"
            }
            """.trimIndent(),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.type").value("/problems/ticket-write-forbidden"))

        performCommand(
            browserB,
            created.ticketNumber,
            "request-invalid-assignee",
            """
            {
              "expectedVersion": 0,
              "changedFields": ["assigneeId"],
              "assigneeId": "$agentA"
            }
            """.trimIndent(),
        )
            .andExpect(status().isUnprocessableContent)
            .andExpect(jsonPath("$.type").value("/problems/ticket-assignment-invalid"))

        performCommand(
            browserB,
            created.ticketNumber,
            "request-group-change-without-explicit-clear",
            """
            {
              "expectedVersion": 0,
              "changedFields": ["groupId"],
              "groupId": "$groupA"
            }
            """.trimIndent(),
        )
            .andExpect(status().isUnprocessableContent)
            .andExpect(jsonPath("$.type").value("/problems/ticket-assignment-invalid"))

        performCommand(
            browserB,
            created.ticketNumber,
            "request-group-field-with-missing-value",
            """
            {
              "expectedVersion": 0,
              "changedFields": ["groupId"]
            }
            """.trimIndent(),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.type").value("/problems/validation"))

        val moved = performCommand(
            browserB,
            created.ticketNumber,
            "request-group-change-clears-assignee",
            """
            {
              "expectedVersion": 0,
              "changedFields": ["groupId", "assigneeId"],
              "groupId": "$groupA",
              "assigneeId": null
            }
            """.trimIndent(),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.version").value(1))
            .andReturn().response.contentAsString

        assertThat(eventTypes(uuidField(moved, "auditId")))
            .containsExactly("GROUP_CHANGED", "ASSIGNEE_CHANGED")
        assertThat(
            jdbcTemplate.queryForObject(
                "select assignee_id is null from tickets where id = ?",
                Boolean::class.java,
                created.ticketId,
            ),
        ).isTrue()
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from ticket_audits where ticket_id = ?",
                Long::class.java,
                created.ticketId,
            ),
        ).isEqualTo(2)
    }

    @Test
    fun `concurrent disjoint fields merge while the same field returns explicit conflict`() {
        val agentA = insertStaff("concurrent-a@example.com", "동시 상담사 A", "AGENT")
        val agentB = insertStaff("concurrent-b@example.com", "동시 상담사 B", "AGENT")
        val group = insertGroup("동시성 그룹", agentA, agentB)
        val browserA = login("concurrent-a@example.com")
        val browserB = login("concurrent-b@example.com")
        val created = createAssignedTicket(browserA, agentA, group, "concurrency@example.com")

        withTicketUpdateDelay {
            val disjoint = concurrently(
                Callable {
                    commandResponse(
                        browserA,
                        created.ticketNumber,
                        "request-disjoint-status",
                        """
                        {
                          "expectedVersion": 0,
                          "changedFields": ["status"],
                          "status": "OPEN"
                        }
                        """.trimIndent(),
                    )
                },
                Callable {
                    commandResponse(
                        browserB,
                        created.ticketNumber,
                        "request-disjoint-priority",
                        """
                        {
                          "expectedVersion": 0,
                          "changedFields": ["priority"],
                          "priority": "HIGH"
                        }
                        """.trimIndent(),
                    )
                },
            )
            assertThat(disjoint.map(HttpResult::status)).containsExactlyInAnyOrder(200, 200)

            val ticket = jdbcTemplate.queryForMap(
                "select status, priority, version from tickets where id = ?",
                created.ticketId,
            )
            assertThat(ticket).containsEntry("status", "OPEN")
            assertThat(ticket).containsEntry("priority", "HIGH")
            assertThat(ticket).containsEntry("version", 2L)

            val sameField = concurrently(
                Callable {
                    commandResponse(
                        browserA,
                        created.ticketNumber,
                        "request-same-field-pending",
                        """
                        {
                          "expectedVersion": 2,
                          "changedFields": ["status"],
                          "status": "PENDING"
                        }
                        """.trimIndent(),
                    )
                },
                Callable {
                    commandResponse(
                        browserB,
                        created.ticketNumber,
                        "request-same-field-solved",
                        """
                        {
                          "expectedVersion": 2,
                          "changedFields": ["status"],
                          "status": "SOLVED"
                        }
                        """.trimIndent(),
                    )
                },
            )
            assertThat(sameField.map(HttpResult::status)).containsExactlyInAnyOrder(200, 409)
            val conflict = sameField.single { it.status == 409 }.body
            assertThat(conflict).contains("\"type\":\"/problems/ticket-field-conflict\"")
            assertThat(conflict).contains("\"currentVersion\":3")
            assertThat(conflict).contains("\"conflictingFields\":[\"status\"]")
        }

        assertThat(
            jdbcTemplate.queryForObject("select version from tickets where id = ?", Long::class.java, created.ticketId),
        ).isEqualTo(3)
        assertThat(
            jdbcTemplate.queryForList(
                """
                select distinct event.field_name
                from ticket_audit_events event
                join ticket_audits audit on audit.id = event.audit_id
                where audit.ticket_id = ? and audit.ticket_version > 0 and event.field_name is not null
                order by event.field_name
                """.trimIndent(),
                String::class.java,
                created.ticketId,
            ).filterNotNull(),
        ).containsExactly("priority", "status")
    }

    @Test
    fun `stale command mixing a conflicting field with a disjoint field and comment persists nothing`() {
        val agentId = insertStaff("mixed-conflict@example.com", "혼합 충돌 상담사", "AGENT")
        val groupId = insertGroup("혼합 충돌 그룹", agentId)
        val browser = login("mixed-conflict@example.com")
        val created = createAssignedTicket(browser, agentId, groupId, "mixed-conflict-customer@example.com")

        performCommand(
            browser,
            created.ticketNumber,
            "request-mixed-conflict-winner",
            """
            {
              "expectedVersion": 0,
              "changedFields": ["status"],
              "status": "OPEN"
            }
            """.trimIndent(),
        ).andExpect(status().isOk)

        val auditCountBefore = auditCount(created.ticketId)
        val commentCountBefore = commentCount(created.ticketId)
        performCommand(
            browser,
            created.ticketNumber,
            "request-mixed-conflict-stale",
            """
            {
              "expectedVersion": 0,
              "changedFields": ["status", "priority"],
              "status": "PENDING",
              "priority": "URGENT",
              "comment": {"visibility": "INTERNAL", "body": "conflicting-command-must-not-persist"}
            }
            """.trimIndent(),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.type").value("/problems/ticket-field-conflict"))
            .andExpect(jsonPath("$.currentVersion").value(1))
            .andExpect(jsonPath("$.conflictingFields[0]").value("status"))

        assertThat(
            jdbcTemplate.queryForMap(
                "select status, priority, version from tickets where id = ?",
                created.ticketId,
            ),
        )
            .containsEntry("status", "OPEN")
            .containsEntry("priority", "NORMAL")
            .containsEntry("version", 1L)
        assertThat(commentCount(created.ticketId)).isEqualTo(commentCountBefore)
        assertThat(auditCount(created.ticketId)).isEqualTo(auditCountBefore)
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from ticket_comments where ticket_id = ? and body = ?",
                Long::class.java,
                created.ticketId,
                "conflicting-command-must-not-persist",
            ),
        ).isZero()
    }

    @Test
    fun `audit insert failure rolls back comment fields version and command audit`() {
        val agentId = insertStaff("rollback@example.com", "롤백 상담사", "AGENT")
        val groupId = insertGroup("롤백 그룹", agentId)
        val browser = login("rollback@example.com")
        val created = createAssignedTicket(browser, agentId, groupId, "rollback-customer@example.com")
        val auditCountBefore = auditCount(created.ticketId)
        val commentCountBefore = commentCount(created.ticketId)

        jdbcTemplate.execute(
            """
            create or replace function fail_ticket_command_audit_insert()
            returns trigger language plpgsql as ${'$'}${'$'}
            begin
                raise exception 'injected ticket audit failure';
            end;
            ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            create trigger fail_ticket_command_audit_insert
            before insert on ticket_audits
            for each row execute function fail_ticket_command_audit_insert()
            """.trimIndent(),
        )
        try {
            performCommand(
                browser,
                created.ticketNumber,
                "request-audit-rollback",
                """
                {
                  "expectedVersion": 0,
                  "changedFields": ["status", "priority"],
                  "status": "OPEN",
                  "priority": "URGENT",
                  "comment": {"visibility": "INTERNAL", "body": "롤백되어야 하는 메모"}
                }
                """.trimIndent(),
            )
                .andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.type").value("/problems/audit-write-unavailable"))

            mockMvc.perform(
                createTicketRequest(
                    browser,
                    "request-create-audit-rollback",
                    "correlation-create-audit-rollback",
                    """
                    {
                      "requester": {"name": "생성 롤백 고객", "email": "create-rollback@example.com"},
                      "subject": "감사 실패로 생성되지 않을 티켓",
                      "firstComment": {"visibility": "INTERNAL", "body": "생성과 함께 롤백될 메모"},
                      "priority": "NORMAL",
                      "groupId": "$groupId",
                      "assigneeId": "$agentId"
                    }
                    """.trimIndent(),
                ),
            )
                .andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.type").value("/problems/audit-write-unavailable"))
        } finally {
            jdbcTemplate.execute("drop trigger if exists fail_ticket_command_audit_insert on ticket_audits")
            jdbcTemplate.execute("drop function if exists fail_ticket_command_audit_insert()")
        }

        val ticket = jdbcTemplate.queryForMap(
            "select status, priority, version from tickets where id = ?",
            created.ticketId,
        )
        assertThat(ticket).containsEntry("status", "NEW")
        assertThat(ticket).containsEntry("priority", "NORMAL")
        assertThat(ticket).containsEntry("version", 0L)
        assertThat(auditCount(created.ticketId)).isEqualTo(auditCountBefore)
        assertThat(commentCount(created.ticketId)).isEqualTo(commentCountBefore)
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from customers where email_normalized = 'create-rollback@example.com'",
                Long::class.java,
            ),
        ).isZero()
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from tickets where subject = '감사 실패로 생성되지 않을 티켓'",
                Long::class.java,
            ),
        ).isZero()
    }

    @Test
    fun `customer projection exposes agent public reply but never internal comment or staff changes`() {
        val customerResponse = mockMvc.perform(
            post("/api/v1/requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name": "고객 projection",
                      "email": "projection-command@example.com",
                      "subject": "projection command",
                      "message": "최초 고객 문의"
                    }
                    """.trimIndent(),
                ),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val ticketNumber = longField(customerResponse, "ticketNumber")
        val accessToken = stringField(customerResponse, "accessToken")
        val ticketId = jdbcTemplate.queryForObject(
            "select id from tickets where ticket_number = ?",
            UUID::class.java,
            ticketNumber,
        )!!
        val agentId = insertStaff("projection-agent@example.com", "Projection 상담사", "AGENT")
        val groupId = insertGroup("Projection 그룹", agentId)
        jdbcTemplate.update(
            "update tickets set group_id = ?, assignee_id = ? where id = ?",
            groupId,
            agentId,
            ticketId,
        )
        val browser = login("projection-agent@example.com")

        performCommand(
            browser,
            ticketNumber,
            "request-public-reply",
            """
            {
              "expectedVersion": 0,
              "changedFields": ["priority"],
              "priority": "HIGH",
              "comment": {"visibility": "PUBLIC", "body": "고객에게 보이는 상담사 답변"}
            }
            """.trimIndent(),
        ).andExpect(status().isOk)
        performCommand(
            browser,
            ticketNumber,
            "request-internal-note",
            """
            {
              "expectedVersion": 1,
              "changedFields": [],
              "comment": {"visibility": "INTERNAL", "body": "고객에게 숨겨야 하는 내부 메모"}
            }
            """.trimIndent(),
        ).andExpect(status().isOk)

        val projection = mockMvc.perform(
            get("/api/v1/requests/{ticketNumber}", ticketNumber)
                .header("X-Request-Access-Token", accessToken),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.comments.length()").value(2))
            .andExpect(jsonPath("$.priority").doesNotExist())
            .andExpect(jsonPath("$.groupId").doesNotExist())
            .andExpect(jsonPath("$.assigneeId").doesNotExist())
            .andExpect(jsonPath("$.audits").doesNotExist())
            .andReturn().response.contentAsString
        assertThat(projection).contains("고객에게 보이는 상담사 답변")
        assertThat(projection).doesNotContain("고객에게 숨겨야 하는 내부 메모")
        assertThat(
            jdbcTemplate.queryForList(
                "select template_key from outbound_mail_intents where ticket_id = ? order by queued_at, id",
                String::class.java,
                ticketId,
            ).filterNotNull(),
        ).containsExactly("REQUEST_RECEIVED", "PUBLIC_AGENT_REPLY")
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from outbound_mail_intents where ticket_id = ? and text_body like ?",
                Long::class.java,
                ticketId,
                "%고객에게 숨겨야 하는 내부 메모%",
            ),
        ).isZero()
    }

    @Test
    fun `canonical ticket audit rows reject update and delete`() {
        val agentId = insertStaff("append-only@example.com", "감사 보호 상담사", "AGENT")
        val groupId = insertGroup("감사 보호 그룹", agentId)
        val browser = login("append-only@example.com")
        val created = createAssignedTicket(browser, agentId, groupId, "append-only-customer@example.com")
        val auditId = jdbcTemplate.queryForObject(
            "select id from ticket_audits where ticket_id = ?",
            UUID::class.java,
            created.ticketId,
        )!!
        val eventId = jdbcTemplate.queryForObject(
            "select id from ticket_audit_events where audit_id = ? order by event_order limit 1",
            UUID::class.java,
            auditId,
        )!!

        assertThat(org.assertj.core.api.Assertions.catchThrowable {
            jdbcTemplate.update("update ticket_audits set source = 'SYSTEM_JOB' where id = ?", auditId)
        }).isInstanceOf(DataAccessException::class.java)
        assertThat(org.assertj.core.api.Assertions.catchThrowable {
            jdbcTemplate.update("delete from ticket_audit_events where id = ?", eventId)
        }).isInstanceOf(DataAccessException::class.java)
        assertThat(auditCount(created.ticketId)).isEqualTo(1)
        assertThat(eventTypes(auditId)).containsExactly("TICKET_CREATED", "COMMENT_CREATED")
    }

    private fun createAssignedTicket(
        browser: Browser,
        assigneeId: UUID,
        groupId: UUID,
        requesterEmail: String,
    ): TicketFixture {
        val response = mockMvc.perform(
            createTicketRequest(
                browser,
                "request-create-${UUID.randomUUID()}",
                "correlation-create",
                """
                {
                  "requester": {"name": "명령 테스트 고객", "email": "$requesterEmail"},
                  "subject": "명령 테스트 티켓",
                  "firstComment": {"visibility": "PUBLIC", "body": "최초 공개 문의"},
                  "priority": "NORMAL",
                  "groupId": "$groupId",
                  "assigneeId": "$assigneeId"
                }
                """.trimIndent(),
            ),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val ticketNumber = longField(response, "ticketNumber")
        val ticketId = jdbcTemplate.queryForObject(
            "select id from tickets where ticket_number = ?",
            UUID::class.java,
            ticketNumber,
        )!!
        return TicketFixture(ticketId, ticketNumber)
    }

    private fun createTicketRequest(
        browser: Browser,
        requestId: String,
        correlationId: String,
        body: String,
    ): MockHttpServletRequestBuilder = post("/api/v1/agent/tickets")
        .session(browser.session)
        .header("X-CSRF-TOKEN", browser.csrfToken)
        .header("X-Request-Id", requestId)
        .header("X-Correlation-Id", correlationId)
        .contentType(MediaType.APPLICATION_JSON)
        .content(body)

    private fun performCommand(
        browser: Browser,
        ticketNumber: Long,
        requestId: String,
        body: String,
    ) = mockMvc.perform(commandRequest(browser, ticketNumber, requestId, body))

    private fun commandRequest(
        browser: Browser,
        ticketNumber: Long,
        requestId: String,
        body: String,
    ) = post("/api/v1/agent/tickets/{ticketNumber}/commands", ticketNumber)
            .session(browser.session)
            .header("X-CSRF-TOKEN", browser.csrfToken)
            .header("X-Request-Id", requestId)
            .header("X-Correlation-Id", "correlation-ticket-command")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body)

    private fun commandResponse(
        browser: Browser,
        ticketNumber: Long,
        requestId: String,
        body: String,
    ): HttpResult {
        val response = mockMvc.perform(commandRequest(browser, ticketNumber, requestId, body))
            .andReturn().response
        return HttpResult(response.status, response.contentAsString)
    }

    private fun concurrently(vararg calls: Callable<HttpResult>): List<HttpResult> {
        val barrier = CyclicBarrier(calls.size)
        val executor = Executors.newFixedThreadPool(calls.size)
        try {
            val futures = calls.map { call ->
                executor.submit<HttpResult> {
                    barrier.await(5, TimeUnit.SECONDS)
                    call.call()
                }
            }
            return futures.map { it.get(15, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    private fun withTicketUpdateDelay(block: () -> Unit) {
        jdbcTemplate.execute(
            """
            create or replace function delay_ticket_command_update()
            returns trigger language plpgsql as ${'$'}${'$'}
            begin
                perform pg_sleep(0.25);
                return new;
            end;
            ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            create trigger delay_ticket_command_update
            before update on tickets
            for each row execute function delay_ticket_command_update()
            """.trimIndent(),
        )
        try {
            block()
        } finally {
            jdbcTemplate.execute("drop trigger if exists delay_ticket_command_update on tickets")
            jdbcTemplate.execute("drop function if exists delay_ticket_command_update()")
        }
    }

    private fun eventTypes(auditId: UUID): List<String> = jdbcTemplate.queryForList(
        "select event_type from ticket_audit_events where audit_id = ? order by event_order",
        String::class.java,
        auditId,
    ).filterNotNull()

    private fun eventRows(auditId: UUID): List<AuditEventRow> = jdbcTemplate.query(
        """
        select event_order, event_type, field_name, old_value_json, new_value_json
        from ticket_audit_events where audit_id = ? order by event_order
        """.trimIndent(),
        { result, _ ->
            AuditEventRow(
                result.getInt("event_order"),
                result.getString("event_type"),
                result.getString("field_name"),
                result.getString("old_value_json"),
                result.getString("new_value_json"),
            )
        },
        auditId,
    )

    private fun auditCount(ticketId: UUID): Long = jdbcTemplate.queryForObject(
        "select count(*) from ticket_audits where ticket_id = ?",
        Long::class.java,
        ticketId,
    )!!

    private fun commentCount(ticketId: UUID): Long = jdbcTemplate.queryForObject(
        "select count(*) from ticket_comments where ticket_id = ?",
        Long::class.java,
        ticketId,
    )!!

    private fun login(email: String): Browser {
        val csrfResult = mockMvc.perform(get("/api/v1/agent/csrf")).andExpect(status().isOk).andReturn()
        val token = Regex("\"token\":\"([^\"]+)\"").find(csrfResult.response.contentAsString)!!.groupValues[1]
        val session = csrfResult.request.session as MockHttpSession
        val login = mockMvc.perform(
            post("/api/v1/agent/session")
                .session(session)
                .header("X-CSRF-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email","password":"$PASSWORD"}"""),
        ).andExpect(status().isNoContent).andReturn()
        return Browser(login.request.session as MockHttpSession, token)
    }

    private fun insertStaff(email: String, displayName: String, role: String): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            """
            insert into staff_accounts
                (id, email_normalized, email_display, display_name, role, status,
                 password_hash, created_at, updated_at, version)
            values (?, ?, ?, ?, ?, 'ACTIVE', ?, now(), now(), 0)
            """.trimIndent(),
            id,
            email.lowercase(),
            email,
            displayName,
            role,
            BCryptPasswordEncoder(4).encode(PASSWORD),
        )
        return id
    }

    private fun insertGroup(name: String, vararg members: UUID): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            "insert into support_groups (id, name, status, created_at, updated_at, version) values (?, ?, 'ACTIVE', now(), now(), 0)",
            id,
            "$name-${UUID.randomUUID()}",
        )
        members.forEach { staffId ->
            jdbcTemplate.update(
                """
                insert into group_memberships
                    (id, group_id, staff_id, status, created_at, updated_at, version)
                values (?, ?, ?, 'ACTIVE', now(), now(), 0)
                """.trimIndent(),
                UUID.randomUUID(),
                id,
                staffId,
            )
        }
        return id
    }

    private fun insertCustomer(name: String, email: String): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            """
            insert into customers (id, name, email_normalized, email_display, created_at, updated_at)
            values (?, ?, ?, ?, now(), now())
            """.trimIndent(),
            id,
            name,
            email.lowercase(),
            email,
        )
        return id
    }

    private fun uuidField(json: String, field: String): UUID =
        UUID.fromString(Regex("\"$field\":\"([^\"]+)\"").find(json)!!.groupValues[1])

    private fun longField(json: String, field: String): Long =
        Regex("\"$field\":(\\d+)").find(json)!!.groupValues[1].toLong()

    private fun stringField(json: String, field: String): String =
        Regex("\"$field\":\"([^\"]+)\"").find(json)!!.groupValues[1]

    private data class Browser(val session: MockHttpSession, val csrfToken: String)

    private data class TicketFixture(val ticketId: UUID, val ticketNumber: Long)

    private data class HttpResult(val status: Int, val body: String)

    private data class AuditEventRow(
        val order: Int,
        val type: String,
        val field: String?,
        val before: String?,
        val after: String?,
    )

    companion object {
        private const val PASSWORD = "Agent password 42!"

        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
    }
}
