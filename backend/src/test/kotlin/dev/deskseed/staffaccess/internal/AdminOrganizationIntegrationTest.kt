package dev.deskseed.staffaccess.internal

import dev.deskseed.organization.OrganizationAdministration
import dev.deskseed.organization.StaffRole
import jakarta.persistence.EntityManagerFactory
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.hibernate.SessionFactory
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@dev.deskseed.testsupport.integration.DeskseedSpringIntegrationTest(
    properties = [
        "deskseed.staff-auth.bootstrap.enabled=false",
        "spring.jpa.properties.hibernate.generate_statistics=true",
    ],
)
@AutoConfigureMockMvc
@dev.deskseed.testsupport.category.IntegrationTest
class AdminOrganizationIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var administration: OrganizationAdministration

    @Autowired
    private lateinit var entityManagerFactory: EntityManagerFactory

    @BeforeEach
    fun clearState() {
        jdbcTemplate.execute("truncate table staff_authority_grants, admin_security_audit_events")
        jdbcTemplate.execute(
            "truncate table customer_registration_intent_consents, customer_registration_intents, " +
                "customer_consent_acceptances, customer_consent_policy_versions, customer_consent_policies cascade",
        )
        jdbcTemplate.update("delete from request_access_tokens")
        jdbcTemplate.update("delete from ticket_comments")
        jdbcTemplate.update("delete from tickets")
        jdbcTemplate.update("delete from customers")
        jdbcTemplate.update("delete from group_memberships")
        jdbcTemplate.update("delete from support_groups")
        jdbcTemplate.update("delete from staff_login_throttles")
        jdbcTemplate.update("delete from staff_accounts")
        jdbcTemplate.update(
            "update system_settings set customer_access_mode = 'ANONYMOUS_ALLOWED', version = 0, updated_at = now() where id = 1",
        )
    }

    @Test
    fun `admin changes customer access mode with optimistic version and atomic security audit`() {
        insertStaff("access-admin@example.com", "Access admin password 42", "ADMIN")
        val browser = login("access-admin@example.com", "Access admin password 42")

        mockMvc.perform(get("/api/v1/admin/settings/customer-access-mode").session(browser.session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.mode").value("ANONYMOUS_ALLOWED"))
            .andExpect(jsonPath("$.version").value(0))

        mockMvc.perform(
            put("/api/v1/admin/settings/customer-access-mode")
                .session(browser.session)
                .csrf(browser)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"mode":"REGISTRATION_REQUIRED","expectedVersion":0}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.mode").value("REGISTRATION_REQUIRED"))
            .andExpect(jsonPath("$.version").value(1))

        assertThat(
            jdbcTemplate.queryForMap(
                """
                select event_type, actor_type, source, metadata_json
                from admin_security_audit_events
                where event_type = 'CUSTOMER_ACCESS_MODE_CHANGED'
                """.trimIndent(),
            ),
        ).containsEntry("event_type", "CUSTOMER_ACCESS_MODE_CHANGED")
            .containsEntry("actor_type", "STAFF")
            .containsEntry("source", "ADMIN_UI")

        mockMvc.perform(
            put("/api/v1/admin/settings/customer-access-mode")
                .session(browser.session)
                .csrf(browser)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"mode":"REGISTRATION_OPTIONAL","expectedVersion":0}"""),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("STALE_CUSTOMER_ACCESS_MODE"))
            .andExpect(jsonPath("$.currentVersion").value(1))
    }

    @Test
    fun `customer access mode audit failure rolls setting back`() {
        insertStaff("access-rollback@example.com", "Access rollback password 42", "ADMIN")
        val browser = login("access-rollback@example.com", "Access rollback password 42")
        jdbcTemplate.execute(
            """
            create or replace function fail_customer_access_mode_audit()
            returns trigger language plpgsql as ${'$'}${'$'}
            begin raise exception 'injected access mode audit failure'; end;
            ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            "create trigger fail_customer_access_mode_audit before insert on admin_security_audit_events for each row execute function fail_customer_access_mode_audit()",
        )
        try {
            mockMvc.perform(
                put("/api/v1/admin/settings/customer-access-mode")
                    .session(browser.session)
                    .csrf(browser)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"mode":"REGISTRATION_REQUIRED","expectedVersion":0}"""),
            ).andExpect(status().isServiceUnavailable)
        } finally {
            jdbcTemplate.execute("drop trigger if exists fail_customer_access_mode_audit on admin_security_audit_events")
            jdbcTemplate.execute("drop function if exists fail_customer_access_mode_audit()")
        }

        assertThat(
            jdbcTemplate.queryForMap("select customer_access_mode, version from system_settings where id = 1"),
        ).containsEntry("customer_access_mode", "ANONYMOUS_ALLOWED").containsEntry("version", 0L)
    }

    @Test
    fun `agent receives 403 for admin API and denial is security audited`() {
        insertStaff("agent@example.com", "Agent password 42", "AGENT")
        val browser = login("agent@example.com", "Agent password 42")

        mockMvc.perform(get("/api/v1/admin/staff").session(browser.session))
            .andExpect(status().isForbidden)
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.type").value("/problems/staff-access-denied"))

        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from admin_security_audit_events where event_type = 'ACCESS_DENIED'",
                Long::class.java,
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `agent cannot bypass method authorization by calling application service`() {
        val principal = StaffPrincipal(
            id = UUID.randomUUID(),
            email = "agent@example.com",
            displayName = "상담사",
            role = StaffRole.AGENT,
        )
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken.authenticated(
            principal,
            null,
            listOf(SimpleGrantedAuthority("ROLE_AGENT")),
        )
        try {
            assertThatThrownBy { administration.listStaff() }
                .isInstanceOf(AccessDeniedException::class.java)
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    @Test
    fun `admin list endpoints are bounded and expose page metadata`() {
        insertStaff("paged-admin@example.com", "Paged admin password 42", "ADMIN")
        val browser = login("paged-admin@example.com", "Paged admin password 42")
        val staffIds = (1..6).map { index ->
            insertStaff("paged-agent-$index@example.com", "Paged agent password 42", "AGENT")
        }
        val groupIds = (1..6).map { index -> insertGroup("페이지 그룹 $index") }
        staffIds.forEach { staffId -> insertMembership(groupIds.first(), staffId) }

        mockMvc.perform(get("/api/v1/admin/staff?page=0&size=3").session(browser.session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(3))
            .andExpect(header().string("X-Page-Number", "0"))
            .andExpect(header().string("X-Page-Size", "3"))
            .andExpect(header().string("X-Total-Count", "7"))
            .andExpect(header().string("X-Total-Pages", "3"))

        mockMvc.perform(get("/api/v1/admin/groups?page=1&size=2").session(browser.session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(header().string("X-Page-Number", "1"))
            .andExpect(header().string("X-Page-Size", "2"))
            .andExpect(header().string("X-Total-Count", "6"))
            .andExpect(header().string("X-Total-Pages", "3"))

        mockMvc.perform(
            get("/api/v1/admin/groups/{groupId}/members?page=0&size=4", groupIds.first())
                .session(browser.session),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(4))
            .andExpect(header().string("X-Total-Count", "6"))
            .andExpect(header().string("X-Total-Pages", "2"))

        mockMvc.perform(get("/api/v1/admin/staff?size=101").session(browser.session))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `admin list query counts stay constant as rows grow`() {
        val adminId = insertStaff("query-admin@example.com", "Query admin password 42", "ADMIN")
        val browser = login("query-admin@example.com", "Query admin password 42")
        val groupIds = (1..6).map { index -> insertGroup("쿼리 그룹 ${index.toString().padStart(2, '0')}") }
        val staffIds = (1..6).map { index ->
            insertStaff("query-agent-$index@example.com", "Query agent password 42", "AGENT")
        }
        (staffIds + adminId).forEach { staffId -> insertMembership(groupIds.first(), staffId) }

        val initialStaffQueries = queryCount {
            mockMvc.perform(get("/api/v1/admin/staff?page=0&size=5").session(browser.session))
                .andExpect(status().isOk)
        }
        val initialGroupQueries = queryCount {
            mockMvc.perform(get("/api/v1/admin/groups?page=0&size=5").session(browser.session))
                .andExpect(status().isOk)
        }
        val initialMemberQueries = queryCount {
            mockMvc.perform(
                get("/api/v1/admin/groups/{groupId}/members?page=0&size=5", groupIds.first())
                    .session(browser.session),
            ).andExpect(status().isOk)
        }

        val addedStaffIds = (7..26).map { index ->
            insertStaff("query-agent-$index@example.com", "Query agent password 42", "AGENT")
        }
        addedStaffIds.forEach { staffId -> insertMembership(groupIds.first(), staffId) }
        (7..26).forEach { index -> insertGroup("쿼리 그룹 ${index.toString().padStart(2, '0')}") }

        val expandedStaffQueries = queryCount {
            mockMvc.perform(get("/api/v1/admin/staff?page=0&size=5").session(browser.session))
                .andExpect(status().isOk)
        }
        val expandedGroupQueries = queryCount {
            mockMvc.perform(get("/api/v1/admin/groups?page=0&size=5").session(browser.session))
                .andExpect(status().isOk)
        }
        val expandedMemberQueries = queryCount {
            mockMvc.perform(
                get("/api/v1/admin/groups/{groupId}/members?page=0&size=5", groupIds.first())
                    .session(browser.session),
            ).andExpect(status().isOk)
        }

        assertThat(expandedStaffQueries).isEqualTo(initialStaffQueries)
        assertThat(expandedGroupQueries).isEqualTo(initialGroupQueries)
        assertThat(expandedMemberQueries).isEqualTo(initialMemberQueries)
        assertThat(initialStaffQueries).isLessThanOrEqualTo(10)
        assertThat(initialGroupQueries).isLessThanOrEqualTo(10)
        assertThat(initialMemberQueries).isLessThanOrEqualTo(10)
    }

    @Test
    fun `admin manages staff groups and memberships while duplicate membership conflicts`() {
        val adminId = insertStaff("admin@example.com", "Admin password 42", "ADMIN")
        val browser = login("admin@example.com", "Admin password 42")

        val createdStaffBody = mockMvc.perform(
            post("/api/v1/admin/staff")
                .session(browser.session)
                .csrf(browser)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"email":"new-agent@example.com","displayName":"새 상담사",
                     "role":"AGENT","password":"Temporary 42!pass"}
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.email").value("new-agent@example.com"))
            .andExpect(jsonPath("$.role").value("AGENT"))
            .andExpect(jsonPath("$.password").doesNotExist())
            .andReturn().response.contentAsString
        val agentId = uuidField(createdStaffBody, "id")

        val createdGroupBody = mockMvc.perform(
            post("/api/v1/admin/groups")
                .session(browser.session)
                .csrf(browser)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"고객 지원"}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andReturn().response.contentAsString
        val groupId = uuidField(createdGroupBody, "id")

        mockMvc.perform(
            patch("/api/v1/admin/groups/{groupId}", groupId)
                .session(browser.session)
                .csrf(browser)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"결제 지원"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("결제 지원"))

        mockMvc.perform(
            post("/api/v1/admin/groups/{groupId}/members", groupId)
                .session(browser.session)
                .csrf(browser)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"staffId":"$agentId"}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.staffDisplayName").value("새 상담사"))

        mockMvc.perform(
            post("/api/v1/admin/groups/{groupId}/members", groupId)
                .session(browser.session)
                .csrf(browser)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"staffId":"$agentId"}"""),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("DUPLICATE_MEMBERSHIP"))

        mockMvc.perform(get("/api/v1/admin/groups/{groupId}/members", groupId).session(browser.session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].staffId").value(agentId.toString()))

        mockMvc.perform(
            delete("/api/v1/admin/groups/{groupId}/members/{staffId}", groupId, agentId)
                .session(browser.session)
                .csrf(browser),
        ).andExpect(status().isNoContent)
        mockMvc.perform(
            delete("/api/v1/admin/groups/{groupId}", groupId)
                .session(browser.session)
                .csrf(browser),
        ).andExpect(status().isNoContent)
        mockMvc.perform(
            delete("/api/v1/admin/staff/{staffId}", agentId)
                .session(browser.session)
                .csrf(browser),
        ).andExpect(status().isNoContent)

        assertThat(
            jdbcTemplate.queryForList(
                "select event_type from admin_security_audit_events where actor_id = ? order by occurred_at, id",
                String::class.java,
                adminId,
            ),
        ).contains("STAFF_CREATED", "STAFF_DISABLED", "GROUP_CREATED", "GROUP_CHANGED", "GROUP_MEMBERSHIP_CHANGED")
        val storedHash = jdbcTemplate.queryForObject(
            "select password_hash from staff_accounts where id = ?",
            String::class.java,
            agentId,
        )
        assertThat(storedHash).doesNotContain("Temporary 42!pass")
    }

    @Test
    fun `admin write requires csrf and self disable is rejected`() {
        val adminId = insertStaff("admin@example.com", "Admin password 42", "ADMIN")
        val browser = login("admin@example.com", "Admin password 42")

        mockMvc.perform(
            post("/api/v1/admin/groups")
                .session(browser.session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"CSRF 우회"}"""),
        ).andExpect(status().isForbidden)
        assertThat(jdbcTemplate.queryForObject("select count(*) from support_groups", Long::class.java)).isZero()

        mockMvc.perform(
            delete("/api/v1/admin/staff/{staffId}", adminId)
                .session(browser.session)
                .csrf(browser),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("SELF_DISABLE_NOT_ALLOWED"))
    }

    @Test
    fun `group names conflict case insensitively through the admin API`() {
        insertStaff("admin@example.com", "Admin password 42", "ADMIN")
        val browser = login("admin@example.com", "Admin password 42")

        mockMvc.perform(
            post("/api/v1/admin/groups")
                .session(browser.session)
                .csrf(browser)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Support"}"""),
        ).andExpect(status().isCreated)

        mockMvc.perform(
            post("/api/v1/admin/groups")
                .session(browser.session)
                .csrf(browser)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":" support "}"""),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("DUPLICATE_GROUP_NAME"))
    }

    @Test
    fun `current assignment blocks membership staff and group deactivation`() {
        insertStaff("admin@example.com", "Admin password 42", "ADMIN")
        val agentId = insertStaff("agent@example.com", "Agent password 42", "AGENT")
        val browser = login("admin@example.com", "Admin password 42")
        val groupId = createGroup(browser, "배정 그룹")
        addMembership(browser, groupId, agentId)
        insertAssignedTicket(groupId, agentId)

        mockMvc.perform(
            delete("/api/v1/admin/groups/{groupId}/members/{staffId}", groupId, agentId)
                .session(browser.session)
                .csrf(browser),
        ).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("MEMBER_HAS_ASSIGNED_TICKETS"))
        mockMvc.perform(
            delete("/api/v1/admin/staff/{staffId}", agentId)
                .session(browser.session)
                .csrf(browser),
        ).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("STAFF_HAS_ASSIGNED_TICKETS"))
        mockMvc.perform(
            delete("/api/v1/admin/groups/{groupId}", groupId)
                .session(browser.session)
                .csrf(browser),
        ).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("GROUP_HAS_ASSIGNED_TICKETS"))
    }

    @Test
    fun `admin mutation rolls back when canonical audit insert fails`() {
        insertStaff("admin@example.com", "Admin password 42", "ADMIN")
        val browser = login("admin@example.com", "Admin password 42")
        jdbcTemplate.execute(
            """
            create or replace function fail_admin_audit_insert() returns trigger language plpgsql as ${'$'}${'$'}
            begin raise exception 'injected audit failure'; end; ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            "create trigger fail_admin_audit before insert on admin_security_audit_events for each row execute function fail_admin_audit_insert()",
        )
        try {
            mockMvc.perform(
                post("/api/v1/admin/groups")
                    .session(browser.session)
                    .csrf(browser)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"롤백 그룹"}"""),
            )
                .andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.type").value("/problems/admin-audit-unavailable"))
            assertThat(
                jdbcTemplate.queryForObject(
                    "select count(*) from support_groups where name = '롤백 그룹'",
                    Long::class.java,
                ),
            ).isZero()
        } finally {
            jdbcTemplate.execute("drop trigger if exists fail_admin_audit on admin_security_audit_events")
            jdbcTemplate.execute("drop function if exists fail_admin_audit_insert()")
        }
    }

    private fun createGroup(browser: Browser, name: String): UUID {
        val body = mockMvc.perform(
            post("/api/v1/admin/groups")
                .session(browser.session)
                .csrf(browser)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"$name"}"""),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return uuidField(body, "id")
    }

    private fun addMembership(browser: Browser, groupId: UUID, staffId: UUID) {
        mockMvc.perform(
            post("/api/v1/admin/groups/{groupId}/members", groupId)
                .session(browser.session)
                .csrf(browser)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"staffId":"$staffId"}"""),
        ).andExpect(status().isCreated)
    }

    private fun insertAssignedTicket(groupId: UUID, staffId: UUID) {
        val customerId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            insert into customers (id, name, email_normalized, email_display, created_at, updated_at)
            values (?, '배정 고객', ?, ?, now(), now())
            """.trimIndent(),
            customerId,
            "assigned-${UUID.randomUUID()}@example.com",
            "assigned@example.com",
        )
        jdbcTemplate.update(
            """
            insert into tickets
                (id, ticket_number, requester_id, kind, subject, status, priority,
                 group_id, assignee_id, channel, version, created_at, updated_at)
            values (?, nextval('ticket_number_seq'), ?, 'CUSTOMER_REQUEST', '배정 티켓', 'OPEN', 'NORMAL',
                    ?, ?, 'WEB', 0, now(), now())
            """.trimIndent(),
            UUID.randomUUID(),
            customerId,
            groupId,
            staffId,
        )
    }

    private fun insertGroup(name: String): UUID = UUID.randomUUID().also { id ->
        jdbcTemplate.update(
            """
            insert into support_groups (id, name, status, created_at, updated_at)
            values (?, ?, 'ACTIVE', now(), now())
            """.trimIndent(),
            id,
            name,
        )
    }

    private fun insertMembership(groupId: UUID, staffId: UUID) {
        jdbcTemplate.update(
            """
            insert into group_memberships (id, group_id, staff_id, status, created_at, updated_at)
            values (?, ?, ?, 'ACTIVE', now(), now())
            """.trimIndent(),
            UUID.randomUUID(),
            groupId,
            staffId,
        )
    }

    private fun queryCount(action: () -> Unit): Long {
        val statistics = entityManagerFactory.unwrap(SessionFactory::class.java).statistics
        statistics.clear()
        action()
        return statistics.prepareStatementCount
    }

    private fun login(email: String, password: String): Browser {
        val csrfResult = mockMvc.perform(get("/api/v1/agent/csrf")).andExpect(status().isOk).andReturn()
        val token = Regex("\"token\":\"([^\"]+)\"").find(csrfResult.response.contentAsString)!!.groupValues[1]
        val session = csrfResult.request.session as MockHttpSession
        val loginResult = mockMvc.perform(
            post("/api/v1/agent/session")
                .session(session)
                .header("X-CSRF-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email","password":"$password"}"""),
        ).andExpect(status().isNoContent).andReturn()
        return Browser(loginResult.request.session as MockHttpSession, token)
    }

    private fun insertStaff(email: String, password: String, role: String): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            """
            insert into staff_accounts
                (id, email_normalized, email_display, display_name, role, status,
                 password_hash, created_at, updated_at, version)
            values (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, 0)
            """.trimIndent(),
            id,
            email.lowercase(),
            email,
            if (role == "ADMIN") "관리자" else "상담사",
            role,
            BCryptPasswordEncoder(4).encode(password),
            Timestamp.from(Instant.parse("2026-08-10T00:00:00Z")),
            Timestamp.from(Instant.parse("2026-08-10T00:00:00Z")),
        )
        return id
    }

    private fun org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder.csrf(
        browser: Browser,
    ) = header("X-CSRF-TOKEN", browser.csrfToken)

    private fun uuidField(json: String, field: String): UUID = UUID.fromString(
        Regex("\"$field\":\"([^\"]+)\"").find(json)!!.groupValues[1],
    )

    private data class Browser(val session: MockHttpSession, val csrfToken: String)

}
