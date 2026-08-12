package dev.deskseed.staffaccess.internal

import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
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
class BusinessScheduleAdminIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun clearAudit() {
        jdbcTemplate.execute("truncate table admin_security_audit_events")
    }

    @Test
    fun `seed schedule and draft preview expose deterministic business boundaries`() {
        val browser = adminBrowser()

        mockMvc.perform(get("/api/v1/admin/business-schedules").session(browser.session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].name").value("Default Support Hours"))
            .andExpect(jsonPath("$[0].timeZone").value("Asia/Seoul"))
            .andExpect(jsonPath("$[0].version").value(1))
            .andExpect(jsonPath("$[0].activeVersion").value(1))
            .andExpect(jsonPath("$[0].activeTimeZone").value("Asia/Seoul"))
            .andExpect(jsonPath("$[0].active").value(true))
            .andExpect(jsonPath("$[0].weekdays[0].weekday").value("MONDAY"))
            .andExpect(jsonPath("$[0].weekdays[0].intervals[0].start").value("09:00"))
            .andExpect(jsonPath("$[0].weekdays[5].enabled").value(false))

        mockMvc.perform(
            post("/api/v1/admin/business-schedules/preview")
                .session(browser.session)
                .csrf(browser)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "schedule": ${scheduleJson("Preview")},
                      "startAt": "2026-08-14T08:00:00Z",
                      "endAt": "2026-08-17T01:00:00Z",
                      "businessMinutes": 120
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.dueAt").value("2026-08-17T01:00:00Z"))
            .andExpect(jsonPath("$.elapsedBusinessMinutes").value(120))
            .andExpect(jsonPath("$.nextOpenAt").value("2026-08-14T08:00:00Z"))
            .andExpect(jsonPath("$.nextCloseAt").value("2026-08-14T09:00:00Z"))
            .andExpect(jsonPath("$.dstPolicy").value("GAP_SHIFT_FORWARD_OVERLAP_INCLUDE_BOTH"))
    }

    @Test
    fun `preview rejects elapsed ranges longer than one year`() {
        val browser = adminBrowser()

        mockMvc.perform(
            post("/api/v1/admin/business-schedules/preview")
                .session(browser.session)
                .csrf(browser)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "schedule": ${scheduleJson("Bounded preview")},
                      "startAt": "2026-01-01T00:00:00Z",
                      "endAt": "2027-01-03T00:00:00Z",
                      "businessMinutes": 120
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fieldErrors[0].field").value("endAt"))
            .andExpect(jsonPath("$.fieldErrors[0].code").value("PREVIEW_RANGE_TOO_LARGE"))
    }

    @Test
    fun `saving creates immutable version and activation is audited with stale writes rejected`() {
        val browser = adminBrowser()
        val uniqueName = "Versioned ${UUID.randomUUID()}"
        val created = mockMvc.perform(
            post("/api/v1/admin/business-schedules")
                .session(browser.session)
                .csrf(browser)
                .contentType(MediaType.APPLICATION_JSON)
                .content(scheduleJson(uniqueName)),
        )
            .andExpect(status().isCreated)
            .andExpect(header().string("ETag", "\"0\""))
            .andExpect(jsonPath("$.version").value(1))
            .andExpect(content().string(containsString("\"activeVersion\":null")))
            .andExpect(content().string(containsString("\"activeTimeZone\":null")))
            .andExpect(jsonPath("$.active").value(false))
            .andReturn().response.contentAsString
        val scheduleId = UUID.fromString(stringField(created, "id"))

        mockMvc.perform(
            post("/api/v1/admin/business-schedules/{scheduleId}/versions", scheduleId)
                .session(browser.session)
                .csrf(browser)
                .header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(scheduleJson(uniqueName, saturdayOpen = true, withExceptions = true)),
        )
            .andExpect(status().isCreated)
            .andExpect(header().string("ETag", "\"1\""))
            .andExpect(jsonPath("$.version").value(2))
            .andExpect(content().string(containsString("\"activeVersion\":null")))
            .andExpect(jsonPath("$.active").value(false))
            .andExpect(jsonPath("$.weekdays[5].enabled").value(true))
            .andExpect(jsonPath("$.exceptions[1].mode").value("CLOSED"))

        mockMvc.perform(
            post("/api/v1/admin/business-schedules/{scheduleId}/versions", scheduleId)
                .session(browser.session)
                .csrf(browser)
                .header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(scheduleJson(uniqueName)),
        )
            .andExpect(status().isPreconditionFailed)

        mockMvc.perform(
            put(
                "/api/v1/admin/business-schedules/{scheduleId}/versions/{version}/activation",
                scheduleId,
                2,
            )
                .session(browser.session)
                .csrf(browser)
                .header("If-Match", "\"1\""),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("ETag", "\"2\""))
            .andExpect(jsonPath("$.version").value(2))
            .andExpect(jsonPath("$.activeVersion").value(2))
            .andExpect(jsonPath("$.active").value(true))

        mockMvc.perform(
            get("/api/v1/admin/business-schedules/{scheduleId}/versions", scheduleId)
                .session(browser.session),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].version").value(2))
            .andExpect(jsonPath("$[0].active").value(true))
            .andExpect(jsonPath("$[1].version").value(1))
            .andExpect(jsonPath("$[1].active").value(false))
            .andExpect(jsonPath("$[1].weekdays[5].enabled").value(false))

        assertThat(
            jdbcTemplate.queryForList(
                """
                select event_type from admin_security_audit_events
                where target_id = ?
                order by occurred_at, id
                """.trimIndent(),
                String::class.java,
                scheduleId,
            ),
        ).containsExactly(
            "BUSINESS_SCHEDULE_CREATED",
            "BUSINESS_SCHEDULE_VERSION_CREATED",
            "BUSINESS_SCHEDULE_ACTIVATED",
        )
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from business_schedule_activations where schedule_id = ? and schedule_version = 2",
                Long::class.java,
                scheduleId,
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `invalid timezone and overlap return field problems while non-admin access is denied and audited`() {
        val admin = adminBrowser()
        mockMvc.perform(
            post("/api/v1/admin/business-schedules/preview")
                .session(admin.session)
                .csrf(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "schedule": {
                        "name": "Invalid",
                        "timeZone": "Mars/Olympus",
                        "weekdays": [
                          {"weekday":"MONDAY","enabled":true,"intervals":[{"start":"09:00","end":"12:00"},{"start":"11:00","end":"13:00"}]},
                          {"weekday":"TUESDAY","enabled":false,"intervals":[]},
                          {"weekday":"WEDNESDAY","enabled":false,"intervals":[]},
                          {"weekday":"THURSDAY","enabled":false,"intervals":[]},
                          {"weekday":"FRIDAY","enabled":false,"intervals":[]},
                          {"weekday":"SATURDAY","enabled":false,"intervals":[]},
                          {"weekday":"SUNDAY","enabled":false,"intervals":[]}
                        ],
                        "exceptions": []
                      },
                      "startAt": "2026-08-14T08:00:00Z",
                      "endAt": "2026-08-17T01:00:00Z",
                      "businessMinutes": 120
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fieldErrors[?(@.field == 'timeZone')].code").value("INVALID_TIMEZONE"))

        val overlapping = scheduleJson("Overlap").replaceFirst(
            "\"intervals\":[{\"start\":\"09:00\",\"end\":\"18:00\"}]",
            "\"intervals\":[{\"start\":\"09:00\",\"end\":\"12:00\"},{\"start\":\"11:00\",\"end\":\"13:00\"}]",
        )
        mockMvc.perform(
            post("/api/v1/admin/business-schedules/preview")
                .session(admin.session)
                .csrf(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "schedule": $overlapping,
                      "startAt": "2026-08-14T08:00:00Z",
                      "endAt": "2026-08-17T01:00:00Z",
                      "businessMinutes": 120
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fieldErrors[0].code").value("OVERLAPPING_INTERVALS"))

        val agent = browser("AGENT")
        mockMvc.perform(get("/api/v1/admin/business-schedules").session(agent.session))
            .andExpect(status().isForbidden)
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))

        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from admin_security_audit_events where event_type = 'ACCESS_DENIED'",
                Long::class.java,
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `audit persistence failure rolls back schedule creation`() {
        val browser = adminBrowser()
        val uniqueName = "Audit rollback ${UUID.randomUUID()}"
        jdbcTemplate.execute(
            """
            create or replace function fail_business_schedule_audit_insert()
            returns trigger language plpgsql as ${'$'}${'$'}
            begin
                if new.event_type = 'BUSINESS_SCHEDULE_CREATED' then
                    raise exception 'injected business schedule audit failure';
                end if;
                return new;
            end;
            ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            create trigger fail_business_schedule_audit_insert
            before insert on admin_security_audit_events
            for each row execute function fail_business_schedule_audit_insert()
            """.trimIndent(),
        )
        try {
            mockMvc.perform(
                post("/api/v1/admin/business-schedules")
                    .session(browser.session)
                    .csrf(browser)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(scheduleJson(uniqueName)),
            )
                .andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.type").value("/problems/admin-audit-unavailable"))
        } finally {
            jdbcTemplate.execute(
                "drop trigger if exists fail_business_schedule_audit_insert on admin_security_audit_events",
            )
            jdbcTemplate.execute("drop function if exists fail_business_schedule_audit_insert()")
        }

        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from business_schedules where name_normalized = ?",
                Long::class.java,
                uniqueName.lowercase(),
            ),
        ).isZero()
    }

    private fun adminBrowser() = browser("ADMIN")

    private fun browser(role: String): Browser {
        val email = "schedule-${role.lowercase()}-${UUID.randomUUID()}@example.com"
        val password = "Schedule password 42!"
        insertStaff(email, password, role)
        return login(email, password)
    }

    private fun login(email: String, password: String): Browser {
        val csrfResult = mockMvc.perform(get("/api/v1/agent/csrf")).andExpect(status().isOk).andReturn()
        val token = stringField(csrfResult.response.contentAsString, "token")
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

    private fun insertStaff(email: String, password: String, role: String) {
        jdbcTemplate.update(
            """
            insert into staff_accounts
                (id, email_normalized, email_display, display_name, role, status,
                 password_hash, created_at, updated_at, version)
            values (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, 0)
            """.trimIndent(),
            UUID.randomUUID(),
            email.lowercase(),
            email,
            if (role == "ADMIN") "일정 관리자" else "상담사",
            role,
            BCryptPasswordEncoder(4).encode(password),
            Timestamp.from(Instant.parse("2026-08-10T00:00:00Z")),
            Timestamp.from(Instant.parse("2026-08-10T00:00:00Z")),
        )
    }

    private fun scheduleJson(
        name: String,
        saturdayOpen: Boolean = false,
        withExceptions: Boolean = false,
    ): String {
        val weekdays = listOf(
            "MONDAY",
            "TUESDAY",
            "WEDNESDAY",
            "THURSDAY",
            "FRIDAY",
            "SATURDAY",
            "SUNDAY",
        ).joinToString(",") { weekday ->
            val enabled = weekday !in setOf("SATURDAY", "SUNDAY") || (weekday == "SATURDAY" && saturdayOpen)
            val intervals = if (enabled) "[{\"start\":\"09:00\",\"end\":\"18:00\"}]" else "[]"
            "{\"weekday\":\"$weekday\",\"enabled\":$enabled,\"intervals\":$intervals}"
        }
        val exceptions = if (withExceptions) {
            """[{"date":"2026-08-17","mode":"CLOSED","intervals":[],"label":"휴일"},{"date":"2026-08-16","mode":"OPEN","intervals":[{"start":"10:00","end":"12:00"}],"label":"특별 운영"}]"""
        } else {
            "[]"
        }
        return """{"name":"$name","timeZone":"Asia/Seoul","weekdays":[$weekdays],"exceptions":$exceptions}"""
    }

    private fun MockHttpServletRequestBuilder.csrf(browser: Browser) = header("X-CSRF-TOKEN", browser.csrfToken)

    private fun stringField(json: String, field: String): String =
        Regex("\"$field\":\"([^\"]+)\"").find(json)!!.groupValues[1]

    private data class Browser(val session: MockHttpSession, val csrfToken: String)

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
    }
}
