package dev.deskseed.foundation

import com.scalar.maven.webmvc.ScalarWebMvcController
import com.scalar.maven.webmvc.SpringBootScalarProperties
import org.springdoc.core.models.GroupedOpenApi
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "scalar", name = ["enabled"], havingValue = "true")
@EnableConfigurationProperties(SpringBootScalarProperties::class)
internal class ApiDocumentationConfiguration {
    @Bean
    fun scalarApiReferenceController(): ScalarWebMvcController = ScalarWebMvcController()

    @Bean
    fun coreApiDocumentation(): GroupedOpenApi = GroupedOpenApi.builder()
        .group("core")
        .pathsToMatch(
            "/api/v1/requests/**",
            "/api/v1/agent/**",
            "/api/v1/admin/**",
            "/api/v1/audit/**",
            "/api/v1/analytics/**",
        )
        .build()

    @Bean
    fun customerIdentityApiDocumentation(): GroupedOpenApi = GroupedOpenApi.builder()
        .group("customer-identity")
        .pathsToMatch("/api/v1/customer/**")
        .build()

    @Bean
    fun platformApiDocumentation(): GroupedOpenApi = GroupedOpenApi.builder()
        .group("platform")
        .pathsToMatch("/api/v1/platform/**")
        .build()
}
