package dev.deskseed.platformapi.internal

import dev.deskseed.integration.IntegrationNetworkPolicy
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.core.io.ClassPathResource
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
        val environment = MockEnvironment().withProperty("spring.profiles.active", "production")
        environment.setActiveProfiles("production")
        val boundary = PlatformNetworkBoundary(policy, environment, "", "")

        assertThatThrownBy(boundary::validate)
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Production Platform API requires")
    }

    @Test
    fun `production accepts only explicit valid deployment CIDRs and never falls back to local defaults`() {
        val environment = MockEnvironment().apply { setActiveProfiles("production") }
        val boundary = PlatformNetworkBoundary(policy, environment, "10.0.0.0/8", "192.0.2.0/24")

        assertThatCode(boundary::validate).doesNotThrowAnyException()
        assertThat(
            boundary.resolveAllowedClient(MockHttpServletRequest().apply { remoteAddr = "127.0.0.1" }),
        ).isNull()
        assertThat(
            boundary.resolveAllowedClient(MockHttpServletRequest().apply { remoteAddr = "10.1.2.3" }),
        ).isEqualTo("10.1.2.3")
        assertThatThrownBy {
            PlatformNetworkBoundary(policy, environment, "not-a-cidr", "192.0.2.0/24").validate()
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `production configuration requires operator supplied Platform CIDRs without defaults`() {
        val production = YamlPropertiesFactoryBean().apply {
            setResources(ClassPathResource("application-production.yml"))
        }.getObject()

        assertThat(production?.getProperty("deskseed.platform.network.allowed-client-cidrs"))
            .isEqualTo("${'$'}{DESKSEED_PLATFORM_ALLOWED_CLIENT_CIDRS}")
        assertThat(production?.getProperty("deskseed.platform.network.trusted-proxy-cidrs"))
            .isEqualTo("${'$'}{DESKSEED_PLATFORM_TRUSTED_PROXY_CIDRS}")
    }

    @Test
    fun `direct peer and forwarded chain reject hostnames legacy IPv4 and malformed IPv6`() {
        val boundary = PlatformNetworkBoundary(
            policy,
            MockEnvironment(),
            "10.0.0.0/8",
            "192.0.2.0/24",
        ).also { it.validate() }
        val invalid = listOf("bad.de", "fade.de", "127.1", "2130706433", "2001:db8:::1")

        invalid.forEach { candidate ->
            val direct = MockHttpServletRequest().apply { remoteAddr = candidate }
            assertThat(boundary.resolveAllowedClient(direct)).describedAs("direct $candidate").isNull()

            val forwarded = MockHttpServletRequest().apply {
                remoteAddr = "192.0.2.10"
                addHeader("X-Forwarded-For", candidate)
            }
            assertThat(boundary.resolveAllowedClient(forwarded)).describedAs("forwarded $candidate").isNull()
        }
    }
}
