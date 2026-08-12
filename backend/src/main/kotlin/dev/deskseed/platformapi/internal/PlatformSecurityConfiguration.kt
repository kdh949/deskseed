package dev.deskseed.platformapi.internal

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter

@Configuration(proxyBeanMethods = false)
internal class PlatformSecurityConfiguration(
    private val securityFilter: PlatformSecurityFilter,
    private val problemWriter: PlatformProblemWriter,
) {
    @Bean
    @Order(1)
    fun platformSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .securityMatcher("/api/v1/platform/**")
            .csrf { it.disable() }
            .cors { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .securityContext { it.requireExplicitSave(true) }
            .requestCache { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .headers {
                it.contentSecurityPolicy { policy -> policy.policyDirectives("default-src 'none'; frame-ancestors 'none'") }
                it.frameOptions { frame -> frame.deny() }
            }
            .exceptionHandling {
                it.authenticationEntryPoint { request, response, _ ->
                    problemWriter.write(
                        request,
                        response,
                        401,
                        "/problems/platform-authentication-failed",
                        "Authentication failed",
                        "The supplied machine credential could not be authenticated.",
                    )
                }
                it.accessDeniedHandler { request, response, _ ->
                    problemWriter.write(
                        request,
                        response,
                        403,
                        "/problems/platform-access-denied",
                        "Access denied",
                        "The authenticated client is not allowed to perform this operation.",
                    )
                }
            }
            .authorizeHttpRequests { it.anyRequest().authenticated() }
            .addFilterBefore(securityFilter, AnonymousAuthenticationFilter::class.java)
        return http.build()
    }
}

