package dev.deskseed.platformapi.internal

import dev.deskseed.integration.IntegrationNetworkPolicy
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import org.springframework.mock.web.MockHttpServletRequest

class PlatformNetworkBoundaryTest {
    private val policy = IntegrationNetworkPolicy()

    @Test
    fun `untrusted peer cannot spoof forwarded client and trusted proxy uses first untrusted hop`() {
        val boundary = PlatformNetworkBoundary(
            policy,
            MockEnvironment(),
            "10.0.0.0/8",
            "192.0.2.0/24",
        ).also { it.validate() }

        val spoofed = MockHttpServletRequest().apply {
            remoteAddr = "203.0.113.9"
            addHeader("X-Forwarded-For", "10.1.2.3")
        }
        assertThat(boundary.resolveAllowedClient(spoofed)).isNull()

        val trusted = MockHttpServletRequest().apply {
            remoteAddr = "192.0.2.10"
            addHeader("X-Forwarded-For", "10.1.2.3, 192.0.2.20")
        }
        assertThat(boundary.resolveAllowedClient(trusted)).isEqualTo("10.1.2.3")
    }

    @Test
    fun `production requires explicit allowlist and trusted proxy configuration`() {
        val environment = MockEnvironment().withProperty("spring.profiles.active", "prod")
        environment.setActiveProfiles("prod")
        val boundary = PlatformNetworkBoundary(policy, environment, "", "")

        assertThatThrownBy(boundary::validate)
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Production Platform API requires")
    }
}

