package dev.deskseed.staffaccess.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.core.io.ClassPathResource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

@SpringBootTest(properties = ["deskseed.staff-auth.bootstrap.enabled=false"])
@AutoConfigureMockMvc
@Testcontainers
class ApiDocumentationIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `development serves the Scalar reference and committed contracts`() {
        mockMvc.perform(get("/docs/api"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith("text/html"))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Deskseed Core API")))
            .andExpect(
                content().string(
                    org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("registry.scalar.com")),
                ),
            )
            .andExpect(
                header().string(
                    "Content-Security-Policy",
                    org.hamcrest.Matchers.containsString("default-src 'self'"),
                ),
            )

        listOf(
            "core-api-outline-v1.yaml",
            "customer-identity-api-v1.yaml",
            "platform-api-outline-v1.yaml",
        ).forEach { contract ->
            mockMvc.perform(get("/api-docs/specs/$contract"))
                .andExpect(status().isOk)
                .andExpect(content().string(org.hamcrest.Matchers.containsString("openapi: 3.1.0")))
        }
    }

    @Test
    fun `development exposes grouped runtime documents without replacing committed sources`() {
        listOf("core", "customer-identity", "platform").forEach { group ->
            mockMvc.perform(get("/v3/api-docs/$group"))
                .andExpect(status().isOk)
                .andExpect(content().contentTypeCompatibleWith("application/json"))
        }
    }

    @Test
    fun `production defaults disable documents and require admin when enabled`() {
        val properties = YamlPropertiesFactoryBean().apply {
            setResources(ClassPathResource("application-production.yml"))
        }.getObject()!!

        assertThat(properties.getProperty("springdoc.api-docs.enabled"))
            .isEqualTo("\${DESKSEED_API_DOCS_ENABLED:false}")
        assertThat(properties.getProperty("scalar.enabled"))
            .isEqualTo("\${DESKSEED_API_DOCS_ENABLED:false}")
        assertThat(properties.getProperty("scalar.hide-test-request-button")).isEqualTo("true")
        assertThat(properties.getProperty("scalar.hide-client-button")).isEqualTo("true")
        assertThat(properties.getProperty("deskseed.api-docs.require-admin")).isEqualTo("true")
    }

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
    }
}
