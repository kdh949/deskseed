package dev.deskseed.portal.internal

import dev.deskseed.integration.IntegrationNetworkPolicy
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.core.io.ClassPathResource

class PublicRequestRateLimitPropertiesTest {
    @Test
    fun `startup validation rejects invalid limits windows keys and forwarding bounds`() {
        listOf(
            properties(window = java.time.Duration.ofMillis(500)),
            properties(destinationLimit = 0),
            properties(fingerprintKey = "not-base64"),
            properties(maxForwardedHops = 11),
        ).forEach { properties ->
            assertThatThrownBy(properties::afterPropertiesSet)
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `startup validation rejects malformed trusted proxy CIDR`() {
        assertThatThrownBy {
            PublicRequestClientAddressResolver(
                IntegrationNetworkPolicy(),
                properties(trustedProxyCidrs = "not-a-cidr"),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `production profile requires a provisioned rate limit fingerprint key`() {
        val production = YamlPropertiesFactoryBean().apply {
            setResources(ClassPathResource("application-production.yml"))
        }.getObject()

        assertThat(production?.getProperty("deskseed.portal.public-request-rate-limit.fingerprint-key"))
            .isEqualTo("${'$'}{DESKSEED_PUBLIC_REQUEST_RATE_LIMIT_FINGERPRINT_KEY}")
    }

    private fun properties(
        window: java.time.Duration = java.time.Duration.ofMinutes(1),
        destinationLimit: Int = 2,
        fingerprintKey: String = "CQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQk=",
        maxForwardedHops: Int = 2,
        trustedProxyCidrs: String = "192.0.2.0/24",
    ) = PublicRequestRateLimitProperties(
        window = window,
        destinationLimit = destinationLimit,
        clientLimit = 2,
        globalLimit = 3,
        fingerprintKey = fingerprintKey,
        maxForwardedHops = maxForwardedHops,
        trustedProxyCidrs = trustedProxyCidrs,
    )
}
