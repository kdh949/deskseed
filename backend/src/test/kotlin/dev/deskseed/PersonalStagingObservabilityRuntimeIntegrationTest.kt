package dev.deskseed

import dev.deskseed.foundation.PersonalStagingOpenTelemetryLogAppenderInitializer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalManagementPort
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.core.env.Environment
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.services.s3.S3Client
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Exercises the deployment profile combination rather than parsing its YAML.
 * The S3 client is replaced because this test proves HTTP/security wiring, not
 * an external object-store boundary; PostgreSQL remains real so aggregate
 * health continues to include a required production dependency.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.profiles.active=production",
        "spring.profiles.include=personal-staging-observability",
        "deskseed.staff-auth.bootstrap.enabled=false",
        "deskseed.scheduling.enabled=false",
        "management.server.port=0",
        "management.health.redis.enabled=false",
        "management.health.mail.enabled=false",
    ],
)
@Testcontainers
@dev.deskseed.testsupport.category.IntegrationTest
class PersonalStagingObservabilityRuntimeIntegrationTest {
    @Autowired private lateinit var environment: Environment

    @Autowired private lateinit var context: org.springframework.context.ApplicationContext

    @LocalServerPort private var applicationPort = 0

    @LocalManagementPort private var managementPort = 0

    @MockitoBean private lateinit var s3Client: S3Client

    private val http = HttpClient.newHttpClient()

    @Test
    fun `production plus included personal observability exposes only the private management read surface`() {
        assertThat(environment.activeProfiles)
            .contains("production", "personal-staging-observability")
            .doesNotContain("load")
        assertThat(context.getBeanNamesForType(PersonalStagingOpenTelemetryLogAppenderInitializer::class.java))
            .isNotEmpty
        assertThat(context.containsBean("personalStagingManagementSecurityFilterChain")).isTrue()
        assertThat(applicationPort).isPositive()
        assertThat(managementPort).isPositive()
        assertThat(managementPort).isNotEqualTo(applicationPort)

        val health = get(managementPort, "/actuator/health")
        assertThat(health.statusCode()).isEqualTo(200)
        assertThat(health.body()).contains("\"status\":\"UP\"")

        assertThat(get(managementPort, "/actuator/info").statusCode()).isEqualTo(200)

        val prometheus = get(managementPort, "/actuator/prometheus")
        assertThat(prometheus.statusCode()).isEqualTo(200)
        assertThat(prometheus.headers().firstValue("content-type").orElse(""))
            .startsWith("text/plain")
        assertThat(prometheus.body()).contains("# HELP")
        assertThat(get(managementPort, "/actuator/metrics").statusCode()).isEqualTo(403)

        // The separate management context must not loosen product-port security.
        assertThat(get(applicationPort, "/actuator/prometheus").statusCode()).isNotEqualTo(200)
        assertThat(get(applicationPort, "/api/v1/agent/me").statusCode()).isIn(401, 403)
        assertThat(get(applicationPort, "/api/v1/customer/me").statusCode()).isIn(401, 403)
    }

    private fun get(port: Int, path: String): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).GET().build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    companion object {
        private const val BASE64_32_BYTE_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        private const val CLAIM_SIGNING_KEY = "BAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQ="
        private const val CLAIM_FINGERPRINT_KEY = "BQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQU="
        private const val CLAIM_CURSOR_KEY = "BgYGBgYGBgYGBgYGBgYGBgYGBgYGBgYGBgYGBgYGBgY="
        private const val CURSOR_KEY = "deskseed-observability-runtime-test-cursor-signing-key"

        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("deskseed")

        @DynamicPropertySource
        @JvmStatic
        fun productionProperties(registry: DynamicPropertyRegistry) {
            registry.add("DATABASE_URL", postgres::getJdbcUrl)
            registry.add("DATABASE_MIGRATION_URL", postgres::getJdbcUrl)
            registry.add("DATABASE_RUNTIME_USERNAME", postgres::getUsername)
            registry.add("DATABASE_RUNTIME_PASSWORD", postgres::getPassword)
            registry.add("DATABASE_MIGRATION_USERNAME", postgres::getUsername)
            registry.add("DATABASE_MIGRATION_PASSWORD", postgres::getPassword)

            mapOf(
                "DESKSEED_CUSTOMER_AUTH_REDIS_HOST" to "redis",
                "DESKSEED_CUSTOMER_AUTH_REDIS_PORT" to "6379",
                "DESKSEED_CUSTOMER_AUTH_REDIS_USERNAME" to "deskseed",
                "DESKSEED_CUSTOMER_AUTH_REDIS_PASSWORD" to "test-password",
                "DESKSEED_CUSTOMER_AUTH_REDIS_TLS_ENABLED" to "true",
                "DESKSEED_PLATFORM_ALLOWED_CLIENT_CIDRS" to "127.0.0.1/32",
                "DESKSEED_PLATFORM_TRUSTED_PROXY_CIDRS" to "127.0.0.1/32",
                "DESKSEED_WEBHOOK_SECRET_KEY_V1" to BASE64_32_BYTE_KEY,
                "DESKSEED_MAIL_PROTECTED_KEY_V1" to BASE64_32_BYTE_KEY,
                "DESKSEED_MAIL_OPERATIONS_CURSOR_SIGNING_KEY" to CURSOR_KEY,
                "DESKSEED_CUSTOMER_AUTH_FINGERPRINT_KEY" to BASE64_32_BYTE_KEY,
                "DESKSEED_CUSTOMER_AUTH_CSRF_KEY" to BASE64_32_BYTE_KEY,
                "DESKSEED_CUSTOMER_AUTH_TRUSTED_PROXY_CIDRS" to "127.0.0.1/32",
                "DESKSEED_CUSTOMER_MAGIC_LINK_CONSUME_URL" to "https://deskseed.test/customer/sign-in/consume",
                "DESKSEED_CUSTOMER_REGISTRATION_VERIFICATION_URL" to "https://deskseed.test/customer/register/verify",
                "DESKSEED_CUSTOMER_PASSWORD_RESET_URL" to "https://deskseed.test/customer/password/reset",
                "DESKSEED_ATTACHMENT_SCAN_MODE" to "UPSTREAM_WAF",
                "DESKSEED_ATTACHMENT_UPSTREAM_WAF_ACKNOWLEDGED" to "true",
                "DESKSEED_ATTACHMENT_S3_ENDPOINT" to "https://storage.deskseed.test",
                "DESKSEED_ATTACHMENT_S3_REGION" to "us-east-1",
                "DESKSEED_ATTACHMENT_S3_BUCKET" to "deskseed-observability-test",
                "DESKSEED_ATTACHMENT_S3_ACCESS_KEY" to "test-access-key",
                "DESKSEED_ATTACHMENT_S3_SECRET_KEY" to "test-secret-key-material",
                "DESKSEED_PUBLIC_REQUEST_RATE_LIMIT_FINGERPRINT_KEY" to BASE64_32_BYTE_KEY,
                "DESKSEED_PUBLIC_REQUEST_RATE_LIMIT_TRUSTED_PROXY_CIDRS" to "127.0.0.1/32",
                "DESKSEED_CUSTOMER_CLAIM_SIGNING_KEY" to CLAIM_SIGNING_KEY,
                "DESKSEED_CUSTOMER_CLAIM_FINGERPRINT_KEY" to CLAIM_FINGERPRINT_KEY,
                "DESKSEED_CUSTOMER_REQUEST_CURSOR_SIGNING_KEY" to CLAIM_CURSOR_KEY,
                "DESKSEED_ACCESS_AUDIT_SESSION_FINGERPRINT_KEY" to BASE64_32_BYTE_KEY,
                "DESKSEED_ACCESS_AUDIT_KEY_V1" to BASE64_32_BYTE_KEY,
                "DESKSEED_AUDIT_CURSOR_SIGNING_KEY" to CURSOR_KEY,
                "DESKSEED_CORS_ALLOWED_ORIGINS" to "https://deskseed.test",
                "DESKSEED_AGENT_TICKET_CURSOR_SIGNING_KEY" to CURSOR_KEY,
            ).forEach { (name, value) -> registry.add(name) { value } }
        }
    }
}
