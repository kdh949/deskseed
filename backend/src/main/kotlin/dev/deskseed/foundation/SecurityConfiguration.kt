package dev.deskseed.foundation

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.web.SecurityFilterChain
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Configuration(proxyBeanMethods = false)
class SecurityConfiguration(
    @Value("\${deskseed.cors.allowed-origins:http://localhost:5173}")
    private val allowedOrigins: String,
) {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .cors { }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .authorizeHttpRequests {
                it.requestMatchers(HttpMethod.POST, "/api/v1/requests").permitAll()
                it.requestMatchers(HttpMethod.GET, "/api/v1/requests/*").permitAll()
                it.requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                it.anyRequest().denyAll()
            }

        return http.build()
    }

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val configuration = CorsConfiguration().apply {
            allowedOrigins = this@SecurityConfiguration.allowedOrigins
                .split(',')
                .map(String::trim)
                .filter(String::isNotBlank)
            allowedMethods = listOf("GET", "POST", "OPTIONS")
            allowedHeaders = listOf(
                "Content-Type",
                "Accept",
                "X-Request-Access-Token",
                RequestIdFilter.REQUEST_ID_HEADER,
                RequestIdFilter.CORRELATION_ID_HEADER,
            )
            exposedHeaders = listOf(
                "Location",
                RequestIdFilter.REQUEST_ID_HEADER,
                RequestIdFilter.CORRELATION_ID_HEADER,
            )
            allowCredentials = false
            maxAge = 3600
        }

        return UrlBasedCorsConfigurationSource().also {
            it.registerCorsConfiguration("/**", configuration)
        }
    }
}
