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
@dev.deskseed.testsupport.category.MigrationTest
class TicketCommandAuditMigrationTest {
    @Test
    fun `version seven backfills expected version and preserves append-only audit protection`() {
        migrateTo("6")
        insertVersionSixAudit()

        migrateTo("7")

        connection().use { jdbc ->
            jdbc.createStatement().use { statement ->
                statement.executeQuery(
                    """
                    select expected_version, ticket_version
                    from ticket_audits
                    where id = '00000000-0000-0000-0000-000000000704'
                    """.trimIndent(),
                ).use { result ->
                    assertThat(result.next()).isTrue()
                    assertThat(result.getLong("expected_version")).isEqualTo(4)
                    assertThat(result.getLong("ticket_version")).isEqualTo(4)
                }
                statement.executeQuery(
                    """
                    select count(*)
                    from pg_indexes
                    where schemaname = 'public'
                      and tablename = 'ticket_audits'
                      and indexname = 'ticket_audits_conflict_fields_idx'
                    """.trimIndent(),
                ).use { result ->
                    assertThat(result.next()).isTrue()
                    assertThat(result.getLong(1)).isOne()
                }
                assertThatThrownBy {
                    statement.executeUpdate(
                        "update ticket_audits set expected_version = 0 where id = '00000000-0000-0000-0000-000000000704'",
                    )
                }.isInstanceOf(SQLException::class.java)
                assertThatThrownBy {
                    statement.executeUpdate(
                        "delete from ticket_audit_events where id = '00000000-0000-0000-0000-000000000705'",
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

    private fun insertVersionSixAudit() {
        connection().use { jdbc ->
            jdbc.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    insert into customers
                        (id, name, email_normalized, email_display, created_at, updated_at)
                    values
                        ('00000000-0000-0000-0000-000000000701', 'Migration Customer',
                         'command-migration@example.com', 'command-migration@example.com', now(), now())
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    insert into tickets
                        (id, ticket_number, requester_id, kind, subject, status, priority,
                         channel, version, created_at, updated_at)
                    values
                        ('00000000-0000-0000-0000-000000000702', 1702,
                         '00000000-0000-0000-0000-000000000701', 'CUSTOMER_REQUEST',
                         'Migration ticket', 'OPEN', 'HIGH', 'WEB', 4, now(), now())
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    insert into ticket_audits
                        (id, ticket_id, ticket_version, actor_type, actor_id, source,
                         request_id, correlation_id, command_id, created_at)
                    values
                        ('00000000-0000-0000-0000-000000000704',
                         '00000000-0000-0000-0000-000000000702', 4, 'STAFF', null,
                         'AGENT_UI', 'migration-request', 'migration-correlation',
                         'migration-command', now())
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    insert into ticket_audit_events
                        (id, audit_id, event_order, event_type, field_name,
                         old_value_json, new_value_json, metadata_json, occurred_at)
                    values
                        ('00000000-0000-0000-0000-000000000705',
                         '00000000-0000-0000-0000-000000000704', 1,
                         'PRIORITY_CHANGED', 'priority', '"NORMAL"', '"HIGH"', '{}', now())
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
