package dev.deskseed.ticketing.internal

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.time.Duration

@Testcontainers
class RequestVerticalSliceMigrationTest {
    @Test
    fun `existing request grants and audit events migrate to mandatory lifecycle metadata`() {
        migrateTo("2")
        insertVersionTwoFixture()

        migrateTo("4")

        connection().use { jdbc ->
            jdbc.createStatement().use { statement ->
                statement.executeQuery(
                    """
                    select created_at, expires_at, revoked_at
                    from request_access_tokens
                    where id = '00000000-0000-0000-0000-000000000005'
                    """.trimIndent(),
                ).use { result ->
                    assertThat(result.next()).isTrue()
                    val createdAt = result.getTimestamp("created_at").toInstant()
                    val expiresAt = result.getTimestamp("expires_at").toInstant()
                    assertThat(Duration.between(createdAt, expiresAt)).isEqualTo(Duration.ofDays(30))
                    assertThat(result.getTimestamp("revoked_at")).isNull()
                }
                statement.executeQuery(
                    """
                    select occurred_at
                    from ticket_audit_events
                    where id = '00000000-0000-0000-0000-000000000004'
                    """.trimIndent(),
                ).use { result ->
                    assertThat(result.next()).isTrue()
                    assertThat(result.getTimestamp("occurred_at").toInstant().toString())
                        .isEqualTo("2026-08-10T00:00:00Z")
                }
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

    private fun insertVersionTwoFixture() {
        connection().use { jdbc ->
            jdbc.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    insert into customers
                        (id, name, email_normalized, email_display, created_at, updated_at)
                    values
                        ('00000000-0000-0000-0000-000000000001', 'Migration Customer',
                         'migration@example.com', 'migration@example.com',
                         '2026-08-10T00:00:00Z', '2026-08-10T00:00:00Z')
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    insert into tickets
                        (id, ticket_number, requester_id, kind, subject, status, priority, channel, created_at, updated_at)
                    values
                        ('00000000-0000-0000-0000-000000000002', 1000,
                         '00000000-0000-0000-0000-000000000001', 'CUSTOMER_REQUEST', 'Migration ticket',
                         'NEW', 'NORMAL', 'WEB', '2026-08-10T00:00:00Z', '2026-08-10T00:00:00Z')
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    insert into ticket_audits
                        (id, ticket_id, ticket_version, actor_type, actor_id, source,
                         request_id, correlation_id, command_id, created_at)
                    values
                        ('00000000-0000-0000-0000-000000000003',
                         '00000000-0000-0000-0000-000000000002', 0, 'CUSTOMER',
                         '00000000-0000-0000-0000-000000000001', 'CUSTOMER_PORTAL',
                         'request-migration', 'correlation-migration', 'command-migration',
                         '2026-08-10T00:00:00Z')
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    insert into ticket_audit_events
                        (id, audit_id, event_order, event_type, metadata_json)
                    values
                        ('00000000-0000-0000-0000-000000000004',
                         '00000000-0000-0000-0000-000000000003', 1, 'TICKET_CREATED', '{}')
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    insert into request_access_tokens
                        (id, ticket_id, token_hash, created_at, expires_at, revoked_at)
                    values
                        ('00000000-0000-0000-0000-000000000005',
                         '00000000-0000-0000-0000-000000000002',
                         'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                         '2026-08-10T00:00:00Z', null, null)
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
