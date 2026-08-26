package dev.deskseed.customerauth.internal

import dev.deskseed.integration.IntegrationNetworkPolicy
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.core.io.ClassPathResource
import org.springframework.mock.web.MockHttpServletRequest
import java.time.Duration

@dev.deskseed.testsupport.category.FastTest
class CustomerAuthPropertiesTest {
    @Test
    fun `magic link ttl accepts the inclusive five to sixty minute policy`() {
        listOf(Duration.ofMinutes(5), Duration.ofMinutes(15), Duration.ofMinutes(60)).forEach { ttl ->
            assertThatCode { CustomerAuthProperties(magicLinkTtl = ttl).validate() }.doesNotThrowAnyException()
        }
    }

    @Test
    fun `magic link ttl rejects values outside policy`() {
        listOf(Duration.ofMinutes(4), Duration.ofMinutes(61)).forEach { ttl ->
            assertThatThrownBy { CustomerAuthProperties(magicLinkTtl = ttl).validate() }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `authentication limiter accepts bounded independent dimension budgets`() {
        assertThatCode {
            CustomerAuthProperties(
                requestLimit = 5,
                networkRequestLimit = 100,
                globalRequestLimit = 36_000,
                requestWindow = Duration.ofMinutes(15),
            ).validate()
        }.doesNotThrowAnyException()
    }

    @Test
    fun `authentication limiter rejects nonpositive or unbounded configuration`() {
        listOf(
            CustomerAuthProperties(requestLimit = 0),
            CustomerAuthProperties(networkRequestLimit = 0),
            CustomerAuthProperties(globalRequestLimit = 0),
            CustomerAuthProperties(requestWindow = Duration.ZERO),
            CustomerAuthProperties(requestWindow = Duration.ofHours(25)),
        ).forEach { properties ->
            assertThatThrownBy { properties.validate() }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `client address trusts forwarding only from configured proxy cidrs`() {
        val resolver = CustomerAuthClientAddressResolver(
            IntegrationNetworkPolicy(),
            CustomerAuthProperties(trustedProxyCidrs = "192.0.2.0/24"),
        )

        val untrusted = MockHttpServletRequest().apply {
            remoteAddr = "203.0.113.9"
            addHeader("X-Forwarded-For", "198.51.100.7")
        }
        val trusted = MockHttpServletRequest().apply {
            remoteAddr = "192.0.2.10"
            addHeader("X-Forwarded-For", "198.51.100.7, 192.0.2.20")
        }

        assertThat(resolver.resolve(untrusted)).isEqualTo("203.0.113.9")
        assertThat(resolver.resolve(trusted)).isEqualTo("198.51.100.7")
    }

    @Test
    fun `trusted proxy rejects malformed duplicate and oversized forwarding chains`() {
        val resolver = CustomerAuthClientAddressResolver(
            IntegrationNetworkPolicy(),
            CustomerAuthProperties(trustedProxyCidrs = "192.0.2.0/24", maxForwardedHops = 2),
        )
        listOf(
            MockHttpServletRequest().apply {
                remoteAddr = "192.0.2.10"
                addHeader("X-Forwarded-For", "not-an-ip")
            },
            MockHttpServletRequest().apply {
                remoteAddr = "192.0.2.10"
                addHeader("X-Forwarded-For", "198.51.100.7")
                addHeader("X-Forwarded-For", "198.51.100.8")
            },
            MockHttpServletRequest().apply {
                remoteAddr = "192.0.2.10"
                addHeader("X-Forwarded-For", "198.51.100.7, 198.51.100.8, 198.51.100.9")
            },
        ).forEach { request ->
            assertThatThrownBy { resolver.resolve(request) }
                .describedAs(request.getHeaders("X-Forwarded-For").asSequence().toList().toString())
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `authentication proxy configuration rejects malformed cidrs and hop bounds`() {
        listOf(
            CustomerAuthProperties(trustedProxyCidrs = "not-a-cidr"),
            CustomerAuthProperties(maxForwardedHops = 0),
            CustomerAuthProperties(maxForwardedHops = 11),
        ).forEach { properties ->
            assertThatThrownBy { properties.validate() }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `production profile requires authenticated TLS redis configuration`() {
        val production = YamlPropertiesFactoryBean().apply {
            setResources(ClassPathResource("application-production.yml"))
        }.getObject()

        assertThat(production?.getProperty("spring.data.redis.host"))
            .isEqualTo("\${DESKSEED_CUSTOMER_AUTH_REDIS_HOST}")
        assertThat(production?.getProperty("spring.data.redis.port"))
            .isEqualTo("\${DESKSEED_CUSTOMER_AUTH_REDIS_PORT}")
        assertThat(production?.getProperty("spring.data.redis.username"))
            .isEqualTo("\${DESKSEED_CUSTOMER_AUTH_REDIS_USERNAME}")
        assertThat(production?.getProperty("spring.data.redis.password"))
            .isEqualTo("\${DESKSEED_CUSTOMER_AUTH_REDIS_PASSWORD}")
        assertThat(production?.getProperty("spring.data.redis.ssl.enabled")).isEqualTo("true")
        assertThat(production?.getProperty("deskseed.customer-auth.trusted-proxy-cidrs"))
            .isEqualTo("${'$'}{DESKSEED_CUSTOMER_AUTH_TRUSTED_PROXY_CIDRS}")
    }
}
