package dev.deskseed.attachments.internal

import dev.deskseed.attachments.AttachmentObjectStore
import dev.deskseed.attachments.AttachmentScanStatus
import dev.deskseed.attachments.AttachmentUnavailableException
import dev.deskseed.attachments.AttachmentVisibility
import dev.deskseed.attachments.MalwareScanner
import dev.deskseed.foundation.ActorType
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Instant
import java.util.UUID

@dev.deskseed.testsupport.category.FastTest
class AttachmentCleanupBoundaryTest {
    private val now = Instant.parse("2026-09-02T00:00:00Z")
    private val attachment = StoredAttachment(
        id = UUID.randomUUID(),
        storageKey = "attachments/quarantine/${UUID.randomUUID()}",
        uploadedActorType = ActorType.CUSTOMER,
        uploadedActorId = UUID.randomUUID(),
        boundTicketId = UUID.randomUUID(),
        allowedVisibility = AttachmentVisibility.PUBLIC,
        fileName = "receipt.pdf",
        sizeBytes = 12,
        contentType = "application/pdf",
        status = AttachmentScanStatus.CLEAN,
        linkedAt = now.minusSeconds(60),
        expiresAt = now.minusSeconds(1),
    )
    private val claim = AttachmentCleanupClaim(attachment, UUID.randomUUID())

    @Test
    fun `remote delete failure releases claim without completing database state`() {
        val transactions = mock(AttachmentCleanupTransactions::class.java)
        val objectStore = mock(AttachmentObjectStore::class.java)
        doThrow(IllegalStateException("S3 unavailable")).`when`(objectStore).delete(attachment.storageKey)
        `when`(transactions.claimExpired(now, 100, now.plusSeconds(300))).thenReturn(listOf(claim))
        val service = service(transactions, objectStore)

        assertThatThrownBy { service.purgeExpired(now) }
            .isInstanceOf(AttachmentUnavailableException::class.java)

        verify(transactions).releaseClaim(claim)
        verify(transactions, never()).completeClaim(claim, now)
    }

    @Test
    fun `database completion runs only after remote delete and remains retryable when completion fails`() {
        val transactions = mock(AttachmentCleanupTransactions::class.java)
        val objectStore = mock(AttachmentObjectStore::class.java)
        `when`(transactions.claimExpired(now, 100, now.plusSeconds(300))).thenReturn(listOf(claim))
        doThrow(IllegalStateException("audit write failed")).`when`(transactions).completeClaim(claim, now)
        val service = service(transactions, objectStore)

        assertThatThrownBy { service.purgeExpired(now) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("audit write failed")

        inOrder(objectStore, transactions).apply {
            verify(objectStore).delete(attachment.storageKey)
            verify(transactions).completeClaim(claim, now)
        }
        verify(transactions, never()).releaseClaim(claim)
    }

    private fun service(
        transactions: AttachmentCleanupTransactions,
        objectStore: AttachmentObjectStore,
    ) = AttachmentApplicationService(
        metadata = mock(AttachmentMetadataStore::class.java),
        transitions = mock(AttachmentStateTransitions::class.java),
        objectStore = objectStore,
        malwareScanner = mock(MalwareScanner::class.java),
        cleanupTransactions = transactions,
        properties = AttachmentStorageProperties(),
        clock = Clock.systemUTC(),
    )

}
