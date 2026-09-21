package dev.deskseed.ticketing.internal

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
class SplitTicketSearchProjectionMigrationTest {
    @BeforeEach
    fun resetSchema() {
        connection().use { jdbc ->
            jdbc.createStatement().use { statement ->
                statement.execute("drop schema public cascade")
                statement.execute("create schema public")
            }
        }
    }

    @Test
    fun `V95 is additive and leaves million row work to resumable batches`() {
        migrateTo("94")
        connection().use { jdbc ->
            jdbc.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    insert into tickets (
                        id, ticket_number, requester_id, kind, subject, status, priority,
                        group_id, assignee_id, channel, version, created_at, updated_at
                    ) values (
                        '95000000-0000-4000-8000-000000000001', 950001, null,
                        'INTERNAL_WORK_ITEM', 'V95 migration fixture', 'OPEN', 'NORMAL',
                        null, null, 'API', 0, clock_timestamp(), clock_timestamp()
                    )
                    """.trimIndent(),
                )
                assertThat(queryLong(statement, "select count(*) from ticket_search_documents")).isEqualTo(1L)
            }
        }

        migrateTo("95")

        connection().use { jdbc ->
            jdbc.createStatement().use { statement ->
                assertThat(queryLong(statement, "select count(*) from ticket_search_documents")).isEqualTo(1L)
                assertThat(queryLong(statement, "select count(*) from active_ticket_search_documents")).isZero()
                assertThat(queryLong(statement, "select count(*) from terminal_ticket_search_documents")).isZero()
                assertThat(queryString(statement, "select status from ticket_search_projection_backfill_state"))
                    .isEqualTo("PENDING")

                val firstBatch = statement.executeQuery(
                    "select batch_processed, total_processed, backfill_status " +
                        "from backfill_split_ticket_search_documents(1)",
                ).use { result ->
                    check(result.next())
                    Triple(result.getInt(1), result.getLong(2), result.getString(3))
                }
                assertThat(firstBatch).isEqualTo(Triple(1, 1L, "COMPLETE"))
                assertThat(queryLong(statement, "select count(*) from active_ticket_search_documents")).isEqualTo(1L)
                assertThat(queryLong(statement, "select missing_active_count from reconcile_split_ticket_search_documents()"))
                    .isZero()
            }
        }
    }

    @Test
    fun `terminal document accepts insert and delete but rejects updates`() {
        migrateTo("95")
        connection().use { jdbc ->
            jdbc.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    insert into tickets (
                        id, ticket_number, requester_id, kind, subject, status, priority,
                        group_id, assignee_id, channel, version, created_at, updated_at
                    ) values (
                        '95000000-0000-4000-8000-000000000002', 950002, null,
                        'INTERNAL_WORK_ITEM', 'immutable terminal fixture', 'CLOSED', 'NORMAL',
                        null, null, 'API', 0, clock_timestamp(), clock_timestamp()
                    )
                    """.trimIndent(),
                )
                assertThat(queryLong(statement, "select count(*) from terminal_ticket_search_documents")).isEqualTo(1L)
                assertThatThrownBy {
                    statement.executeUpdate(
                        "update terminal_ticket_search_documents set subject_text = 'forbidden' " +
                            "where ticket_number = 950002",
                    )
                }.hasMessageContaining("terminal ticket search documents are immutable")

                statement.executeUpdate(
                    "delete from tickets where id = '95000000-0000-4000-8000-000000000002'",
                )
                assertThat(queryLong(statement, "select count(*) from terminal_ticket_search_documents")).isZero()
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

    private fun queryString(statement: java.sql.Statement, sql: String): String? =
        statement.executeQuery(sql).use { result ->
            check(result.next())
            result.getString(1)
        }

    private fun queryLong(statement: java.sql.Statement, sql: String): Long =
        statement.executeQuery(sql).use { result ->
            check(result.next())
            result.getLong(1)
        }

    private companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
    }
}
