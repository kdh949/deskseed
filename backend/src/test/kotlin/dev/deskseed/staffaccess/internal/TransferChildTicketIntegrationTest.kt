package dev.deskseed.staffaccess.internal

import dev.deskseed.ticketing.StaffTicketReadStore
import dev.deskseed.testsupport.integration.DeskseedSpringIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessException
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.hamcrest.Matchers.containsString
import java.util.UUID

@DeskseedSpringIntegrationTest
@AutoConfigureMockMvc
@dev.deskseed.testsupport.category.IntegrationTest
class TransferChildTicketIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var databaseCleaner: dev.deskseed.testsupport.integration.StaffTicketTestDatabaseCleaner

    @Autowired
    private lateinit var ticketReadStore: StaffTicketReadStore

    @BeforeEach
    fun clearState() {
        databaseCleaner.resetMutableStaffTicketState()
    }

    @Test
    fun `allowed browser origin can preflight ETag guarded ticket commands`() {
        mockMvc.perform(
            options("/api/v1/agent/tickets/1000/children")
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "If-Match, X-CSRF-TOKEN"),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
            .andExpect(header().string("Access-Control-Allow-Headers", containsString("If-Match")))
            .andExpect(header().string("Access-Control-Allow-Headers", containsString("X-CSRF-TOKEN")))
    }

    @Test
    fun `transfer moves one ticket while child keeps parent ownership and records two ticket timelines`() {
        val agentA = insertStaff("transfer-a@example.com", "이관 상담사 A")
        val agentB = insertStaff("transfer-b@example.com", "이관 상담사 B")
        val groupA = insertGroup("이관 그룹 A", agentA)
        val groupB = insertGroup("이관 그룹 B", agentB)
        val browserA = login("transfer-a@example.com")

        val transferParent = createCustomerParent("transfer-parent@example.com", groupA, agentA)
        val ticketCountBeforeTransfer = count("tickets")
        val transferred = mockMvc.perform(
            transferRequest(
                browserA,
                transferParent.ticketNumber,
                0,
                """
                {
                  "expectedVersion": 0,
                  "groupId": "$groupB",
                  "assigneeId": "$agentB",
                  "reason": "결제 전문 그룹이 고객 응답 책임을 인수"
                }
                """.trimIndent(),
            ),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("ETag", "\"1\""))
            .andExpect(jsonPath("$.ticketNumber").value(transferParent.ticketNumber))
            .andExpect(jsonPath("$.version").value(1))
            .andExpect(jsonPath("$.warnings").isEmpty)
            .andReturn().response.contentAsString

        assertThat(count("tickets")).isEqualTo(ticketCountBeforeTransfer)
        assertThat(ownership(transferParent.ticketId)).containsEntry("group_id", groupB)
            .containsEntry("assignee_id", agentB)
        val transferAuditId = uuidField(transferred, "auditId")
        assertThat(eventTypes(transferAuditId))
            .containsExactly("COMMENT_CREATED", "GROUP_CHANGED", "ASSIGNEE_CHANGED")
        assertThat(auditActor(transferAuditId)).isEqualTo(agentA)
        assertThat(
            jdbcTemplate.queryForObject(
                "select visibility from ticket_comments where ticket_id = ? order by created_at desc limit 1",
                String::class.java,
                transferParent.ticketId,
            ),
        ).isEqualTo("INTERNAL")
        val childParent = createCustomerParent("child-parent@example.com", groupA, agentA)
        val parentOwnershipBefore = ownership(childParent.ticketId)
        val childResponse = createChild(
            browserA,
            childParent.ticketNumber,
            expectedVersion = 0,
            groupId = groupB,
            assigneeId = agentB,
            subject = "결제 승인 로그 확인",
            body = "고객에게 노출하지 않고 승인 실패 원인을 확인해 주세요.",
        )
            .andExpect(status().isCreated)
            .andExpect(header().string("ETag", "\"1\""))
            .andExpect(jsonPath("$.parentTicketNumber").value(childParent.ticketNumber))
            .andExpect(jsonPath("$.parentVersion").value(1))
            .andExpect(jsonPath("$.parentAuditId").isString)
            .andExpect(jsonPath("$.childAuditId").isString)
            .andReturn().response.contentAsString

        val childNumber = longField(childResponse, "childTicketNumber")
        val childId = ticketId(childNumber)
        assertThat(ownership(childParent.ticketId)).isEqualTo(parentOwnershipBefore)
        val child = jdbcTemplate.queryForMap(
            "select kind, group_id, assignee_id, version from tickets where id = ?",
            childId,
        )
        assertThat(child).containsEntry("kind", "INTERNAL_CHILD")
            .containsEntry("group_id", groupB)
            .containsEntry("assignee_id", agentB)
            .containsEntry("version", 0L)
        assertThat(
            jdbcTemplate.queryForMap(
                "select relation_type, created_by_actor_type, created_by_actor_id from ticket_relations where source_ticket_id = ? and target_ticket_id = ?",
                childParent.ticketId,
                childId,
            ),
        ).containsEntry("relation_type", "PARENT_CHILD")
            .containsEntry("created_by_actor_type", "STAFF")
            .containsEntry("created_by_actor_id", agentA)
        assertThat(eventTypes(uuidField(childResponse, "parentAuditId")))
            .containsExactly("CHILD_TICKET_CREATED", "TICKET_RELATION_CREATED")
        assertThat(eventTypes(uuidField(childResponse, "childAuditId")))
            .containsExactly("TICKET_CREATED", "COMMENT_CREATED", "TICKET_RELATION_CREATED")
        assertThat(
            jdbcTemplate.queryForObject(
                "select visibility from ticket_comments where ticket_id = ?",
                String::class.java,
                childId,
            ),
        ).isEqualTo("INTERNAL")
        assertThat(ticketReadStore.hasRelationReadGrant(childParent.ticketId, agentB)).isTrue()
        assertThat(ticketReadStore.hasRelationReadGrant(childId, agentA)).isTrue()

        val browserB = login("transfer-b@example.com")
        readTicket(browserB, childParent.ticketNumber)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.context.children[0].ticketNumber").value(childNumber))
            .andExpect(jsonPath("$.ticket.openChildCount").value(1))
        readTicket(browserB, childNumber)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.context.parent.ticketNumber").value(childParent.ticketNumber))
            .andExpect(jsonPath("$.ticket.isChild").value(true))

        val childVersionBeforePublicAttempt = versionOf(childId)
        val childCommentCountBeforePublicAttempt = jdbcTemplate.queryForObject(
            "select count(*) from ticket_comments where ticket_id = ?",
            Long::class.java,
            childId,
        )!!
        val childAuditCountBeforePublicAttempt = jdbcTemplate.queryForObject(
            "select count(*) from ticket_audits where ticket_id = ?",
            Long::class.java,
            childId,
        )!!
        performCommand(
            browserB,
            childNumber,
            """
            {
              "expectedVersion":0,
              "changedFields":[],
              "comment":{"visibility":"PUBLIC","body":"고객에게 보내면 안 되는 child 답변"}
            }
            """.trimIndent(),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.type").value("/problems/validation"))
        assertThat(versionOf(childId)).isEqualTo(childVersionBeforePublicAttempt)
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from ticket_comments where ticket_id = ?",
                Long::class.java,
                childId,
            ),
        ).isEqualTo(childCommentCountBeforePublicAttempt)
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from ticket_audits where ticket_id = ?",
                Long::class.java,
                childId,
            ),
        ).isEqualTo(childAuditCountBeforePublicAttempt)

        performCommand(
            browserB,
            childParent.ticketNumber,
            """
            {"expectedVersion":1,"changedFields":["priority"],"priority":"HIGH"}
            """.trimIndent(),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.type").value("/problems/ticket-write-forbidden"))
    }

    @Test
    fun `invalid membership stale permission depth self link and duplicate parent are rejected`() {
        val owner = insertStaff("rules-owner@example.com", "규칙 소유자")
        val specialist = insertStaff("rules-specialist@example.com", "규칙 전문가")
        val outsider = insertStaff("rules-outsider@example.com", "규칙 외부자")
        val ownerGroup = insertGroup("규칙 부모 그룹", owner)
        val specialistGroup = insertGroup("규칙 전문 그룹", specialist)
        val outsiderGroup = insertGroup("규칙 외부 그룹", outsider)
        val ownerBrowser = login("rules-owner@example.com")
        val outsiderBrowser = login("rules-outsider@example.com")
        val parent = createCustomerParent("rules-parent@example.com", ownerGroup, owner)

        mockMvc.perform(
            transferRequest(
                ownerBrowser,
                parent.ticketNumber,
                0,
                """{"expectedVersion":0,"groupId":"$specialistGroup","assigneeId":"$owner"}""",
            ),
        )
            .andExpect(status().isUnprocessableContent)
            .andExpect(jsonPath("$.type").value("/problems/ticket-assignment-invalid"))

        createChild(
            ownerBrowser,
            parent.ticketNumber,
            0,
            specialistGroup,
            owner,
            "잘못된 담당자",
            "대상 그룹 멤버가 아니므로 실패해야 합니다.",
        )
            .andExpect(status().isUnprocessableContent)
            .andExpect(jsonPath("$.type").value("/problems/ticket-assignment-invalid"))
        assertThat(count("ticket_relations")).isZero()

        readTicket(outsiderBrowser, parent.ticketNumber).andExpect(status().isOk)
        createChild(
            outsiderBrowser,
            parent.ticketNumber,
            0,
            outsiderGroup,
            outsider,
            "권한 없는 child",
            "ALL_TICKETS read가 parent write를 주면 안 됩니다.",
        )
            .andExpect(status().isForbidden)

        val created = createChild(
            ownerBrowser,
            parent.ticketNumber,
            0,
            specialistGroup,
            specialist,
            "정상 child",
            "depth 검증을 위한 정상 child입니다.",
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val childNumber = longField(created, "childTicketNumber")

        mockMvc.perform(
            transferRequest(
                ownerBrowser,
                parent.ticketNumber,
                0,
                """{"expectedVersion":0,"groupId":"$ownerGroup","assigneeId":"$owner"}""",
            ),
        )
            .andExpect(status().isPreconditionFailed)
            .andExpect(jsonPath("$.type").value("/problems/ticket-version-precondition-failed"))
            .andExpect(jsonPath("$.currentVersion").value(1))

        val specialistBrowser = login("rules-specialist@example.com")
        createChild(
            specialistBrowser,
            childNumber,
            0,
            specialistGroup,
            specialist,
            "허용되지 않는 grandchild",
            "초기 relation depth는 1입니다.",
        )
            .andExpect(status().isUnprocessableContent)
            .andExpect(jsonPath("$.type").value("/problems/ticket-relation-invalid"))

        val childId = ticketId(childNumber)
        assertThat(org.assertj.core.api.Assertions.catchThrowable {
            jdbcTemplate.update(
                """
                insert into ticket_relations
                    (id, source_ticket_id, target_ticket_id, relation_type,
                     created_by_actor_type, created_by_actor_id, created_at)
                values (?, ?, ?, 'PARENT_CHILD', 'STAFF', ?, now())
                """.trimIndent(),
                UUID.randomUUID(),
                parent.ticketId,
                parent.ticketId,
                owner,
            )
        }).isInstanceOf(DataAccessException::class.java)
        val secondParent = createCustomerParent("rules-second-parent@example.com", ownerGroup, owner)
        assertThat(org.assertj.core.api.Assertions.catchThrowable {
            jdbcTemplate.update(
                """
                insert into ticket_relations
                    (id, source_ticket_id, target_ticket_id, relation_type,
                     created_by_actor_type, created_by_actor_id, created_at)
                values (?, ?, ?, 'PARENT_CHILD', 'STAFF', ?, now())
                """.trimIndent(),
                UUID.randomUUID(),
                secondParent.ticketId,
                childId,
                owner,
            )
        }).isInstanceOf(DataAccessException::class.java)
    }

    @Test
    fun `customer cannot discover child relation internal body or guessed child number`() {
        val owner = insertStaff("privacy-owner@example.com", "비노출 소유자")
        val specialist = insertStaff("privacy-specialist@example.com", "비노출 전문가")
        val ownerGroup = insertGroup("비노출 부모 그룹", owner)
        val specialistGroup = insertGroup("비노출 전문 그룹", specialist)
        val browser = login("privacy-owner@example.com")
        val parent = createCustomerParent("privacy-parent@example.com", ownerGroup, owner)
        val childResponse = createChild(
            browser,
            parent.ticketNumber,
            0,
            specialistGroup,
            specialist,
            "고객 비노출 fraud 검토",
            "CHILD-INTERNAL-SECRET-DO-NOT-EXPOSE",
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val childNumber = longField(childResponse, "childTicketNumber")
        val childId = ticketId(childNumber)

        val publicBody = mockMvc.perform(
            get("/api/v1/requests/{ticketNumber}", parent.ticketNumber)
                .header("X-Request-Access-Token", parent.accessToken),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.children").doesNotExist())
            .andExpect(jsonPath("$.parent").doesNotExist())
            .andExpect(jsonPath("$.relations").doesNotExist())
            .andReturn().response.contentAsString
        assertThat(publicBody).doesNotContain(childNumber.toString())
            .doesNotContain("CHILD-INTERNAL-SECRET-DO-NOT-EXPOSE")
            .doesNotContain("PARENT_CHILD")

        mockMvc.perform(
            get("/api/v1/requests/{ticketNumber}", childNumber)
                .header("X-Request-Access-Token", parent.accessToken),
        ).andExpect(status().isNotFound)

        jdbcTemplate.update(
            "update request_access_tokens set ticket_id = ? where ticket_id = ?",
            childId,
            parent.ticketId,
        )
        mockMvc.perform(
            get("/api/v1/requests/{ticketNumber}", childNumber)
                .header("X-Request-Access-Token", parent.accessToken),
        ).andExpect(status().isNotFound)
    }

    @Test
    fun `parent solve warning is non blocking and child solve never transitions parent`() {
        val owner = insertStaff("solve-owner@example.com", "해결 소유자")
        val specialist = insertStaff("solve-specialist@example.com", "해결 전문가")
        val ownerGroup = insertGroup("해결 부모 그룹", owner)
        val specialistGroup = insertGroup("해결 전문 그룹", specialist)
        val ownerBrowser = login("solve-owner@example.com")
        val specialistBrowser = login("solve-specialist@example.com")
        val parent = createCustomerParent("solve-parent@example.com", ownerGroup, owner)
        val childResponse = createChild(
            ownerBrowser,
            parent.ticketNumber,
            0,
            specialistGroup,
            specialist,
            "해결 전 child",
            "부모 해결 전에 아직 열린 내부 작업입니다.",
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val childNumber = longField(childResponse, "childTicketNumber")

        performCommand(
            ownerBrowser,
            parent.ticketNumber,
            """{"expectedVersion":1,"changedFields":["status"],"status":"SOLVED"}""",
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.version").value(2))
            .andExpect(jsonPath("$.warnings[0].code").value("OPEN_CHILD_TICKETS"))
            .andExpect(jsonPath("$.warnings[0].count").value(1))
            .andExpect(jsonPath("$.warnings[0].relatedTicketNumbers[0]").value(childNumber))
        assertThat(statusOf(parent.ticketId)).isEqualTo("SOLVED")
        assertThat(statusOf(ticketId(childNumber))).isEqualTo("NEW")

        val parentVersionBeforeChildSolve = versionOf(parent.ticketId)
        performCommand(
            specialistBrowser,
            childNumber,
            """{"expectedVersion":0,"changedFields":["status"],"status":"SOLVED"}""",
        ).andExpect(status().isOk)
        assertThat(statusOf(parent.ticketId)).isEqualTo("SOLVED")
        assertThat(versionOf(parent.ticketId)).isEqualTo(parentVersionBeforeChildSolve)
    }

    @Test
    fun `child creation audit failure rolls back parent child comment relation and both audits`() {
        val owner = insertStaff("rollback-child-owner@example.com", "Child 롤백 소유자")
        val specialist = insertStaff("rollback-child-specialist@example.com", "Child 롤백 전문가")
        val ownerGroup = insertGroup("Child 롤백 부모 그룹", owner)
        val specialistGroup = insertGroup("Child 롤백 전문 그룹", specialist)
        val browser = login("rollback-child-owner@example.com")
        val parent = createCustomerParent("rollback-child-parent@example.com", ownerGroup, owner)
        val ticketCountBefore = count("tickets")
        val auditCountBefore = count("ticket_audits")

        jdbcTemplate.execute(
            """
            create or replace function fail_child_audit_insert()
            returns trigger language plpgsql as ${'$'}${'$'}
            begin
                if new.ticket_id <> '${parent.ticketId}'::uuid then
                    raise exception 'injected child audit failure';
                end if;
                return new;
            end;
            ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            create trigger fail_child_audit_insert
            before insert on ticket_audits
            for each row execute function fail_child_audit_insert()
            """.trimIndent(),
        )
        try {
            createChild(
                browser,
                parent.ticketNumber,
                0,
                specialistGroup,
                specialist,
                "롤백되어야 하는 child",
                "감사 실패 시 관계와 comment도 남으면 안 됩니다.",
            )
                .andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.type").value("/problems/audit-write-unavailable"))
        } finally {
            jdbcTemplate.execute("drop trigger if exists fail_child_audit_insert on ticket_audits")
            jdbcTemplate.execute("drop function if exists fail_child_audit_insert()")
        }

        assertThat(count("tickets")).isEqualTo(ticketCountBefore)
        assertThat(count("ticket_audits")).isEqualTo(auditCountBefore)
        assertThat(count("ticket_relations")).isZero()
        assertThat(versionOf(parent.ticketId)).isZero()
        assertThat(ownership(parent.ticketId)).containsEntry("group_id", ownerGroup)
            .containsEntry("assignee_id", owner)
    }

    private fun transferRequest(
        browser: Browser,
        ticketNumber: Long,
        expectedVersion: Long,
        body: String,
    ): MockHttpServletRequestBuilder = post("/api/v1/agent/tickets/{ticketNumber}/transfer", ticketNumber)
        .session(browser.session)
        .header("X-CSRF-TOKEN", browser.csrfToken)
        .header("If-Match", "\"$expectedVersion\"")
        .header("X-Request-Id", "request-transfer-${UUID.randomUUID()}")
        .header("X-Correlation-Id", "correlation-transfer-child")
        .contentType(MediaType.APPLICATION_JSON)
        .content(body)

    private fun createChild(
        browser: Browser,
        parentNumber: Long,
        expectedVersion: Long,
        groupId: UUID,
        assigneeId: UUID?,
        subject: String,
        body: String,
    ) = mockMvc.perform(
        post("/api/v1/agent/tickets/{ticketNumber}/children", parentNumber)
            .session(browser.session)
            .header("X-CSRF-TOKEN", browser.csrfToken)
            .header("If-Match", "\"$expectedVersion\"")
            .header("X-Request-Id", "request-child-${UUID.randomUUID()}")
            .header("X-Correlation-Id", "correlation-transfer-child")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {
                  "expectedVersion": $expectedVersion,
                  "subject": "$subject",
                  "body": "$body",
                  "groupId": "$groupId",
                  "assigneeId": ${assigneeId?.let { "\"$it\"" } ?: "null"},
                  "priority": "NORMAL"
                }
                """.trimIndent(),
            ),
    )

    private fun performCommand(browser: Browser, ticketNumber: Long, body: String) = mockMvc.perform(
        post("/api/v1/agent/tickets/{ticketNumber}/commands", ticketNumber)
            .session(browser.session)
            .header("X-CSRF-TOKEN", browser.csrfToken)
            .header("X-Request-Id", "request-command-${UUID.randomUUID()}")
            .header("X-Correlation-Id", "correlation-transfer-child")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body),
    )

    private fun readTicket(browser: Browser, ticketNumber: Long) = mockMvc.perform(
        get("/api/v1/agent/tickets/{ticketNumber}", ticketNumber)
            .session(browser.session)
            .header("X-Interaction-Id", UUID.randomUUID())
            .header("X-Deskseed-Read-Intent", "NAVIGATION"),
    )

    private fun createCustomerParent(email: String, groupId: UUID, assigneeId: UUID): ParentFixture {
        val response = mockMvc.perform(
            post("/api/v1/requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name": "Transfer Child 고객",
                      "email": "$email",
                      "subject": "Transfer Child parent",
                      "message": "고객이 보낸 최초 공개 문의"
                    }
                    """.trimIndent(),
                ),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val ticketNumber = longField(response, "ticketNumber")
        val ticketId = ticketId(ticketNumber)
        jdbcTemplate.update(
            "update tickets set group_id = ?, assignee_id = ? where id = ?",
            groupId,
            assigneeId,
            ticketId,
        )
        return ParentFixture(ticketId, ticketNumber, stringField(response, "accessToken"))
    }

    private fun insertStaff(email: String, displayName: String): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            """
            insert into staff_accounts
                (id, email_normalized, email_display, display_name, role, status,
                 password_hash, created_at, updated_at, version)
            values (?, ?, ?, ?, 'AGENT', 'ACTIVE', ?, now(), now(), 0)
            """.trimIndent(),
            id,
            email,
            email,
            displayName,
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

    private fun login(email: String): Browser {
        val csrf = mockMvc.perform(get("/api/v1/agent/csrf")).andExpect(status().isOk).andReturn()
        val token = Regex("\"token\":\"([^\"]+)\"").find(csrf.response.contentAsString)!!.groupValues[1]
        val session = csrf.request.session as MockHttpSession
        val login = mockMvc.perform(
            post("/api/v1/agent/session")
                .session(session)
                .header("X-CSRF-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email","password":"$PASSWORD"}"""),
        ).andExpect(status().isNoContent).andReturn()
        return Browser(login.request.session as MockHttpSession, token)
    }

    private fun eventTypes(auditId: UUID): List<String> = jdbcTemplate.queryForList(
        "select event_type from ticket_audit_events where audit_id = ? order by event_order",
        String::class.java,
        auditId,
    ).filterNotNull()

    private fun auditActor(auditId: UUID): UUID? = jdbcTemplate.queryForObject(
        "select actor_id from ticket_audits where id = ?",
        UUID::class.java,
        auditId,
    )

    private fun ownership(ticketId: UUID): Map<String, Any?> = jdbcTemplate.queryForMap(
        "select group_id, assignee_id from tickets where id = ?",
        ticketId,
    )

    private fun statusOf(ticketId: UUID): String = jdbcTemplate.queryForObject(
        "select status from tickets where id = ?",
        String::class.java,
        ticketId,
    )!!

    private fun versionOf(ticketId: UUID): Long = jdbcTemplate.queryForObject(
        "select version from tickets where id = ?",
        Long::class.java,
        ticketId,
    )!!

    private fun ticketId(ticketNumber: Long): UUID = jdbcTemplate.queryForObject(
        "select id from tickets where ticket_number = ?",
        UUID::class.java,
        ticketNumber,
    )!!

    private fun count(table: String): Long = jdbcTemplate.queryForObject(
        "select count(*) from $table",
        Long::class.java,
    )!!

    private fun uuidField(json: String, field: String): UUID =
        UUID.fromString(Regex("\"$field\":\"([^\"]+)\"").find(json)!!.groupValues[1])

    private fun longField(json: String, field: String): Long =
        Regex("\"$field\":(\\d+)").find(json)!!.groupValues[1].toLong()

    private fun stringField(json: String, field: String): String =
        Regex("\"$field\":\"([^\"]+)\"").find(json)!!.groupValues[1]

    private data class Browser(val session: MockHttpSession, val csrfToken: String)

    private data class ParentFixture(val ticketId: UUID, val ticketNumber: Long, val accessToken: String)

    companion object {
        private const val PASSWORD = "Agent password 42!"
    }
}
