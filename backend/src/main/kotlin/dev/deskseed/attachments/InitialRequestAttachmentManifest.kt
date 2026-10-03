package dev.deskseed.attachments

import java.time.Instant
import java.util.UUID

data class InitialRequestAttachmentContent(val sha256: String, val sizeBytes: Long, val mediaType: String)

/** Server-computed CLEAN content descriptors, in submission order, for initial-request replay comparison. */
fun interface InitialRequestAttachmentManifest {
    fun read(attachmentIds: List<UUID>, customerId: UUID, now: Instant): List<InitialRequestAttachmentContent>
}
