package dev.deskseed.automation.internal

import org.assertj.core.api.Assertions.assertThat
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
class AutomationMigrationTest {
    @BeforeEach
    fun resetSchema() {
        connection().use { connection -> connection.createStatement().use { statement ->
            statement.execute("drop schema public cascade")
            statement.execute("create schema public")
        } }
    }

    @Test
    fun `V74 creates versioned solved automation candidates and execution provenance`() {
        Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration").target("74").load().migrate()
        connection().use { connection -> connection.createStatement().use { statement ->
            listOf(
                "automation_definitions", "automation_versions", "automation_activations",
                "automation_candidates", "automation_executions",
            ).forEach { table ->
                statement.executeQuery("select to_regclass('public.$table')").use { result ->
                    check(result.next())
                    assertThat(result.getString(1)).isEqualTo(table)
                }
            }
            statement.executeQuery(
                "select indexdef from pg_indexes where indexname = 'tickets_solved_automation_candidate_idx'",
            ).use { result ->
                check(result.next())
                assertThat(result.getString(1)).contains("solved_at", "status", "SOLVED")
            }
        } }
    }

    private fun connection() = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    private companion object {
        @Container @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
    }
}
