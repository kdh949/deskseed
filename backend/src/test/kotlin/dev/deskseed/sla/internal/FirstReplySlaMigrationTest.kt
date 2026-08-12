package dev.deskseed.sla.internal

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
class FirstReplySlaMigrationTest {
    @Test
    fun `migration creates indexed target facts scanner checkpoint and immutable policy history`() {
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        connection().use { jdbc ->
            jdbc.autoCommit = false
            jdbc.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    insert into staff_accounts
                        (id, email_normalized, email_display, display_name, role, status, password_hash,
                         created_at, updated_at)
                    values
                        ('61000000-0000-0000-0000-000000000001', 'sla-admin@example.com',
                         'sla-admin@example.com', 'SLA Admin', 'ADMIN', 'ACTIVE', 'fixture-hash', now(), now())
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    insert into sla_policies
                        (id, current_version, active_version, aggregate_version, created_at, updated_at)
                    values ('62000000-0000-0000-0000-000000000001', 1, null, 0, now(), now())
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    insert into sla_policy_versions
                        (policy_id, version, name, position, schedule_id, schedule_version,
                         condition_group_id, condition_channel, created_by_staff_id, created_by_display, created_at)
                    values
                        ('62000000-0000-0000-0000-000000000001', 1, 'Default First Reply', 10,
                         '51000000-0000-0000-0000-000000000001', 1, null, 'WEB',
                         '61000000-0000-0000-0000-000000000001', 'SLA Admin', now())
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    insert into sla_policy_priority_targets
                        (policy_id, policy_version, priority, target_minutes)
                    values ('62000000-0000-0000-0000-000000000001', 1, 'NORMAL', 60)
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    insert into sla_policy_pause_statuses (policy_id, policy_version, status)
                    values ('62000000-0000-0000-0000-000000000001', 1, 'PENDING')
                    """.trimIndent(),
                )
                jdbc.commit()

                assertThat(
                    statement.executeQuery(
                        "select count(*) from sla_breach_scan_state where id = 1",
                    ).use { result -> result.next(); result.getInt(1) },
                ).isEqualTo(1)
                assertThat(
                    statement.executeQuery(
                        """
                        select count(*) from pg_indexes
                         where indexname in ('sla_target_instances_breach_scan_idx',
                                             'analytics_first_reply_facts_summary_idx',
                                             'ticket_state_intervals_one_open_idx')
                        """.trimIndent(),
                    ).use { result -> result.next(); result.getInt(1) },
                ).isEqualTo(3)

                assertThatThrownBy {
                    statement.executeUpdate(
                        """
                        update sla_policy_versions set name = 'rewritten'
                         where policy_id = '62000000-0000-0000-0000-000000000001' and version = 1
                        """.trimIndent(),
                    )
                }.isInstanceOf(SQLException::class.java)
            }
        }
    }

    private fun connection() = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
    }
}
