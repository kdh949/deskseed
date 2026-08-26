package dev.deskseed.customerauth.internal

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement

@Testcontainers
@dev.deskseed.testsupport.category.MigrationTest
class CustomerAuthenticationMigrationTest {
    @BeforeEach
    fun cleanDatabase() {
        flyway().clean()
    }

    @Test
    fun `V80 data upgrades to purpose bound credential storage without a PostgreSQL limiter`() {
        migrateTo("80")
        insertV80AuthenticationFixture()

        migrateTo("81")

        connection().use { connection ->
            connection.createStatement().use { statement ->
                assertThat(table(statement, "customer_one_time_tokens")).isEqualTo("customer_one_time_tokens")
                assertThat(table(statement, "customer_magic_link_tokens")).isNull()
                assertThat(table(statement, "customer_magic_link_request_limits")).isNull()
                assertThat(table(statement, "customer_registration_intents")).isEqualTo("customer_registration_intents")
                assertThat(table(statement, "customer_registration_intent_consents"))
                    .isEqualTo("customer_registration_intent_consents")
                assertThat(queryString(statement, "select purpose from customer_one_time_tokens where id = '$TOKEN_ID'"))
                    .isEqualTo("PASSWORDLESS_LOGIN")
                assertThat(queryLong(statement, "select credential_version from customer_accounts where id = '$ACCOUNT_ID'"))
                    .isZero()
                assertThat(queryString(statement, "select authentication_method from customer_sessions where id = '$SESSION_ID'"))
                    .isEqualTo("MAGIC_LINK")
                assertThat(queryLong(statement, "select credential_version_snapshot from customer_sessions where id = '$SESSION_ID'"))
                    .isZero()
                assertThat(queryLong(statement, "select count(*) from flyway_schema_history where version in ('80', '81') and success"))
                    .isEqualTo(2)
            }
        }
    }

    @Test
    fun `clean V81 constrains pending registration proofs consent references and token purposes`() {
        migrateTo("81")

        connection().use { connection ->
            insertPolicyAndAccountFixtures(connection)
            connection.createStatement().use { statement ->
                insertRegistrationIntent(statement, INTENT_ID, "pending@example.test")
                statement.executeUpdate(
                    """
                    insert into customer_registration_intent_consents
                        (intent_id, policy_id, policy_version, context, selected_at)
                    values ('$INTENT_ID', '$POLICY_ID', 1, 'REGISTRATION', now())
                    """.trimIndent(),
                )

                assertThatThrownBy {
                    insertRegistrationIntent(statement, SECOND_INTENT_ID, "pending@example.test")
                }.hasMessageContaining("customer_registration_intents_pending_email_unique")

                assertThatThrownBy {
                    statement.executeUpdate(
                        """
                        insert into customer_one_time_tokens
                            (id, token_digest, purpose, email_normalized, email_display,
                             request_id, correlation_id, created_at, expires_at, consumed_at)
                        values ('$TOKEN_ID', '${"b".repeat(64)}', 'EMAIL_VERIFICATION',
                                'pending@example.test', 'pending@example.test', 'request', 'correlation',
                                now(), now() + interval '1 hour', null)
                        """.trimIndent(),
                    )
                }.hasMessageContaining("customer_one_time_tokens_purpose_resource_valid")

                insertToken(statement, TOKEN_ID, "b".repeat(64), "EMAIL_VERIFICATION", INTENT_ID, null)
                insertToken(statement, RESET_TOKEN_ID, "c".repeat(64), "PASSWORD_RESET", null, ACCOUNT_ID)
                insertToken(statement, MAGIC_TOKEN_ID, "d".repeat(64), "PASSWORDLESS_LOGIN", null, null)

                assertThatThrownBy {
                    insertToken(
                        statement,
                        INVALID_MAGIC_TOKEN_ID,
                        "e".repeat(64),
                        "PASSWORDLESS_LOGIN",
                        INTENT_ID,
                        null,
                    )
                }.hasMessageContaining("customer_one_time_tokens_purpose_resource_valid")

                val columns = statement.executeQuery(
                    """
                    select table_name || '.' || column_name
                    from information_schema.columns
                    where table_schema = 'public'
                      and table_name in ('customer_accounts', 'customer_registration_intents', 'customer_one_time_tokens')
                    order by table_name, ordinal_position
                    """.trimIndent(),
                ).use { result ->
                    buildList {
                        while (result.next()) add(result.getString(1))
                    }
                }
                assertThat(columns)
                    .doesNotContain(
                        "customer_accounts.password",
                        "customer_registration_intents.password",
                        "customer_registration_intents.continuation_secret",
                        "customer_one_time_tokens.token",
                    )
            }
        }
    }

    private fun insertV80AuthenticationFixture() {
        connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    insert into customers
                        (id, name, email_normalized, email_display, verified_at, created_at, updated_at)
                    values ('$CUSTOMER_ID', 'Migration Customer', 'migration@example.test',
                            'migration@example.test', now(), now(), now())
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    insert into customer_accounts
                        (id, customer_id, email_normalized, status, verified_at, last_login_at,
                         created_at, updated_at, version)
                    values ('$ACCOUNT_ID', '$CUSTOMER_ID', 'migration@example.test', 'ACTIVE',
                            now(), now(), now(), now(), 0)
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    insert into customer_sessions
                        (id, account_id, session_token_digest, created_at, last_activity_at,
                         expires_at, absolute_expires_at, revoked_at)
                    values ('$SESSION_ID', '$ACCOUNT_ID', '${"a".repeat(64)}', now(), now(),
                            now() + interval '30 minutes', now() + interval '12 hours', null)
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    insert into customer_magic_link_tokens
                        (id, token_digest, email_normalized, email_display, request_id, correlation_id,
                         created_at, expires_at, consumed_at)
                    values ('$TOKEN_ID', '${"b".repeat(64)}', 'migration@example.test',
                            'migration@example.test', 'request-v80', 'correlation-v80', now(),
                            now() + interval '15 minutes', null)
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    insert into customer_magic_link_request_limits
                        (destination_fingerprint, network_fingerprint, request_count,
                         window_started_at, locked_until, updated_at)
                    values ('${"c".repeat(64)}', '${"d".repeat(64)}', 1, now(), null, now())
                    """.trimIndent(),
                )
            }
        }
    }

    private fun insertPolicyAndAccountFixtures(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """
                insert into staff_accounts
                    (id, email_normalized, email_display, display_name, role, status,
                     password_hash, created_at, updated_at)
                values ('$STAFF_ID', 'auth-admin@example.test', 'auth-admin@example.test',
                        'Auth Admin', 'ADMIN', 'ACTIVE', 'synthetic-hash', now(), now())
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                insert into customers
                    (id, name, email_normalized, email_display, verified_at, created_at, updated_at)
                values ('$CUSTOMER_ID', 'Auth Customer', 'account@example.test',
                        'account@example.test', now(), now(), now())
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                insert into customer_accounts
                    (id, customer_id, email_normalized, status, verified_at, last_login_at,
                     created_at, updated_at, version)
                values ('$ACCOUNT_ID', '$CUSTOMER_ID', 'account@example.test', 'ACTIVE',
                        now(), now(), now(), now(), 0)
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                insert into customer_consent_policies
                    (id, policy_key, context, lifecycle, draft_title, draft_document_json,
                     draft_plain_text, draft_checksum_sha256, draft_required, draft_display_order,
                     draft_version, published_version, aggregate_version, created_at, updated_at)
                values ('$POLICY_ID', 'test-terms', 'REGISTRATION', 'DRAFT', 'Synthetic terms',
                        '{"schemaVersion":1,"blocks":[{"type":"paragraph","text":"Synthetic"}]}'::jsonb,
                        'Synthetic', '${"f".repeat(64)}', true, 10, 1, null, 0, now(), now())
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                insert into customer_consent_policy_versions
                    (policy_id, version, title, document_json, plain_text, checksum_sha256, required,
                     display_order, effective_at, published_by_staff_id, published_by_display, published_at)
                values ('$POLICY_ID', 1, 'Synthetic terms',
                        '{"schemaVersion":1,"blocks":[{"type":"paragraph","text":"Synthetic"}]}'::jsonb,
                        'Synthetic', '${"f".repeat(64)}', true, 10, now(), '$STAFF_ID', 'Auth Admin', now())
                """.trimIndent(),
            )
        }
    }

    private fun insertRegistrationIntent(statement: Statement, id: String, email: String) {
        val continuationDigest = if (id == INTENT_ID) "a".repeat(64) else "9".repeat(64)
        statement.executeUpdate(
            """
            insert into customer_registration_intents
                (id, email_normalized, email_display, password_hash, display_name, company_name,
                 continuation_secret_digest, status, request_id, correlation_id,
                 created_at, updated_at, expires_at, consumed_at, cancelled_at, version)
            values ('$id', '$email', '$email', '${'$'}argon2id${'$'}v=19${'$'}m=19456,t=2,p=1${'$'}synthetic',
                    'Pending Customer', 'Pending Company', '$continuationDigest', 'PENDING',
                    'request-intent', 'correlation-intent', now(), now(), now() + interval '24 hours',
                    null, null, 0)
            """.trimIndent(),
        )
    }

    private fun insertToken(
        statement: Statement,
        id: String,
        digest: String,
        purpose: String,
        intentId: String?,
        accountId: String?,
    ) {
        statement.executeUpdate(
            """
            insert into customer_one_time_tokens
                (id, token_digest, purpose, registration_intent_id, account_id,
                 email_normalized, email_display, request_id, correlation_id,
                 created_at, expires_at, consumed_at)
            values ('$id', '$digest', '$purpose', ${intentId.sqlUuid()}, ${accountId.sqlUuid()},
                    'proof@example.test', 'proof@example.test', 'request-token', 'correlation-token',
                    now(), now() + interval '1 hour', null)
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

    private fun queryString(statement: Statement, sql: String): String? =
        statement.executeQuery(sql).use { result -> check(result.next()); result.getString(1) }

    private fun queryLong(statement: Statement, sql: String): Long =
        statement.executeQuery(sql).use { result -> check(result.next()); result.getLong(1) }

    private companion object {
        private const val STAFF_ID = "00000000-0000-4000-8000-000000008101"
        private const val CUSTOMER_ID = "00000000-0000-4000-8000-000000008102"
        private const val ACCOUNT_ID = "00000000-0000-4000-8000-000000008103"
        private const val SESSION_ID = "00000000-0000-4000-8000-000000008104"
        private const val TOKEN_ID = "00000000-0000-4000-8000-000000008105"
        private const val POLICY_ID = "00000000-0000-4000-8000-000000008106"
        private const val INTENT_ID = "00000000-0000-4000-8000-000000008107"
        private const val SECOND_INTENT_ID = "00000000-0000-4000-8000-000000008108"
        private const val RESET_TOKEN_ID = "00000000-0000-4000-8000-000000008109"
        private const val MAGIC_TOKEN_ID = "00000000-0000-4000-8000-000000008110"
        private const val INVALID_MAGIC_TOKEN_ID = "00000000-0000-4000-8000-000000008111"

        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
    }
}
