package dev.deskseed.foundation

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration(proxyBeanMethods = false)
class ClockConfiguration {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
