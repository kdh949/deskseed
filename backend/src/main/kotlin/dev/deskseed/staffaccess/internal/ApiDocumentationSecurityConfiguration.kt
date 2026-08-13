package dev.deskseed.staffaccess.internal

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.context.SecurityContextHolderFilter

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "scalar", name = ["enabled"], havingValue = "true")
internal class ApiDocumentationSecurityConfiguration(
    private val authenticationEntryPoint: StaffAuthenticationEntryPoint,
    private val accessDeniedHandler: StaffAccessDeniedHandler,
    private val sessionValidationFilter: StaffSessionValidationFilter,
    @Value("\${deskseed.api-docs.require-admin:false}")
    private val requireAdmin: Boolean,
) {
    @Bean
    @Order(2)
    fun apiDocumentationSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .securityMatcher("/docs/api", "/docs/api/**", "/api-docs/specs/**", "/v3/api-docs/**")
            .csrf { it.disable() }
            .cors { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED) }
            .securityContext { it.requireExplicitSave(true) }
            .requestCache { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .headers {
                it.contentSecurityPolicy { policy ->
                    policy.policyDirectives(
                        "default-src 'self'; " +
                            "script-src 'self' 'unsafe-inline'; " +
                            "style-src 'self' 'unsafe-inline'; " +
                            "img-src 'self' data:; " +
                            "font-src 'self' data:; " +
                            "connect-src 'self'; " +
                            "object-src 'none'; base-uri 'none'; frame-ancestors 'none'",
                    )
                }
                it.frameOptions { frame -> frame.deny() }
            }
            .exceptionHandling {
                it.authenticationEntryPoint(authenticationEntryPoint)
                it.accessDeniedHandler(accessDeniedHandler)
            }
            .authorizeHttpRequests { authorization ->
                if (requireAdmin) {
                    authorization.anyRequest().hasRole("ADMIN")
                } else {
                    authorization.anyRequest().permitAll()
                }
            }
            .addFilterAfter(sessionValidationFilter, SecurityContextHolderFilter::class.java)

        return http.build()
    }
}
