package dev.deskseed.aiassistance.internal

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
class AiReplyRoutingMigrationTest {
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
    fun `V98 upgrades populated settings to fail closed reply routing defaults`() {
        migrateTo("97")
        connection().use { jdbc ->
            jdbc.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    update ai_settings
                    set enabled = true, reply_draft_enabled = true, version = 7
                    where singleton = true
                    """.trimIndent(),
                )
            }
        }

        migrateTo("98")

        connection().use { jdbc ->
            jdbc.createStatement().use { statement ->
                statement.executeQuery(
                    """
                    select enabled, reply_draft_enabled, version, reply_routing_mode,
                           reply_routing_rollout_percent,
                           reply_routing_evaluation_approval_version
                    from ai_settings where singleton = true
                    """.trimIndent(),
                ).use { result ->
                    check(result.next())
                    assertThat(result.getBoolean("enabled")).isTrue()
                    assertThat(result.getBoolean("reply_draft_enabled")).isTrue()
                    assertThat(result.getLong("version")).isEqualTo(7)
                    assertThat(result.getString("reply_routing_mode")).isEqualTo("STANDARD_ONLY")
                    assertThat(result.getInt("reply_routing_rollout_percent")).isZero()
                    assertThat(result.getString("reply_routing_evaluation_approval_version")).isNull()
                }
                statement.executeQuery(
                    "select to_regclass('public.ai_reply_routing_cohorts')",
                ).use { result ->
                    check(result.next())
                    assertThat(result.getString(1)).isEqualTo("ai_reply_routing_cohorts")
                }
                assertThatThrownBy {
                    statement.executeUpdate(
                        """
                        update ai_settings
                        set reply_routing_mode = 'EVALUATED_COHORT',
                            reply_routing_rollout_percent = 25,
                            reply_routing_evaluation_approval_version = 'holdout-v1'
                        where singleton = true
                        """.trimIndent(),
                    )
                }.hasMessageContaining("ai_settings_reply_routing_rollout_valid")
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

    private fun connection() =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    private companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
    }
}
