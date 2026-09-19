package dev.deskseed.aiassistance.internal

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
class AiReplyRewriteMigrationTest {
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
    fun `V99 upgrades V98 with rewrite disabled and additive source binding`() {
        migrateTo("98")
        connection().use { jdbc ->
            jdbc.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    update ai_settings
                    set enabled = true, reply_draft_enabled = true, version = 9
                    where singleton = true
                    """.trimIndent(),
                )
            }
        }

        migrateTo("99")

        connection().use { jdbc ->
            jdbc.createStatement().use { statement ->
                statement.executeQuery(
                    """
                    select enabled, reply_draft_enabled, reply_rewrite_enabled, version
                    from ai_settings where singleton = true
                    """.trimIndent(),
                ).use { result ->
                    check(result.next())
                    assertThat(result.getBoolean("enabled")).isTrue()
                    assertThat(result.getBoolean("reply_draft_enabled")).isTrue()
                    assertThat(result.getBoolean("reply_rewrite_enabled")).isFalse()
                    assertThat(result.getLong("version")).isEqualTo(9)
                }
                statement.executeQuery(
                    """
                    select is_nullable
                    from information_schema.columns
                    where table_schema = 'public'
                      and table_name = 'ai_requests'
                      and column_name = 'source_job_id'
                    """.trimIndent(),
                ).use { result ->
                    check(result.next())
                    assertThat(result.getString("is_nullable")).isEqualTo("YES")
                }
                statement.executeQuery(
                    """
                    select conname
                    from pg_constraint
                    where conrelid = 'ai_requests'::regclass
                      and conname in ('ai_request_feature_valid', 'ai_request_rewrite_source_shape')
                    order by conname
                    """.trimIndent(),
                ).use { result ->
                    val names = buildList {
                        while (result.next()) add(result.getString("conname"))
                    }
                    assertThat(names).containsExactly(
                        "ai_request_feature_valid",
                        "ai_request_rewrite_source_shape",
                    )
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

    private fun connection() =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    private companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
    }
}
