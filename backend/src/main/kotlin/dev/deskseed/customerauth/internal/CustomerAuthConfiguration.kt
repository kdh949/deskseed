package dev.deskseed.customerauth.internal

import dev.deskseed.customerauth.CustomerCsrfFilter
import dev.deskseed.customerauth.CustomerSessionAuthenticationFilter
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ClassPathResource
import org.springframework.data.redis.core.script.RedisScript
import java.net.URI
import java.time.Duration
import java.util.Base64

@ConfigurationProperties("deskseed.customer-auth")
internal data class CustomerAuthProperties(
    var magicLinkTtl: Duration = Duration.ofMinutes(15),
    var registrationVerificationTtl: Duration = Duration.ofHours(24),
    var requestLimit: Int = 5,
    var networkRequestLimit: Int = 100,
    var globalRequestLimit: Int = 36_000,
    var requestWindow: Duration = Duration.ofMinutes(15),
    var limiterKeyPrefix: String = "deskseed:customer-auth:limiter:v1",
    var responseMinDuration: Duration = Duration.ofMillis(100),
    var sessionIdle: Duration = Duration.ofMinutes(30),
    var sessionAbsolute: Duration = Duration.ofHours(12),
    var fingerprintKey: String = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
    var csrfKey: String = "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=",
    var consumeUrl: String = "http://localhost:5173/customer/sign-in/consume",
    var registrationVerificationUrl: String = "http://localhost:5173/customer/register/verify",
) {
    fun validate() {
        require(magicLinkTtl in Duration.ofMinutes(5)..Duration.ofMinutes(60)) {
            "customer magic-link TTL must be between 5 and 60 minutes"
        }
        require(registrationVerificationTtl in Duration.ofHours(1)..Duration.ofHours(48)) {
            "customer registration verification TTL must be between 1 and 48 hours"
        }
        require(requestLimit in 1..10_000) { "customer authentication destination limit is invalid" }
        require(networkRequestLimit in 1..1_000_000) { "customer authentication network limit is invalid" }
        require(globalRequestLimit in 1..1_000_000) { "customer authentication global limit is invalid" }
        require(requestWindow in Duration.ofSeconds(1)..Duration.ofHours(24)) {
            "customer authentication request window is invalid"
        }
        require(limiterKeyPrefix.matches(Regex("^[a-z0-9:-]{1,100}$"))) {
            "customer authentication limiter key prefix is invalid"
        }
        require(responseMinDuration in Duration.ZERO..Duration.ofSeconds(2)) { "customer auth response padding is invalid" }
        require(!sessionIdle.isZero && !sessionIdle.isNegative) { "customer session idle duration is invalid" }
        require(sessionAbsolute >= sessionIdle) { "customer absolute session duration must cover idle duration" }
        listOf(fingerprintKey, csrfKey).forEach { encoded ->
            require(Base64.getDecoder().decode(encoded).size >= 32) {
                "customer authentication keys must contain at least 32 bytes"
            }
        }
        validateAbsoluteUrl(consumeUrl, "customer magic-link consume URL")
        validateAbsoluteUrl(registrationVerificationUrl, "customer registration verification URL")
    }

    private fun validateAbsoluteUrl(value: String, label: String) {
        val uri = URI(value)
        require(
            uri.isAbsolute && uri.host != null && uri.scheme in setOf("http", "https") &&
                uri.rawQuery == null && uri.rawFragment == null,
        ) { "$label is invalid" }
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CustomerAuthProperties::class)
internal class CustomerAuthConfiguration {
    @Bean
    fun customerAuthenticationRateLimitScript(): RedisScript<String> = RedisScript.of(
        ClassPathResource("redis/customer-authentication-rate-limit.lua"),
        String::class.java,
    )

    @Bean
    fun customerSessionAuthenticationServletRegistration(
        filter: CustomerSessionAuthenticationFilter,
    ) = FilterRegistrationBean(filter).apply { isEnabled = false }

    @Bean
    fun customerCsrfServletRegistration(
        filter: CustomerCsrfFilter,
    ) = FilterRegistrationBean(filter).apply { isEnabled = false }
}
