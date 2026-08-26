package dev.deskseed.customerauth.internal

import dev.deskseed.customerauth.AuthenticationAttemptLimiter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.support.TransactionSynchronizationManager

@dev.deskseed.testsupport.integration.DeskseedSpringIntegrationTest
@AutoConfigureMockMvc
@Import(CustomerAuthenticationLimiterUnavailableTestConfiguration::class)
@dev.deskseed.testsupport.category.IntegrationTest
class CustomerAuthenticationLimiterUnavailableIntegrationTest {
    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate
    @Autowired private lateinit var unavailableLimiter: RecordingUnavailableAuthenticationAttemptLimiter

    @Test
    fun `required limiter failure returns generic 503 before database authentication work`() {
        val auditBefore = jdbcTemplate.queryForObject(
            "select count(*) from admin_security_audit_events",
            Long::class.java,
        )!!
        val tokenBefore = jdbcTemplate.queryForObject("select count(*) from customer_one_time_tokens", Long::class.java)!!

        mockMvc.perform(
            post("/api/v1/customer/auth/magic-link-requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"unavailable@example.test"}"""),
        )
            .andExpect(status().isServiceUnavailable)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("Referrer-Policy", "no-referrer"))
            .andExpect(jsonPath("$.type").value("/problems/customer-authentication-unavailable"))
            .andExpect(jsonPath("$.status").value(503))

        assertThat(jdbcTemplate.queryForObject("select count(*) from admin_security_audit_events", Long::class.java))
            .isEqualTo(auditBefore)
        assertThat(jdbcTemplate.queryForObject("select count(*) from customer_one_time_tokens", Long::class.java))
            .isEqualTo(tokenBefore)
        assertThat(unavailableLimiter.transactionActiveAtAcquire).isFalse()
    }

    @Test
    fun `magic consume limiter failure returns generic 503 before proof work`() {
        val tokenBefore = jdbcTemplate.queryForObject("select count(*) from customer_one_time_tokens", Long::class.java)!!
        val sessionBefore = jdbcTemplate.queryForObject("select count(*) from customer_sessions", Long::class.java)!!

        mockMvc.perform(
            post("/api/v1/customer/auth/magic-link-sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"token":"${"m".repeat(43)}"}"""),
        )
            .andExpect(status().isServiceUnavailable)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.type").value("/problems/customer-authentication-unavailable"))

        assertThat(jdbcTemplate.queryForObject("select count(*) from customer_one_time_tokens", Long::class.java))
            .isEqualTo(tokenBefore)
        assertThat(jdbcTemplate.queryForObject("select count(*) from customer_sessions", Long::class.java))
            .isEqualTo(sessionBefore)
        assertThat(unavailableLimiter.transactionActiveAtAcquire).isFalse()
    }

    @Test
    fun `registration limiter failure returns generic 503 before database registration work`() {
        val intentBefore = jdbcTemplate.queryForObject(
            "select count(*) from customer_registration_intents",
            Long::class.java,
        )!!
        val auditBefore = jdbcTemplate.queryForObject(
            "select count(*) from admin_security_audit_events",
            Long::class.java,
        )!!

        mockMvc.perform(
            post("/api/v1/customer/registrations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "email": "unavailable-registration@example.test",
                      "password": "synthetic registration password",
                      "displayName": "합성 고객",
                      "companyName": "합성 회사",
                      "acceptedPolicies": [{"policyKey":"synthetic-policy","version":1}]
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isServiceUnavailable)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("Referrer-Policy", "no-referrer"))
            .andExpect(jsonPath("$.type").value("/problems/customer-authentication-unavailable"))
            .andExpect(jsonPath("$.status").value(503))

        assertThat(jdbcTemplate.queryForObject("select count(*) from customer_registration_intents", Long::class.java))
            .isEqualTo(intentBefore)
        assertThat(jdbcTemplate.queryForObject("select count(*) from admin_security_audit_events", Long::class.java))
            .isEqualTo(auditBefore)
        assertThat(unavailableLimiter.transactionActiveAtAcquire).isFalse()
    }

    @Test
    fun `registration verification limiter failure returns generic 503 before proof consumption`() {
        val tokenBefore = jdbcTemplate.queryForObject(
            "select count(*) from customer_one_time_tokens",
            Long::class.java,
        )!!
        val accountBefore = jdbcTemplate.queryForObject(
            "select count(*) from customer_accounts",
            Long::class.java,
        )!!

        mockMvc.perform(
            post("/api/v1/customer/registration-verifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"token":"${"a".repeat(43)}"}"""),
        )
            .andExpect(status().isServiceUnavailable)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("Referrer-Policy", "no-referrer"))
            .andExpect(jsonPath("$.type").value("/problems/customer-authentication-unavailable"))

        assertThat(jdbcTemplate.queryForObject("select count(*) from customer_one_time_tokens", Long::class.java))
            .isEqualTo(tokenBefore)
        assertThat(jdbcTemplate.queryForObject("select count(*) from customer_accounts", Long::class.java))
            .isEqualTo(accountBefore)
        assertThat(unavailableLimiter.transactionActiveAtAcquire).isFalse()
    }

    @Test
    fun `password login limiter failure returns generic 503 before credential work`() {
        val sessionBefore = jdbcTemplate.queryForObject("select count(*) from customer_sessions", Long::class.java)!!
        val auditBefore = jdbcTemplate.queryForObject(
            "select count(*) from admin_security_audit_events",
            Long::class.java,
        )!!

        mockMvc.perform(
            post("/api/v1/customer/auth/password-sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"unavailable-password@example.test","password":"synthetic password"}"""),
        )
            .andExpect(status().isServiceUnavailable)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.type").value("/problems/customer-authentication-unavailable"))

        assertThat(jdbcTemplate.queryForObject("select count(*) from customer_sessions", Long::class.java))
            .isEqualTo(sessionBefore)
        assertThat(jdbcTemplate.queryForObject("select count(*) from admin_security_audit_events", Long::class.java))
            .isEqualTo(auditBefore)
        assertThat(unavailableLimiter.transactionActiveAtAcquire).isFalse()
    }

    @Test
    fun `password reset request limiter failure returns generic 503 before token work`() {
        val tokenBefore = jdbcTemplate.queryForObject("select count(*) from customer_one_time_tokens", Long::class.java)!!

        mockMvc.perform(
            post("/api/v1/customer/auth/password-reset-requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"unavailable-reset@example.test"}"""),
        )
            .andExpect(status().isServiceUnavailable)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.type").value("/problems/customer-authentication-unavailable"))

        assertThat(jdbcTemplate.queryForObject("select count(*) from customer_one_time_tokens", Long::class.java))
            .isEqualTo(tokenBefore)
        assertThat(unavailableLimiter.transactionActiveAtAcquire).isFalse()
    }

    @Test
    fun `password reset consume limiter failure returns generic 503 before proof or credential work`() {
        val tokenBefore = jdbcTemplate.queryForObject("select count(*) from customer_one_time_tokens", Long::class.java)!!
        val accountBefore = jdbcTemplate.queryForObject("select count(*) from customer_accounts", Long::class.java)!!

        mockMvc.perform(
            post("/api/v1/customer/auth/password-resets")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"token":"${"r".repeat(43)}","newPassword":"synthetic replacement password"}""",
                ),
        )
            .andExpect(status().isServiceUnavailable)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.type").value("/problems/customer-authentication-unavailable"))

        assertThat(jdbcTemplate.queryForObject("select count(*) from customer_one_time_tokens", Long::class.java))
            .isEqualTo(tokenBefore)
        assertThat(jdbcTemplate.queryForObject("select count(*) from customer_accounts", Long::class.java))
            .isEqualTo(accountBefore)
        assertThat(unavailableLimiter.transactionActiveAtAcquire).isFalse()
    }
}

internal class RecordingUnavailableAuthenticationAttemptLimiter : AuthenticationAttemptLimiter {
    var transactionActiveAtAcquire: Boolean? = null

    override fun acquire(attempt: dev.deskseed.customerauth.AuthenticationAttempt): Nothing {
        transactionActiveAtAcquire = TransactionSynchronizationManager.isActualTransactionActive()
        throw AuthenticationAttemptLimiterUnavailableException()
    }
}

@TestConfiguration(proxyBeanMethods = false)
internal class CustomerAuthenticationLimiterUnavailableTestConfiguration {
    @Bean
    @Primary
    fun unavailableAuthenticationAttemptLimiter() = RecordingUnavailableAuthenticationAttemptLimiter()
}
