package dev.deskseed.attachments.internal

import dev.deskseed.attachments.AttachmentCleanupService
import dev.deskseed.attachments.AttachmentUploadCommand
import dev.deskseed.attachments.AttachmentUploadService
import dev.deskseed.attachments.AttachmentVisibility
import dev.deskseed.attachments.TicketAttachmentLinkCommand
import dev.deskseed.attachments.TicketAttachmentLinker
import dev.deskseed.foundation.ActorRef
import dev.deskseed.foundation.ActorType
import dev.deskseed.foundation.CommandContext
import dev.deskseed.foundation.RequestSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@dev.deskseed.testsupport.integration.DeskseedSpringIntegrationTest(
    properties = [
        "deskseed.staff-auth.bootstrap.enabled=false",
    ],
)
@AutoConfigureMockMvc
@dev.deskseed.testsupport.category.IntegrationTest
class AttachmentPipelineIntegrationTest {
    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var uploadService: AttachmentUploadService
    @Autowired private lateinit var attachmentLinker: TicketAttachmentLinker
    @Autowired private lateinit var cleanupService: AttachmentCleanupService

    @Test
    fun `customer CLEAN upload links in the follow-up transaction and downloads with required access audit`() {
        val submitted = submitRequest("clean-link")
        val attachmentId = uploadCustomerPdf(submitted)

        mockMvc.perform(
            post("/api/v1/requests/{ticketNumber}/comments", submitted.ticketNumber)
                .header("X-Request-Access-Token", submitted.accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"body":"영수증을 첨부합니다.","attachmentIds":["$attachmentId"],"clientCommandId":"attachment-follow-up-${UUID.randomUUID()}"}
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.attachments[0].id").value(attachmentId.toString()))

        mockMvc.perform(
            get("/api/v1/requests/{ticketNumber}/attachments/{attachmentId}/download", submitted.ticketNumber, attachmentId)
                .header("X-Request-Access-Token", submitted.accessToken),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().exists("Content-Disposition"))

        assertThat(
            jdbcTemplate.queryForObject(
                """
                select count(*) from access_audit_events
                where action = 'ATTACHMENT_DOWNLOADED' and resource_id = ? and actor_type = 'CUSTOMER'
                """.trimIndent(),
                Long::class.java,
                attachmentId,
            ),
        ).isEqualTo(1)
        assertThat(
            jdbcTemplate.queryForObject(
                "select scan_status from attachment_objects where id = ?",
                String::class.java,
                attachmentId,
            ),
        ).isEqualTo("CLEAN")
    }

    @Test
    fun `initial multipart request scans before creation and links only the CLEAN first PUBLIC attachment`() {
        val response = mockMvc.perform(
            multipart("/api/v1/requests")
                .file(MockMultipartFile("name", "", "text/plain", "최초 첨부 고객".toByteArray()))
                .file(MockMultipartFile("email", "", "text/plain", "initial-${UUID.randomUUID()}@example.test".toByteArray()))
                .file(MockMultipartFile("subject", "", "text/plain", "최초 첨부 문의".toByteArray()))
                .file(MockMultipartFile("message", "", "text/plain", "PDF를 포함한 첫 문의".toByteArray()))
                .file(MockMultipartFile("attachments", "initial.pdf", "application/pdf", PDF_BYTES)),
        )
            .andExpect(status().isCreated)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andReturn().response.contentAsString
        val json = objectMapper.readTree(response)
        val ticketNumber = json.path("ticketNumber").asLong()
        val accessToken = json.path("accessToken").asText()

        mockMvc.perform(
            get("/api/v1/requests/{ticketNumber}", ticketNumber)
                .header("X-Request-Access-Token", accessToken),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.comments[0].attachments.length()").value(1))
            .andExpect(jsonPath("$.comments[0].attachments[0].fileName").value("initial.pdf"))
    }

    @Test
    fun `MIME mismatch and infected content are failed closed before a ticket link`() {
        val submitted = submitRequest("scan-failures")

        mockMvc.perform(
            multipart("/api/v1/requests/{ticketNumber}/attachments/uploads", submitted.ticketNumber)
                .file(MockMultipartFile("file", "receipt.pdf", "image/png", PDF_BYTES))
                .header("X-Request-Access-Token", submitted.accessToken),
        )
            .andExpect(status().isUnsupportedMediaType)
            .andExpect(jsonPath("$.type").value("/problems/attachment-mime-mismatch"))

        mockMvc.perform(
            multipart("/api/v1/requests/{ticketNumber}/attachments/uploads", submitted.ticketNumber)
                .file(MockMultipartFile("file", "infected.pdf", "application/pdf", "%PDF-EICAR-STANDARD-ANTIVIRUS-TEST-FILE".toByteArray()))
                .header("X-Request-Access-Token", submitted.accessToken),
        )
            .andExpect(status().isUnprocessableContent)
            .andExpect(jsonPath("$.type").value("/problems/attachment-infected"))

        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from attachment_objects where scan_status in ('FAILED', 'INFECTED')",
                Long::class.java,
            ),
        ).isGreaterThanOrEqualTo(2)
        assertThat(
            jdbcTemplate.queryForObject("select count(*) from ticket_comment_attachments", Long::class.java),
        ).isZero()
    }

    @Test
    fun `attachment scan audit failure fails closed and leaves no link`() {
        val submitted = submitRequest("attachment-audit-failure")
        jdbcTemplate.execute(
            """
            create function reject_attachment_scan_audit_for_test()
            returns trigger language plpgsql as ${'$'}${'$'}
            begin
                if new.event_type = 'ATTACHMENT_SCANNED_CLEAN' then
                    raise exception 'injected attachment scan audit failure';
                end if;
                return new;
            end;
            ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            create trigger reject_attachment_scan_audit_for_test
            before insert on admin_security_audit_events
            for each row execute function reject_attachment_scan_audit_for_test()
            """.trimIndent(),
        )
        try {
            mockMvc.perform(
                multipart("/api/v1/requests/{ticketNumber}/attachments/uploads", submitted.ticketNumber)
                    .file(MockMultipartFile("file", "audit.pdf", "application/pdf", PDF_BYTES))
                    .header("X-Request-Access-Token", submitted.accessToken),
            )
                .andExpect(status().isServiceUnavailable)

            assertThat(
                jdbcTemplate.queryForObject(
                    "select count(*) from attachment_objects where file_name = 'audit.pdf' and scan_status = 'CLEAN'",
                    Long::class.java,
                ),
            ).isZero()
            assertThat(
                jdbcTemplate.queryForObject(
                    """
                    select count(*)
                    from ticket_comment_attachments link
                    join attachment_objects object on object.id = link.attachment_id
                    where object.file_name = 'audit.pdf'
                    """.trimIndent(),
                    Long::class.java,
                ),
            ).isZero()
        } finally {
            jdbcTemplate.execute("drop trigger reject_attachment_scan_audit_for_test on admin_security_audit_events")
            jdbcTemplate.execute("drop function reject_attachment_scan_audit_for_test()")
        }
    }

    @Test
    fun `PUBLIC request projection and download cannot expose an INTERNAL attachment`() {
        val submitted = submitRequest("visibility")
        val ticketId = jdbcTemplate.queryForObject(
            "select id from tickets where ticket_number = ?",
            UUID::class.java,
            submitted.ticketNumber,
        )!!
        val internalCommentId = UUID.randomUUID()
        val staffId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            insert into ticket_comments (id, ticket_id, author_type, author_id, visibility, body, created_at)
            values (?, ?, 'AGENT', ?, 'INTERNAL', 'internal attachment', ?)
            """.trimIndent(),
            internalCommentId,
            ticketId,
            staffId,
            Timestamp.from(Instant.now()),
        )
        val internalAttachment = uploadService.upload(
            AttachmentUploadCommand(
                actor = ActorRef(ActorType.STAFF, staffId),
                actorDisplayName = "테스트 상담사",
                source = RequestSource.AGENT_UI,
                context = context(),
                boundTicketId = null,
                allowedVisibility = null,
                fileName = "internal.pdf",
                declaredContentType = "application/pdf",
                content = ByteArrayInputStream(PDF_BYTES),
            ),
        ).attachment
        attachmentLinker.linkCleanAttachments(
            TicketAttachmentLinkCommand(
                ticketId = ticketId,
                commentId = internalCommentId,
                visibility = AttachmentVisibility.INTERNAL,
                actor = ActorRef(ActorType.STAFF, staffId),
                attachmentIds = setOf(internalAttachment.id),
                linkedAt = Instant.now(),
            ),
        )

        mockMvc.perform(
            get("/api/v1/requests/{ticketNumber}", submitted.ticketNumber)
                .header("X-Request-Access-Token", submitted.accessToken),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.comments.length()").value(1))
            .andExpect(jsonPath("$.comments[0].attachments").isEmpty)

        mockMvc.perform(
            get("/api/v1/requests/{ticketNumber}/attachments/{attachmentId}/download", submitted.ticketNumber, internalAttachment.id)
                .header("X-Request-Access-Token", submitted.accessToken),
        )
            .andExpect(status().isNotFound)
    }

    @Test
    fun `expired linked attachment is deleted and cannot be downloaded`() {
        val submitted = submitRequest("expiry")
        val attachmentId = uploadCustomerPdf(submitted)
        val commentResponse = mockMvc.perform(
            post("/api/v1/requests/{ticketNumber}/comments", submitted.ticketNumber)
                .header("X-Request-Access-Token", submitted.accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"body":"만료될 파일","attachmentIds":["$attachmentId"],"clientCommandId":"expiry-${UUID.randomUUID()}"}""",
                ),
        ).andExpect(status().isCreated).andReturn()
        assertThat(commentResponse.response.status).isEqualTo(201)
        jdbcTemplate.update(
            "update attachment_objects set created_at = ?, expires_at = ? where id = ?",
            Timestamp.from(Instant.now().minusSeconds(2 * 24 * 60 * 60)),
            Timestamp.from(Instant.now().minusSeconds(1)),
            attachmentId,
        )
        assertThat(cleanupService.purgeExpired(Instant.now())).isEqualTo(1)

        mockMvc.perform(
            get("/api/v1/requests/{ticketNumber}/attachments/{attachmentId}/download", submitted.ticketNumber, attachmentId)
                .header("X-Request-Access-Token", submitted.accessToken),
        ).andExpect(status().isNotFound)
        assertThat(
            jdbcTemplate.queryForObject("select scan_status from attachment_objects where id = ?", String::class.java, attachmentId),
        ).isEqualTo("EXPIRED")
    }

    private fun uploadCustomerPdf(submitted: Submitted): UUID {
        val body = mockMvc.perform(
            multipart("/api/v1/requests/{ticketNumber}/attachments/uploads", submitted.ticketNumber)
                .file(MockMultipartFile("file", "receipt.pdf", "application/pdf", PDF_BYTES))
                .header("X-Request-Access-Token", submitted.accessToken),
        )
            .andExpect(status().isCreated)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andReturn().response.contentAsString
        return objectMapper.readTree(body).path("id").let { UUID.fromString(it.asText()) }
    }

    private fun submitRequest(label: String): Submitted {
        val body = mockMvc.perform(
            post("/api/v1/requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"name":"첨부 고객","email":"$label-${UUID.randomUUID()}@example.test","subject":"첨부 테스트","message":"처음 문의"}
                    """.trimIndent(),
                ),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val json = objectMapper.readTree(body)
        return Submitted(json.path("ticketNumber").asLong(), json.path("accessToken").asText())
    }

    private fun context() = CommandContext(
        source = RequestSource.AGENT_UI,
        requestId = "attachment-test-request-${UUID.randomUUID()}",
        correlationId = "attachment-test-correlation-${UUID.randomUUID()}",
        commandId = "attachment-test-command-${UUID.randomUUID()}",
    )

    private data class Submitted(val ticketNumber: Long, val accessToken: String)

    private companion object {
        val PDF_BYTES = "%PDF-1.7\nprivate attachment test\n".toByteArray()
    }
}
