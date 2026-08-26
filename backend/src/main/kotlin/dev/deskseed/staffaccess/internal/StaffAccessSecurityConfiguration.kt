package dev.deskseed.staffaccess.internal

import dev.deskseed.customerauth.CustomerCsrfFilter
import dev.deskseed.customerauth.CustomerSessionAuthenticationFilter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.context.SecurityContextHolderFilter
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher
import dev.deskseed.integration.INTEGRATION_CLIENT_MANAGE_AUTHORITY
import dev.deskseed.integration.EXTERNAL_SYSTEM_MANAGE_AUTHORITY
import dev.deskseed.webhook.WEBHOOK_MANAGE_AUTHORITY
import dev.deskseed.organization.StaffAuthorityCatalog

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
internal class StaffAccessSecurityConfiguration(
    private val authenticationEntryPoint: StaffAuthenticationEntryPoint,
    private val accessDeniedHandler: StaffAccessDeniedHandler,
    private val sessionValidationFilter: StaffSessionValidationFilter,
    private val customerSessionAuthenticationFilter: CustomerSessionAuthenticationFilter,
    private val customerCsrfFilter: CustomerCsrfFilter,
) {
    @Bean
    @Order(3)
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        val csrfRepository = HttpSessionCsrfTokenRepository().apply {
            setHeaderName("X-CSRF-TOKEN")
        }
        http
            .csrf {
                it.csrfTokenRepository(csrfRepository)
                it.ignoringRequestMatchers("/api/v1/requests/**", "/api/v1/customer/**")
                it.ignoringRequestMatchers(
                    PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/v1/help/search"),
                    PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/v1/help/articles/{articleSlug}/feedback"),
                )
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
                it.requestMatchers(HttpMethod.POST, "/api/v1/requests/*/comments").permitAll()
                it.requestMatchers(HttpMethod.POST, "/api/v1/requests/*/attachments/uploads").permitAll()
                it.requestMatchers(HttpMethod.GET, "/api/v1/requests/*/attachments/*/download").permitAll()
                it.requestMatchers(HttpMethod.POST, "/api/v1/requests/*/claim-grants").permitAll()
                it.requestMatchers(HttpMethod.GET, "/api/v1/help/**").permitAll()
                it.requestMatchers(HttpMethod.POST, "/api/v1/help/search").permitAll()
                it.requestMatchers(HttpMethod.POST, "/api/v1/help/articles/*/feedback").permitAll()
                it.requestMatchers(HttpMethod.GET, "/api/v1/agent/csrf").permitAll()
                it.requestMatchers(HttpMethod.POST, "/api/v1/agent/session").permitAll()
                it.requestMatchers(HttpMethod.POST, "/api/v1/customer/auth/magic-link-requests").permitAll()
                it.requestMatchers(HttpMethod.POST, "/api/v1/customer/auth/magic-link-sessions").permitAll()
                it.requestMatchers(HttpMethod.GET, "/api/v1/customer/access-mode").permitAll()
                it.requestMatchers(HttpMethod.GET, "/api/v1/customer/ticket-forms").permitAll()
                it.requestMatchers(HttpMethod.GET, "/api/v1/customer/consent-policies").permitAll()
                it.requestMatchers("/api/v1/customer/**").hasRole("CUSTOMER")
                it.requestMatchers(HttpMethod.DELETE, "/api/v1/agent/session").authenticated()
                it.requestMatchers(HttpMethod.GET, "/api/v1/agent/me").authenticated()
                it.requestMatchers("/ws/agent/collaboration").hasAnyRole("ADMIN", "AGENT")
                it.requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                it.requestMatchers("/api/v1/admin/integration-clients/**")
                    .hasAuthority(INTEGRATION_CLIENT_MANAGE_AUTHORITY)
                it.requestMatchers("/api/v1/admin/integrations/webhooks/**")
                    .hasAuthority(WEBHOOK_MANAGE_AUTHORITY)
                it.requestMatchers("/api/v1/admin/external-systems/**")
                    .hasAuthority(EXTERNAL_SYSTEM_MANAGE_AUTHORITY)
                it.requestMatchers(
                    "/api/v1/admin/customer-consent-policies",
                    "/api/v1/admin/customer-consent-policies/**",
                ).hasAuthority(StaffAuthorityCatalog.CUSTOMER_CONSENT_MANAGE)
                it.requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                it.requestMatchers(HttpMethod.GET, "/api/v1/analytics/**").hasAnyRole("ADMIN", "AGENT")
                it.requestMatchers(HttpMethod.POST, "/api/v1/audit/activities/*/search-query-reveal")
                    .authenticated()
                it.requestMatchers(HttpMethod.POST, "/api/v1/audit/exports")
                    .hasAuthority("audit:export")
                it.requestMatchers(HttpMethod.GET, "/api/v1/audit/exports/*/download")
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
            .addFilterAfter(customerSessionAuthenticationFilter, SecurityContextHolderFilter::class.java)
            .addFilterAfter(customerCsrfFilter, CustomerSessionAuthenticationFilter::class.java)

        return http.build()
    }
}
