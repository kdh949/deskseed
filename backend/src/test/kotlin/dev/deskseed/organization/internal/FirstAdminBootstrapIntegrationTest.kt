package dev.deskseed.organization.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

@SpringBootTest
@Testcontainers
class FirstAdminBootstrapIntegrationTest {
    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var runner: FirstAdminBootstrapRunner

    @Test
    fun `password file bootstraps exactly one admin without persisting the secret`() {
        val account = jdbcTemplate.queryForMap(
            """
            select email_normalized, display_name, role, status, password_hash
            from staff_accounts
            where email_normalized = 'first-admin@example.com'
            """.trimIndent(),
        )
        assertThat(account["display_name"]).isEqualTo("최초 관리자")
        assertThat(account["role"]).isEqualTo("ADMIN")
        assertThat(account["status"]).isEqualTo("ACTIVE")
        val passwordHash = account["password_hash"].toString()
        assertThat(passwordHash).doesNotContain(bootstrapPassword)
        assertThat(BCryptPasswordEncoder().matches(bootstrapPassword, passwordHash)).isTrue()

        val auditJson = jdbcTemplate.queryForObject(
            "select metadata_json from admin_security_audit_events where event_type = 'STAFF_CREATED'",
            String::class.java,
        )
        assertThat(auditJson).contains("PASSWORD_FILE").doesNotContain(bootstrapPassword)

        runner.run(DefaultApplicationArguments())
        assertThat(jdbcTemplate.queryForObject("select count(*) from staff_accounts", Long::class.java))
            .isEqualTo(1)
    }

    companion object {
        private val bootstrapPassword = "T!${UUID.randomUUID()}-bootstrap"
        private val passwordFile: Path = Files.createTempFile("deskseed-bootstrap-", ".secret").also {
            Files.writeString(it, "$bootstrapPassword\n")
        }

        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))

        @DynamicPropertySource
        @JvmStatic
        fun bootstrapProperties(registry: DynamicPropertyRegistry) {
            registry.add("deskseed.staff-auth.bootstrap.email") { "first-admin@example.com" }
            registry.add("deskseed.staff-auth.bootstrap.display-name") { "최초 관리자" }
            registry.add("deskseed.staff-auth.bootstrap.password-file") { passwordFile.toString() }
        }

        @AfterAll
        @JvmStatic
        fun removeSecretFile() {
            Files.deleteIfExists(passwordFile)
        }
    }
}
