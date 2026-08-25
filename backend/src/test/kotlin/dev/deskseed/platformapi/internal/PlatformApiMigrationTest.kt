package dev.deskseed.platformapi.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import org.flywaydb.core.Flyway
import java.sql.DriverManager

@dev.deskseed.testsupport.category.MigrationTest
class PlatformApiMigrationTest {
    @Test
    fun `migration adds internal work items integration authors and bounded idempotency identity`() {
        PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine")).use { postgres ->
            postgres.start()
            Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password).load().migrate()

            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        insert into customers (id, name, email_normalized, email_display, created_at, updated_at)
                        values ('00000000-0000-0000-0000-000000000001', 'Admin', 'admin@example.com', 'admin@example.com', now(), now())
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        insert into tickets (id, ticket_number, requester_id, kind, subject, status, priority, channel, created_at, updated_at)
                        values ('00000000-0000-0000-0000-000000000002', 99001, null, 'INTERNAL_WORK_ITEM', 'Ops', 'NEW', 'NORMAL', 'API', now(), now())
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        insert into ticket_comments (id, ticket_id, author_type, author_id, visibility, body, created_at)
                        values ('00000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000002',
                                'INTEGRATION_CLIENT', '00000000-0000-0000-0000-000000000004', 'INTERNAL', 'Investigate', now())
                        """.trimIndent(),
                    )
                }

                val nullable = connection.prepareStatement(
                    "select requester_id is null from tickets where ticket_number = 99001",
                ).use { query ->
                    query.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
                }
                assertThat(nullable).isTrue()

                val uniqueColumns = connection.prepareStatement(
                    """
                    select count(*)
                    from information_schema.table_constraints
                    where table_name = 'platform_idempotency_records'
                      and constraint_name = 'platform_idempotency_identity_unique'
                      and constraint_type = 'UNIQUE'
                    """.trimIndent(),
                ).use { query ->
                    query.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
                }
                assertThat(uniqueColumns).isEqualTo(1)
            }
        }
    }
}
