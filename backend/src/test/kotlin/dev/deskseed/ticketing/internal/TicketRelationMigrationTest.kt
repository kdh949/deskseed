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
class TicketRelationMigrationTest {
    @Test
    fun `version eight upgrades existing tickets and enforces typed one parent non self relations`() {
        migrateTo("7")
        insertVersionSevenTickets()

        migrateTo("8")

        connection().use { jdbc ->
            jdbc.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    insert into ticket_relations
                        (id, source_ticket_id, target_ticket_id, relation_type,
                         created_by_actor_type, created_by_actor_id, created_at)
                    values
                        ('00000000-0000-0000-0000-000000000804',
                         '00000000-0000-0000-0000-000000000802',
                         '00000000-0000-0000-0000-000000000803',
                         'PARENT_CHILD', 'STAFF', null, now())
                    """.trimIndent(),
                )
                statement.executeQuery(
                    "select relation_type from ticket_relations where id = '00000000-0000-0000-0000-000000000804'",
                ).use { result ->
                    assertThat(result.next()).isTrue()
                    assertThat(result.getString(1)).isEqualTo("PARENT_CHILD")
                }
                assertThatThrownBy {
                    statement.executeUpdate(
                        """
                        insert into ticket_relations
                            (id, source_ticket_id, target_ticket_id, relation_type,
                             created_by_actor_type, created_by_actor_id, created_at)
                        values
                            ('00000000-0000-0000-0000-000000000805',
                             '00000000-0000-0000-0000-000000000802',
                             '00000000-0000-0000-0000-000000000802',
                             'PARENT_CHILD', 'STAFF', null, now())
                        """.trimIndent(),
                    )
                }.isInstanceOf(SQLException::class.java)
                assertThatThrownBy {
                    statement.executeUpdate(
                        """
                        insert into ticket_relations
                            (id, source_ticket_id, target_ticket_id, relation_type,
                             created_by_actor_type, created_by_actor_id, created_at)
                        values
                            ('00000000-0000-0000-0000-000000000806',
                             '00000000-0000-0000-0000-000000000807',
                             '00000000-0000-0000-0000-000000000803',
                             'PARENT_CHILD', 'STAFF', null, now())
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

    private fun insertVersionSevenTickets() {
        connection().use { jdbc ->
            jdbc.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    insert into customers
                        (id, name, email_normalized, email_display, created_at, updated_at)
                    values
                        ('00000000-0000-0000-0000-000000000801', 'Relation Migration Customer',
                         'relation-migration@example.com', 'relation-migration@example.com', now(), now())
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    insert into tickets
                        (id, ticket_number, requester_id, kind, subject, status, priority,
                         channel, version, created_at, updated_at)
                    values
                        ('00000000-0000-0000-0000-000000000802', 1802,
                         '00000000-0000-0000-0000-000000000801', 'CUSTOMER_REQUEST',
                         'Relation parent', 'OPEN', 'NORMAL', 'WEB', 0, now(), now()),
                        ('00000000-0000-0000-0000-000000000803', 1803,
                         '00000000-0000-0000-0000-000000000801', 'INTERNAL_CHILD',
                         'Relation child', 'NEW', 'NORMAL', 'AGENT', 0, now(), now()),
                        ('00000000-0000-0000-0000-000000000807', 1807,
                         '00000000-0000-0000-0000-000000000801', 'CUSTOMER_REQUEST',
                         'Second relation parent', 'OPEN', 'NORMAL', 'WEB', 0, now(), now())
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
