package dev.deskseed.ticketing.internal

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager

@Testcontainers
class StaffTicketCommandReplayIndexMigrationTest {
    @Test
    fun `version fourteen adds the query aligned partial staff command replay index`() {
        migrateTo("13")

        connection().use { jdbc ->
            jdbc.createStatement().use { statement ->
                statement.executeQuery(
                    """
                    select count(*)
                    from pg_indexes
                    where schemaname = 'public'
                      and tablename = 'ticket_audits'
                      and indexname = 'ticket_audits_staff_command_replay_idx'
                    """.trimIndent(),
                ).use { result ->
                    assertThat(result.next()).isTrue()
                    assertThat(result.getLong(1)).isZero()
                }
            }
        }

        migrateTo("14")

        connection().use { jdbc ->
            jdbc.createStatement().use { statement ->
                statement.executeQuery(
                    """
                    select indexdef
                    from pg_indexes
                    where schemaname = 'public'
                      and tablename = 'ticket_audits'
                      and indexname = 'ticket_audits_staff_command_replay_idx'
                    """.trimIndent(),
                ).use { result ->
                    assertThat(result.next()).isTrue()
                    assertThat(result.getString("indexdef"))
                        .contains("(actor_id, command_id, created_at, id)")
                        .contains("actor_type", "STAFF", "actor_id IS NOT NULL")
                        .doesNotContain("UNIQUE INDEX")
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

    private fun connection() = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
    }
}
