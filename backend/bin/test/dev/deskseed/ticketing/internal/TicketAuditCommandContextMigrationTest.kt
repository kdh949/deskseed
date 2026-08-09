package dev.deskseed.ticketing.internal

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.sql.SQLException

@Testcontainers
class TicketAuditCommandContextMigrationTest {
    @Test
    fun `command context migration preserves append-only legacy audits`() {
        migrateTo("1")
        insertLegacyAudit()

        migrateTo("2")

        connection().use { jdbc ->
            jdbc.createStatement().use { statement ->
                statement.executeQuery(
                    """
                    select request_id, correlation_id, command_id
                    from ticket_audits
                    where id = '00000000-0000-0000-0000-000000000003'
                    """.trimIndent(),
                ).use { result ->
                    assertThat(result.next()).isTrue()
                    assertThat(result.getString("request_id")).isEqualTo("legacy-migration")
                    assertThat(result.getString("correlation_id")).isEqualTo("legacy-migration")
                    assertThat(result.getString("command_id")).isEqualTo("legacy-migration")
                }

                assertThatThrownBy {
                    statement.executeUpdate(
                        """
                        update ticket_audits
                        set source = 'MUTATED'
                        where id = '00000000-0000-0000-0000-000000000003'
                        """.trimIndent(),
                    )
                }.isInstanceOf(SQLException::class.java)
            }
        }
    }

    private fun migrateTo(version: String) {
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .target(version)
            .load()
            .migrate()
    }

    private fun insertLegacyAudit() {
        connection().use { jdbc ->
            jdbc.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    insert into customers
                        (id, name, email_normalized, email_display, created_at, updated_at)
                    values
                        ('00000000-0000-0000-0000-000000000001', 'Legacy Customer',
                         'legacy@example.com', 'legacy@example.com', now(), now())
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    insert into tickets
                        (id, ticket_number, requester_id, kind, subject, status, priority, channel, created_at, updated_at)
                    values
                        ('00000000-0000-0000-0000-000000000002', 1000,
                         '00000000-0000-0000-0000-000000000001', 'CUSTOMER_REQUEST', 'Legacy ticket',
                         'NEW', 'NORMAL', 'WEB', now(), now())
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    insert into ticket_audits
                        (id, ticket_id, ticket_version, actor_type, actor_id, source, created_at)
                    values
                        ('00000000-0000-0000-0000-000000000003',
                         '00000000-0000-0000-0000-000000000002', 0, 'CUSTOMER',
                         '00000000-0000-0000-0000-000000000001', 'WEB_FORM', now())
                    """.trimIndent(),
                )
            }
        }
    }

    private fun connection() = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
    }
}
