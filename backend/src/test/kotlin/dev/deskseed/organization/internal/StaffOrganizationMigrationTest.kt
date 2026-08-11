package dev.deskseed.organization.internal

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
class StaffOrganizationMigrationTest {
    @Test
    fun `migration creates constrained organization and append-only security audit tables`() {
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        connection().use { jdbc ->
            jdbc.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    insert into staff_accounts
                        (id, email_normalized, email_display, display_name, role, status,
                         password_hash, created_at, updated_at, version)
                    values
                        ('10000000-0000-0000-0000-000000000001', 'admin@example.com',
                         'admin@example.com', '관리자', 'ADMIN', 'ACTIVE', 'bcrypt-hash', now(), now(), 0)
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    insert into support_groups (id, name, status, created_at, updated_at, version)
                    values ('20000000-0000-0000-0000-000000000001', '고객 지원', 'ACTIVE', now(), now(), 0)
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    insert into group_memberships (id, group_id, staff_id, status, created_at, updated_at, version)
                    values ('30000000-0000-0000-0000-000000000001',
                            '20000000-0000-0000-0000-000000000001',
                            '10000000-0000-0000-0000-000000000001', 'ACTIVE', now(), now(), 0)
                    """.trimIndent(),
                )

                assertThatThrownBy {
                    statement.executeUpdate(
                        """
                        insert into group_memberships (id, group_id, staff_id, status, created_at, updated_at, version)
                        values ('30000000-0000-0000-0000-000000000002',
                                '20000000-0000-0000-0000-000000000001',
                                '10000000-0000-0000-0000-000000000001', 'ACTIVE', now(), now(), 0)
                        """.trimIndent(),
                    )
                }.isInstanceOf(SQLException::class.java)

                statement.executeUpdate(
                    """
                    insert into support_groups (id, name, status, created_at, updated_at, version)
                    values ('20000000-0000-0000-0000-000000000002', 'Support', 'ACTIVE', now(), now(), 0)
                    """.trimIndent(),
                )

                assertThatThrownBy {
                    statement.executeUpdate(
                        """
                        insert into support_groups (id, name, status, created_at, updated_at, version)
                        values ('20000000-0000-0000-0000-000000000003', ' support ', 'ACTIVE', now(), now(), 0)
                        """.trimIndent(),
                    )
                }.isInstanceOf(SQLException::class.java)

                statement.executeUpdate(
                    """
                    insert into admin_security_audit_events
                        (id, event_type, actor_type, actor_id, actor_display_snapshot, source,
                         target_type, target_id, outcome, request_id, correlation_id, metadata_json, occurred_at)
                    values
                        ('40000000-0000-0000-0000-000000000001', 'GROUP_MEMBERSHIP_CHANGED',
                         'STAFF', '10000000-0000-0000-0000-000000000001', '관리자', 'ADMIN_UI',
                         'GROUP_MEMBERSHIP', '30000000-0000-0000-0000-000000000001', 'SUCCEEDED',
                         'request-migration', 'correlation-migration', '{}', now())
                    """.trimIndent(),
                )

                assertThatThrownBy {
                    statement.executeUpdate(
                        """
                        update admin_security_audit_events
                        set outcome = 'FAILED'
                        where id = '40000000-0000-0000-0000-000000000001'
                        """.trimIndent(),
                    )
                }.isInstanceOf(SQLException::class.java)

                val tableCount = statement.executeQuery(
                    """
                    select count(*)
                    from information_schema.tables
                    where table_schema = 'public'
                      and table_name in ('staff_accounts', 'support_groups', 'group_memberships',
                                         'admin_security_audit_events', 'staff_login_throttles')
                    """.trimIndent(),
                ).use { result ->
                    result.next()
                    result.getInt(1)
                }
                assertThat(tableCount).isEqualTo(5)
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
