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
@dev.deskseed.testsupport.category.MigrationTest
class KnowledgeContentMigrationTest {
    @Test
    fun `V50 through V52 create fixed hierarchy immutable revisions and a derived PostgreSQL search projection without altering prior history`() {
        migrateTo("36")
        migrateTo("52")

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
                assertThat(queryString(statement, "select to_regclass('public.knowledge_search_documents')"))
                    .isEqualTo("knowledge_search_documents")
                assertThat(queryString(statement, "select to_regclass('public.knowledge_access_audit_events')"))
                    .isEqualTo("knowledge_access_audit_events")
                assertThat(queryLong(statement, "select count(*) from flyway_schema_history where version = '36' and success"))
                    .isEqualTo(1)
                assertThat(queryLong(statement, "select count(*) from flyway_schema_history where version = '50' and success"))
                    .isEqualTo(1)
                assertThat(queryLong(statement, "select count(*) from flyway_schema_history where version = '51' and success"))
                    .isEqualTo(1)
                assertThat(queryLong(statement, "select count(*) from flyway_schema_history where version = '52' and success"))
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

                statement.executeUpdate(
                    """
                    insert into staff_accounts
                        (id, email_normalized, email_display, display_name, role, status, password_hash, created_at, updated_at)
                    values ('00000000-0000-0000-0000-000000000053', 'knowledge-owner@example.test',
                            'knowledge-owner@example.test', 'Knowledge owner', 'ADMIN', 'ACTIVE', 'not-a-real-password-hash',
                            clock_timestamp(), clock_timestamp())
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    insert into knowledge_categories
                        (id, slug, title, description, status, display_order, created_at, updated_at)
                    values ('00000000-0000-0000-0000-000000000054', 'billing', 'Billing', '', 'ACTIVE', 0,
                            clock_timestamp(), clock_timestamp())
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    insert into knowledge_sections
                        (id, category_id, slug, title, description, status, display_order, created_at, updated_at)
                    values ('00000000-0000-0000-0000-000000000055', '00000000-0000-0000-0000-000000000054',
                            'payments', 'Payments', '', 'ACTIVE', 0, clock_timestamp(), clock_timestamp())
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    insert into knowledge_articles
                        (id, section_id, slug, lifecycle, audience_type, author_id, created_at, updated_at)
                    values ('00000000-0000-0000-0000-000000000056', '00000000-0000-0000-0000-000000000055',
                            'payment-help', 'DRAFT', 'PUBLIC', '00000000-0000-0000-0000-000000000053',
                            clock_timestamp(), clock_timestamp())
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    insert into knowledge_article_revisions
                        (id, article_id, revision_number, title, document_json, plain_text, content_checksum,
                         created_by_staff_id, created_at)
                    values ('00000000-0000-0000-0000-000000000057', '00000000-0000-0000-0000-000000000056', 1,
                            'Payment help', '{"schemaVersion": 1, "blocks": [{"type":"paragraph","text":"Use card"}]}'::jsonb,
                            E'Use card\\nSafely', repeat('b', 64), '00000000-0000-0000-0000-000000000053', clock_timestamp())
                    """.trimIndent(),
                )
                assertThatThrownBy {
                    statement.executeUpdate(
                        "update knowledge_article_revisions set title = 'Mutated' where id = '00000000-0000-0000-0000-000000000057'",
                    )
                }.hasMessageContaining("Knowledge article revisions are immutable")
                statement.executeUpdate(
                    """
                    update knowledge_articles
                       set lifecycle = 'PUBLISHED', current_published_revision_id = '00000000-0000-0000-0000-000000000057',
                           published_at = clock_timestamp()
                     where id = '00000000-0000-0000-0000-000000000056'
                    """.trimIndent(),
                )
                assertThat(queryLong(statement, "select count(*) from knowledge_search_documents where article_id = '00000000-0000-0000-0000-000000000056'"))
                    .isEqualTo(1)
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
