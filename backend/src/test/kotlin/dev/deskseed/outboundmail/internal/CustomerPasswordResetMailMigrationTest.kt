package dev.deskseed.outboundmail.internal

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager

@Testcontainers
@dev.deskseed.testsupport.category.MigrationTest
class CustomerPasswordResetMailMigrationTest {
    @BeforeEach
    fun cleanDatabase() {
        flyway().clean()
    }

    @Test
    fun `V82 clean migration allows password reset while preserving every mail template`() {
        migrateTo("82")

        connection().use { connection ->
            connection.createStatement().use { statement ->
                assertThat(templateConstraint(statement))
                    .contains(
                        "CUSTOMER_PASSWORD_RESET",
                        "CUSTOMER_REGISTRATION_VERIFICATION",
                        "CUSTOMER_MAGIC_LINK",
                        "REQUEST_RECEIVED",
                        "PUBLIC_AGENT_REPLY",
                    )
                assertThat(
                    statement.executeQuery(
                        "select count(*) from flyway_schema_history where version = '82' and success",
                    ).use { result -> check(result.next()); result.getLong(1) },
                ).isEqualTo(1)
            }
        }
    }

    @Test
    fun `V81 point one upgrade expands the allowlist without changing existing mail rows`() {
        migrateTo("81.1")
        connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    insert into outbound_mail_intents
                        (id, idempotency_key, stable_message_id, template_key, template_version,
                         sender_address, recipient_address, subject, text_body, status,
                         actor_type, source, request_id, correlation_id, command_id,
                         attempt_count, max_attempts, next_attempt_at, queued_at, version)
                    values ('00000000-0000-4000-8000-000000008401', 'password-reset-migration-mail',
                            '<password-reset-migration@deskseed.invalid>', 'CUSTOMER_REGISTRATION_VERIFICATION', 1,
                            'sender@example.test', 'recipient@example.test', 'Synthetic', 'Synthetic',
                            'QUEUED', 'SYSTEM', 'CUSTOMER_PORTAL', 'request-reset-migration',
                            'correlation-reset-migration', 'command-reset-migration', 0, 5, now(), now(), 0)
                    """.trimIndent(),
                )
            }
        }

        migrateTo("82")

        connection().use { connection ->
            connection.createStatement().use { statement ->
                assertThat(templateConstraint(statement)).contains("CUSTOMER_PASSWORD_RESET")
                assertThat(
                    statement.executeQuery(
                        "select template_key from outbound_mail_intents where id = '00000000-0000-4000-8000-000000008401'",
                    ).use { result -> check(result.next()); result.getString(1) },
                ).isEqualTo("CUSTOMER_REGISTRATION_VERIFICATION")
            }
        }
    }

    private fun templateConstraint(statement: java.sql.Statement): String = statement.executeQuery(
        """
        select pg_get_constraintdef(oid)
          from pg_constraint
         where conname = 'outbound_mail_template_valid'
        """.trimIndent(),
    ).use { result -> check(result.next()); result.getString(1) }

    private fun migrateTo(version: String) {
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .target(version)
            .load()
            .migrate()
    }

    private fun flyway(): Flyway = Flyway.configure()
        .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        .locations("classpath:db/migration")
        .cleanDisabled(false)
        .load()

    private fun connection() = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    private companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
    }
}
