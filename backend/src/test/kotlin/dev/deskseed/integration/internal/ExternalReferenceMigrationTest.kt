package dev.deskseed.integration.internal

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
class ExternalReferenceMigrationTest {
    @Test
    fun `version twenty two upgrades version twenty one and enforces bounded unique external references`() {
        migrateTo("21")
        insertBaseTicket()

        migrateTo("22")

        connection().use { jdbc ->
            jdbc.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    insert into external_systems
                        (id, system_key, display_name, status, allowed_hostnames_json,
                         created_by_staff_id, created_at, updated_at, version)
                    values
                        ('$SYSTEM_ID', 'shop-order', 'Shop Order', 'ACTIVE', '["admin.shop.example"]',
                         '$STAFF_ID', now(), now(), 0)
                    """.trimIndent(),
                )
                statement.executeUpdate(externalReferenceInsert(REFERENCE_ID, TICKET_ID, "order-100"))

                statement.executeQuery(
                    "select object_type, created_by_actor_display from external_references where id = '$REFERENCE_ID'",
                ).use { result ->
                    assertThat(result.next()).isTrue()
                    assertThat(result.getString(1)).isEqualTo("ORDER")
                    assertThat(result.getString(2)).isEqualTo("Migration Agent")
                }
                assertThatThrownBy {
                    statement.executeUpdate(externalReferenceInsert(SECOND_REFERENCE_ID, TICKET_ID, "order-100"))
                }.isInstanceOf(SQLException::class.java)
                assertThatThrownBy {
                    statement.executeUpdate(
                        externalReferenceInsert(THIRD_REFERENCE_ID, TICKET_ID, "order-101", "http://admin.shop.example/orders/101"),
                    )
                }.isInstanceOf(SQLException::class.java)
                assertThatThrownBy {
                    statement.executeUpdate(
                        externalReferenceInsert(
                            FOURTH_REFERENCE_ID,
                            TICKET_ID,
                            "order-102",
                            metadata = "{\"status\":\"${"x".repeat(2048)}\"}",
                        ),
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

    private fun insertBaseTicket() {
        connection().use { jdbc ->
            jdbc.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    insert into staff_accounts
                        (id, email_normalized, email_display, display_name, role, status,
                         password_hash, created_at, updated_at, version)
                    values
                        ('$STAFF_ID', 'migration-agent@example.com', 'migration-agent@example.com',
                         'Migration Agent', 'AGENT', 'ACTIVE', 'hash', now(), now(), 0)
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    insert into customers
                        (id, name, email_normalized, email_display, created_at, updated_at)
                    values
                        ('$CUSTOMER_ID', 'Migration Customer', 'external-migration@example.com',
                         'external-migration@example.com', now(), now())
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    insert into tickets
                        (id, ticket_number, requester_id, kind, subject, status, priority,
                         channel, version, created_at, updated_at)
                    values
                        ('$TICKET_ID', 1919, '$CUSTOMER_ID', 'CUSTOMER_REQUEST',
                         'External reference migration', 'OPEN', 'NORMAL', 'WEB', 0, now(), now())
                    """.trimIndent(),
                )
            }
        }
    }

    private fun externalReferenceInsert(
        id: String,
        ticketId: String,
        externalId: String,
        deepLink: String = "https://admin.shop.example/orders/100",
        metadata: String = "{\"status\":\"paid\"}",
    ) =
        """
        insert into external_references
            (id, ticket_id, external_system_id, object_type, external_id, display_label,
             safe_deep_link, metadata_snapshot_json, metadata_observed_at,
             created_by_actor_type, created_by_actor_id, created_by_actor_display, created_at)
        values
            ('$id', '$ticketId', '$SYSTEM_ID', 'ORDER', '$externalId', 'Order 100',
             '$deepLink', '$metadata', now(), 'STAFF', '$STAFF_ID', 'Migration Agent', now())
        """.trimIndent()

    private fun connection() = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    companion object {
        private const val STAFF_ID = "10000000-0000-0000-0000-000000000019"
        private const val CUSTOMER_ID = "20000000-0000-0000-0000-000000000019"
        private const val TICKET_ID = "30000000-0000-0000-0000-000000000019"
        private const val SYSTEM_ID = "40000000-0000-0000-0000-000000000019"
        private const val REFERENCE_ID = "50000000-0000-0000-0000-000000000019"
        private const val SECOND_REFERENCE_ID = "50000000-0000-0000-0000-000000000020"
        private const val THIRD_REFERENCE_ID = "50000000-0000-0000-0000-000000000021"
        private const val FOURTH_REFERENCE_ID = "50000000-0000-0000-0000-000000000022"

        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
    }
}
