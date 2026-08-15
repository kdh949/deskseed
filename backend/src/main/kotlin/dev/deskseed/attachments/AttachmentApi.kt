package dev.deskseed.attachments

import dev.deskseed.audit.AccessAuditContext
import dev.deskseed.foundation.ActorRef
import dev.deskseed.foundation.CommandContext
import dev.deskseed.foundation.RequestSource
import java.io.InputStream
import java.time.Instant
import java.util.UUID

enum class AttachmentVisibility {
    PUBLIC,
    INTERNAL,
}

enum class AttachmentScanStatus {
    QUARANTINED,
    CLEAN,
    INFECTED,
    FAILED,
    DELETED,
    EXPIRED,
}

data class TicketAttachment(
    val id: UUID,
    val fileName: String,
    val sizeBytes: Long,
    val contentType: String,
)

data class AttachmentUploadCommand(
    val actor: ActorRef,
    val actorDisplayName: String,
    val source: RequestSource,
    val context: CommandContext,
    /** Customer token uploads are bound to this ticket before they can be linked. */
    val boundTicketId: UUID?,
    val allowedVisibility: AttachmentVisibility?,
    /** Only the public-request orchestration may set this before its first ticket transaction exists. */
    val initialPublicSubmission: Boolean = false,
    val fileName: String,
    val declaredContentType: String?,
    val content: InputStream,
)

data class AttachmentUploadResult(
    val attachment: TicketAttachment,
    val scanStatus: AttachmentScanStatus,
    val expiresAt: Instant,
)

data class TicketAttachmentLinkCommand(
    val ticketId: UUID,
    val commentId: UUID,
    val visibility: AttachmentVisibility,
    val actor: ActorRef,
    val attachmentIds: Set<UUID>,
    val linkedAt: Instant,
)

data class LinkedTicketAttachment(
    val attachment: TicketAttachment,
    val visibility: AttachmentVisibility,
)

data class AttachmentLinkLocator(
    val ticketId: UUID,
    val ticketNumber: Long,
    val visibility: AttachmentVisibility,
)

data class AttachmentDownloadCommand(
    val attachmentId: UUID,
    val ticketId: UUID,
    val ticketNumber: Long,
    val allowedVisibilities: Set<AttachmentVisibility>,
    val accessContext: AccessAuditContext,
    val interactionId: UUID?,
    val occurredAt: Instant,
)

data class AttachmentContent(
    val attachment: TicketAttachment,
    val stream: InputStream,
)

interface AttachmentUploadService {
    /** Bounded quarantine upload, content verification and scan. No ticket transaction is open here. */
    fun upload(command: AttachmentUploadCommand): AttachmentUploadResult
}

interface TicketAttachmentLinker {
    /** Must run inside the caller's ticket/comment/audit transaction. */
    fun linkCleanAttachments(command: TicketAttachmentLinkCommand): List<LinkedTicketAttachment>
}

interface TicketAttachmentReadProjection {
    fun listForComments(
        commentIds: Collection<UUID>,
        allowedVisibilities: Set<AttachmentVisibility>,
    ): Map<UUID, List<TicketAttachment>>
}

interface AttachmentDownloadService {
    /** Internal lookup only; callers must authorize its returned ticket before opening bytes. */
    fun locateLinkedAttachment(attachmentId: UUID): AttachmentLinkLocator?

    /** Rechecks CLEAN/link/visibility and commits required access audit before returning a private stream. */
    fun openForDownload(command: AttachmentDownloadCommand): AttachmentContent
}

interface AttachmentCleanupService {
    /** Deletes bounded batches of unlinked/expired private objects and marks metadata terminally unavailable. */
    fun purgeExpired(now: Instant): Int
}

class AttachmentNotFoundException : RuntimeException()
class AttachmentAccessDeniedException : RuntimeException()
class AttachmentLinkInvalidException(message: String) : RuntimeException(message)
class AttachmentTooLargeException : RuntimeException()
class AttachmentMimeMismatchException : RuntimeException()
class AttachmentInfectedException : RuntimeException()
class AttachmentUnavailableException(cause: Throwable? = null) : RuntimeException(cause)
