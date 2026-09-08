package dev.deskseed.attachments.internal

import dev.deskseed.attachments.AttachmentLinkInvalidException
import dev.deskseed.attachments.InitialRequestAttachmentContent
import dev.deskseed.attachments.InitialRequestAttachmentManifest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
internal class JdbcInitialRequestAttachmentManifest(private val jdbc: JdbcTemplate) : InitialRequestAttachmentManifest {
    override fun read(attachmentIds: List<UUID>, customerId: UUID, now: Instant): List<InitialRequestAttachmentContent> {
        require(attachmentIds.size <= 5 && attachmentIds.distinct().size == attachmentIds.size)
        return attachmentIds.map { id ->
            jdbc.query("""
                select sha256, size_bytes, content_type from attachment_objects
                where id = ? and uploaded_actor_type = 'CUSTOMER' and uploaded_actor_id = ?
                  and initial_public_submission and bound_ticket_id is null and allowed_visibility = 'PUBLIC'
                  and scan_status = 'CLEAN' and expires_at > ?
                for share
            """.trimIndent(), { row, _ -> InitialRequestAttachmentContent(
                row.getString("sha256"), row.getLong("size_bytes"), row.getString("content_type"),
            ) }, id, customerId, Timestamp.from(now)).singleOrNull()
                ?: throw AttachmentLinkInvalidException("Initial request attachment is unavailable")
        }
    }
}
