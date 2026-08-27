package dev.deskseed.staffaccess.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.core.io.ClassPathResource
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import tools.jackson.databind.ObjectMapper

@dev.deskseed.testsupport.integration.DeskseedSpringIntegrationTest
@AutoConfigureMockMvc
@dev.deskseed.testsupport.category.ContractTest
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
    fun `runtime routes stay within committed contracts and cover frozen operations`() {
        val runtimeOperations = listOf("core", "customer-identity", "platform")
            .flatMapTo(mutableSetOf()) { runtimeOperations(it) }
        val declaredOperations = setOf(
            "core-api-outline-v1.yaml",
            "customer-identity-api-v1.yaml",
            "platform-api-outline-v1.yaml",
        ).flatMapTo(mutableSetOf()) { resource ->
            committedOperations(resource, frozenOnly = false)
        }
        val frozenOperations = setOf(
            "core-api-outline-v1.yaml" to true,
            "customer-identity-api-v1.yaml" to true,
            "platform-api-outline-v1.yaml" to false,
        ).flatMapTo(mutableSetOf()) { (resource, frozenOnly) ->
            committedOperations(resource, frozenOnly)
        }

        assertThat(declaredOperations).containsAll(runtimeOperations)
        assertThat(runtimeOperations).containsAll(frozenOperations)
    }

    @Test
    fun `frozen customer session problem response matches the committed schema`() {
        val result = mockMvc.perform(
            put("/api/v1/customer/me/registration")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andReturn()

        val document = committedDocument("customer-identity-api-v1.yaml")
        val operation = document.mapAt("paths").mapAt("/api/v1/customer/me/registration").mapAt("put")
        val response = resolve(document, operation.mapAt("responses").mapAt("401"))
        val schema = resolve(
            document,
            response.mapAt("content").mapAt("application/problem+json").mapAt("schema"),
        )
        assertThat(schema["additionalProperties"]).isEqualTo(false)
        val properties = schema.mapAt("properties")
        @Suppress("UNCHECKED_CAST")
        val required = schema["required"] as List<String>
        @Suppress("UNCHECKED_CAST")
        val actual = objectMapper.readValue(result.response.contentAsString, Map::class.java) as Map<String, Any?>

        assertThat(actual.keys).containsAll(required).isEqualTo(properties.keys)
        properties.forEach { (name, rawDefinition) ->
            val value = actual[name] ?: return@forEach
            val definition = rawDefinition.asMap()
            definition["const"]?.let { expected ->
                assertThat(value).describedAs("%s const", name).isEqualTo(expected)
            }
            when (definition["type"]) {
                "string" -> assertThat(value).describedAs("%s type", name).isInstanceOf(String::class.java)
                "integer" -> assertThat(value).describedAs("%s type", name).isInstanceOf(Number::class.java)
            }
            (definition["maxLength"] as? Number)?.let { maximum ->
                assertThat(value.toString().length)
                    .describedAs("%s maxLength", name)
                    .isLessThanOrEqualTo(maximum.toInt())
            }
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
        val document = committedDocument(resource)
        @Suppress("UNCHECKED_CAST")
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

    private fun committedDocument(resource: String): Map<String, Any?> =
        ClassPathResource("static/api-docs/specs/$resource").inputStream.use {
            requireNotNull(
                Yaml(LoaderOptions().apply { maxAliasesForCollections = 10_000 }).load<Map<String, Any?>>(it),
            )
        }

    private fun resolve(document: Map<String, Any?>, value: Map<String, Any?>): Map<String, Any?> {
        val reference = value["\$ref"] as? String ?: return value
        require(reference.startsWith("#/")) { "only local OpenAPI references are supported: $reference" }
        var resolved: Any? = document
        reference.removePrefix("#/").split('/').forEach { part -> resolved = resolved.asMap()[part] }
        return resolved.asMap()
    }

    private fun Map<String, Any?>.mapAt(key: String): Map<String, Any?> = get(key).asMap()

    @Suppress("UNCHECKED_CAST")
    private fun Any?.asMap(): Map<String, Any?> = this as Map<String, Any?>

    companion object {
        private val HTTP_METHODS = setOf("get", "post", "put", "patch", "delete", "head", "options", "trace")
    }
}

@dev.deskseed.testsupport.integration.DeskseedSpringIntegrationTest(
    properties = [
        "deskseed.staff-auth.bootstrap.enabled=false",
        "springdoc.api-docs.enabled=true",
        "scalar.enabled=true",
        "deskseed.api-docs.require-admin=true",
    ],
)
@AutoConfigureMockMvc
@dev.deskseed.testsupport.category.ContractTest
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

}
