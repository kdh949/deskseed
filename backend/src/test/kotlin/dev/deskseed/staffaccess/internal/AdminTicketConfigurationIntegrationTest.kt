package dev.deskseed.staffaccess.internal

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
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

@SpringBootTest(properties = ["deskseed.staff-auth.bootstrap.enabled=false", "deskseed.sla.breach-scanner.initial-delay=1d"])
@AutoConfigureMockMvc
@Testcontainers
class AdminTicketConfigurationIntegrationTest {
    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun clearConfiguration() {
        jdbc.execute("truncate table ticket_custom_field_values, ticket_tag_assignments, ticket_field_options, ticket_field_definitions cascade")
        jdbc.execute("truncate table ticket_tag_definitions, custom_ticket_statuses cascade")
        jdbc.execute("truncate table admin_security_audit_events")
    }

    @Test
    fun `admin manages typed select fields with stable options optimistic versions and audit`() {
        val browser = browser("ADMIN")
        val fieldResponse = mockMvc.perform(
            post("/api/v1/admin/ticket-fields")
                .session(browser.session).csrf(browser).contentType(MediaType.APPLICATION_JSON)
                .content(fieldJson("payment.method")),
        )
            .andExpect(status().isCreated)
            .andExpect(header().string("ETag", "\"1\""))
            .andExpect(jsonPath("$.machineKey").value("payment.method"))
            .andExpect(jsonPath("$.type").value("SINGLE_SELECT"))
            .andReturn().response.contentAsString
        val fieldId = UUID.fromString(stringField(fieldResponse, "id"))

        val cardResponse = mockMvc.perform(
            post("/api/v1/admin/ticket-fields/{fieldId}/options", fieldId)
                .session(browser.session).csrf(browser).contentType(MediaType.APPLICATION_JSON)
                .content("""{"machineKey":"card","staffLabel":"카드","customerLabel":"카드","order":10}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(header().string("ETag", "\"1\""))
            .andReturn().response.contentAsString
        val cardId = UUID.fromString(stringField(cardResponse, "id"))

        val bankResponse = mockMvc.perform(
            post("/api/v1/admin/ticket-fields/{fieldId}/options", fieldId)
                .session(browser.session).csrf(browser).contentType(MediaType.APPLICATION_JSON)
                .content("""{"machineKey":"bank-transfer","staffLabel":"계좌 이체","order":20}"""),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val bankId = UUID.fromString(stringField(bankResponse, "id"))

        mockMvc.perform(
            put("/api/v1/admin/ticket-fields/{fieldId}/options/order", fieldId)
                .session(browser.session).csrf(browser).contentType(MediaType.APPLICATION_JSON)
                .content("""{"ids":["$bankId","$cardId"]}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].id").value(bankId.toString()))
            .andExpect(jsonPath("$[0].order").value(0))
            .andExpect(jsonPath("$[1].id").value(cardId.toString()))
            .andExpect(jsonPath("$[1].order").value(1))

        mockMvc.perform(
            put("/api/v1/admin/ticket-fields/{fieldId}/options/{optionId}", fieldId, cardId)
                .session(browser.session).csrf(browser).header("If-Match", "\"2\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"staffLabel":"카드 결제","customerLabel":"카드","active":false}"""),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("ETag", "\"3\""))
            .andExpect(jsonPath("$.active").value(false))

        mockMvc.perform(
            put("/api/v1/admin/ticket-fields/{fieldId}/activation", fieldId)
                .session(browser.session).csrf(browser).header("If-Match", "\"1\"")
                .contentType(MediaType.APPLICATION_JSON).content("""{"active":false}"""),
        ).andExpect(status().isOk).andExpect(header().string("ETag", "\"2\""))

        mockMvc.perform(
            put("/api/v1/admin/ticket-fields/{fieldId}/activation", fieldId)
                .session(browser.session).csrf(browser).header("If-Match", "\"1\"")
                .contentType(MediaType.APPLICATION_JSON).content("""{"active":true}"""),
        ).andExpect(status().isPreconditionFailed).andExpect(jsonPath("$.currentVersion").value(2))

        assertThat(jdbc.queryForList(
            "select event_type from admin_security_audit_events where target_id = ? order by occurred_at, id",
            String::class.java,
            fieldId,
        )).containsExactly("TICKET_FIELD_CREATED", "TICKET_FIELD_OPTIONS_REORDERED", "TICKET_FIELD_DEACTIVATED")
        assertThat(jdbc.queryForObject(
            "select count(*) from admin_security_audit_events where target_type = 'TICKET_FIELD_OPTION'",
            Long::class.java,
        )).isEqualTo(3)
    }

    @Test
    fun `non-admin is denied and required audit failure rolls back field creation`() {
        val agent = browser("AGENT")
        mockMvc.perform(get("/api/v1/admin/ticket-fields").session(agent.session))
            .andExpect(status().isForbidden)

        val admin = browser("ADMIN")
        jdbc.execute(
            """
            create or replace function fail_ticket_configuration_audit_insert()
            returns trigger language plpgsql as ${'$'}${'$'}
            begin
                if new.event_type = 'TICKET_FIELD_CREATED' then raise exception 'injected configuration audit failure'; end if;
                return new;
            end;
            ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbc.execute(
            "create trigger fail_ticket_configuration_audit_insert before insert on admin_security_audit_events for each row execute function fail_ticket_configuration_audit_insert()",
        )
        try {
            mockMvc.perform(
                post("/api/v1/admin/ticket-fields")
                    .session(admin.session).csrf(admin).contentType(MediaType.APPLICATION_JSON)
                    .content(fieldJson("payment.audit-rollback")),
            ).andExpect(status().isServiceUnavailable)
        } finally {
            jdbc.execute("drop trigger if exists fail_ticket_configuration_audit_insert on admin_security_audit_events")
            jdbc.execute("drop function if exists fail_ticket_configuration_audit_insert()")
        }
        assertThat(jdbc.queryForObject(
            "select count(*) from ticket_field_definitions where machine_key = 'payment.audit-rollback'",
            Long::class.java,
        )).isZero()
    }

    @Test
    fun `admin previews validates and publishes immutable conditional form versions`() {
        val browser = browser("ADMIN")
        val fieldResponse = mockMvc.perform(
            post("/api/v1/admin/ticket-fields")
                .session(browser.session).csrf(browser).contentType(MediaType.APPLICATION_JSON)
                .content(fieldJson("payment.confirmed")),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val fieldId = UUID.fromString(stringField(fieldResponse, "id"))
        val formResponse = mockMvc.perform(
            post("/api/v1/admin/ticket-forms")
                .session(browser.session).csrf(browser).contentType(MediaType.APPLICATION_JSON)
                .content(formJson(fieldId)),
        )
            .andExpect(status().isCreated)
            .andExpect(header().string("ETag", "\"1\""))
            .andExpect(jsonPath("$.lifecycle").value("DRAFT"))
            .andReturn().response.contentAsString
        val formId = UUID.fromString(stringField(formResponse, "id"))

        mockMvc.perform(
            post("/api/v1/admin/ticket-forms/{formId}/preview", formId)
                .session(browser.session).csrf(browser).contentType(MediaType.APPLICATION_JSON)
                .content("""{"actorKind":"CUSTOMER","ticketKind":"CUSTOMER_REQUEST","statusCategory":"NEW"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.fields[0].visible").value(true))
            .andExpect(jsonPath("$.fields[0].editable").value(false))

        mockMvc.perform(
            post("/api/v1/admin/ticket-forms/validate")
                .session(browser.session).csrf(browser).contentType(MediaType.APPLICATION_JSON)
                .content(cyclicFormJson(fieldId)),
        ).andExpect(status().isOk).andExpect(jsonPath("$.valid").value(false))
            .andExpect(jsonPath("$.issues[0].code").value("CONDITIONAL_FIELD_CYCLE"))

        mockMvc.perform(
            post("/api/v1/admin/ticket-forms/{formId}/publish", formId)
                .session(browser.session).csrf(browser).header("If-Match", "\"1\""),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("ETag", "\"2\""))
            .andExpect(jsonPath("$.lifecycle").value("PUBLISHED"))

        assertThatThrownBy {
            jdbc.update("update ticket_form_versions set definition_json = '{}'::jsonb where form_id = ? and version = 1", formId)
        }.hasMessageContaining("ticket_form_versions rows are immutable")
        assertThat(jdbc.queryForList(
            "select event_type from admin_security_audit_events where target_id = ? order by occurred_at, id",
            String::class.java,
            formId,
        )).containsExactly("TICKET_FORM_DRAFT_CREATED", "TICKET_FORM_PUBLISHED")
    }

    @Test
    fun `admin normalizes tag catalog and preserves fixed status categories`() {
        val browser = browser("ADMIN")
        val fieldResponse = mockMvc.perform(
            post("/api/v1/admin/ticket-fields")
                .session(browser.session).csrf(browser).contentType(MediaType.APPLICATION_JSON)
                .content(fieldJson("status.form-field")),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val formFieldId = UUID.fromString(stringField(fieldResponse, "id"))
        val formResponse = mockMvc.perform(
            post("/api/v1/admin/ticket-forms")
                .session(browser.session).csrf(browser).contentType(MediaType.APPLICATION_JSON)
                .content(formJson(formFieldId)),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val formId = UUID.fromString(stringField(formResponse, "id"))
        val tagResponse = mockMvc.perform(
            post("/api/v1/admin/ticket-tags")
                .session(browser.session).csrf(browser).contentType(MediaType.APPLICATION_JSON)
                .content("""{"value":"Payment","label":"결제","active":true}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(header().string("ETag", "\"1\""))
            .andExpect(jsonPath("$.value").value("payment"))
            .andReturn().response.contentAsString
        val tagId = UUID.fromString(stringField(tagResponse, "id"))
        mockMvc.perform(
            put("/api/v1/admin/ticket-tags/{tagId}", tagId)
                .session(browser.session).csrf(browser).header("If-Match", "\"1\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"value":"payment","label":"결제 확인","active":false}"""),
        ).andExpect(status().isOk).andExpect(header().string("ETag", "\"2\""))
            .andExpect(jsonPath("$.active").value(false))

        val pendingResponse = mockMvc.perform(
            post("/api/v1/admin/ticket-statuses")
                .session(browser.session).csrf(browser).contentType(MediaType.APPLICATION_JSON)
                .content(statusJson("waiting-for-customer", "PENDING", 20, true, formId)),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val pendingId = UUID.fromString(stringField(pendingResponse, "id"))
        mockMvc.perform(
            post("/api/v1/admin/ticket-statuses")
                .session(browser.session).csrf(browser).contentType(MediaType.APPLICATION_JSON)
                .content(statusJson("also-pending", "PENDING", 30, true)),
        ).andExpect(status().isConflict)
        mockMvc.perform(
            post("/api/v1/admin/ticket-statuses")
                .session(browser.session).csrf(browser).contentType(MediaType.APPLICATION_JSON)
                .content(statusJson("under-review", "OPEN", 10, false)),
        ).andExpect(status().isCreated).andReturn().response.contentAsString.let { response ->
            val openId = UUID.fromString(stringField(response, "id"))
            mockMvc.perform(
                put("/api/v1/admin/ticket-statuses/order")
                    .session(browser.session).csrf(browser).contentType(MediaType.APPLICATION_JSON)
                    .content("""{"ids":["$openId","$pendingId"]}"""),
            ).andExpect(status().isOk).andExpect(jsonPath("$[0].id").value(openId.toString()))
                .andExpect(jsonPath("$[1].id").value(pendingId.toString()))
        }
        mockMvc.perform(
            post("/api/v1/admin/ticket-statuses")
                .session(browser.session).csrf(browser).contentType(MediaType.APPLICATION_JSON)
                .content(statusJson("closed-label", "CLOSED", 40, false)),
        ).andExpect(status().isBadRequest)
    }

    private fun fieldJson(machineKey: String) =
        """{"machineKey":"$machineKey","type":"SINGLE_SELECT","staffLabel":"결제 수단","customerLabel":"결제 수단","customerVisible":true,"customerEditable":true,"agentVisible":true,"agentEditable":true,"searchable":true,"analyticsEligible":false,"sensitive":false,"validation":{}}"""

    private fun formJson(fieldId: UUID) =
        """{"name":"결제 문의","description":"결제 확인","defaultForCustomer":true,"defaultForAgent":false,"placements":[{"fieldId":"$fieldId","order":10,"customer":{"visible":true,"editable":true,"required":false},"agent":{"visible":true,"editable":true,"required":false}}],"conditionalRules":[{"id":"${UUID.randomUUID()}","priority":10,"condition":{"schemaVersion":1,"root":{"kind":"LEAF","typeKey":"ticket.form.fact-equals","schemaVersion":1,"config":{"fact":"actorKind","equals":"CUSTOMER"}}},"effects":[{"fieldId":"$fieldId","behavior":"READ_ONLY"}]}],"allowedCustomStatusIds":[]}"""

    private fun cyclicFormJson(fieldId: UUID) =
        """{"name":"순환 검증","placements":[{"fieldId":"$fieldId","order":10,"customer":{"visible":true,"editable":true,"required":false},"agent":{"visible":true,"editable":true,"required":false}}],"conditionalRules":[{"id":"${UUID.randomUUID()}","priority":10,"condition":{"schemaVersion":1,"root":{"kind":"LEAF","typeKey":"ticket.form.fact-equals","schemaVersion":1,"config":{"fact":"field.$fieldId","equals":"true"}}},"effects":[{"fieldId":"$fieldId","behavior":"HIDE"}]}]}"""

    private fun statusJson(
        machineKey: String,
        category: String,
        order: Int,
        defaultForCategory: Boolean,
        allowedFormId: UUID? = null,
    ) =
        """{"machineKey":"$machineKey","agentLabel":"$machineKey","customerLabel":"$machineKey","statusCategory":"$category","active":true,"order":$order,"defaultForCategory":$defaultForCategory,"allowedFormIds":${allowedFormId?.let { "[\"$it\"]" } ?: "[]"}}"""

    private fun browser(role: String): Browser {
        val email = "configuration-${role.lowercase()}-${UUID.randomUUID()}@example.com"
        val password = "Configuration password 42!"
        jdbc.update(
            """
            insert into staff_accounts
                (id, email_normalized, email_display, display_name, role, status,
                 password_hash, created_at, updated_at, version)
            values (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, 0)
            """.trimIndent(),
            UUID.randomUUID(), email.lowercase(), email,
            if (role == "ADMIN") "구성 관리자" else "구성 상담사", role,
            BCryptPasswordEncoder(4).encode(password),
            Timestamp.from(Instant.parse("2026-08-10T00:00:00Z")),
            Timestamp.from(Instant.parse("2026-08-10T00:00:00Z")),
        )
        val csrf = mockMvc.perform(get("/api/v1/agent/csrf")).andExpect(status().isOk).andReturn()
        val token = stringField(csrf.response.contentAsString, "token")
        val session = csrf.request.session as MockHttpSession
        val login = mockMvc.perform(
            post("/api/v1/agent/session").session(session).header("X-CSRF-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email","password":"$password"}"""),
        ).andExpect(status().isNoContent).andReturn()
        return Browser(login.request.session as MockHttpSession, token)
    }

    private fun MockHttpServletRequestBuilder.csrf(browser: Browser) = header("X-CSRF-TOKEN", browser.csrfToken)

    private fun stringField(json: String, field: String): String =
        Regex("\\\"$field\\\":\\\"([^\\\"]+)\\\"").find(json)!!.groupValues[1]

    private data class Browser(val session: MockHttpSession, val csrfToken: String)

    companion object {
        @Container @ServiceConnection @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
    }
}
