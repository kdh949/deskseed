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
class IntegrationClientMigrationTest {
    @Test
    fun `migration creates constrained credential storage without a raw secret column`() {
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                val secretColumns = statement.executeQuery(
                    """
                    select column_name
                    from information_schema.columns
                    where table_schema = 'public'
                      and table_name in ('integration_clients', 'integration_credentials')
                      and column_name in ('secret', 'api_key', 'raw_secret')
                    """.trimIndent(),
                ).use { result -> buildList { while (result.next()) add(result.getString(1)) } }
                assertThat(secretColumns).isEmpty()

                statement.executeUpdate(
                    """
                    insert into staff_accounts
                        (id, email_normalized, email_display, display_name, role, status,
                         password_hash, created_at, updated_at, version)
                    values ('10000000-0000-0000-0000-000000000001', 'admin@example.com',
                            'admin@example.com', '관리자', 'ADMIN', 'ACTIVE', 'hash', now(), now(), 0)
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    insert into integration_clients
                        (id, name, description, status, scopes_json, resource_constraints_json,
                         created_by_staff_id, created_at, updated_at)
                    values ('20000000-0000-0000-0000-000000000001', 'orders', '', 'ACTIVE',
                            '["tickets:read"]', '{}',
                            '10000000-0000-0000-0000-000000000001', now(), now())
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    credentialInsert("30000000-0000-0000-0000-000000000001", "public-key-id-0001", 1),
                )
                assertThatThrownBy {
                    statement.executeUpdate(
                        credentialInsert("30000000-0000-0000-0000-000000000002", "public-key-id-0002", 2),
                    )
                }.isInstanceOf(SQLException::class.java)
                assertThatThrownBy {
                    statement.executeUpdate(
                        """
                        insert into integration_clients
                            (id, name, description, status, scopes_json, resource_constraints_json,
                             created_by_staff_id, created_at, updated_at)
                        values ('20000000-0000-0000-0000-000000000002', 'unsupported', '', 'ACTIVE',
                                '["webhooks:manage"]', '{}',
                                '10000000-0000-0000-0000-000000000001', now(), now())
                        """.trimIndent(),
                    )
                }.isInstanceOf(SQLException::class.java)
            }
        }
    }

    private fun credentialInsert(id: String, publicKeyId: String, sequence: Int) =
        """
        insert into integration_credentials
            (id, client_id, sequence, public_key_id, secret_hash, status, expires_at,
             created_by_staff_id, created_at)
        values ('$id', '20000000-0000-0000-0000-000000000001', $sequence,
                '$publicKeyId', 'strong-verifier-value-that-is-long-enough', 'ACTIVE', now() + interval '1 day',
                '10000000-0000-0000-0000-000000000001', now())
        """.trimIndent()

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
    }
}
