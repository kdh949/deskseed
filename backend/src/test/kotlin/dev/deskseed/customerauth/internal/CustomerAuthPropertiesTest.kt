package dev.deskseed.customerauth.internal

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.core.io.ClassPathResource
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
    fun `registration verification ttl accepts one to forty eight hours`() {
        listOf(Duration.ofHours(1), Duration.ofHours(24), Duration.ofHours(48)).forEach { ttl ->
            assertThatCode { CustomerAuthProperties(registrationVerificationTtl = ttl).validate() }
                .doesNotThrowAnyException()
        }
    }

    @Test
    fun `registration verification ttl and URL reject unsafe configuration`() {
        listOf(
            CustomerAuthProperties(registrationVerificationTtl = Duration.ofMinutes(59)),
            CustomerAuthProperties(registrationVerificationTtl = Duration.ofHours(49)),
            CustomerAuthProperties(registrationVerificationUrl = "/customer/register/verify"),
            CustomerAuthProperties(registrationVerificationUrl = "javascript:alert(1)"),
            CustomerAuthProperties(registrationVerificationUrl = "https://deskseed.example/verify?token=placeholder"),
            CustomerAuthProperties(registrationVerificationUrl = "https://deskseed.example/verify#token=placeholder"),
        ).forEach { properties ->
            assertThatThrownBy { properties.validate() }
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
        assertThat(production?.getProperty("deskseed.customer-auth.registration-verification-url"))
            .isEqualTo("\${DESKSEED_CUSTOMER_REGISTRATION_VERIFICATION_URL}")
    }
}
