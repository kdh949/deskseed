package dev.deskseed.staffaccess.internal

import dev.deskseed.audit.SearchQueryProtector
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@SpringBootTest(properties = ["deskseed.staff-auth.bootstrap.enabled=false"])
@AutoConfigureMockMvc
@Testcontainers
class AuditExplorerIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var searchQueryProtector: SearchQueryProtector

    @BeforeEach
    fun clearState() {
        jdbcTemplate.execute(
            """
            truncate table audit_export_artifacts, audit_export_jobs, audit_activity_projection, search_audit_result_items,
                search_audit_query_ciphertexts, search_audit_details, access_audit_events,
                ticket_audit_events, ticket_audits, ticket_relations, ticket_comments,
                tickets, customers, admin_security_audit_events, group_memberships,
                support_groups, staff_login_throttles, staff_accounts cascade
            """.trimIndent(),
        )
        jdbcTemplate.update(
            "update audit_activity_projection_state set state = 'CURRENT', last_failure_at = null where id = 1",
        )
    }

    @Test
    fun `auditor filters all normalized dimensions and reads ticket before after without protected content`() {
        val auditorId = insertStaff("auditor-list@example.com")
        val session = login("auditor-list@example.com")
        val fixture = insertTicketChangeFixture(auditorId)
        val interactionId = UUID.randomUUID()

        val list = mockMvc.perform(
            get("/api/v1/audit/activities")
                .session(session)
                .header("X-Interaction-Id", interactionId)
                .queryParam("from", "2026-08-10T00:00:00Z")
                .queryParam("to", "2026-08-14T00:00:00Z")
                .queryParam("ledger", "TICKET_CHANGE")
                .queryParam("action", "STATUS_CHANGED")
                .queryParam("actorType", "STAFF")
                .queryParam("actorId", auditorId.toString())
                .queryParam("ticketNumber", fixture.ticketNumber.toString())
                .queryParam("groupId", fixture.groupId.toString())
                .queryParam("field", "status")
                .queryParam("source", "AGENT_UI")
                .queryParam("outcome", "SUCCEEDED")
                .queryParam("requestId", "change-request")
                .queryParam("correlationId", "change-correlation")
                .queryParam("limit", "10"),
        )
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].id").value(fixture.activityId.toString()))
            .andExpect(jsonPath("$.items[0].ledger").value("TICKET_CHANGE"))
            .andExpect(jsonPath("$.items[0].action").value("STATUS_CHANGED"))
            .andExpect(jsonPath("$.items[0].ticketNumber").value(fixture.ticketNumber))
            .andExpect(jsonPath("$.items[0].field").value("status"))
            .andExpect(jsonPath("$.items[0].actor.id").value(auditorId.toString()))
            .andExpect(jsonPath("$.projection.state").value("CURRENT"))
            .andExpect(jsonPath("$.snapshotAt").exists())
            .andExpect(jsonPath("$.items[0].rawQuery").doesNotExist())
            .andExpect(jsonPath("$.items[0].commentBody").doesNotExist())
            .andReturn()

        assertThat(list.response.contentAsString)
            .doesNotContain("private comment body", "alice exact search")
        assertThat(selfAuditCount("AUDIT_LOG_VIEWED", auditorId)).isEqualTo(1)

        mockMvc.perform(
            get("/api/v1/audit/activities/{activityId}", fixture.activityId)
                .session(session)
                .header("X-Interaction-Id", UUID.randomUUID()),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.canonicalEventId").value(fixture.eventId.toString()))
            .andExpect(jsonPath("$.canonicalParentId").value(fixture.auditId.toString()))
            .andExpect(jsonPath("$.fieldChange.field").value("status"))
            .andExpect(jsonPath("$.fieldChange.before").value("OPEN"))
            .andExpect(jsonPath("$.fieldChange.after").value("PENDING"))
            .andExpect(jsonPath("$.metadata.visibility").value("PUBLIC"))
            .andExpect(jsonPath("$.rawQuery").doesNotExist())
            .andExpect(jsonPath("$.commentBody").doesNotExist())

        assertThat(selfAuditCount("AUDIT_LOG_VIEWED", auditorId)).isEqualTo(2)
    }

    @Test
    fun `self audit persistence failure blocks list success`() {
        val auditorId = insertStaff("auditor-fail-closed@example.com")
        val session = login("auditor-fail-closed@example.com")
        insertTicketChangeFixture(auditorId)
        jdbcTemplate.execute(
            """
            create function reject_explorer_self_audit_for_test()
            returns trigger language plpgsql as ${'$'}${'$'}
            begin
                if new.event_type = 'AUDIT_LOG_VIEWED' then
                    raise exception 'injected canonical self-audit failure';
                end if;
                return new;
            end;
            ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            create trigger reject_explorer_self_audit_for_test
            before insert on admin_security_audit_events
            for each row execute function reject_explorer_self_audit_for_test()
            """.trimIndent(),
        )

        try {
            mockMvc.perform(
                get("/api/v1/audit/activities")
                    .session(session)
                    .header("X-Interaction-Id", UUID.randomUUID()),
            )
                .andExpect(status().isServiceUnavailable)
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("/problems/audit-persistence-unavailable"))
                .andExpect(jsonPath("$.items").doesNotExist())
        } finally {
            jdbcTemplate.execute("drop trigger reject_explorer_self_audit_for_test on admin_security_audit_events")
            jdbcTemplate.execute("drop function reject_explorer_self_audit_for_test()")
        }

        assertThat(selfAuditCount("AUDIT_LOG_VIEWED", auditorId)).isZero()
    }

    @Test
    fun `detail deduplicates one semantic interaction and self audit failure blocks the protected response`() {
        val auditorId = insertStaff("auditor-detail-strict@example.com")
        val session = login("auditor-detail-strict@example.com")
        val fixture = insertTicketChangeFixture(auditorId)
        val interactionId = UUID.randomUUID()

        repeat(2) {
            mockMvc.perform(
                get("/api/v1/audit/activities/{activityId}", fixture.activityId)
                    .session(session)
                    .header("X-Interaction-Id", interactionId),
            ).andExpect(status().isOk)
        }
        assertThat(selfAuditCount("AUDIT_LOG_VIEWED", auditorId)).isEqualTo(1)

        jdbcTemplate.execute(
            """
            create function reject_detail_self_audit_for_test()
            returns trigger language plpgsql as ${'$'}${'$'}
            begin
                if new.event_type = 'AUDIT_LOG_VIEWED' then
                    raise exception 'injected detail self-audit failure';
                end if;
                return new;
            end;
            ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            create trigger reject_detail_self_audit_for_test
            before insert on admin_security_audit_events
            for each row execute function reject_detail_self_audit_for_test()
            """.trimIndent(),
        )
        try {
            val failed = mockMvc.perform(
                get("/api/v1/audit/activities/{activityId}", fixture.activityId)
                    .session(session)
                    .header("X-Interaction-Id", UUID.randomUUID()),
            )
                .andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.type").value("/problems/audit-persistence-unavailable"))
                .andExpect(jsonPath("$.fieldChange").doesNotExist())
                .andReturn()
            assertThat(failed.response.contentAsString).doesNotContain("OPEN", "PENDING")
        } finally {
            jdbcTemplate.execute("drop trigger reject_detail_self_audit_for_test on admin_security_audit_events")
            jdbcTemplate.execute("drop function reject_detail_self_audit_for_test()")
        }
        assertThat(selfAuditCount("AUDIT_LOG_VIEWED", auditorId)).isEqualTo(1)
    }

    @Test
    fun `search detail links result opens and one event reveal is reason gated no store and self audited`() {
        val auditorId = insertStaff("auditor-reveal@example.com")
        val session = login("auditor-reveal@example.com")
        val ticket = insertTicketChangeFixture(auditorId)
        val rawQuery = "  alice@example.com\npriority:urgent  "
        val search = insertSearchFixture(auditorId, ticket, rawQuery)
        insertTicketViewForSearch(auditorId, ticket, search.eventId)

        mockMvc.perform(
            get("/api/v1/audit/activities")
                .session(session)
                .header("X-Interaction-Id", UUID.randomUUID())
                .queryParam("ledger", "ACCESS_SEARCH")
                .queryParam("action", "SEARCH_EXECUTED")
                .queryParam("searchFingerprint", search.fingerprint),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].id").value(search.activityId.toString()))

        mockMvc.perform(
            get("/api/v1/audit/activities/{activityId}", search.activityId)
                .session(session)
                .header("X-Interaction-Id", UUID.randomUUID()),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.search.queryFingerprint").value(search.fingerprint))
            .andExpect(jsonPath("$.search.filters.status").value("OPEN"))
            .andExpect(jsonPath("$.search.resultCount").value(1))
            .andExpect(jsonPath("$.search.openedActivities.length()").value(1))
            .andExpect(jsonPath("$.search.openedActivities[0].activityId").value(search.openedActivityId.toString()))
            .andExpect(jsonPath("$.rawQuery").doesNotExist())

        val reveal = mockMvc.perform(
            post("/api/v1/audit/activities/{activityId}/search-query-reveal", search.activityId)
                .session(session)
                .header("X-CSRF-TOKEN", csrf(session))
                .header("X-Interaction-Id", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"reason":"incident 42 investigation"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.state").value("AVAILABLE"))
            .andExpect(jsonPath("$.rawQuery").value(rawQuery))
            .andExpect(jsonPath("$.keyVersion").value("local-v1"))
            .andReturn()
        assertThat(reveal.response.contentAsString).doesNotContain("incident 42 investigation")
        assertThat(selfAuditCount("AUDIT_SENSITIVE_CONTENT_REVEALED", auditorId)).isEqualTo(1)
        val revealAuditMetadata = jdbcTemplate.queryForObject(
            """
            select metadata_json from admin_security_audit_events
            where event_type = 'AUDIT_SENSITIVE_CONTENT_REVEALED' and actor_id = ?
            order by occurred_at desc limit 1
            """.trimIndent(),
            String::class.java,
            auditorId,
        )
        assertThat(revealAuditMetadata)
            .contains("incident 42 investigation", "AVAILABLE")
            .doesNotContain(rawQuery, "alice@example.com")

        jdbcTemplate.execute("alter table search_audit_query_ciphertexts disable trigger search_audit_query_ciphertexts_immutable")
        jdbcTemplate.update(
            """
            update search_audit_query_ciphertexts
            set query_ciphertext = set_byte(
                query_ciphertext,
                octet_length(query_ciphertext) - 1,
                (get_byte(query_ciphertext, octet_length(query_ciphertext) - 1) + 1) % 256
            )
            where access_event_id = ?
            """.trimIndent(),
            search.eventId,
        )
        jdbcTemplate.execute("alter table search_audit_query_ciphertexts enable trigger search_audit_query_ciphertexts_immutable")

        mockMvc.perform(
            post("/api/v1/audit/activities/{activityId}/search-query-reveal", search.activityId)
                .session(session)
                .header("X-CSRF-TOKEN", csrf(session))
                .header("X-Interaction-Id", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"reason":"tamper verification"}"""),
        )
            .andExpect(status().isUnprocessableContent)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.rawQuery").doesNotExist())
        assertThat(selfAuditCount("AUDIT_SENSITIVE_CONTENT_REVEALED", auditorId)).isEqualTo(2)

        jdbcTemplate.update("delete from search_audit_query_ciphertexts where access_event_id = ?", search.eventId)
        mockMvc.perform(
            post("/api/v1/audit/activities/{activityId}/search-query-reveal", search.activityId)
                .session(session)
                .header("X-CSRF-TOKEN", csrf(session))
                .header("X-Interaction-Id", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"reason":"retention verification"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.state").value("RETENTION_EXPIRED"))
            .andExpect(jsonPath("$.rawQuery").isEmpty())

        val retired = insertSearchFixture(
            actorId = auditorId,
            ticket = ticket,
            rawQuery = "retired key query",
            storedKeyVersion = "retired-v0",
        )
        mockMvc.perform(
            post("/api/v1/audit/activities/{activityId}/search-query-reveal", retired.activityId)
                .session(session)
                .header("X-CSRF-TOKEN", csrf(session))
                .header("X-Interaction-Id", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"reason":"key retirement verification"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.state").value("KEY_UNAVAILABLE"))
            .andExpect(jsonPath("$.rawQuery").isEmpty())

        mockMvc.perform(
            post("/api/v1/audit/activities/{activityId}/search-query-reveal", retired.activityId)
                .session(session)
                .header("X-CSRF-TOKEN", csrf(session))
                .header("X-Interaction-Id", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"reason":"   "}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.rawQuery").doesNotExist())

        session.setAttribute(
            StaffSessionValidationFilter.AUTHENTICATED_AT,
            Instant.now().minusSeconds(3600),
        )
        mockMvc.perform(
            post("/api/v1/audit/activities/{activityId}/search-query-reveal", retired.activityId)
                .session(session)
                .header("X-CSRF-TOKEN", csrf(session))
                .header("X-Interaction-Id", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"reason":"stale authentication verification"}"""),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.type").value("/problems/audit-reveal-reauthentication-required"))
            .andExpect(jsonPath("$.rawQuery").doesNotExist())
    }

    @Test
    fun `reveal self audit failure blocks decrypted response`() {
        val auditorId = insertStaff("auditor-reveal-fail@example.com")
        val session = login("auditor-reveal-fail@example.com")
        val ticket = insertTicketChangeFixture(auditorId)
        val search = insertSearchFixture(auditorId, ticket, "exact protected query")
        jdbcTemplate.execute(
            """
            create function reject_reveal_self_audit_for_test()
            returns trigger language plpgsql as ${'$'}${'$'}
            begin
                if new.event_type = 'AUDIT_SENSITIVE_CONTENT_REVEALED' then
                    raise exception 'injected reveal self-audit failure';
                end if;
                return new;
            end;
            ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            create trigger reject_reveal_self_audit_for_test
            before insert on admin_security_audit_events
            for each row execute function reject_reveal_self_audit_for_test()
            """.trimIndent(),
        )
        try {
            val failed = mockMvc.perform(
                post("/api/v1/audit/activities/{activityId}/search-query-reveal", search.activityId)
                    .session(session)
                    .header("X-CSRF-TOKEN", csrf(session))
                    .header("X-Interaction-Id", UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"reason":"strict audit persistence verification"}"""),
            )
                .andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.type").value("/problems/audit-persistence-unavailable"))
                .andExpect(jsonPath("$.rawQuery").doesNotExist())
                .andReturn()
            assertThat(failed.response.contentAsString).doesNotContain("exact protected query")
        } finally {
            jdbcTemplate.execute("drop trigger reject_reveal_self_audit_for_test on admin_security_audit_events")
            jdbcTemplate.execute("drop function reject_reveal_self_audit_for_test()")
        }
        assertThat(selfAuditCount("AUDIT_SENSITIVE_CONTENT_REVEALED", auditorId)).isZero()
    }

    @Test
    fun `signed cursor keeps a first page snapshot and one list self audit per interaction`() {
        val auditorId = insertStaff("auditor-cursor@example.com")
        val session = login("auditor-cursor@example.com")
        repeat(5) { index ->
            insertAdminEvent(auditorId, Instant.parse("2026-08-11T0${index + 1}:00:00Z"))
        }
        val interactionId = UUID.randomUUID()
        val first = auditPage(session, interactionId, null)
        val firstCursor = nextCursor(first)
        assertThat(firstCursor).isNotNull()
        val snapshotAt = Instant.parse(stringField(first, "snapshotAt"))
        val expectedIds = jdbcTemplate.queryForList(
            """
            select id from audit_activity_projection
            where occurred_at >= '2026-08-10T00:00:00Z'
              and occurred_at <= '2026-08-14T00:00:00Z'
              and occurred_at <= ?
            """.trimIndent(),
            UUID::class.java,
            Timestamp.from(snapshotAt),
        ).toSet()

        val lateActivityId = insertAdminEvent(auditorId, Instant.parse("2026-08-13T00:00:00Z"))
        val collected = activityIds(first).toMutableList()
        var cursor = firstCursor
        while (cursor != null) {
            val page = auditPage(session, interactionId, cursor)
            collected += activityIds(page)
            cursor = nextCursor(page)
        }

        assertThat(collected).doesNotHaveDuplicates()
        assertThat(collected.toSet()).isEqualTo(expectedIds)
        assertThat(collected).doesNotContain(lateActivityId)
        assertThat(
            jdbcTemplate.queryForObject(
                """
                select count(*) from admin_security_audit_events
                where event_type = 'AUDIT_LOG_VIEWED'
                  and actor_id = ?
                  and metadata_json::jsonb ->> 'interactionId' = ?
                """.trimIndent(),
                Long::class.java,
                auditorId,
                interactionId.toString(),
            ),
        ).isEqualTo(1)

        val cursorParts = firstCursor!!.split('.')
        val signature = cursorParts[2]
        val tampered = listOf(
            cursorParts[0],
            cursorParts[1],
            (if (signature.first() == 'A') 'B' else 'A').toString() + signature.drop(1),
        ).joinToString(".")
        mockMvc.perform(
            get("/api/v1/audit/activities")
                .session(session)
                .header("X-Interaction-Id", interactionId)
                .queryParam("from", "2026-08-10T00:00:00Z")
                .queryParam("to", "2026-08-14T00:00:00Z")
                .queryParam("cursor", tampered)
                .queryParam("limit", "2"),
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `export request atomically stores requester permission snapshot placeholder and self audit`() {
        val auditorId = insertStaff("auditor-export@example.com")
        val session = login("auditor-export@example.com")
        val create = mockMvc.perform(
            post("/api/v1/audit/exports")
                .session(session)
                .header("X-CSRF-TOKEN", csrf(session))
                .header("X-Interaction-Id", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(EXPORT_REQUEST),
        )
            .andExpect(status().isAccepted)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.status").value("REQUESTED"))
            .andExpect(jsonPath("$.format").value("CSV"))
            .andExpect(jsonPath("$.artifact.state").value("NOT_CREATED"))
            .andExpect(jsonPath("$.artifact.generationAvailable").value(false))
            .andReturn()
        val jobId = UUID.fromString(stringField(create.response.contentAsString, "id"))

        val persisted = jdbcTemplate.queryForMap(
            """
            select job.reason, job.permission_snapshot_json::text as permissions,
                   artifact.state, artifact.generation_available
            from audit_export_jobs job
            join audit_export_artifacts artifact on artifact.job_id = job.id
            where job.id = ? and job.requester_id = ?
            """.trimIndent(),
            jobId,
            auditorId,
        )
        assertThat(persisted["reason"]).isEqualTo("case 2042 review")
        assertThat(persisted["permissions"].toString()).contains("audit:export", "audit:activity:read")
        assertThat(persisted["state"]).isEqualTo("NOT_CREATED")
        assertThat(persisted["generation_available"]).isEqualTo(false)
        assertThat(selfAuditCount("AUDIT_EXPORT_REQUESTED", auditorId)).isEqualTo(1)

        mockMvc.perform(
            get("/api/v1/audit/exports/{jobId}", jobId)
                .session(session)
                .header("X-Interaction-Id", UUID.randomUUID()),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(jobId.toString()))
            .andExpect(jsonPath("$.artifact.generationAvailable").value(false))

        insertStaff("auditor-export-other@example.com")
        val otherSession = login("auditor-export-other@example.com")
        mockMvc.perform(
            get("/api/v1/audit/exports/{jobId}", jobId)
                .session(otherSession)
                .header("X-Interaction-Id", UUID.randomUUID()),
        ).andExpect(status().isNotFound)

        mockMvc.perform(
            get("/api/v1/audit/exports/{jobId}/download", jobId)
                .session(session)
                .header("X-Interaction-Id", UUID.randomUUID()),
        ).andExpect(status().isNotFound)
    }

    @Test
    fun `export self audit failure rolls back job and placeholder`() {
        val auditorId = insertStaff("auditor-export-fail@example.com")
        val session = login("auditor-export-fail@example.com")
        jdbcTemplate.execute(
            """
            create function reject_export_self_audit_for_test()
            returns trigger language plpgsql as ${'$'}${'$'}
            begin
                if new.event_type = 'AUDIT_EXPORT_REQUESTED' then
                    raise exception 'injected export self-audit failure';
                end if;
                return new;
            end;
            ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            create trigger reject_export_self_audit_for_test
            before insert on admin_security_audit_events
            for each row execute function reject_export_self_audit_for_test()
            """.trimIndent(),
        )
        try {
            mockMvc.perform(
                post("/api/v1/audit/exports")
                    .session(session)
                    .header("X-CSRF-TOKEN", csrf(session))
                    .header("X-Interaction-Id", UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(EXPORT_REQUEST),
            )
                .andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.type").value("/problems/audit-persistence-unavailable"))
        } finally {
            jdbcTemplate.execute("drop trigger reject_export_self_audit_for_test on admin_security_audit_events")
            jdbcTemplate.execute("drop function reject_export_self_audit_for_test()")
        }
        assertThat(jdbcTemplate.queryForObject("select count(*) from audit_export_jobs", Long::class.java)).isZero()
        assertThat(selfAuditCount("AUDIT_EXPORT_REQUESTED", auditorId)).isZero()
    }

    @Test
    fun `projection rebuild restores canonical equality and self audits the operation`() {
        val auditorId = insertStaff("auditor-rebuild@example.com")
        val session = login("auditor-rebuild@example.com")
        val ticket = insertTicketChangeFixture(auditorId)
        insertSearchFixture(auditorId, ticket, "rebuild search")
        insertAdminEvent(auditorId, Instant.parse("2026-08-11T04:00:00Z"))
        jdbcTemplate.update("delete from audit_activity_projection")
        assertThat(jdbcTemplate.queryForObject("select count(*) from audit_activity_projection", Long::class.java)).isZero()

        mockMvc.perform(
            post("/api/v1/audit/projection/rebuild")
                .session(session)
                .header("X-CSRF-TOKEN", csrf(session))
                .header("X-Interaction-Id", UUID.randomUUID()),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.ticketChangeCount").value(1))
            .andExpect(jsonPath("$.accessSearchCount").value(2))
            .andExpect(jsonPath("$.adminSecurityCount").value(3))
            .andExpect(jsonPath("$.totalCount").value(6))
            .andExpect(jsonPath("$.projection.state").value("CURRENT"))
            .andExpect(jsonPath("$.projection.projectedCount").value(6))

        assertThat(selfAuditCount("AUDIT_PROJECTION_REBUILT", auditorId)).isEqualTo(1)
        assertThat(
            jdbcTemplate.queryForObject(
                """
                select count(*) from (
                    select ledger_type, source_event_id from audit_activity_projection
                    except
                    select ledger_type, source_event_id from audit_activity_projection_source
                ) difference
                """.trimIndent(),
                Long::class.java,
            ),
        ).isZero()
    }

    private fun insertTicketChangeFixture(actorId: UUID): TicketChangeFixture {
        val customerId = UUID.randomUUID()
        val groupId = UUID.randomUUID()
        val ticketId = UUID.randomUUID()
        val auditId = UUID.randomUUID()
        val eventId = UUID.randomUUID()
        val occurredAt = Instant.parse("2026-08-11T01:00:00Z")
        jdbcTemplate.update(
            """
            insert into customers (id, name, email_normalized, email_display, created_at, updated_at)
            values (?, 'Alice', ?, ?, ?, ?)
            """.trimIndent(),
            customerId,
            "alice-${customerId}@example.com",
            "alice-${customerId}@example.com",
            Timestamp.from(occurredAt),
            Timestamp.from(occurredAt),
        )
        jdbcTemplate.update(
            """
            insert into support_groups (id, name, status, created_at, updated_at, version)
            values (?, ?, 'ACTIVE', ?, ?, 0)
            """.trimIndent(),
            groupId,
            "Audit group $groupId",
            Timestamp.from(occurredAt),
            Timestamp.from(occurredAt),
        )
        jdbcTemplate.update(
            """
            insert into tickets (
                id, ticket_number, requester_id, kind, subject, status, priority,
                group_id, channel, version, created_at, updated_at
            ) values (?, ?, ?, 'CUSTOMER_REQUEST', 'Audit fixture', 'PENDING', 'HIGH',
                ?, 'WEB', 2, ?, ?)
            """.trimIndent(),
            ticketId,
            TICKET_NUMBER,
            customerId,
            groupId,
            Timestamp.from(occurredAt),
            Timestamp.from(occurredAt),
        )
        jdbcTemplate.update(
            """
            insert into ticket_audits (
                id, ticket_id, ticket_version, expected_version, actor_type, actor_id,
                source, request_id, correlation_id, command_id, created_at
            ) values (?, ?, 2, 1, 'STAFF', ?, 'AGENT_UI', 'change-request',
                'change-correlation', 'change-command', ?)
            """.trimIndent(),
            auditId,
            ticketId,
            actorId,
            Timestamp.from(occurredAt),
        )
        jdbcTemplate.update(
            """
            insert into ticket_audit_events (
                id, audit_id, event_order, event_type, field_name,
                old_value_json, new_value_json, metadata_json, occurred_at
            ) values (?, ?, 1, 'STATUS_CHANGED', 'status', '"OPEN"', '"PENDING"',
                '{"visibility":"PUBLIC","commentBody":"private comment body"}', ?)
            """.trimIndent(),
            eventId,
            auditId,
            Timestamp.from(occurredAt),
        )
        val activityId = jdbcTemplate.queryForObject(
            "select id from audit_activity_projection where source_event_id = ?",
            UUID::class.java,
            eventId,
        ) ?: error("projection missing")
        return TicketChangeFixture(activityId, eventId, auditId, ticketId, groupId, TICKET_NUMBER)
    }

    private fun insertAdminEvent(actorId: UUID, occurredAt: Instant): UUID {
        val eventId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            insert into admin_security_audit_events (
                id, event_type, actor_type, actor_id, actor_display_snapshot, source,
                target_type, target_id, outcome, request_id, correlation_id,
                metadata_json, occurred_at
            ) values (?, 'ROLE_CHANGED', 'STAFF', ?, '감사 담당자', 'ADMIN_UI',
                'STAFF_ACCOUNT', ?, 'SUCCEEDED', ?, 'cursor-correlation', '{}', ?)
            """.trimIndent(),
            eventId,
            actorId,
            UUID.randomUUID(),
            "cursor-$eventId",
            Timestamp.from(occurredAt),
        )
        return projectionId(eventId)
    }

    private fun auditPage(session: MockHttpSession, interactionId: UUID, cursor: String?): String {
        val request = get("/api/v1/audit/activities")
            .session(session)
            .header("X-Interaction-Id", interactionId)
            .queryParam("from", "2026-08-10T00:00:00Z")
            .queryParam("to", "2026-08-14T00:00:00Z")
            .queryParam("limit", "2")
        cursor?.let { request.queryParam("cursor", it) }
        return mockMvc.perform(request)
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
    }

    private fun nextCursor(json: String): String? =
        Regex("\\\"nextCursor\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(json)?.groupValues?.get(1)

    private fun activityIds(json: String): List<UUID> =
        Regex("\\{\\\"id\\\":\\\"([0-9a-f-]{36})\\\",\\\"ledger\\\"")
            .findAll(json)
            .map { UUID.fromString(it.groupValues[1]) }
            .toList()

    private fun insertSearchFixture(
        actorId: UUID,
        ticket: TicketChangeFixture,
        rawQuery: String,
        storedKeyVersion: String = "local-v1",
    ): SearchFixture {
        val eventId = UUID.randomUUID()
        val openedEventId = UUID.randomUUID()
        val occurredAt = Instant.now().minusSeconds(60)
        val protected = searchQueryProtector.protect(eventId, rawQuery, occurredAt)
        jdbcTemplate.update(
            """
            insert into access_audit_events (
                id, occurred_at, actor_type, actor_id, actor_display_snapshot, source,
                action, resource_type, resource_id, ticket_number, interaction_id,
                session_fingerprint, auth_type, request_id, correlation_id, ip_address,
                user_agent, outcome, http_status
            ) values (?, ?, 'STAFF', ?, '감사 담당자', 'AGENT_UI', 'SEARCH_EXECUTED',
                'SEARCH', null, null, ?, 'local-v1:fixture', 'STAFF_SESSION',
                'search-request', 'search-correlation', '192.0.2.8', 'Deskseed test',
                'SUCCEEDED', 200)
            """.trimIndent(),
            eventId,
            Timestamp.from(occurredAt),
            actorId,
            UUID.randomUUID(),
        )
        jdbcTemplate.update(
            """
            insert into search_audit_details (
                access_event_id, query_redacted, query_fingerprint, query_key_version,
                normalized_filters, sort, result_count
            ) values (?, ?, ?, ?, '{"status":"OPEN"}',
                'updatedAt:desc,ticketNumber:desc', 1)
            """.trimIndent(),
            eventId,
            protected.queryRedacted,
            protected.queryFingerprint,
            storedKeyVersion,
        )
        jdbcTemplate.update(
            """
            insert into search_audit_result_items (
                access_event_id, ticket_id, ticket_number, result_ordinal
            ) values (?, ?, ?, 0)
            """.trimIndent(),
            eventId,
            ticket.ticketId,
            ticket.ticketNumber,
        )
        jdbcTemplate.update(
            """
            insert into search_audit_query_ciphertexts (
                access_event_id, key_version, query_ciphertext, created_at, expires_at
            ) values (?, ?, ?, ?, ?)
            """.trimIndent(),
            eventId,
            storedKeyVersion,
            protected.queryCiphertext,
            Timestamp.from(occurredAt),
            Timestamp.from(protected.expiresAt),
        )
        jdbcTemplate.update(
            """
            insert into access_audit_events (
                id, occurred_at, actor_type, actor_id, actor_display_snapshot, source,
                action, resource_type, resource_id, ticket_number, interaction_id,
                session_fingerprint, auth_type, request_id, correlation_id, ip_address,
                user_agent, origin_search_event_id, outcome, http_status
            ) values (?, ?, 'STAFF', ?, '감사 담당자', 'AGENT_UI', 'SEARCH_RESULT_OPENED',
                'TICKET', ?, ?, ?, 'local-v1:fixture', 'STAFF_SESSION',
                'open-request', 'search-correlation', '192.0.2.8', 'Deskseed test', ?,
                'SUCCEEDED', 200)
            """.trimIndent(),
            openedEventId,
            Timestamp.from(occurredAt.plusSeconds(1)),
            actorId,
            ticket.ticketId,
            ticket.ticketNumber,
            UUID.randomUUID(),
            eventId,
        )
        return SearchFixture(
            eventId = eventId,
            activityId = projectionId(eventId),
            openedActivityId = projectionId(openedEventId),
            fingerprint = protected.queryFingerprint,
        )
    }

    private fun projectionId(sourceEventId: UUID): UUID = jdbcTemplate.queryForObject(
        "select id from audit_activity_projection where source_event_id = ?",
        UUID::class.java,
        sourceEventId,
    ) ?: error("projection missing")

    private fun insertTicketViewForSearch(
        actorId: UUID,
        ticket: TicketChangeFixture,
        searchEventId: UUID,
    ) {
        jdbcTemplate.update(
            """
            insert into access_audit_events (
                id, occurred_at, actor_type, actor_id, actor_display_snapshot, source,
                action, resource_type, resource_id, ticket_number, interaction_id,
                session_fingerprint, auth_type, request_id, correlation_id, ip_address,
                user_agent, origin_search_event_id, outcome, http_status
            ) values (?, ?, 'STAFF', ?, '감사 담당자', 'AGENT_UI', 'TICKET_VIEWED',
                'TICKET', ?, ?, ?, 'local-v1:fixture', 'STAFF_SESSION',
                'view-request', 'search-correlation', '192.0.2.8', 'Deskseed test', ?,
                'SUCCEEDED', 200)
            """.trimIndent(),
            UUID.randomUUID(),
            Timestamp.from(Instant.now().minusSeconds(58)),
            actorId,
            ticket.ticketId,
            ticket.ticketNumber,
            UUID.randomUUID(),
            searchEventId,
        )
    }

    private fun insertStaff(email: String): UUID {
        val id = UUID.randomUUID()
        val now = Instant.parse("2026-08-12T00:00:00Z")
        jdbcTemplate.update(
            """
            insert into staff_accounts (
                id, email_normalized, email_display, display_name, role, status,
                password_hash, created_at, updated_at, version
            ) values (?, ?, ?, '감사 담당자', 'SECURITY_AUDITOR', 'ACTIVE', ?, ?, ?, 0)
            """.trimIndent(),
            id,
            email,
            email,
            BCryptPasswordEncoder(4).encode(PASSWORD),
            Timestamp.from(now),
            Timestamp.from(now),
        )
        return id
    }

    private fun login(email: String): MockHttpSession {
        val csrfResponse = mockMvc.perform(get("/api/v1/agent/csrf"))
            .andExpect(status().isOk)
            .andReturn()
        val session = csrfResponse.request.session as MockHttpSession
        val csrf = stringField(csrfResponse.response.contentAsString, "token")
        mockMvc.perform(
            post("/api/v1/agent/session")
                .session(session)
                .header("X-CSRF-TOKEN", csrf)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email","password":"$PASSWORD"}"""),
        ).andExpect(status().isNoContent)
        return session
    }

    private fun csrf(session: MockHttpSession): String = mockMvc.perform(
        get("/api/v1/agent/csrf").session(session),
    ).andExpect(status().isOk).andReturn().response.contentAsString.let { stringField(it, "token") }

    private fun selfAuditCount(eventType: String, actorId: UUID): Long = jdbcTemplate.queryForObject(
        "select count(*) from admin_security_audit_events where event_type = ? and actor_id = ?",
        Long::class.java,
        eventType,
        actorId,
    ) ?: 0

    private fun stringField(json: String, field: String): String =
        Regex("\\\"$field\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(json)?.groupValues?.get(1)
            ?: error("Missing $field")

    private data class TicketChangeFixture(
        val activityId: UUID,
        val eventId: UUID,
        val auditId: UUID,
        val ticketId: UUID,
        val groupId: UUID,
        val ticketNumber: Long,
    )

    private data class SearchFixture(
        val eventId: UUID,
        val activityId: UUID,
        val openedActivityId: UUID,
        val fingerprint: String,
    )

    companion object {
        private const val PASSWORD = "Auditor password 42"
        private const val TICKET_NUMBER = 9201L
        private val EXPORT_REQUEST =
            """
            {
              "format":"CSV",
              "filters":{"from":"2026-08-10T00:00:00Z","to":"2026-08-14T00:00:00Z","ledger":"ACCESS_SEARCH"},
              "fields":["occurredAt","ledger","action","actor","searchFingerprint"],
              "reason":"case 2042 review"
            }
            """.trimIndent()

        @Container
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer = PostgreSQLContainer(
            DockerImageName.parse("postgres:18.3-alpine"),
        )
    }
}
