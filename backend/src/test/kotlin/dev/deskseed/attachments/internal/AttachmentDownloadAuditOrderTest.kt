package dev.deskseed.attachments.internal

import dev.deskseed.attachments.AttachmentDownloadCommand
import dev.deskseed.attachments.AttachmentObjectStore
import dev.deskseed.attachments.AttachmentScanStatus
import dev.deskseed.attachments.AttachmentUnavailableException
import dev.deskseed.attachments.AttachmentVisibility
import dev.deskseed.attachments.MalwareScanResult
import dev.deskseed.attachments.MalwareScanner
import dev.deskseed.audit.AccessAuditAuthType
import dev.deskseed.audit.AccessAuditContext
import dev.deskseed.foundation.ActorType
import dev.deskseed.foundation.RequestSource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class AttachmentDownloadAuditOrderTest {
    @Test
    fun `required audit failure does not open private attachment stream`() {
        val now = Instant.parse("2026-08-18T00:00:00Z")
        val attachmentId = UUID.randomUUID()
        val ticketId = UUID.randomUUID()
        val customerId = UUID.randomUUID()
        val command = AttachmentDownloadCommand(
            attachmentId = attachmentId,
            ticketId = ticketId,
            ticketNumber = 103L,
            allowedVisibilities = setOf(AttachmentVisibility.PUBLIC),
            accessContext = AccessAuditContext(
                actorType = ActorType.CUSTOMER,
                actorId = customerId,
                actorDisplaySnapshot = "customer",
                source = RequestSource.CUSTOMER_PORTAL,
                sessionFingerprint = "session-fingerprint",
                authType = AccessAuditAuthType.CUSTOMER_SESSION,
                requestId = "req-attachment-download",
                correlationId = "corr-attachment-download",
                ipAddress = null,
                userAgent = null,
            ),
            interactionId = null,
            occurredAt = now,
        )
        val row = StoredAttachment(
            id = attachmentId,
            storageKey = "attachments/quarantine/$attachmentId",
            uploadedActorType = ActorType.CUSTOMER,
            uploadedActorId = customerId,
            boundTicketId = ticketId,
            allowedVisibility = AttachmentVisibility.PUBLIC,
            fileName = "evidence.pdf",
            sizeBytes = 12L,
            contentType = "application/pdf",
            status = AttachmentScanStatus.CLEAN,
            linkedAt = now.minusSeconds(60),
            expiresAt = now.plusSeconds(3600),
        )
        val metadata = mock(AttachmentMetadataStore::class.java)
        val transitions = mock(AttachmentStateTransitions::class.java)
        val objectStore = RecordingObjectStore()
        `when`(
            metadata.findDownloadable(
                attachmentId,
                ticketId,
                setOf(AttachmentVisibility.PUBLIC),
                now,
            ),
        ).thenReturn(row)
        doThrow(IllegalStateException("forced audit failure"))
            .`when`(transitions).recordDownload(row, command)
        val service = AttachmentApplicationService(
            metadata = metadata,
            transitions = transitions,
            objectStore = objectStore,
            malwareScanner = CleanScanner,
            properties = AttachmentStorageProperties(),
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

        assertThatThrownBy { service.openForDownload(command) }
            .isInstanceOf(AttachmentUnavailableException::class.java)
        assertThat(objectStore.openCount).isZero()
    }

    private class RecordingObjectStore : AttachmentObjectStore {
        var openCount: Int = 0
            private set

        override fun putQuarantine(key: String, content: InputStream): Long = error("not used")

        override fun openPrivate(key: String): InputStream {
            openCount += 1
            return ByteArrayInputStream("attachment".toByteArray())
        }

        override fun delete(key: String) = error("not used")
    }

    private object CleanScanner : MalwareScanner {
        override fun scan(content: InputStream, fileName: String, contentType: String) = MalwareScanResult.CLEAN
    }
}
