package dev.deskseed.customerauth.internal

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.time.Duration

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
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CustomerAuthProperties::class)
internal class CustomerAuthConfiguration
