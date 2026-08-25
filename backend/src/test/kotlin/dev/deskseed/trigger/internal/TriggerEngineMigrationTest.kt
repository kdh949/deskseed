package dev.deskseed.trigger.internal

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
class TriggerEngineMigrationTest {
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
    fun `V73 creates versioned rules durable jobs and execution provenance`() {
        migrateTo("73")
        connection().use { jdbc ->
            jdbc.createStatement().use { statement ->
                listOf(
                    "trigger_definitions", "trigger_versions", "trigger_conditions", "trigger_actions",
                    "trigger_activations", "trigger_evaluation_jobs", "trigger_executions",
                ).forEach { table ->
                    assertThat(queryString(statement, "select to_regclass('public.$table')")).isEqualTo(table)
                }
                assertThat(queryString(
                    statement,
                    "select data_type from information_schema.columns where table_name = 'trigger_evaluation_jobs' and column_name = 'trigger_versions_json'",
                )).isEqualTo("jsonb")
            }
        }
    }

    @Test
    fun `rule versions conditions and actions are immutable`() {
        migrateTo("73")
        connection().use { jdbc ->
            jdbc.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    insert into staff_accounts (
                        id, email_normalized, email_display, display_name, role, status,
                        password_hash, created_at, updated_at, version
                    ) values (
                        '10000000-0000-4000-8000-000000000001', 'trigger@example.com', 'trigger@example.com',
                        'Trigger Admin', 'ADMIN', 'ACTIVE', 'hash', clock_timestamp(), clock_timestamp(), 0
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    insert into trigger_definitions (
                        id, normalized_name, name, position, current_version, active_version,
                        aggregate_version, created_at, updated_at
                    ) values (
                        '20000000-0000-4000-8000-000000000001', 'urgent routing', 'Urgent Routing',
                        10, 1, null, 1, clock_timestamp(), clock_timestamp()
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    insert into trigger_versions (
                        trigger_id, version, name, created_by_staff_id, created_by_display, created_at
                    ) values (
                        '20000000-0000-4000-8000-000000000001', 1, 'Urgent Routing',
                        '10000000-0000-4000-8000-000000000001', 'Trigger Admin', clock_timestamp()
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    insert into trigger_conditions (
                        trigger_id, trigger_version, ordinal, condition_group, field_name, operator, value_text
                    ) values (
                        '20000000-0000-4000-8000-000000000001', 1, 0, 'ALL', 'PRIORITY', 'IS', 'URGENT'
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    insert into trigger_actions (trigger_id, trigger_version, ordinal, action_type, configuration_json)
                    values (
                        '20000000-0000-4000-8000-000000000001', 1, 0, 'ENQUEUE_WEBHOOK',
                        '{"eventType":"ticket.trigger.executed"}'::jsonb
                    )
                    """.trimIndent(),
                )
                listOf(
                    "update trigger_versions set name = 'Changed' where trigger_id = '20000000-0000-4000-8000-000000000001'",
                    "delete from trigger_conditions where trigger_id = '20000000-0000-4000-8000-000000000001'",
                    "update trigger_actions set action_type = 'SET_GROUP' where trigger_id = '20000000-0000-4000-8000-000000000001'",
                ).forEach { mutation ->
                    assertThatThrownBy { statement.executeUpdate(mutation) }.hasMessageContaining("immutable")
                }
            }
        }
    }

    private fun migrateTo(version: String) {
        Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration").target(version).load().migrate()
    }

    private fun connection() = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    private fun queryString(statement: java.sql.Statement, sql: String): String? = statement.executeQuery(sql).use { result ->
        check(result.next())
        result.getString(1)
    }

    private companion object {
        @Container @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
    }
}
