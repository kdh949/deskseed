package dev.deskseed.organization.internal

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.sql.SQLException

@Testcontainers
class Issue24RemediationMigrationTest {
    @Test
    fun `v15 data upgrades without implicit audit grants and enforces verified only email uniqueness`() {
        migrateTo("15")
        insertVersionFifteenFixture()

        migrateTo("17")

        connection().use { jdbc ->
            jdbc.createStatement().use { statement ->
                statement.executeQuery("select count(*) from staff_authority_grants").use { result ->
                    assertThat(result.next()).isTrue()
                    assertThat(result.getLong(1)).isZero()
                }
                statement.executeQuery("select name from customers where id = '$EXISTING_CUSTOMER_ID'").use { result ->
                    assertThat(result.next()).isTrue()
                    assertThat(result.getString("name")).isEqualTo("기존 익명 고객")
                }

                statement.executeUpdate(
                    """
                    insert into customers
                        (id, name, email_normalized, email_display, created_at, updated_at)
                    values
                        ('$SECOND_CUSTOMER_ID', '새 익명 고객', 'shared@example.com',
                         'Shared@example.com', now(), now())
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    "update customers set verified_at = now(), updated_at = now() where id = '$EXISTING_CUSTOMER_ID'",
                )

                val duplicateVerifiedEmail: SQLException? = try {
                    statement.executeUpdate(
                        "update customers set verified_at = now(), updated_at = now() where id = '$SECOND_CUSTOMER_ID'",
                    )
                    null
                } catch (failure: SQLException) {
                    failure
                }
                assertThat(requireNotNull(duplicateVerifiedEmail).sqlState).isEqualTo("23505")

                statement.executeUpdate(
                    """
                    insert into staff_authority_grants
                        (id, staff_id, authority, granted_by_staff_id, granted_at)
                    values
                        ('00000000-0000-0000-0000-000000000105', '$AUDITOR_ID',
                         'AUDIT_EXPORT', '$ADMIN_ID', now())
                    """.trimIndent(),
                )
                statement.executeQuery(
                    "select authority from staff_authority_grants where staff_id = '$AUDITOR_ID'",
                ).use { result ->
                    assertThat(result.next()).isTrue()
                    assertThat(result.getString("authority")).isEqualTo("AUDIT_EXPORT")
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

    private fun insertVersionFifteenFixture() {
        connection().use { jdbc ->
            jdbc.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    insert into staff_accounts
                        (id, email_normalized, email_display, display_name, role, status,
                         password_hash, created_at, updated_at, version)
                    values
                        ('$ADMIN_ID', 'migration-admin@example.com', 'migration-admin@example.com',
                         '마이그레이션 관리자', 'ADMIN', 'ACTIVE', 'not-used', now(), now(), 0),
                        ('$AUDITOR_ID', 'migration-auditor@example.com', 'migration-auditor@example.com',
                         '마이그레이션 감사자', 'SECURITY_AUDITOR', 'ACTIVE', 'not-used', now(), now(), 0)
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    insert into customers
                        (id, name, email_normalized, email_display, created_at, updated_at)
                    values
                        ('$EXISTING_CUSTOMER_ID', '기존 익명 고객', 'shared@example.com',
                         'shared@example.com', now(), now())
                    """.trimIndent(),
                )
            }
        }
    }

    private fun connection() = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    companion object {
        private const val ADMIN_ID = "00000000-0000-0000-0000-000000000101"
        private const val AUDITOR_ID = "00000000-0000-0000-0000-000000000102"
        private const val EXISTING_CUSTOMER_ID = "00000000-0000-0000-0000-000000000103"
        private const val SECOND_CUSTOMER_ID = "00000000-0000-0000-0000-000000000104"

        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
    }
}
