package dev.deskseed.platformapi.internal

import dev.deskseed.foundation.RequestSource
import dev.deskseed.integration.CreateIntegrationClientCommand
import dev.deskseed.integration.IntegrationAdminActor
import dev.deskseed.integration.IntegrationClientAdministration
import dev.deskseed.integration.IntegrationResourceConstraints
import dev.deskseed.integration.IntegrationScope
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@SpringBootTest(
    properties = [
        "deskseed.staff-auth.bootstrap.enabled=false",
        "deskseed.platform.rate-limit.requests-per-minute=2",
    ],
)
@AutoConfigureMockMvc
@Testcontainers
class PlatformRateLimitIntegrationTest {
    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate
    @Autowired private lateinit var administration: IntegrationClientAdministration

    @Test
    fun `third request is rate limited with headers and audited without key material`() {
        jdbcTemplate.execute(
            "truncate table platform_rate_limit_buckets, platform_idempotency_records, integration_credentials, integration_clients, " +
                "access_audit_events, admin_security_audit_events, ticket_audit_events, ticket_audits, " +
                "ticket_comments, tickets, staff_authority_grants, staff_accounts, customers cascade",
        )
        val adminId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            insert into staff_accounts (
                id, email_normalized, email_display, display_name, role, status, password_hash, created_at, updated_at
            ) values (?, 'rate-admin@example.com', 'rate-admin@example.com', 'Admin', 'ADMIN', 'ACTIVE', 'unused', now(), now())
            """.trimIndent(),
            adminId,
        )
        val apiKey = issueClient(adminId)
        val request = { mockMvc.perform(get("/api/v1/platform/tickets/999999").header("Authorization", "Bearer $apiKey")).andReturn() }

        assertThat(request().response.status).isEqualTo(404)
        assertThat(request().response.status).isEqualTo(404)
        val limited = request().response
        assertThat(limited.status).isEqualTo(429)
        assertThat(limited.getHeader("Retry-After")?.toLong()).isBetween(1, 60)
        assertThat(limited.getHeader("X-RateLimit-Limit")).isEqualTo("2")
        assertThat(limited.getHeader("X-RateLimit-Remaining")).isEqualTo("0")

        val audit = jdbcTemplate.queryForObject(
            """
            select metadata_json from admin_security_audit_events
            where event_type = 'ACCESS_DENIED' and metadata_json like '%RATE_LIMITED%'
            """.trimIndent(),
            String::class.java,
        )!!
        assertThat(audit).contains("RATE_LIMITED").doesNotContain(apiKey).doesNotContain(apiKey.substringAfter('.'))
    }

    @Test
    fun `rate limit database failure is fail closed with documented 503`() {
        jdbcTemplate.execute("truncate table platform_rate_limit_buckets")
        val adminId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            insert into staff_accounts (
                id, email_normalized, email_display, display_name, role, status, password_hash, created_at, updated_at
            ) values (?, 'rate-failure-admin@example.com', 'rate-failure-admin@example.com', 'Admin', 'ADMIN', 'ACTIVE', 'unused', now(), now())
            """.trimIndent(),
            adminId,
        )
        val apiKey = issueClient(adminId)
        jdbcTemplate.execute(
            """
            create or replace function fail_platform_rate_limit_insert() returns trigger language plpgsql as ${'$'}${'$'}
            begin raise exception 'injected platform rate-limit database failure'; end; ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            "create trigger fail_platform_rate_limit before insert on platform_rate_limit_buckets " +
                "for each row execute function fail_platform_rate_limit_insert()",
        )
        try {
            val response = mockMvc.perform(
                get("/api/v1/platform/tickets/999999").header("Authorization", "Bearer $apiKey"),
            ).andReturn().response
            assertThat(response.status).isEqualTo(503)
            assertThat(response.contentAsString).contains("/problems/platform-rate-limit-unavailable")
            assertThat(response.getHeader("X-RateLimit-Limit")).isNull()
        } finally {
            jdbcTemplate.execute("drop trigger if exists fail_platform_rate_limit on platform_rate_limit_buckets")
            jdbcTemplate.execute("drop function if exists fail_platform_rate_limit_insert()")
        }
    }

    private fun issueClient(adminId: UUID): String {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken.authenticated(
            "admin",
            null,
            listOf(SimpleGrantedAuthority("integration:clients:manage")),
        )
        return try {
            administration.create(
                CreateIntegrationClientCommand(
                    "rate-${UUID.randomUUID()}",
                    "Rate test",
                    setOf(IntegrationScope.TICKETS_READ),
                    IntegrationResourceConstraints(),
                    Instant.now().plus(1, ChronoUnit.DAYS),
                ),
                IntegrationAdminActor(
                    adminId,
                    "Admin",
                    RequestSource.ADMIN_UI,
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                ),
            ).apiKey
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
    }
}
