package dev.deskseed.foundation

import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.util.matcher.RequestMatcher
import org.springframework.web.context.support.WebApplicationContextUtils

/**
 * The management server is a child context, but it inherits the application's
 * Spring Security filter proxy. Register this chain in the parent context and
 * select it only when the request belongs to that child server. This keeps the
 * product port's existing staff/customer rules intact.
 */
@Configuration(proxyBeanMethods = false)
@Profile("personal-staging-observability")
class PersonalStagingManagementSecurityConfiguration {
    @Bean
    @Order(0)
    fun personalStagingManagementSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .securityMatcher(RequestMatcher(::isManagementServerRequest))
            .csrf { it.disable() }
            .cors { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .requestCache { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .authorizeHttpRequests {
                it.requestMatchers(RequestMatcher(::isAllowedManagementReadRequest)).permitAll()
                it.anyRequest().denyAll()
            }

        return http.build()
    }

    private fun isManagementServerRequest(request: HttpServletRequest): Boolean =
        (WebApplicationContextUtils.getWebApplicationContext(request.servletContext) as? WebServerApplicationContext)
            ?.serverNamespace == "management"

    private fun isAllowedManagementReadRequest(request: HttpServletRequest): Boolean {
        if (request.method != HttpMethod.GET.name()) {
            return false
        }

        val path = request.requestURI.removePrefix(request.contextPath)
        return path in MANAGEMENT_READ_PATHS || path.startsWith("/actuator/health/")
    }

    private companion object {
        val MANAGEMENT_READ_PATHS = setOf(
            "/actuator/health",
            "/actuator/info",
            "/actuator/prometheus",
        )
    }
}
