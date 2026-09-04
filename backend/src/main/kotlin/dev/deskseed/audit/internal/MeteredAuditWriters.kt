package dev.deskseed.audit.internal

import dev.deskseed.audit.AccessAuditWriter
import dev.deskseed.audit.AdminSecurityAudit
import dev.deskseed.audit.AdminSecurityAuditWriter
import dev.deskseed.audit.AttachmentDownloadAccessAudit
import dev.deskseed.audit.CustomerSearchExecutedAccessAudit
import dev.deskseed.audit.MacroPreviewedAccessAudit
import dev.deskseed.audit.SavedViewExecutedAccessAudit
import dev.deskseed.audit.SearchExecutedAccessAudit
import dev.deskseed.audit.SearchResultOpenedAccessAudit
import dev.deskseed.audit.TicketResourceReadAccessAudit
import dev.deskseed.audit.TicketViewAccessAudit
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Service
import java.util.UUID

@Primary
@Service
internal class MeteredAccessAuditWriter(
    private val delegate: JpaAccessAuditWriter,
    private val metrics: AuditPersistenceMetrics,
) : AccessAuditWriter {
    override fun appendTicketResourceRead(event: TicketResourceReadAccessAudit) =
        record(AuditPersistenceMetrics.Operation.TICKET_RESOURCE_READ) {
            delegate.appendTicketResourceRead(event)
        }

    override fun appendSavedViewExecuted(event: SavedViewExecutedAccessAudit) =
        record(AuditPersistenceMetrics.Operation.SAVED_VIEW_EXECUTED) {
            delegate.appendSavedViewExecuted(event)
        }

    override fun appendMacroPreviewed(event: MacroPreviewedAccessAudit) =
        record(AuditPersistenceMetrics.Operation.MACRO_PREVIEWED) {
            delegate.appendMacroPreviewed(event)
        }

    override fun appendAttachmentDownloaded(event: AttachmentDownloadAccessAudit) =
        record(AuditPersistenceMetrics.Operation.ATTACHMENT_DOWNLOADED) {
            delegate.appendAttachmentDownloaded(event)
        }

    override fun appendTicketViewed(event: TicketViewAccessAudit): Boolean =
        record(AuditPersistenceMetrics.Operation.TICKET_VIEWED) {
            delegate.appendTicketViewed(event)
        }

    override fun appendSearchExecuted(event: SearchExecutedAccessAudit) =
        record(AuditPersistenceMetrics.Operation.SEARCH_EXECUTED) {
            delegate.appendSearchExecuted(event)
        }

    override fun appendCustomerSearchExecuted(event: CustomerSearchExecutedAccessAudit) =
        record(AuditPersistenceMetrics.Operation.CUSTOMER_SEARCH_EXECUTED) {
            delegate.appendCustomerSearchExecuted(event)
        }

    override fun isValidSearchOrigin(
        originSearchEventId: UUID,
        actorId: UUID,
        sessionFingerprint: String,
        ticketId: UUID,
    ): Boolean = delegate.isValidSearchOrigin(originSearchEventId, actorId, sessionFingerprint, ticketId)

    override fun appendSearchResultOpened(event: SearchResultOpenedAccessAudit): Boolean =
        record(AuditPersistenceMetrics.Operation.SEARCH_RESULT_OPENED) {
            delegate.appendSearchResultOpened(event)
        }

    private fun <T> record(operation: AuditPersistenceMetrics.Operation, block: () -> T): T =
        metrics.record(AuditPersistenceMetrics.Ledger.ACCESS, operation, block)
}

@Primary
@Service
internal class MeteredAdminSecurityAuditWriter(
    private val delegate: JpaAdminSecurityAuditWriter,
    private val metrics: AuditPersistenceMetrics,
) : AdminSecurityAuditWriter {
    override fun append(event: AdminSecurityAudit): UUID = metrics.record(
        AuditPersistenceMetrics.Ledger.ADMIN_SECURITY,
        AuditPersistenceMetrics.Operation.APPEND,
    ) {
        delegate.append(event)
    }
}
