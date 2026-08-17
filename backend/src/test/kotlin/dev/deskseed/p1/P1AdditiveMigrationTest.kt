package dev.deskseed.p1

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager

/**
 * P1 is forward-only: this proves a populated V29 schema upgrades without
 * rewriting prior data. Operational rollback is therefore application rollback
 * plus a forward repair, documented in the P1 task brief; it never deletes or
 * edits an applied Flyway history row.
 */
@Testcontainers
class P1AdditiveMigrationTest {
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
    fun `versions thirty through thirty four preserve V29 data and backfill saved view descriptions`() {
        migrateTo("29")
        insertV29ExportFixture()

        migrateTo("34")

        connection().use { jdbc ->
            jdbc.createStatement().use { statement ->
                assertThat(queryLong(statement, "select count(*) from staff_accounts where id = '$STAFF_ID'"))
                    .isEqualTo(1)
                assertThat(queryString(statement, "select status from audit_export_jobs where id = '$JOB_ID'"))
                    .isEqualTo("REQUESTED")
                assertThat(queryString(statement, "select state from audit_export_artifacts where job_id = '$JOB_ID'"))
                    .isEqualTo("PENDING")
                assertThat(queryLong(statement, "select count(*) from saved_ticket_views where scope = 'SYSTEM'"))
                    .isEqualTo(5)
                assertThat(queryLong(statement, "select count(*) from saved_ticket_views where description = ''"))
                    .isEqualTo(5)
                assertThat(queryLong(statement, "select count(*) from information_schema.columns where table_name = 'saved_ticket_views' and column_name = 'ticket_count_as_of'"))
                    .isZero()

                statement.executeUpdate(
                    "update saved_ticket_views set description = '  고객 이관 전 확인  ' where view_key = 'my-open'",
                )
                assertThat(queryString(statement, "select description from saved_ticket_views where view_key = 'my-open'"))
                    .isEqualTo("  고객 이관 전 확인  ")
                org.assertj.core.api.Assertions.assertThatThrownBy {
                    statement.executeUpdate(
                        "update saved_ticket_views set description = E'잘못된\\n설명' where view_key = 'my-open'",
                    )
                }.hasMessageContaining("saved_ticket_views_description_bounded")

                listOf(
                    "platform_rate_limit_buckets",
                    "attachment_objects",
                    "ticket_comment_attachments",
                    "saved_view_order_states",
                ).forEach { table ->
                    assertThat(queryString(statement, "select to_regclass('public.$table')"))
                        .isEqualTo(table)
                }
                assertThat(queryLong(statement, "select count(*) from information_schema.columns where table_name = 'audit_export_jobs' and column_name = 'lease_expires_at'"))
                    .isEqualTo(1)
                assertThat(queryLong(statement, "select count(*) from information_schema.columns where table_name = 'audit_export_artifacts' and column_name = 'checksum_sha256'"))
                    .isEqualTo(1)
                assertThat(queryLong(statement, "select count(*) from flyway_schema_history where version in ('30', '31', '32', '33', '34') and success"))
                    .isEqualTo(5)
            }
        }
    }

    private fun insertV29ExportFixture() {
        connection().use { jdbc ->
            jdbc.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    insert into staff_accounts (
                        id, email_normalized, email_display, display_name, role, status,
                        password_hash, created_at, updated_at, version
                    ) values (
                        '$STAFF_ID', 'p1-migration@example.com', 'p1-migration@example.com', 'P1 Migration',
                        'ADMIN', 'ACTIVE', 'hash', clock_timestamp(), clock_timestamp(), 0
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    insert into audit_export_jobs (
                        id, requester_id, status, format, filters_json, fields_json, reason,
                        permission_snapshot_json, request_id, correlation_id, interaction_id, created_at
                    ) values (
                        '$JOB_ID', '$STAFF_ID', 'REQUESTED', 'CSV', '{}'::jsonb, '["action"]'::jsonb,
                        'V29 compatibility fixture', '[]'::jsonb, 'p1-migration-request',
                        'p1-migration-correlation', '$INTERACTION_ID', clock_timestamp()
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    insert into audit_export_artifacts (job_id, state, generation_available, created_at)
                    values ('$JOB_ID', 'NOT_CREATED', false, clock_timestamp())
                    """.trimIndent(),
                )
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

    private fun queryLong(statement: java.sql.Statement, sql: String): Long =
        statement.executeQuery(sql).use { result ->
            check(result.next())
            result.getLong(1)
        }

    private companion object {
        const val STAFF_ID = "10000000-0000-4000-8000-000000000001"
        const val JOB_ID = "10000000-0000-4000-8000-000000000002"
        const val INTERACTION_ID = "10000000-0000-4000-8000-000000000003"

        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
    }
}
