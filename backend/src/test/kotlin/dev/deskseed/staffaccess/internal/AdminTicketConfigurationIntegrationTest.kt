package dev.deskseed.staffaccess.internal

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
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
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@dev.deskseed.testsupport.integration.DeskseedSpringIntegrationTest
@AutoConfigureMockMvc
@dev.deskseed.testsupport.category.IntegrationTest
class AdminTicketConfigurationIntegrationTest {
    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var requestService: dev.deskseed.portal.internal.PublicRequestApplicationService
    @Autowired private lateinit var mapper: tools.jackson.databind.ObjectMapper

    @BeforeEach
    fun clearConfiguration() {
        jdbc.execute("truncate table ticket_custom_field_values, ticket_tag_assignments, ticket_field_options, ticket_form_versions, ticket_forms, ticket_field_definitions cascade")
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
                .content("""{"machineKey":"card","staffLabel":"카드","customerLabel":"카드","order":0}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(header().string("ETag", "\"1\""))
            .andReturn().response.contentAsString
        val cardId = UUID.fromString(stringField(cardResponse, "id"))

        val bankResponse = mockMvc.perform(
            post("/api/v1/admin/ticket-fields/{fieldId}/options", fieldId)
                .session(browser.session).csrf(browser).contentType(MediaType.APPLICATION_JSON)
                .content("""{"machineKey":"bank-transfer","staffLabel":"계좌 이체","order":1000000}"""),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val bankId = UUID.fromString(stringField(bankResponse, "id"))
        val (lowerId, higherId) = listOf(cardId, bankId).sortedBy(UUID::toString)
        jdbc.update("update ticket_field_options set display_order = 2000000 where id = ?", cardId)
        jdbc.update("update ticket_field_options set display_order = 0 where id = ?", lowerId)
        jdbc.update("update ticket_field_options set display_order = 1000000 where id = ?", higherId)

        mockMvc.perform(
            put("/api/v1/admin/ticket-fields/{fieldId}/options/order", fieldId)
                .session(browser.session).csrf(browser).contentType(MediaType.APPLICATION_JSON)
                .content("""{"ids":["$higherId","$lowerId"]}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].id").value(higherId.toString()))
            .andExpect(jsonPath("$[0].order").value(0))
            .andExpect(jsonPath("$[1].id").value(lowerId.toString()))
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

        mockMvc.perform(
            get("/api/v1/customer/ticket-forms"),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.formId").value(formId.toString()))
            .andExpect(jsonPath("$.fields[0].field.label").value("결제 수단"))
            .andExpect(jsonPath("$.fields[0].field.staffLabel").doesNotExist())

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
    fun `published customer and agent defaults are unique and report a domain conflict`() {
        val browser = browser("ADMIN")
        val fieldId = UUID.fromString(stringField(
            mockMvc.perform(
                post("/api/v1/admin/ticket-fields")
                    .session(browser.session).csrf(browser).contentType(MediaType.APPLICATION_JSON)
                    .content(fieldJson("default-form.field")),
            ).andExpect(status().isCreated).andReturn().response.contentAsString,
            "id",
        ))

        val customerDefault = createForm(browser, "고객 기본 양식", fieldId, defaultForCustomer = true, defaultForAgent = false)
        publishForm(browser, customerDefault)
        val agentDefault = createForm(browser, "상담사 기본 양식", fieldId, defaultForCustomer = false, defaultForAgent = true)
        publishForm(browser, agentDefault)

        val secondCustomerDefault = createForm(browser, "두 번째 고객 기본 양식", fieldId, defaultForCustomer = true, defaultForAgent = false)
        mockMvc.perform(
            post("/api/v1/admin/ticket-forms/{formId}/publish", secondCustomerDefault)
                .session(browser.session).csrf(browser).header("If-Match", "\"1\""),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("PUBLISHED_DEFAULT_FORM_EXISTS"))

        val secondAgentDefault = createForm(browser, "두 번째 상담사 기본 양식", fieldId, defaultForCustomer = false, defaultForAgent = true)
        mockMvc.perform(
            post("/api/v1/admin/ticket-forms/{formId}/publish", secondAgentDefault)
                .session(browser.session).csrf(browser).header("If-Match", "\"1\""),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("PUBLISHED_DEFAULT_FORM_EXISTS"))
    }

    @Test
    fun `customer projection never makes a globally read-only field editable`() {
        val browser = browser("ADMIN")
        val fieldId = UUID.fromString(stringField(
            mockMvc.perform(
                post("/api/v1/admin/ticket-fields")
                    .session(browser.session).csrf(browser).contentType(MediaType.APPLICATION_JSON)
                    .content(fieldJson("customer.read-only", customerEditable = false)),
            ).andExpect(status().isCreated).andReturn().response.contentAsString,
            "id",
        ))
        val formId = createForm(browser, "고객 읽기 전용 양식", fieldId, defaultForCustomer = true, defaultForAgent = false)
        publishForm(browser, formId)

        mockMvc.perform(
            get("/api/v1/customer/ticket-forms"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.formId").value(formId.toString()))
            .andExpect(jsonPath("$.fields[0].editable").value(false))
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
        mockMvc.perform(get("/api/v1/admin/ticket-tags").session(browser.session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].id").value(tagId.toString()))
            .andExpect(jsonPath("$[0].highCardinalityWarning").value(false))
        assertThat(jdbc.queryForList(
            "select indexname from pg_indexes where schemaname = current_schema() and tablename = 'ticket_tag_assignments'",
            String::class.java,
        )).contains("ticket_tag_assignments_tag_definition_idx")
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

    @Test
    fun `customer candidate values control visibility and final request stores only editable values`() {
        val admin = browser("ADMIN")
        val reason = createPublicField(admin, "request.refund", "CHECKBOX")
        val order = createPublicField(admin, "order.reference", "SHORT_TEXT")
        val config = """{"name":"환불 접수","defaultForCustomer":true,"placements":[
            {"fieldId":"$reason","order":0,"customer":{"visible":true,"editable":true,"required":false},"agent":{"visible":true,"editable":true,"required":false}},
            {"fieldId":"$order","order":1,"customer":{"visible":true,"editable":true,"required":true},"agent":{"visible":true,"editable":true,"required":false}}],
            "conditionalRules":[{"id":"${UUID.randomUUID()}","priority":1,"condition":{"schemaVersion":1,"root":{"kind":"LEAF","typeKey":"ticket.form.fact-equals","schemaVersion":1,"config":{"fact":"field.$reason","equals":"false"}}},"effects":[{"fieldId":"$order","behavior":"HIDE"}]}],"allowedCustomStatusIds":[]}"""
        val form = createPublishedForm(admin, config)
        val hidden = mapOf("request.refund" to mapOf("booleanValue" to false), "order.reference" to mapOf("shortTextValue" to "discard-me"))
        mockMvc.perform(post("/api/v1/customer/ticket-form-projections").contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(mapOf("ticketKind" to "CUSTOMER_REQUEST", "formId" to form, "formVersion" to 1, "fieldValues" to hidden))))
            .andExpect(status().isOk).andExpect(jsonPath("$.fields.length()").value(1))
        val result = submitForm(form, hidden).andExpect(status().isCreated).andReturn().response.contentAsString
        val number = mapper.readTree(result).path("ticketNumber").asLong()
        assertThat(jdbc.queryForList("select short_text_value from ticket_custom_field_values value join tickets ticket on ticket.id = value.ticket_id where ticket.ticket_number = ?", number))
            .hasSize(1).allSatisfy { assertThat(it["short_text_value"]).isNull() }
        assertThat(jdbc.queryForObject("select count(*) from ticket_customer_form_bindings binding join tickets ticket on ticket.id = binding.ticket_id where ticket.ticket_number = ? and form_version = 1", Long::class.java, number)).isEqualTo(1)
        submitForm(form, mapOf("request.refund" to mapOf("booleanValue" to true)))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.type").value("/problems/customer-ticket-form-validation-failed"))
        submitForm(form, mapOf("staff.secret" to mapOf("shortTextValue" to "guess")))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.detail").value("Check the customer form values."))
    }

    @Test
    fun `same initial command replays after form archive without duplicate business rows and conflicts on different content`() {
        val admin = browser("ADMIN")
        val field = createPublicField(admin, "order.reference", "SHORT_TEXT")
        val form = createPublishedForm(admin, defaultFormJson("주문 문의", field, true, false))
        val commandId = UUID.randomUUID()
        val email = "replay-${UUID.randomUUID()}@example.test"
        val values = mapOf("order.reference" to mapOf("shortTextValue" to "ORD-1042"))
        val first = mapper.readTree(submitForm(form, values, commandId, email).andExpect(status().isCreated).andExpect(jsonPath("$.replayed").value(false)).andReturn().response.contentAsString)
        mockMvc.perform(post("/api/v1/admin/ticket-forms/$form/archive").session(admin.session).csrf(admin).header("If-Match", "\"2\""))
            .andExpect(status().isOk)
        val replay = mapper.readTree(submitForm(form, values, commandId, email).andExpect(status().isCreated).andExpect(jsonPath("$.replayed").value(true)).andReturn().response.contentAsString)
        assertThat(replay.path("ticketNumber")).isEqualTo(first.path("ticketNumber"))
        assertThat(replay.path("accessToken")).isNotEqualTo(first.path("accessToken"))
        assertThat(jdbc.queryForObject("select count(*) from customers where email_normalized = ?", Long::class.java, email)).isEqualTo(1)
        val number = first.path("ticketNumber").asLong()
        assertThat(jdbc.queryForObject("select count(*) from ticket_audits audit join tickets ticket on ticket.id = audit.ticket_id where ticket.ticket_number = ?", Long::class.java, number)).isEqualTo(1)
        assertThat(jdbc.queryForObject("select count(*) from outbound_mail_intents intent join tickets ticket on ticket.id = intent.ticket_id where ticket.ticket_number = ?", Long::class.java, number)).isEqualTo(1)
        submitForm(form, values, commandId, email, subject = "changed").andExpect(status().isConflict)
            .andExpect(jsonPath("$.type").value("/problems/customer-request-command-conflict"))
        assertThat(jdbc.queryForObject("select command_id from ticket_audits audit join tickets ticket on ticket.id = audit.ticket_id where ticket.ticket_number = ?", String::class.java, number)).doesNotContain(commandId.toString())
    }

    @Test
    fun `published snapshot validation survives draft field edits and an empty value form still binds`() {
        val admin = browser("ADMIN")
        val field = createPublicField(admin, "order.reference", "SHORT_TEXT")
        val form = createPublishedForm(admin, defaultFormJson("주문 문의", field, true, false))
        mockMvc.perform(put("/api/v1/admin/ticket-fields/$field").session(admin.session).csrf(admin)
            .header("If-Match", "\"1\"").contentType(MediaType.APPLICATION_JSON)
            .content(fieldJson("order.reference").replace("SINGLE_SELECT", "SHORT_TEXT").replace("\"validation\":{}", "\"validation\":{\"maxLength\":2}")))
            .andExpect(status().isBadRequest)
        // Validation belongs to the published form, while customer copy can change independently.
        jdbc.update("update ticket_field_definitions set validation_json = '{\"maxLength\":2}'::jsonb, customer_label = '새 주문번호' where id = ?", field)
        submitForm(form, mapOf("order.reference" to mapOf("shortTextValue" to "ORD-1042"))).andExpect(status().isCreated)
        val empty = mapper.readTree(submitForm(form, emptyMap()).andExpect(status().isCreated).andReturn().response.contentAsString)
        assertThat(jdbc.queryForObject("select count(*) from ticket_customer_form_bindings binding join tickets ticket on ticket.id = binding.ticket_id where ticket.ticket_number = ?", Long::class.java, empty.path("ticketNumber").asLong())).isEqualTo(1)
        submitForm(null, emptyMap()).andExpect(status().isConflict)
            .andExpect(jsonPath("$.type").value("/problems/customer-request-configuration-conflict"))
        submitForm(form, emptyMap(), version = 999).andExpect(status().isConflict)
            .andExpect(jsonPath("$.type").value("/problems/customer-ticket-form-version-conflict"))
    }

    @Test
    fun `concurrent identical initial requests create one logical ticket`() {
        val commandId = UUID.randomUUID()
        val email = "concurrent-${UUID.randomUUID()}@example.test"
        val barrier = java.util.concurrent.CyclicBarrier(2)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map { executor.submit(java.util.concurrent.Callable {
                barrier.await(10, java.util.concurrent.TimeUnit.SECONDS)
                mapper.readTree(submitForm(null, emptyMap(), commandId, email).andExpect(status().isCreated).andReturn().response.contentAsString)
            }) }
            val responses = futures.map { it.get(20, java.util.concurrent.TimeUnit.SECONDS) }
            assertThat(responses.map { it.path("ticketNumber").asLong() }.distinct()).hasSize(1)
            assertThat(responses.map { it.path("replayed").asBoolean() }).containsExactlyInAnyOrder(false, true)
            assertThat(jdbc.queryForObject("select count(*) from customers where email_normalized = ?", Long::class.java, email)).isEqualTo(1)
        } finally { executor.shutdownNow() }
    }

    @Test
    fun `initial request requires current consent and records one acceptance across replay`() {
        val admin = browser("ADMIN")
        val key = "request-consent-${UUID.randomUUID()}"
        val created = mockMvc.perform(post("/api/v1/admin/customer-consent-policies").session(admin.session).csrf(admin)
            .header("X-Deskseed-Expected-Staff-Id", admin.staffId).header("If-None-Match", "*").contentType(MediaType.APPLICATION_JSON)
            .content("""{"policyKey":"$key","context":"REQUEST_SUBMISSION","title":"문의 처리 동의","document":{"schemaVersion":1,"blocks":[{"type":"paragraph","text":"문의 처리에 이메일을 사용합니다."}]},"required":true,"displayOrder":10}"""))
            .andExpect(status().isCreated).andReturn().response.contentAsString
        val id = stringField(created, "id")
        mockMvc.perform(post("/api/v1/admin/customer-consent-policies/$id/publish").session(admin.session).csrf(admin)
            .header("X-Deskseed-Expected-Staff-Id", admin.staffId).header("If-Match", "\"0\""))
            .andExpect(status().isOk)
        try {
            val email = "consent-request-${UUID.randomUUID()}@example.test"
            submitForm(null, emptyMap(), email = email).andExpect(status().isConflict)
            assertThat(jdbc.queryForObject("select count(*) from customers where email_normalized = ?", Long::class.java, email)).isZero()
            val commandId = UUID.randomUUID()
            val selection = listOf(mapOf("policyKey" to key, "version" to 1))
            val first = mapper.readTree(submitForm(null, emptyMap(), commandId, email, policies = selection).andExpect(status().isCreated).andReturn().response.contentAsString)
            submitForm(null, emptyMap(), commandId, email, policies = selection).andExpect(status().isCreated).andExpect(jsonPath("$.replayed").value(true))
            val number = first.path("ticketNumber").asLong()
            assertThat(jdbc.queryForObject("select count(*) from customer_consent_acceptances acceptance join tickets ticket on ticket.id = acceptance.ticket_id where ticket.ticket_number = ? and acceptance.context = 'REQUEST_SUBMISSION'", Long::class.java, number)).isEqualTo(1)
            submitForm(null, emptyMap(), email = email, policies = listOf(mapOf("policyKey" to key, "version" to 2))).andExpect(status().isConflict)
            assertThat(jdbc.queryForObject("select count(*) from admin_security_audit_events audit join tickets ticket on ticket.id = audit.target_id where ticket.ticket_number = ? and audit.event_type = 'CUSTOMER_CONSENT_ACCEPTED'", Long::class.java, number)).isEqualTo(1)
            jdbc.execute("""create function fail_request_consent_audit() returns trigger language plpgsql as ${'$'}${'$'}
                begin if new.event_type = 'CUSTOMER_CONSENT_ACCEPTED' then raise exception 'injected consent audit failure'; end if; return new; end; ${'$'}${'$'}""")
            jdbc.execute("create trigger fail_request_consent_audit before insert on admin_security_audit_events for each row execute function fail_request_consent_audit()")
            val rollbackEmail = "rollback-${UUID.randomUUID()}@example.test"
            val ticketCount = jdbc.queryForObject("select count(*) from tickets", Long::class.java)
            val receiptCount = jdbc.queryForObject("select count(*) from customer_request_command_receipts", Long::class.java)
            val acceptanceCount = jdbc.queryForObject("select count(*) from customer_consent_acceptances", Long::class.java)
            try {
                submitForm(null, emptyMap(), email = rollbackEmail, policies = selection)
                    .andExpect(status().isServiceUnavailable)
                    .andExpect(jsonPath("$.type").value("/problems/customer-request-configuration-unavailable"))
            } finally {
                jdbc.execute("drop trigger if exists fail_request_consent_audit on admin_security_audit_events")
                jdbc.execute("drop function if exists fail_request_consent_audit()")
            }
            assertThat(jdbc.queryForObject("select count(*) from customers where email_normalized = ?", Long::class.java, rollbackEmail)).isZero()
            assertThat(jdbc.queryForObject("select count(*) from tickets", Long::class.java)).isEqualTo(ticketCount)
            assertThat(jdbc.queryForObject("select count(*) from customer_request_command_receipts", Long::class.java)).isEqualTo(receiptCount)
            assertThat(jdbc.queryForObject("select count(*) from customer_consent_acceptances", Long::class.java)).isEqualTo(acceptanceCount)

        } finally {
            mockMvc.perform(post("/api/v1/admin/customer-consent-policies/$id/archive").session(admin.session).csrf(admin)
                .header("X-Deskseed-Expected-Staff-Id", admin.staffId).header("If-Match", "\"1\""))
                .andExpect(status().isOk)
        }
    }

    @Test
    fun `form withdrawal between preparation and finalization leaves no planned customer`() {
        val admin = browser("ADMIN")
        val field = createPublicField(admin, "order.reference", "SHORT_TEXT")
        val form = createPublishedForm(admin, defaultFormJson("주문 문의", field, true, false))
        mockMvc.perform(get("/api/v1/customer/ticket-forms").param("ticketKind", "INTERNAL_CHILD"))
            .andExpect(status().isBadRequest)
        val command = dev.deskseed.portal.internal.SubmitAnonymousRequest(
            name = "접수 고객", email = "planned-${UUID.randomUUID()}@example.test", subject = "주문 확인", message = "주문을 확인해 주세요.",
            context = dev.deskseed.foundation.CommandContext(source = dev.deskseed.foundation.RequestSource.CUSTOMER_PORTAL,
                requestId = "planned-request", correlationId = "planned-correlation", commandId = UUID.randomUUID().toString()),
            formValues = dev.deskseed.ticketing.CustomerRequestFormValues(form, 1),
        )
        val prepared = requestService.prepareInitialSubmission(command)
        assertThat(jdbc.queryForObject("select count(*) from customers where id = ?", Long::class.java, prepared.customerId)).isZero()
        mockMvc.perform(post("/api/v1/admin/ticket-forms/$form/archive").session(admin.session).csrf(admin).header("If-Match", "\"2\""))
            .andExpect(status().isOk)
        assertThatThrownBy { requestService.finishInitialSubmission(prepared, emptyList()) }
            .isInstanceOf(dev.deskseed.ticketing.CustomerFormUnavailableException::class.java)
        assertThat(jdbc.queryForObject("select count(*) from customers where id = ?", Long::class.java, prepared.customerId)).isZero()
    }

    @Test
    fun `customer long text accepts line breaks in preview and preserves them on submit`() {
        val admin = browser("ADMIN")
        val field = createPublicField(admin, "request.details", "LONG_TEXT")
        val form = createPublishedForm(admin, defaultFormJson("상세 문의", field, true, false))
        for (lineBreak in listOf("\n", "\r\n")) {
            val text = "첫 번째 줄${lineBreak}두 번째 줄"
            val values = mapOf("request.details" to mapOf("longTextValue" to text))
            previewForm(form, values).andExpect(status().isOk)
            val response = submitForm(form, values).andExpect(status().isCreated).andReturn().response.contentAsString
            val number = mapper.readTree(response).path("ticketNumber").asLong()
            assertThat(jdbc.queryForObject("select long_text_value from ticket_custom_field_values value join tickets ticket on ticket.id = value.ticket_id where ticket.ticket_number = ?", String::class.java, number))
                .isEqualTo(text)
        }
    }

    @Test
    fun `customer text still rejects short text line breaks and other control characters`() {
        val admin = browser("ADMIN")
        val shortField = createPublicField(admin, "request.reference", "SHORT_TEXT")
        val shortForm = createPublishedForm(admin, defaultFormJson("주문 문의", shortField, true, false))
        val longField = createPublicField(admin, "request.details", "LONG_TEXT")
        val longForm = createPublishedForm(admin, defaultFormJson("상세 문의", longField, false, false))
        val ticketCount = jdbc.queryForObject("select count(*) from tickets", Long::class.java)
        for (control in listOf("\n", "\r\n", "\t", "\u0000", "\u001b", "\u007f")) {
            val values = mapOf("request.reference" to mapOf("shortTextValue" to "첫 줄${control}다음 줄"))
            previewForm(shortForm, values).andExpect(status().isBadRequest)
            submitForm(shortForm, values).andExpect(status().isBadRequest)
        }
        for (control in listOf("\r", "\t", "\u0000", "\u001b", "\u007f")) {
            val values = mapOf("request.details" to mapOf("longTextValue" to "첫 줄${control}다음 줄"))
            previewForm(longForm, values).andExpect(status().isBadRequest)
            submitForm(longForm, values).andExpect(status().isBadRequest)
        }
        assertThat(jdbc.queryForObject("select count(*) from tickets", Long::class.java)).isEqualTo(ticketCount)
    }

    private fun previewForm(form: UUID, values: Map<String, Any>) = mockMvc.perform(
        post("/api/v1/customer/ticket-form-projections").contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(mapOf("ticketKind" to "CUSTOMER_REQUEST", "formId" to form, "formVersion" to 1, "fieldValues" to values))))

    private fun createPublicField(admin: Browser, key: String, type: String): UUID = UUID.fromString(stringField(
        mockMvc.perform(post("/api/v1/admin/ticket-fields").session(admin.session).csrf(admin).contentType(MediaType.APPLICATION_JSON)
            .content(fieldJson(key).replace("SINGLE_SELECT", type))).andExpect(status().isCreated).andReturn().response.contentAsString, "id"))

    private fun createPublishedForm(admin: Browser, definition: String): UUID {
        val id = UUID.fromString(stringField(mockMvc.perform(post("/api/v1/admin/ticket-forms").session(admin.session).csrf(admin).contentType(MediaType.APPLICATION_JSON)
            .content(definition)).andExpect(status().isCreated).andReturn().response.contentAsString, "id"))
        publishForm(admin, id)
        return id
    }

    private fun submitForm(form: UUID?, values: Map<String, Any>, commandId: UUID = UUID.randomUUID(),
        email: String = "forms-${UUID.randomUUID()}@example.test", subject: String = "주문 확인", version: Int = 1, policies: List<Map<String, Any>> = emptyList()) = mockMvc.perform(
        post("/api/v1/requests").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(mapOf(
            "clientCommandId" to commandId, "requester" to mapOf("name" to "폼 고객", "email" to email),
            "subject" to subject, "message" to "주문 상태를 확인해 주세요.", "fieldValues" to values, "acceptedPolicies" to policies,
            "formId" to form, "formVersion" to form?.let { version },
        ).filterValues { it != null })))

    private fun fieldJson(machineKey: String, customerEditable: Boolean = true) =
        """{"machineKey":"$machineKey","type":"SINGLE_SELECT","staffLabel":"결제 수단","customerLabel":"결제 수단","customerVisible":true,"customerEditable":$customerEditable,"agentVisible":true,"agentEditable":true,"searchable":true,"analyticsEligible":false,"sensitive":false,"validation":{}}"""

    private fun createForm(
        browser: Browser,
        name: String,
        fieldId: UUID,
        defaultForCustomer: Boolean,
        defaultForAgent: Boolean,
    ): UUID = UUID.fromString(stringField(
        mockMvc.perform(
            post("/api/v1/admin/ticket-forms")
                .session(browser.session).csrf(browser).contentType(MediaType.APPLICATION_JSON)
                .content(defaultFormJson(name, fieldId, defaultForCustomer, defaultForAgent)),
        ).andExpect(status().isCreated).andReturn().response.contentAsString,
        "id",
    ))

    private fun publishForm(browser: Browser, formId: UUID) {
        mockMvc.perform(
            post("/api/v1/admin/ticket-forms/{formId}/publish", formId)
                .session(browser.session).csrf(browser).header("If-Match", "\"1\""),
        ).andExpect(status().isOk)
    }

    private fun defaultFormJson(name: String, fieldId: UUID, defaultForCustomer: Boolean, defaultForAgent: Boolean) =
        """{"name":"$name","defaultForCustomer":$defaultForCustomer,"defaultForAgent":$defaultForAgent,"placements":[{"fieldId":"$fieldId","order":10,"customer":{"visible":true,"editable":true,"required":false},"agent":{"visible":true,"editable":true,"required":false}}],"conditionalRules":[],"allowedCustomStatusIds":[]}"""

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
        val staffId = UUID.randomUUID()
        val email = "configuration-${role.lowercase()}-${UUID.randomUUID()}@example.com"
        val password = "Configuration password 42!"
        jdbc.update(
            """
            insert into staff_accounts
                (id, email_normalized, email_display, display_name, role, status,
                 password_hash, created_at, updated_at, version)
            values (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, 0)
            """.trimIndent(),
            staffId, email.lowercase(), email,
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
        return Browser(login.request.session as MockHttpSession, token, staffId)
    }

    private fun MockHttpServletRequestBuilder.csrf(browser: Browser) = header("X-CSRF-TOKEN", browser.csrfToken)

    private fun stringField(json: String, field: String): String =
        Regex("\\\"$field\\\":\\\"([^\\\"]+)\\\"").find(json)!!.groupValues[1]

    private data class Browser(val session: MockHttpSession, val csrfToken: String, val staffId: UUID)

}
