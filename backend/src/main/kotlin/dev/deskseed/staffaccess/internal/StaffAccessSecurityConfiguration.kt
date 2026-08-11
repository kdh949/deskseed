package dev.deskseed.staffaccess.internal

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.context.SecurityContextHolderFilter
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
internal class StaffAccessSecurityConfiguration(
    private val authenticationEntryPoint: StaffAuthenticationEntryPoint,
    private val accessDeniedHandler: StaffAccessDeniedHandler,
    private val sessionValidationFilter: StaffSessionValidationFilter,
) {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        val csrfRepository = HttpSessionCsrfTokenRepository().apply {
            setHeaderName("X-CSRF-TOKEN")
        }
        http
            .csrf {
                it.csrfTokenRepository(csrfRepository)
                it.ignoringRequestMatchers("/api/v1/requests/**")
            }
            .cors { }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED) }
            .securityContext { it.requireExplicitSave(true) }
            .requestCache { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .headers {
                it.contentSecurityPolicy { policy ->
                    policy.policyDirectives("default-src 'none'; frame-ancestors 'none'")
                }
                it.frameOptions { frame -> frame.deny() }
            }
            .exceptionHandling {
                it.authenticationEntryPoint(authenticationEntryPoint)
                it.accessDeniedHandler(accessDeniedHandler)
            }
            .authorizeHttpRequests {
                it.requestMatchers(HttpMethod.POST, "/api/v1/requests").permitAll()
                it.requestMatchers(HttpMethod.GET, "/api/v1/requests/*").permitAll()
                it.requestMatchers(HttpMethod.GET, "/api/v1/agent/csrf").permitAll()
                it.requestMatchers(HttpMethod.POST, "/api/v1/agent/session").permitAll()
                it.requestMatchers(HttpMethod.DELETE, "/api/v1/agent/session").authenticated()
                it.requestMatchers(HttpMethod.GET, "/api/v1/agent/me").authenticated()
                it.requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                it.requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                it.requestMatchers(HttpMethod.POST, "/api/v1/audit/activities/*/search-query-reveal")
                    .hasAuthority("audit:search-query:reveal")
                it.requestMatchers(HttpMethod.POST, "/api/v1/audit/exports")
                    .hasAuthority("audit:export")
                it.requestMatchers(HttpMethod.GET, "/api/v1/audit/exports/*")
                    .hasAuthority("audit:export")
                it.requestMatchers(HttpMethod.POST, "/api/v1/audit/projection/rebuild")
                    .hasAuthority("audit:projection:rebuild")
                it.requestMatchers(HttpMethod.GET, "/api/v1/audit/**")
                    .hasAuthority("audit:activity:read")
                it.requestMatchers("/api/v1/audit/**").denyAll()
                it.requestMatchers("/api/v1/agent/**").hasAnyRole("ADMIN", "AGENT")
                it.anyRequest().denyAll()
            }
            .addFilterAfter(sessionValidationFilter, SecurityContextHolderFilter::class.java)

        return http.build()
    }
}
