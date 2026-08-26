package dev.deskseed.customerconsent.internal

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement

@Testcontainers
@dev.deskseed.testsupport.category.MigrationTest
class CustomerConsentMigrationTest {
    @BeforeEach
    fun cleanDatabase() {
        flyway().clean()
    }

    @Test
    fun `V76 to V80 adds consent evidence without changing prior migration history`() {
        migrateTo("76")
        migrateTo("80")

        connection().use { connection ->
            connection.createStatement().use { statement ->
                assertThat(table(statement, "customer_consent_policies")).isEqualTo("customer_consent_policies")
                assertThat(table(statement, "customer_consent_policy_versions")).isEqualTo("customer_consent_policy_versions")
                assertThat(table(statement, "customer_consent_acceptances")).isEqualTo("customer_consent_acceptances")
                assertThat(count(statement, "select count(*) from flyway_schema_history where version = '76' and success")).isEqualTo(1)
                assertThat(count(statement, "select count(*) from flyway_schema_history where version = '80' and success")).isEqualTo(1)
                assertThat(count(statement, "select count(*) from customer_consent_policies")).isZero()
            }
        }
    }

    @Test
    fun `clean V80 enforces immutable versions append only acceptances and resource linkage`() {
        migrateTo("80")

        connection().use { connection ->
            insertFixtures(connection)
            connection.createStatement().use { statement ->
                assertThatThrownBy {
                    statement.executeUpdate("update customer_consent_policy_versions set title = 'changed' where policy_id = '$POLICY_ID' and version = 1")
                }.hasMessageContaining("Customer consent policy versions are immutable")
                assertThatThrownBy {
                    statement.executeUpdate("delete from customer_consent_policy_versions where policy_id = '$POLICY_ID' and version = 1")
                }.hasMessageContaining("Customer consent policy versions are immutable")
                assertThatThrownBy {
                    statement.executeUpdate("update customer_consent_acceptances set request_id = 'changed' where id = '$REGISTRATION_ACCEPTANCE_ID'")
                }.hasMessageContaining("Customer consent acceptances are append-only")
                assertThatThrownBy {
                    statement.executeUpdate("delete from customer_consent_acceptances where id = '$REGISTRATION_ACCEPTANCE_ID'")
                }.hasMessageContaining("Customer consent acceptances are append-only")
                assertThatThrownBy {
                    insertPolicy(statement, SECOND_POLICY_ID, "test-terms", "REGISTRATION")
                }.hasMessageContaining("customer_consent_policies_context_key_unique")
                assertThatThrownBy {
                    statement.executeUpdate("update customer_consent_policies set policy_key = 'changed' where id = '$POLICY_ID'")
                }.hasMessageContaining("Customer consent policy key and context are immutable")
                assertThatThrownBy {
                    insertAcceptance(
                        statement,
                        SECOND_ACCEPTANCE_ID,
                        "REGISTRATION",
                        accountId = null,
                        ticketId = null,
                    )
                }.hasMessageContaining("customer_consent_acceptances_resource_valid")
                assertThatThrownBy {
                    insertAcceptance(
                        statement,
                        SECOND_ACCEPTANCE_ID,
                        "REQUEST_SUBMISSION",
                        accountId = null,
                        ticketId = "00000000-0000-0000-0000-000000009999",
                    )
                }.hasMessageContaining("customer_consent_acceptances_ticket_id_fkey")
            }
        }
    }

    private fun insertFixtures(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """
                insert into staff_accounts
                    (id, email_normalized, email_display, display_name, role, status, password_hash, created_at, updated_at)
                values ('$STAFF_ID', 'consent-admin@example.test', 'consent-admin@example.test', 'Consent Admin',
                        'ADMIN', 'ACTIVE', 'synthetic-hash', now(), now())
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                insert into customers (id, name, email_normalized, email_display, created_at, updated_at)
                values ('$CUSTOMER_ID', 'Consent Customer', 'consent-customer@example.test',
                        'consent-customer@example.test', now(), now())
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                insert into customer_accounts
                    (id, customer_id, email_normalized, status, verified_at, last_login_at, created_at, updated_at)
                values ('$ACCOUNT_ID', '$CUSTOMER_ID', 'consent-customer@example.test', 'ACTIVE', now(), now(), now(), now())
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                insert into tickets
                    (id, ticket_number, requester_id, kind, subject, status, priority, channel, created_at, updated_at)
                values ('$TICKET_ID', 9080, '$CUSTOMER_ID', 'CUSTOMER_REQUEST', 'Synthetic consent ticket',
                        'NEW', 'NORMAL', 'WEB', now(), now())
                """.trimIndent(),
            )
            insertPolicy(statement, POLICY_ID, "test-terms", "REGISTRATION")
            statement.executeUpdate(
                """
                insert into customer_consent_policy_versions
                    (policy_id, version, title, document_json, plain_text, checksum_sha256, required,
                     display_order, effective_at, published_by_staff_id, published_by_display, published_at)
                values ('$POLICY_ID', 1, 'Synthetic terms',
                        '{"schemaVersion":1,"blocks":[{"type":"paragraph","text":"Synthetic policy"}]}'::jsonb,
                        'Synthetic policy', repeat('a', 64), true, 10, now(), '$STAFF_ID', 'Consent Admin', now())
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                update customer_consent_policies
                   set lifecycle = 'PUBLISHED', published_version = 1, aggregate_version = 1, updated_at = now()
                 where id = '$POLICY_ID'
                """.trimIndent(),
            )
            insertAcceptance(statement, REGISTRATION_ACCEPTANCE_ID, "REGISTRATION", ACCOUNT_ID, null)
        }
    }

    private fun insertPolicy(statement: Statement, id: String, key: String, context: String) {
        statement.executeUpdate(
            """
            insert into customer_consent_policies
                (id, policy_key, context, lifecycle, draft_title, draft_document_json, draft_plain_text,
                 draft_checksum_sha256, draft_required, draft_display_order, draft_version,
                 aggregate_version, created_at, updated_at)
            values ('$id', '$key', '$context', 'DRAFT', 'Synthetic terms',
                    '{"schemaVersion":1,"blocks":[{"type":"paragraph","text":"Synthetic policy"}]}'::jsonb,
                    'Synthetic policy', repeat('a', 64), true, 10, 1, 0, now(), now())
            """.trimIndent(),
        )
    }

    private fun insertAcceptance(
        statement: Statement,
        id: String,
        context: String,
        accountId: String?,
        ticketId: String?,
    ) {
        statement.executeUpdate(
            """
            insert into customer_consent_acceptances
                (id, customer_id, account_id, ticket_id, policy_id, policy_version, context,
                 accepted_at, source, request_id, correlation_id)
            values ('$id', '$CUSTOMER_ID', ${accountId.sqlUuid()}, ${ticketId.sqlUuid()}, '$POLICY_ID', 1,
                    '$context', now(), 'CUSTOMER_PORTAL', 'request-consent-test', 'correlation-consent-test')
            """.trimIndent(),
        )
    }

    private fun String?.sqlUuid(): String = this?.let { "'$it'" } ?: "null"

    private fun migrateTo(version: String) {
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .target(version)
            .load()
            .migrate()
    }

    private fun flyway(): Flyway = Flyway.configure()
        .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        .locations("classpath:db/migration")
        .cleanDisabled(false)
        .load()

    private fun connection(): Connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    private fun table(statement: Statement, name: String): String? =
        statement.executeQuery("select to_regclass('public.$name')").use { result -> check(result.next()); result.getString(1) }

    private fun count(statement: Statement, sql: String): Long =
        statement.executeQuery(sql).use { result -> check(result.next()); result.getLong(1) }

    private companion object {
        private const val STAFF_ID = "00000000-0000-0000-0000-000000008001"
        private const val CUSTOMER_ID = "00000000-0000-0000-0000-000000008002"
        private const val ACCOUNT_ID = "00000000-0000-0000-0000-000000008003"
        private const val TICKET_ID = "00000000-0000-0000-0000-000000008004"
        private const val POLICY_ID = "00000000-0000-0000-0000-000000008005"
        private const val SECOND_POLICY_ID = "00000000-0000-0000-0000-000000008006"
        private const val REGISTRATION_ACCEPTANCE_ID = "00000000-0000-0000-0000-000000008007"
        private const val SECOND_ACCEPTANCE_ID = "00000000-0000-0000-0000-000000008008"

        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
    }
}
