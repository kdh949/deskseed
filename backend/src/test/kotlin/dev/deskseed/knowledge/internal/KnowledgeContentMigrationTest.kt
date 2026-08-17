package dev.deskseed.knowledge.internal

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager

@Testcontainers
class KnowledgeContentMigrationTest {
    @Test
    fun `V50 and V51 create a fixed hierarchy and canonical multi-block revision storage without altering prior history`() {
        migrateTo("36")
        migrateTo("51")

        connection().use { jdbc ->
            jdbc.createStatement().use { statement ->
                assertThat(queryString(statement, "select to_regclass('public.knowledge_categories')"))
                    .isEqualTo("knowledge_categories")
                assertThat(queryString(statement, "select to_regclass('public.knowledge_sections')"))
                    .isEqualTo("knowledge_sections")
                assertThat(queryString(statement, "select to_regclass('public.knowledge_articles')"))
                    .isEqualTo("knowledge_articles")
                assertThat(queryString(statement, "select to_regclass('public.knowledge_article_revisions')"))
                    .isEqualTo("knowledge_article_revisions")
                assertThat(queryLong(statement, "select count(*) from flyway_schema_history where version = '36' and success"))
                    .isEqualTo(1)
                assertThat(queryLong(statement, "select count(*) from flyway_schema_history where version = '50' and success"))
                    .isEqualTo(1)
                assertThat(queryLong(statement, "select count(*) from flyway_schema_history where version = '51' and success"))
                    .isEqualTo(1)

                assertThatThrownBy {
                    statement.executeUpdate(
                        """
                        insert into knowledge_article_revisions (
                            id, article_id, revision_number, title, document_json, plain_text, content_checksum,
                            created_by_staff_id, created_at
                        ) values ('00000000-0000-0000-0000-000000000050', '00000000-0000-0000-0000-000000000051', 1,
                            'missing parent', '{}'::jsonb, 'plain', repeat('a', 64), '00000000-0000-0000-0000-000000000052', clock_timestamp())
                        """.trimIndent(),
                    )
                }.hasMessageContaining("knowledge_article_revisions_article_id_fkey")
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
        statement.executeQuery(sql).use { result -> check(result.next()); result.getString(1) }

    private fun queryLong(statement: java.sql.Statement, sql: String): Long =
        statement.executeQuery(sql).use { result -> check(result.next()); result.getLong(1) }

    private companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
    }
}
