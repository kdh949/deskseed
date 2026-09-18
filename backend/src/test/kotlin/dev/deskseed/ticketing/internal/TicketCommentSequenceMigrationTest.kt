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

@Testcontainers
@dev.deskseed.testsupport.category.MigrationTest
class TicketCommentSequenceMigrationTest {
    @Test
    fun `version ninety four backfills deterministic order and assigns later sequence`() {
        migrateTo("93")
        connection().use { jdbc ->
            jdbc.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    insert into tickets (
                        id, ticket_number, kind, subject, status, priority, channel,
                        version, created_at, updated_at
                    ) values (
                        '94000000-0000-0000-0000-000000000001', 9401, 'INTERNAL_WORK_ITEM',
                        'AI ordering migration', 'OPEN', 'NORMAL', 'API', 0,
                        '2026-09-19T00:00:00Z', '2026-09-19T00:00:00Z'
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    insert into ticket_comments (
                        id, ticket_id, author_type, visibility, body, created_at
                    ) values
                        ('94000000-0000-0000-0000-000000000003', '94000000-0000-0000-0000-000000000001',
                         'SYSTEM', 'PUBLIC', 'third by id', '2026-09-19T00:00:01Z'),
                        ('94000000-0000-0000-0000-000000000002', '94000000-0000-0000-0000-000000000001',
                         'SYSTEM', 'PUBLIC', 'second by id', '2026-09-19T00:00:01Z'),
                        ('94000000-0000-0000-0000-000000000004', '94000000-0000-0000-0000-000000000001',
                         'SYSTEM', 'INTERNAL', 'later', '2026-09-19T00:00:02Z')
                    """.trimIndent(),
                )
            }
        }

        migrateTo("94")

        connection().use { jdbc ->
            jdbc.createStatement().use { statement ->
                statement.executeQuery(
                    """
                    select id, sequence_number
                    from ticket_comments
                    where ticket_id = '94000000-0000-0000-0000-000000000001'
                    order by sequence_number
                    """.trimIndent(),
                ).use { result ->
                    val rows = buildList {
                        while (result.next()) add(result.getString("id") to result.getLong("sequence_number"))
                    }
                    assertThat(rows).containsExactly(
                        "94000000-0000-0000-0000-000000000002" to 1L,
                        "94000000-0000-0000-0000-000000000003" to 2L,
                        "94000000-0000-0000-0000-000000000004" to 3L,
                    )
                }
                statement.executeUpdate(
                    """
                    insert into ticket_comments (
                        id, ticket_id, author_type, visibility, body, created_at
                    ) values (
                        '94000000-0000-0000-0000-000000000005',
                        '94000000-0000-0000-0000-000000000001',
                        'SYSTEM', 'PUBLIC', 'new comment', '2026-09-19T00:00:03Z'
                    )
                    """.trimIndent(),
                )
                statement.executeQuery(
                    """
                    select sequence_number from ticket_comments
                    where id = '94000000-0000-0000-0000-000000000005'
                    """.trimIndent(),
                ).use { result ->
                    assertThat(result.next()).isTrue()
                    assertThat(result.getLong(1)).isEqualTo(4L)
                }
                assertThatThrownBy {
                    statement.executeUpdate(
                        """
                        insert into ticket_comments (
                            id, ticket_id, author_type, visibility, body, created_at, sequence_number
                        ) values (
                            '94000000-0000-0000-0000-000000000006',
                            '94000000-0000-0000-0000-000000000001',
                            'SYSTEM', 'PUBLIC', 'duplicate sequence', '2026-09-19T00:00:04Z', 4
                        )
                        """.trimIndent(),
                    )
                }.hasMessageContaining("ticket_comment_sequence_unique")
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
