package dev.deskseed.foundation

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Configuration(proxyBeanMethods = false)
class SecurityConfiguration(
    @Value("\${deskseed.cors.allowed-origins:http://localhost:5173}")
    private val allowedOrigins: String,
    @Value("\${deskseed.staff-auth.password-cost:12}")
    private val passwordCost: Int,
) {
    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder(passwordCost)

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val configuration = CorsConfiguration().apply {
            allowedOrigins = this@SecurityConfiguration.allowedOrigins
                .split(',')
                .map(String::trim)
                .filter(String::isNotBlank)
            allowedMethods = listOf("GET", "POST", "PATCH", "DELETE", "OPTIONS")
            allowedHeaders = listOf(
                "Content-Type",
                "Accept",
                "X-CSRF-TOKEN",
                "X-Request-Access-Token",
                "X-Interaction-Id",
                "X-Deskseed-Read-Intent",
                "X-Origin-Search-Event-Id",
                "If-Match",
                RequestIdFilter.REQUEST_ID_HEADER,
                RequestIdFilter.CORRELATION_ID_HEADER,
            )
            exposedHeaders = listOf(
                "Location",
                "ETag",
                RequestIdFilter.REQUEST_ID_HEADER,
                RequestIdFilter.CORRELATION_ID_HEADER,
            )
            allowCredentials = true
            maxAge = 3600
        }

        return UrlBasedCorsConfigurationSource().also {
            it.registerCorsConfiguration("/**", configuration)
        }
    }
}
