package dev.deskseed.macro.internal

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
class MacroDefinitionMigrationTest {
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
    fun `V71 creates versioned personal and shared macro definitions`() {
        migrateTo("71")

        connection().use { jdbc ->
            jdbc.createStatement().use { statement ->
                listOf("macro_definitions", "macro_versions", "macro_actions", "macro_activations").forEach { table ->
                    assertThat(queryString(statement, "select to_regclass('public.$table')"))
                        .isEqualTo(table)
                }
                assertThat(queryString(statement, "select data_type from information_schema.columns where table_name = 'macro_actions' and column_name = 'configuration_json'"))
                    .isEqualTo("jsonb")
            }
        }
    }

    @Test
    fun `published versions actions and activation history are immutable`() {
        migrateTo("71")
        connection().use { jdbc ->
            jdbc.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    insert into staff_accounts (
                        id, email_normalized, email_display, display_name, role, status,
                        password_hash, created_at, updated_at, version
                    ) values (
                        '10000000-0000-4000-8000-000000000001', 'macro@example.com', 'macro@example.com',
                        'Macro Owner', 'AGENT', 'ACTIVE', 'hash', clock_timestamp(), clock_timestamp(), 0
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    insert into macro_definitions (
                        id, normalized_name, name, scope, owner_staff_id, current_version, active_version,
                        aggregate_version, created_at, updated_at
                    ) values (
                        '20000000-0000-4000-8000-000000000001', 'urgent reply', 'Urgent Reply', 'PERSONAL',
                        '10000000-0000-4000-8000-000000000001', 1, null, 1, clock_timestamp(), clock_timestamp()
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    insert into macro_versions (
                        macro_id, version, name, created_by_staff_id, created_by_display, created_at
                    ) values (
                        '20000000-0000-4000-8000-000000000001', 1, 'Urgent Reply',
                        '10000000-0000-4000-8000-000000000001', 'Macro Owner', clock_timestamp()
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    insert into macro_actions (macro_id, macro_version, ordinal, action_type, configuration_json)
                    values (
                        '20000000-0000-4000-8000-000000000001', 1, 0, 'COMMENT',
                        '{"visibility":"PUBLIC","body":"Hello"}'::jsonb
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    insert into macro_activations (
                        id, macro_id, macro_version, activation_state, actor_staff_id, actor_display,
                        source, request_id, correlation_id, occurred_at
                    ) values (
                        '30000000-0000-4000-8000-000000000001', '20000000-0000-4000-8000-000000000001', 1,
                        'ACTIVE', '10000000-0000-4000-8000-000000000001', 'Macro Owner',
                        'AGENT_UI', 'macro-request', 'macro-correlation', clock_timestamp()
                    )
                    """.trimIndent(),
                )

                listOf(
                    "update macro_versions set name = 'Changed' where macro_id = '20000000-0000-4000-8000-000000000001'",
                    "delete from macro_actions where macro_id = '20000000-0000-4000-8000-000000000001'",
                    "update macro_activations set activation_state = 'INACTIVE' where id = '30000000-0000-4000-8000-000000000001'",
                ).forEach { mutation ->
                    assertThatThrownBy { statement.executeUpdate(mutation) }
                        .hasMessageContaining("immutable")
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

    private fun queryString(statement: java.sql.Statement, sql: String): String? =
        statement.executeQuery(sql).use { result ->
            check(result.next())
            result.getString(1)
        }

    private companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
    }
}
