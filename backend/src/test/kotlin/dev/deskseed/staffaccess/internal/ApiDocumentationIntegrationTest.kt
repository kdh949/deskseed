package dev.deskseed.staffaccess.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.core.io.ClassPathResource
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import tools.jackson.databind.ObjectMapper

@SpringBootTest(properties = ["deskseed.staff-auth.bootstrap.enabled=false"])
@AutoConfigureMockMvc
@Testcontainers
class ApiDocumentationIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

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
    fun `runtime routes stay aligned with implemented committed contract operations`() {
        val runtimeOperations = listOf("core", "customer-identity", "platform")
            .flatMapTo(mutableSetOf()) { runtimeOperations(it) }
        val committedOperations = setOf(
            "core-api-outline-v1.yaml" to true,
            "customer-identity-api-v1.yaml" to true,
            "platform-api-outline-v1.yaml" to false,
        ).flatMapTo(mutableSetOf()) { (resource, frozenOnly) ->
            committedOperations(resource, frozenOnly)
        }

        assertThat(runtimeOperations).containsExactlyInAnyOrderElementsOf(committedOperations)
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

    private fun runtimeOperations(group: String): Set<String> {
        val body = mockMvc.perform(get("/v3/api-docs/$group"))
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString
        val paths = objectMapper.readTree(body).get("paths")
        val operations = mutableSetOf<String>()
        paths.propertyNames().forEach { path ->
            val pathItem = paths.path(path)
            pathItem.propertyNames().forEach { method ->
                if (method in HTTP_METHODS) {
                    operations += "${method.uppercase()} $path"
                }
            }
        }
        return operations
    }

    private fun committedOperations(resource: String, frozenOnly: Boolean): Set<String> {
        @Suppress("UNCHECKED_CAST")
        val document = ClassPathResource("static/api-docs/specs/$resource").inputStream.use {
            Yaml(LoaderOptions().apply { maxAliasesForCollections = 10_000 }).load<Map<String, Any?>>(it)
        }
        val paths = document["paths"] as Map<String, Map<String, Any?>>
        val pathPrefix = if (resource == "platform-api-outline-v1.yaml") "/api/v1/platform" else ""
        return buildSet {
            paths.forEach { (path, pathItem) ->
                pathItem.forEach { (method, candidate) ->
                    if (method !in HTTP_METHODS || candidate !is Map<*, *>) return@forEach
                    if (!frozenOnly || candidate["x-deskseed-contract-status"] == "FROZEN") {
                        add("${method.uppercase()} $pathPrefix$path")
                    }
                }
            }
        }
    }

    companion object {
        private val HTTP_METHODS = setOf("get", "post", "put", "patch", "delete", "head", "options", "trace")

        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
    }
}

@SpringBootTest(
    properties = [
        "deskseed.staff-auth.bootstrap.enabled=false",
        "springdoc.api-docs.enabled=true",
        "scalar.enabled=true",
        "deskseed.api-docs.require-admin=true",
    ],
)
@AutoConfigureMockMvc
@Testcontainers
class ApiDocumentationAdminSecurityIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `admin-only documentation rejects anonymous and agent access`() {
        mockMvc.perform(get("/docs/api"))
            .andExpect(status().isUnauthorized)

        mockMvc.perform(get("/docs/api").with(user("agent").roles("AGENT")))
            .andExpect(status().isForbidden)

        mockMvc.perform(get("/docs/api").with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk)
    }

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
    }
}
