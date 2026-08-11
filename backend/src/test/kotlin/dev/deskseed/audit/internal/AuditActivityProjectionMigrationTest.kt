package dev.deskseed.audit.internal

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager

@Testcontainers
class AuditActivityProjectionMigrationTest {
    @Test
    fun `version eleven backfills three canonical ledgers survives hook failure and rebuilds identically`() {
        migrateTo("10")
        insertCanonicalFixture()

        migrateTo("11")

        connection().use { jdbc ->
            jdbc.createStatement().use { statement ->
                statement.executeQuery(
                    """
                    select ledger_type, action, ticket_number, field_name,
                           old_value_json::text, new_value_json::text,
                           query_redacted, search_fingerprint, protected_content_available
                    from audit_activity_projection
                    order by ledger_type
                    """.trimIndent(),
                ).use { rows ->
                    val projected = buildList {
                        while (rows.next()) {
                            add(
                                listOf(
                                    rows.getString("ledger_type"),
                                    rows.getString("action"),
                                    rows.getObject("ticket_number"),
                                    rows.getString("field_name"),
                                    rows.getString("old_value_json"),
                                    rows.getString("new_value_json"),
                                    rows.getString("query_redacted"),
                                    rows.getString("search_fingerprint"),
                                    rows.getBoolean("protected_content_available"),
                                ),
                            )
                        }
                    }
                    assertThat(projected).hasSize(3)
                    assertThat(projected).anySatisfy { row ->
                        assertThat(row[0]).isEqualTo("TICKET_CHANGE")
                        assertThat(row[1]).isEqualTo("STATUS_CHANGED")
                        assertThat(row[2]).isEqualTo(9101L)
                        assertThat(row[3]).isEqualTo("status")
                        assertThat(row[4]).isEqualTo("\"OPEN\"")
                        assertThat(row[5]).isEqualTo("\"PENDING\"")
                    }
                    assertThat(projected).anySatisfy { row ->
                        assertThat(row[0]).isEqualTo("ACCESS_SEARCH")
                        assertThat(row[1]).isEqualTo("SEARCH_EXECUTED")
                        assertThat(row[6]).isEqualTo("c***@example.com")
                        assertThat(row[7]).isEqualTo("fingerprint-v1")
                        assertThat(row[8]).isEqualTo(true)
                    }
                    assertThat(projected).anySatisfy { row ->
                        assertThat(row[0]).isEqualTo("ADMIN_SECURITY")
                        assertThat(row[1]).isEqualTo("ROLE_CHANGED")
                    }
                }

                statement.executeUpdate(
                    adminEventSql(
                        id = "00000000-0000-0000-0000-000000001108",
                        type = "AUDIT_LOG_VIEWED",
                    ),
                )
                assertThat(count(statement, "audit_activity_projection")).isEqualTo(4)

                statement.execute(
                    """
                    create function reject_projection_insert_for_test()
                    returns trigger language plpgsql as ${'$'}${'$'}
                    begin
                        raise exception 'injected projection failure';
                    end;
                    ${'$'}${'$'}
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    create trigger reject_projection_insert_for_test
                    before insert on audit_activity_projection
                    for each row execute function reject_projection_insert_for_test()
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    adminEventSql(
                        id = "00000000-0000-0000-0000-000000001109",
                        type = "AUDIT_EXPORT_REQUESTED",
                    ),
                )
                assertThat(count(statement, "admin_security_audit_events")).isEqualTo(3)
                assertThat(
                    statement.executeQuery(
                        "select state from audit_activity_projection_state where id = 1",
                    ).use { result -> result.next(); result.getString(1) },
                ).isEqualTo("DEGRADED")
                statement.execute("drop trigger reject_projection_insert_for_test on audit_activity_projection")
                statement.execute("drop function reject_projection_insert_for_test()")

                statement.executeQuery("select * from rebuild_audit_activity_projection()").use { rebuilt ->
                    assertThat(rebuilt.next()).isTrue()
                    assertThat(rebuilt.getLong("ticket_change_count")).isEqualTo(1)
                    assertThat(rebuilt.getLong("access_search_count")).isEqualTo(1)
                    assertThat(rebuilt.getLong("admin_security_count")).isEqualTo(3)
                    assertThat(rebuilt.getLong("total_count")).isEqualTo(5)
                }
                assertThat(count(statement, "audit_activity_projection")).isEqualTo(5)
                assertThat(
                    statement.executeQuery(
                        "select state from audit_activity_projection_state where id = 1",
                    ).use { result -> result.next(); result.getString(1) },
                ).isEqualTo("CURRENT")
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

    private fun insertCanonicalFixture() {
        connection().use { jdbc ->
            jdbc.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    insert into customers (id, name, email_normalized, email_display, created_at, updated_at)
                    values ('00000000-0000-0000-0000-000000001101', '감사 고객',
                            'audit-customer@example.com', 'audit-customer@example.com', now(), now())
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    insert into staff_accounts (
                        id, email_normalized, email_display, display_name, role, status,
                        password_hash, created_at, updated_at, version
                    ) values (
                        '00000000-0000-0000-0000-000000001102', 'auditor-fixture@example.com',
                        'auditor-fixture@example.com', '감사자', 'SECURITY_AUDITOR', 'ACTIVE',
                        'not-used-by-migration-test', now(), now(), 0
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    insert into tickets (
                        id, ticket_number, requester_id, kind, subject, status, priority,
                        channel, version, created_at, updated_at
                    ) values (
                        '00000000-0000-0000-0000-000000001103', 9101,
                        '00000000-0000-0000-0000-000000001101', 'CUSTOMER_REQUEST',
                        'projection ticket', 'PENDING', 'HIGH', 'WEB', 2, now(), now()
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    insert into ticket_audits (
                        id, ticket_id, ticket_version, expected_version, actor_type, actor_id,
                        source, request_id, correlation_id, command_id, created_at
                    ) values (
                        '00000000-0000-0000-0000-000000001104',
                        '00000000-0000-0000-0000-000000001103', 2, 1, 'STAFF',
                        '00000000-0000-0000-0000-000000001102', 'AGENT_UI',
                        'change-request', 'root-correlation', 'change-command', now()
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    insert into ticket_audit_events (
                        id, audit_id, event_order, event_type, field_name,
                        old_value_json, new_value_json, metadata_json, occurred_at
                    ) values (
                        '00000000-0000-0000-0000-000000001105',
                        '00000000-0000-0000-0000-000000001104', 1, 'STATUS_CHANGED',
                        'status', '"OPEN"', '"PENDING"', '{}', now()
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    insert into access_audit_events (
                        id, occurred_at, actor_type, actor_id, actor_display_snapshot,
                        source, action, resource_type, resource_id, ticket_number,
                        interaction_id, session_fingerprint, auth_type, request_id,
                        correlation_id, ip_address, user_agent, outcome, http_status
                    ) values (
                        '00000000-0000-0000-0000-000000001106', now(), 'STAFF',
                        '00000000-0000-0000-0000-000000001102', '감사자', 'AGENT_UI',
                        'SEARCH_EXECUTED', 'SEARCH', null, null,
                        '00000000-0000-0000-0000-000000001206', 'v1:session',
                        'STAFF_SESSION', 'search-request', 'root-correlation',
                        '192.0.2.10', 'Deskseed test', 'SUCCEEDED', 200
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    insert into search_audit_details (
                        access_event_id, query_redacted, query_fingerprint, query_key_version,
                        normalized_filters, sort, result_count
                    ) values (
                        '00000000-0000-0000-0000-000000001106', 'c***@example.com',
                        'fingerprint-v1', 'local-v1', '{"status":"OPEN"}',
                        'updatedAt:desc,ticketNumber:desc', 1
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    insert into search_audit_query_ciphertexts (
                        access_event_id, key_version, query_ciphertext, created_at, expires_at
                    ) values (
                        '00000000-0000-0000-0000-000000001106', 'local-v1',
                        decode(repeat('01', 32), 'hex'), now(), now() + interval '30 days'
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    adminEventSql(
                        id = "00000000-0000-0000-0000-000000001107",
                        type = "ROLE_CHANGED",
                    ),
                )
            }
        }
    }

    private fun adminEventSql(id: String, type: String): String =
        """
        insert into admin_security_audit_events (
            id, event_type, actor_type, actor_id, actor_display_snapshot, source,
            target_type, target_id, outcome, request_id, correlation_id,
            metadata_json, occurred_at
        ) values (
            '$id', '$type', 'STAFF',
            '00000000-0000-0000-0000-000000001102', '감사자', 'ADMIN_UI',
            'AUDIT_ACTIVITY', null, 'SUCCEEDED', 'admin-request',
            'root-correlation', '{}', now()
        )
        """.trimIndent()

    private fun count(statement: java.sql.Statement, table: String): Long =
        statement.executeQuery("select count(*) from $table").use { result -> result.next(); result.getLong(1) }

    private fun connection() = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18.3-alpine"))
    }
}
