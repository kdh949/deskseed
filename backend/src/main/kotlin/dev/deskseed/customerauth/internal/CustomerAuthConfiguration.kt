package dev.deskseed.customerauth.internal

import dev.deskseed.customerauth.CustomerCsrfFilter
import dev.deskseed.customerauth.CustomerSessionAuthenticationFilter
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.net.URI
import java.time.Duration
import java.util.Base64

@ConfigurationProperties("deskseed.customer-auth")
internal data class CustomerAuthProperties(
    var magicLinkTtl: Duration = Duration.ofMinutes(15),
    var requestLimit: Int = 5,
    var requestWindow: Duration = Duration.ofMinutes(15),
    var responseMinDuration: Duration = Duration.ofMillis(100),
    var sessionIdle: Duration = Duration.ofMinutes(30),
    var sessionAbsolute: Duration = Duration.ofHours(12),
    var fingerprintKey: String = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
    var csrfKey: String = "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=",
    var consumeUrl: String = "http://localhost:5173/customer/sign-in/consume",
) {
    fun validate() {
        require(magicLinkTtl in Duration.ofMinutes(5)..Duration.ofMinutes(60)) {
            "customer magic-link TTL must be between 5 and 60 minutes"
        }
        require(requestLimit in 1..100) { "customer magic-link request limit is invalid" }
        require(!requestWindow.isZero && !requestWindow.isNegative) { "customer magic-link request window is invalid" }
        require(responseMinDuration in Duration.ZERO..Duration.ofSeconds(2)) { "customer auth response padding is invalid" }
        require(!sessionIdle.isZero && !sessionIdle.isNegative) { "customer session idle duration is invalid" }
        require(sessionAbsolute >= sessionIdle) { "customer absolute session duration must cover idle duration" }
        listOf(fingerprintKey, csrfKey).forEach { encoded ->
            require(Base64.getDecoder().decode(encoded).size >= 32) {
                "customer authentication keys must contain at least 32 bytes"
            }
        }
        val consumeUri = URI(consumeUrl)
        require(
            consumeUri.isAbsolute && consumeUri.host != null && consumeUri.scheme in setOf("http", "https") &&
                consumeUri.rawQuery == null && consumeUri.rawFragment == null,
        ) { "customer magic-link consume URL is invalid" }
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CustomerAuthProperties::class)
internal class CustomerAuthConfiguration {
    @Bean
    fun customerSessionAuthenticationServletRegistration(
        filter: CustomerSessionAuthenticationFilter,
    ) = FilterRegistrationBean(filter).apply { isEnabled = false }

    @Bean
    fun customerCsrfServletRegistration(
        filter: CustomerCsrfFilter,
    ) = FilterRegistrationBean(filter).apply { isEnabled = false }
}
