package dev.deskseed.staffaccess.internal

import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.containsInAnyOrder
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@SpringBootTest(properties = ["deskseed.staff-auth.bootstrap.enabled=false"])
@AutoConfigureMockMvc
@Testcontainers
class AgentTicketReadIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun clearState() {
        // SYSTEM seeds intentionally survive staff cleanup. Remove only test-created mutable definitions so
        // their non-cascading ownership identity cannot leak across test cases.
        jdbcTemplate.update("delete from saved_ticket_views where scope <> 'SYSTEM'")
        jdbcTemplate.update("delete from saved_view_order_states where scope = 'PERSONAL'")
        jdbcTemplate.update(
            "update saved_view_order_states set order_version = 1, updated_at = clock_timestamp() where scope = 'SHARED'",
        )
        // truncate (not delete) so this cascades through the append-only ticket_audit_events/ticket_audits
        // triggers and any search_audit_* child tables referencing access_audit_events, regardless of
        // whether a given test populated them or not.
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
    fun `active agents read every staff-visible ticket across group boundaries while anonymous and inactive staff are denied`() {
        val agentA = insertStaff("agent-a@example.com", "Agent password 42", "AGENT", "상담사 A")
        val agentB = insertStaff("agent-b@example.com", "Agent password 42", "AGENT", "상담사 B")
        val groupA = insertGroup("고객 지원", agentA)
        val groupB = insertGroup("결제 지원", agentB)
        val otherGroupTicket = insertTicket(
            number = 2001,
            subject = "다른 그룹 결제 오류",
            status = "PENDING",
            priority = "URGENT",
            groupId = groupB,
            assigneeId = agentB,
        )
        insertComment(otherGroupTicket.id, "PUBLIC", "CUSTOMER", otherGroupTicket.customerId, "결제 버튼이 작동하지 않습니다.")
        insertComment(otherGroupTicket.id, "INTERNAL", "AGENT", agentB, "PG 승인 로그를 확인합니다.")
        insertCreationAudit(otherGroupTicket.id, agentB)
        insertTicket(
            number = 2002,
            subject = "내 티켓",
            status = "OPEN",
            priority = "NORMAL",
            groupId = groupA,
            assigneeId = agentA,
        )
        insertTicket(
            number = 2003,
            subject = "종료된 내 티켓",
            status = "CLOSED",
            priority = "NORMAL",
            groupId = groupA,
            assigneeId = agentA,
        )
        val browser = login("agent-a@example.com", "Agent password 42")

        mockMvc.perform(get("/api/v1/agent/views").session(browser))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(5))
            .andExpect(jsonPath("$[0].readScope").value("ALL_TICKETS"))

        mockMvc.perform(
            get("/api/v1/agent/views/pending/tickets")
                .session(browser)
                .queryParam("status", "PENDING")
                .queryParam("priority", "URGENT")
                .queryParam("groupId", groupB.toString())
                .queryParam("assigneeId", agentB.toString()),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].ticketNumber").value(2001))
            .andExpect(jsonPath("$.items[0].group.name").value("결제 지원"))
            .andExpect(jsonPath("$.items[0].assignee.displayName").value("상담사 B"))
            .andExpect(jsonPath("$.items[0].createdAt").value("2026-08-09T23:59:00Z"))
            .andExpect(jsonPath("$.sort").value("updatedAt:desc,ticketNumber:desc"))

        val interactionId = UUID.randomUUID()
        mockMvc.perform(ticketDetail(2001, browser, interactionId, "NAVIGATION"))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("ETag", "\"0\""))
            .andExpect(jsonPath("$.ticket.ticketNumber").value(2001))
            .andExpect(jsonPath("$.ticket.createdAt").value("2026-08-09T23:59:00Z"))
            .andExpect(jsonPath("$.context.customer.email").value("customer-2001@example.com"))
            .andExpect(jsonPath("$.comments.length()").value(2))
            .andExpect(jsonPath("$.comments[1].visibility").value("INTERNAL"))
            .andExpect(jsonPath("$.history[0].eventType").value("TICKET_CREATED"))
            .andExpect(jsonPath("$.capabilities.length()").value(1))
            .andExpect(jsonPath("$.capabilities[0]").value("READ"))
            .andExpect(
                jsonPath("$.assignmentOptions.groups[*].name")
                    .value(containsInAnyOrder("고객 지원", "결제 지원")),
            )
            .andExpect(
                jsonPath("$.assignmentOptions.groups[*].members[*].displayName")
                    .value(containsInAnyOrder("상담사 A", "상담사 B")),
            )

        mockMvc.perform(ticketDetail(2002, browser, UUID.randomUUID(), "NAVIGATION"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.capabilities.length()").value(2))
            .andExpect(jsonPath("$.capabilities[0]").value("READ"))
            .andExpect(jsonPath("$.capabilities[1]").value("UPDATE"))

        mockMvc.perform(ticketDetail(2003, browser, UUID.randomUUID(), "NAVIGATION"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.capabilities.length()").value(1))
            .andExpect(jsonPath("$.capabilities[0]").value("READ"))

        mockMvc.perform(get("/api/v1/agent/tickets/2001"))
            .andExpect(status().isUnauthorized)

        jdbcTemplate.update("update staff_accounts set status = 'DISABLED' where id = ?", agentA)
        mockMvc.perform(ticketDetail(2001, browser, UUID.randomUUID(), "NAVIGATION"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `cursor is stable for updated time ties and is bound to the selected filters`() {
        val agent = insertStaff("agent@example.com", "Agent password 42", "AGENT", "상담사")
        val group = insertGroup("고객 지원", agent)
        val tiedAt = Instant.parse("2026-08-10T10:15:30Z")
        repeat(5) { index ->
            insertTicket(
                number = 3001L + index,
                subject = "동일 시각 ${index + 1}",
                status = "OPEN",
                priority = if (index == 0) "HIGH" else "NORMAL",
                groupId = group,
                assigneeId = agent,
                updatedAt = tiedAt,
            )
        }
        val browser = login("agent@example.com", "Agent password 42")

        val first = mockMvc.perform(
            get("/api/v1/agent/views/my-open/tickets")
                .session(browser)
                .queryParam("limit", "2"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].ticketNumber").value(3005))
            .andExpect(jsonPath("$.items[1].ticketNumber").value(3004))
            .andReturn().response.contentAsString
        val cursor = stringField(first, "nextCursor")

        val second = mockMvc.perform(
            get("/api/v1/agent/views/my-open/tickets")
                .session(browser)
                .queryParam("limit", "2")
                .queryParam("cursor", cursor),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].ticketNumber").value(3003))
            .andExpect(jsonPath("$.items[1].ticketNumber").value(3002))
            .andReturn().response.contentAsString
        val secondCursor = stringField(second, "nextCursor")

        mockMvc.perform(
            get("/api/v1/agent/views/my-open/tickets")
                .session(browser)
                .queryParam("limit", "2")
                .queryParam("cursor", secondCursor),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].ticketNumber").value(3001))
            .andExpect(jsonPath("$.nextCursor").isEmpty)

        mockMvc.perform(
            get("/api/v1/agent/views/my-open/tickets")
                .session(browser)
                .queryParam("priority", "HIGH")
                .queryParam("cursor", cursor),
        ).andExpect(status().isBadRequest)

        val cursorParts = cursor.split('.')
        val signature = cursorParts[2]
        val tampered = listOf(
            cursorParts[0],
            cursorParts[1],
            (if (signature.first() == 'A') 'B' else 'A').toString() + signature.drop(1),
        ).joinToString(".")
        mockMvc.perform(
            get("/api/v1/agent/views/my-open/tickets")
                .session(browser)
                .queryParam("limit", "2")
                .queryParam("cursor", tampered),
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `each default view applies its documented server-side condition`() {
        val agent = insertStaff("views@example.com", "Agent password 42", "AGENT", "Views 상담사")
        val group = insertGroup("Views 그룹", agent)
        insertTicket(3501, "내 open", status = "OPEN", groupId = group, assigneeId = agent)
        insertTicket(3502, "내 그룹 미배정", status = "NEW", groupId = group)
        insertTicket(3503, "공유 pending", status = "PENDING")
        insertTicket(3504, "최근 solved", status = "SOLVED", groupId = group, assigneeId = agent)
        insertTicket(
            3505,
            "내 child task",
            status = "OPEN",
            groupId = group,
            assigneeId = agent,
            kind = "INTERNAL_CHILD",
        )
        val browser = login("views@example.com", "Agent password 42")

        listOf(
            "my-open" to 3501,
            "unassigned-my-groups" to 3502,
            "pending" to 3503,
            "recently-solved" to 3504,
            "my-child-tasks" to 3505,
        ).forEach { (viewKey, expectedTicketNumber) ->
            mockMvc.perform(get("/api/v1/agent/views/{viewKey}/tickets", viewKey).session(browser))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items[?(@.ticketNumber == $expectedTicketNumber)]").isNotEmpty)
        }
    }

    @Test
    fun `versioned saved views use allowlisted conditions exact counts owner shared capability and execution audit`() {
        val owner = insertStaff("saved-view-owner@example.com", "Agent password 42", "AGENT", "뷰 소유 상담사")
        val other = insertStaff("saved-view-other@example.com", "Agent password 42", "AGENT", "다른 상담사")
        val admin = insertStaff("saved-view-admin@example.com", "Admin password 42", "ADMIN", "뷰 관리자")
        val ownerGroup = insertGroup("뷰 소유 그룹", owner)
        insertTicket(3601, "OPEN view row", status = "OPEN", groupId = ownerGroup, assigneeId = owner)
        insertTicket(3602, "PENDING not in view", status = "PENDING", groupId = ownerGroup, assigneeId = owner)
        val ownerSession = login("saved-view-owner@example.com", "Agent password 42")
        val otherSession = login("saved-view-other@example.com", "Agent password 42")
        val adminSession = login("saved-view-admin@example.com", "Admin password 42")

        val personal = mockMvc.perform(
            post("/api/v1/agent/views")
                .session(ownerSession)
                .header("X-CSRF-TOKEN", csrf(ownerSession))
                .contentType(MediaType.APPLICATION_JSON)
                .content(savedViewCreateBody("PERSONAL", "내 OPEN", "OPEN", "  고객 응대 집중 View  ")),
        )
            .andExpect(status().isCreated)
            .andExpect(header().string("ETag", "\"1\""))
            .andExpect(jsonPath("$.scope").value("PERSONAL"))
            .andExpect(jsonPath("$.description").value("고객 응대 집중 View"))
            .andExpect(jsonPath("$.ticketCountAsOf").isEmpty)
            .andExpect(jsonPath("$.definitionVersion").value(1))
            .andReturn().response.contentAsString
        val personalId = UUID.fromString(stringField(personal, "id"))
        val personalKey = stringField(personal, "key")

        val listed = mockMvc.perform(get("/api/v1/agent/views").session(ownerSession))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        assertThat(listed)
            .contains(
                "\"key\":\"$personalKey\"",
                "\"description\":\"고객 응대 집중 View\"",
                "\"ticketCount\":1",
                "\"ticketCountState\":\"EXACT\"",
            )
        val countBasis = Regex("\\\"ticketCountAsOf\\\":\\\"([^\\\"]+)\\\"")
            .findAll(listed)
            .map { match -> Instant.parse(match.groupValues[1]) }
            .toList()
        assertThat(countBasis).isNotEmpty
        assertThat(countBasis.distinct()).hasSize(1)

        mockMvc.perform(
            post("/api/v1/agent/views/preview")
                .session(ownerSession)
                .header("X-CSRF-TOKEN", csrf(ownerSession))
                .header("X-Interaction-Id", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(savedViewDefinitionBody("미리보기 OPEN", "OPEN", "저장하지 않는 설명")),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.ticketCount").value(1))
            .andExpect(jsonPath("$.ticketCountAsOf").isNotEmpty)
            .andExpect(jsonPath("$.items[0].ticketNumber").value(3601))

        mockMvc.perform(
            get("/api/v1/agent/views/{viewKey}/tickets", personalKey)
                .session(ownerSession)
                .queryParam("limit", "25"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].ticketNumber").value(3601))

        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from access_audit_events where action = 'VIEW_EXECUTED' and resource_id = ?",
                Long::class.java,
                personalId,
            ),
        ).isEqualTo(1)
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from admin_security_audit_events where event_type = 'SAVED_VIEW_CREATED' and target_id = ?",
                Long::class.java,
                personalId,
            ),
        ).isEqualTo(1)

        mockMvc.perform(
            patch("/api/v1/agent/views/{viewKey}", personalKey)
                .session(otherSession)
                .header("X-CSRF-TOKEN", csrf(otherSession))
                .contentType(MediaType.APPLICATION_JSON)
                .content(savedViewUpdateBody(1, "다른 상담사 수정", "PENDING")),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.type").value("/problems/saved-view-not-found"))

        val updated = mockMvc.perform(
            patch("/api/v1/agent/views/{viewKey}", personalKey)
                .session(ownerSession)
                .header("X-CSRF-TOKEN", csrf(ownerSession))
                .contentType(MediaType.APPLICATION_JSON)
                .content(savedViewUpdateBody(1, "내 PENDING", "PENDING", "  새 설명  ")),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("ETag", "\"2\""))
            .andExpect(jsonPath("$.description").value("새 설명"))
            .andExpect(jsonPath("$.definitionVersion").value(2))
            .andReturn().response.contentAsString
        assertThat(updated).contains("\"name\":\"내 PENDING\"")

        mockMvc.perform(
            patch("/api/v1/agent/views/{viewKey}", personalKey)
                .session(ownerSession)
                .header("X-CSRF-TOKEN", csrf(ownerSession))
                .contentType(MediaType.APPLICATION_JSON)
                .content(savedViewUpdateBody(2, "내 PENDING", "PENDING", "설명만 변경")),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("ETag", "\"3\""))
            .andExpect(jsonPath("$.description").value("설명만 변경"))
            .andExpect(jsonPath("$.definitionVersion").value(3))

        mockMvc.perform(
            patch("/api/v1/agent/views/{viewKey}", personalKey)
                .session(ownerSession)
                .header("X-CSRF-TOKEN", csrf(ownerSession))
                .contentType(MediaType.APPLICATION_JSON)
                .content(savedViewUpdateBody(2, "오래된 버전", "OPEN", "유실되면 안 되는 초안")),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.type").value("/problems/saved-view-conflict"))

        assertThat(
            jdbcTemplate.queryForObject(
                "select description from saved_ticket_views where id = ?",
                String::class.java,
                personalId,
            ),
        ).isEqualTo("설명만 변경")
        assertThat(
            jdbcTemplate.queryForObject(
                "select string_agg(metadata_json, ' ') from admin_security_audit_events where target_id = ?",
                String::class.java,
                personalId,
            ),
        ).doesNotContain("고객 응대 집중 View", "새 설명", "설명만 변경")

        mockMvc.perform(
            post("/api/v1/agent/views/reorder")
                .session(ownerSession)
                .header("X-CSRF-TOKEN", csrf(ownerSession))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"scope":"PERSONAL","expectedOrderVersion":2,"viewKeys":["$personalKey"]}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.scope").value("PERSONAL"))
            .andExpect(jsonPath("$.orderVersion").value(3))

        mockMvc.perform(
            post("/api/v1/agent/views")
                .session(ownerSession)
                .header("X-CSRF-TOKEN", csrf(ownerSession))
                .contentType(MediaType.APPLICATION_JSON)
                .content(savedViewCreateBody("SHARED", "상담사 공유 시도", "OPEN")),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.type").value("/problems/saved-view-forbidden"))

        val shared = mockMvc.perform(
            post("/api/v1/agent/views")
                .session(adminSession)
                .header("X-CSRF-TOKEN", csrf(adminSession))
                .contentType(MediaType.APPLICATION_JSON)
                .content(savedViewCreateBody("SHARED", "관리자 공유", "OPEN")),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.scope").value("SHARED"))
            .andReturn().response.contentAsString
        val sharedKey = stringField(shared, "key")

        mockMvc.perform(
            patch("/api/v1/agent/views/{viewKey}", sharedKey)
                .session(ownerSession)
                .header("X-CSRF-TOKEN", csrf(ownerSession))
                .contentType(MediaType.APPLICATION_JSON)
                .content(savedViewUpdateBody(1, "권한 없는 공유 수정", "PENDING")),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.type").value("/problems/saved-view-forbidden"))

        mockMvc.perform(
            patch("/api/v1/agent/views/{viewKey}", "my-open")
                .session(adminSession)
                .header("X-CSRF-TOKEN", csrf(adminSession))
                .contentType(MediaType.APPLICATION_JSON)
                .content(savedViewUpdateBody(1, "시스템 변경", "PENDING")),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.type").value("/problems/saved-view-forbidden"))

        mockMvc.perform(
            delete("/api/v1/agent/views/{viewKey}", personalKey)
                .session(ownerSession)
                .header("X-CSRF-TOKEN", csrf(ownerSession))
                .header("If-Match", "\"3\""),
        ).andExpect(status().isNoContent)
    }

    @Test
    fun `saved view description defaults to empty and rejects oversized or control characters`() {
        insertStaff("saved-view-validation@example.com", "Agent password 42", "AGENT", "검증 상담사")
        val session = login("saved-view-validation@example.com", "Agent password 42")
        val csrf = csrf(session)

        mockMvc.perform(
            post("/api/v1/agent/views")
                .session(session)
                .header("X-CSRF-TOKEN", csrf)
                .contentType(MediaType.APPLICATION_JSON)
                .content(savedViewCreateBody("PERSONAL", "기본 설명", "OPEN")),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.description").value(""))

        listOf("x".repeat(501), "잘못된\\n설명").forEach { description ->
            mockMvc.perform(
                post("/api/v1/agent/views")
                    .session(session)
                    .header("X-CSRF-TOKEN", csrf)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(savedViewCreateBody("PERSONAL", "거부 설명", "OPEN", description)),
            ).andExpect(status().isBadRequest)
        }

        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from saved_ticket_views where scope = 'PERSONAL'",
                Long::class.java,
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `twenty first visible saved view omits count and count basis`() {
        insertStaff("saved-view-limit@example.com", "Agent password 42", "AGENT", "보기 제한 상담사")
        val session = login("saved-view-limit@example.com", "Agent password 42")
        val csrf = csrf(session)

        repeat(16) { index ->
            mockMvc.perform(
                post("/api/v1/agent/views")
                    .session(session)
                    .header("X-CSRF-TOKEN", csrf)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(savedViewCreateBody("PERSONAL", "개인 보기 ${index + 1}", "OPEN")),
            ).andExpect(status().isCreated)
        }

        mockMvc.perform(get("/api/v1/agent/views").session(session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(21))
            .andExpect(jsonPath("$[19].ticketCountState").value("EXACT"))
            .andExpect(jsonPath("$[19].ticketCount").value(0))
            .andExpect(jsonPath("$[19].ticketCountAsOf").isNotEmpty)
            .andExpect(jsonPath("$[20].ticketCountState").value("OMITTED_VISIBLE_LIMIT"))
            .andExpect(jsonPath("$[20].ticketCount").isEmpty)
            .andExpect(jsonPath("$[20].ticketCountAsOf").isEmpty)
    }

    @Test
    fun `agent reads record every protected resource access while navigation writes one semantic view`() {
        val agent = insertStaff("agent@example.com", "Agent password 42", "AGENT", "상담사")
        val group = insertGroup("감사 그룹", agent)
        val ticket = insertTicket(number = 4001, subject = "감사 대상 티켓", groupId = group, assigneeId = agent)
        insertComment(ticket.id, "PUBLIC", "CUSTOMER", ticket.customerId, "문의 내용")
        val browser = login("agent@example.com", "Agent password 42")
        val interactionId = UUID.randomUUID()

        mockMvc.perform(ticketDetail(4001, browser, interactionId, "NAVIGATION")).andExpect(status().isOk)
        mockMvc.perform(ticketDetail(4001, browser, interactionId, "NAVIGATION")).andExpect(status().isOk)
        mockMvc.perform(ticketDetail(4001, browser, interactionId, "BACKGROUND")).andExpect(status().isOk)

        assertThat(viewEventCount(agent, 4001)).isEqualTo(1)
        assertThat(resourceReadEventCount(agent, 4001)).isEqualTo(3)

        mockMvc.perform(ticketDetail(4001, browser, UUID.randomUUID(), "NAVIGATION")).andExpect(status().isOk)
        assertThat(viewEventCount(agent, 4001)).isEqualTo(2)
        assertThat(resourceReadEventCount(agent, 4001)).isEqualTo(4)
    }

    @Test
    fun `background read audit failure fails closed without returning protected detail`() {
        val agent = insertStaff("agent@example.com", "Agent password 42", "AGENT", "상담사")
        val group = insertGroup("보호 그룹", agent)
        val ticket = insertTicket(
            number = 5001,
            subject = "audit-failure-protected-subject",
            groupId = group,
            assigneeId = agent,
        )
        insertComment(ticket.id, "INTERNAL", "AGENT", agent, "audit-failure-protected-internal-note")
        val browser = login("agent@example.com", "Agent password 42")
        jdbcTemplate.execute(
            """
            create or replace function fail_access_audit_insert() returns trigger language plpgsql as ${'$'}${'$'}
            begin raise exception 'injected access audit failure'; end; ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            "create trigger fail_access_audit before insert on access_audit_events for each row execute function fail_access_audit_insert()",
        )
        try {
            val response = mockMvc.perform(ticketDetail(5001, browser, UUID.randomUUID(), "BACKGROUND"))
                .andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.type").value("/problems/audit-write-unavailable"))
                .andExpect(jsonPath("$.ticket").doesNotExist())
                .andExpect(jsonPath("$.comments").doesNotExist())
                .andExpect(jsonPath("$.context").doesNotExist())
                .andReturn().response.contentAsString
            assertThat(response)
                .doesNotContain("audit-failure-protected-subject")
                .doesNotContain("audit-failure-protected-internal-note")
                .doesNotContain("customer-5001@example.com")
        } finally {
            jdbcTemplate.execute("drop trigger if exists fail_access_audit on access_audit_events")
            jdbcTemplate.execute("drop function if exists fail_access_audit_insert()")
        }
    }

    @Test
    fun `anonymous and inactive staff reads return no protected detail and create zero success audit rows`() {
        val agent = insertStaff("denied@example.com", "Agent password 42", "AGENT", "차단 상담사")
        val group = insertGroup("차단 그룹", agent)
        val ticket = insertTicket(
            number = 5101,
            subject = "authorization-denied-protected-subject",
            groupId = group,
            assigneeId = agent,
        )
        insertComment(ticket.id, "INTERNAL", "AGENT", agent, "authorization-denied-protected-note")
        val browser = login("denied@example.com", "Agent password 42")

        val anonymousResponse = mockMvc.perform(
            get("/api/v1/agent/tickets/5101")
                .header("X-Interaction-Id", UUID.randomUUID())
                .header("X-Deskseed-Read-Intent", "NAVIGATION"),
        ).andExpect(status().isUnauthorized).andReturn().response.contentAsString

        jdbcTemplate.update("update staff_accounts set status = 'DISABLED' where id = ?", agent)
        val inactiveResponse = mockMvc.perform(ticketDetail(5101, browser, UUID.randomUUID(), "NAVIGATION"))
            .andExpect(status().isUnauthorized)
            .andReturn().response.contentAsString

        assertThat(anonymousResponse + inactiveResponse)
            .doesNotContain("authorization-denied-protected-subject")
            .doesNotContain("authorization-denied-protected-note")
            .doesNotContain("customer-5101@example.com")
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from access_audit_events where ticket_number = 5101 and outcome = 'SUCCEEDED'",
                Long::class.java,
            ),
        ).isZero()
    }

    @Test
    fun `agent read supports canonical on hold list rows and closed direct detail`() {
        val agent = insertStaff("canonical@example.com", "Agent password 42", "AGENT", "상담사")
        val group = insertGroup("상태 그룹", agent)
        insertTicket(6001, "보류 중 티켓", status = "ON_HOLD", groupId = group)
        insertTicket(6002, "종료 티켓", status = "CLOSED", groupId = group, assigneeId = agent)
        val browser = login("canonical@example.com", "Agent password 42")

        mockMvc.perform(get("/api/v1/agent/views/unassigned-my-groups/tickets").session(browser))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[?(@.ticketNumber == 6001)].status").value("ON_HOLD"))

        mockMvc.perform(ticketDetail(6001, browser, UUID.randomUUID(), "BACKGROUND"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.ticket.status").value("ON_HOLD"))
            .andExpect(jsonPath("$.capabilities.length()").value(2))
            .andExpect(jsonPath("$.capabilities[0]").value("READ"))
            .andExpect(jsonPath("$.capabilities[1]").value("UPDATE"))

        mockMvc.perform(ticketDetail(6002, browser, UUID.randomUUID(), "BACKGROUND"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.ticket.status").value("CLOSED"))
            .andExpect(jsonPath("$.capabilities.length()").value(1))
            .andExpect(jsonPath("$.capabilities[0]").value("READ"))
    }

    @Test
    fun `allowed origin can preflight and complete protected detail with read headers`() {
        val agent = insertStaff("cors@example.com", "Agent password 42", "AGENT", "상담사")
        val group = insertGroup("CORS 그룹", agent)
        insertTicket(7001, "CORS 대상 티켓", groupId = group, assigneeId = agent)
        val browser = login("cors@example.com", "Agent password 42")

        mockMvc.perform(
            options("/api/v1/agent/tickets/7001")
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "GET")
                .header(
                    "Access-Control-Request-Headers",
                    "X-Interaction-Id, X-Deskseed-Read-Intent, X-Origin-Search-Event-Id",
                ),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
            .andExpect(header().string("Access-Control-Allow-Headers", containsString("X-Interaction-Id")))
            .andExpect(header().string("Access-Control-Allow-Headers", containsString("X-Deskseed-Read-Intent")))
            .andExpect(header().string("Access-Control-Allow-Headers", containsString("X-Origin-Search-Event-Id")))

        mockMvc.perform(
            ticketDetail(7001, browser, UUID.randomUUID(), "BACKGROUND")
                .header("Origin", "http://localhost:5173"),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
    }

    @Test
    fun `assignment options list active groups and members without requiring an existing ticket`() {
        val agent = insertStaff("assignment-options@example.com", "Agent password 42", "AGENT", "배정 상담사")
        val group = insertGroup("배정 옵션 그룹", agent)
        val disabledGroupOwner = insertStaff("disabled-owner@example.com", "Agent password 42", "AGENT", "비활성 그룹 상담사")
        val disabledGroup = insertGroup("비활성 그룹", disabledGroupOwner)
        jdbcTemplate.update("update support_groups set status = 'DISABLED' where id = ?", disabledGroup)
        val browser = login("assignment-options@example.com", "Agent password 42")

        mockMvc.perform(get("/api/v1/agent/assignment-options").session(browser))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.groups[?(@.id == '$group')].name").value("배정 옵션 그룹"))
            .andExpect(jsonPath("$.groups[?(@.id == '$group')].members[0].id").value(agent.toString()))
            .andExpect(jsonPath("$.groups[?(@.id == '$disabledGroup')]").isEmpty)
    }

    @Test
    fun `assignment options are denied without an authenticated staff session`() {
        mockMvc.perform(get("/api/v1/agent/assignment-options"))
            .andExpect(status().isUnauthorized)
    }

    private fun ticketDetail(number: Long, session: MockHttpSession, interactionId: UUID, intent: String) =
        get("/api/v1/agent/tickets/{ticketNumber}", number)
            .session(session)
            .header("X-Interaction-Id", interactionId)
            .header("X-Deskseed-Read-Intent", intent)
            .header("X-Request-Id", "agent-read-${UUID.randomUUID()}")

    private fun viewEventCount(agentId: UUID, ticketNumber: Long): Long = jdbcTemplate.queryForObject(
        """
        select count(*) from access_audit_events
        where actor_id = ? and ticket_number = ? and action = 'TICKET_VIEWED' and outcome = 'SUCCEEDED'
        """.trimIndent(),
        Long::class.java,
        agentId,
        ticketNumber,
    )!!

    private fun resourceReadEventCount(agentId: UUID, ticketNumber: Long): Long = jdbcTemplate.queryForObject(
        """
        select count(*) from access_audit_events
        where actor_id = ? and ticket_number = ? and action = 'API_RESOURCE_READ' and outcome = 'SUCCEEDED'
        """.trimIndent(),
        Long::class.java,
        agentId,
        ticketNumber,
    )!!

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
    ).andExpect(status().isOk).andReturn().response.contentAsString.let { stringField(it, "token") }

    private fun savedViewCreateBody(
        scope: String,
        name: String,
        status: String,
        description: String? = null,
    ): String =
        """{"scope":"$scope",${savedViewDefinitionBody(name, status, description).removePrefix("{").removeSuffix("}")}}"""

    private fun savedViewUpdateBody(
        expectedVersion: Long,
        name: String,
        status: String,
        description: String? = null,
    ): String =
        """{"expectedVersion":$expectedVersion,${savedViewDefinitionBody(name, status, description).removePrefix("{").removeSuffix("}")}}"""

    private fun savedViewDefinitionBody(name: String, status: String, description: String? = null): String =
        """
        {
          "name":"$name",
          ${description?.let { "\"description\":\"$it\"," }.orEmpty()}
          "conditions":{"version":1,"all":[{"field":"STATUS","operator":"EQUALS","values":["$status"]}],"any":[]},
          "columns":["TICKET_NUMBER","SUBJECT","STATUS","UPDATED_AT"],
          "sort":"updatedAt:desc,ticketNumber:desc"
        }
        """.trimIndent()

    private fun insertStaff(email: String, password: String, role: String, displayName: String): UUID {
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
            BCryptPasswordEncoder(4).encode(password),
        )
        return id
    }

    private fun insertGroup(name: String, staffId: UUID): UUID {
        val groupId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            insert into support_groups (id, name, status, created_at, updated_at, version)
            values (?, ?, 'ACTIVE', now(), now(), 0)
            """.trimIndent(),
            groupId,
            name,
        )
        jdbcTemplate.update(
            """
            insert into group_memberships (id, group_id, staff_id, status, created_at, updated_at)
            values (?, ?, ?, 'ACTIVE', now(), now())
            """.trimIndent(),
            UUID.randomUUID(),
            groupId,
            staffId,
        )
        return groupId
    }

    private fun insertTicket(
        number: Long,
        subject: String,
        status: String = "OPEN",
        priority: String = "NORMAL",
        groupId: UUID? = null,
        assigneeId: UUID? = null,
        kind: String = "CUSTOMER_REQUEST",
        updatedAt: Instant = Instant.parse("2026-08-10T00:00:00Z"),
    ): TicketFixture {
        val customerId = UUID.randomUUID()
        val ticketId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            insert into customers (id, name, email_normalized, email_display, created_at, updated_at)
            values (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            customerId,
            "고객 $number",
            "customer-$number@example.com",
            "customer-$number@example.com",
            Timestamp.from(updatedAt.minusSeconds(60)),
            Timestamp.from(updatedAt),
        )
        jdbcTemplate.update(
            """
            insert into tickets
                (id, ticket_number, requester_id, kind, subject, status, priority,
                 group_id, assignee_id, channel, version, created_at, updated_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'WEB', 0, ?, ?)
            """.trimIndent(),
            ticketId,
            number,
            customerId,
            kind,
            subject,
            status,
            priority,
            groupId,
            assigneeId,
            Timestamp.from(updatedAt.minusSeconds(60)),
            Timestamp.from(updatedAt),
        )
        return TicketFixture(ticketId, customerId)
    }

    private fun insertComment(ticketId: UUID, visibility: String, authorType: String, authorId: UUID, body: String) {
        jdbcTemplate.update(
            """
            insert into ticket_comments (id, ticket_id, author_type, author_id, visibility, body, created_at)
            values (?, ?, ?, ?, ?, ?, now())
            """.trimIndent(),
            UUID.randomUUID(),
            ticketId,
            authorType,
            authorId,
            visibility,
            body,
        )
    }

    private fun insertCreationAudit(ticketId: UUID, actorId: UUID) {
        val auditId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            insert into ticket_audits
                (id, ticket_id, ticket_version, expected_version, actor_type, actor_id, source,
                 request_id, correlation_id, command_id, created_at)
            values (?, ?, 0, 0, 'STAFF', ?, 'AGENT_UI', 'fixture-request', 'fixture-correlation',
                    'fixture-command', now())
            """.trimIndent(),
            auditId,
            ticketId,
            actorId,
        )
        jdbcTemplate.update(
            """
            insert into ticket_audit_events
                (id, audit_id, event_order, event_type, metadata_json, occurred_at)
            values (?, ?, 1, 'TICKET_CREATED', '{}', now())
            """.trimIndent(),
            UUID.randomUUID(),
            auditId,
        )
    }

    private fun stringField(json: String, field: String): String =
        Regex("\"$field\":\"([^\"]+)\"").find(json)!!.groupValues[1]

    private data class TicketFixture(val id: UUID, val customerId: UUID)

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
    }
}
