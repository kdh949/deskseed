package dev.deskseed.portal.internal

import dev.deskseed.audit.AdminSecurityAudit
import dev.deskseed.audit.AdminSecurityAuditWriter
import dev.deskseed.audit.AdminSecurityOutcome
import dev.deskseed.attachments.AttachmentContent
import dev.deskseed.attachments.AttachmentDownloadCommand
import dev.deskseed.attachments.AttachmentDownloadService
import dev.deskseed.attachments.AttachmentUploadCommand
import dev.deskseed.attachments.AttachmentUploadResult
import dev.deskseed.attachments.AttachmentUploadService
import dev.deskseed.attachments.AttachmentVisibility
import dev.deskseed.audit.AccessAuditAuthType
import dev.deskseed.audit.AccessAuditContext
import dev.deskseed.customerauth.CustomerPrincipal
import dev.deskseed.foundation.ActorType
import dev.deskseed.foundation.ActorRef
import dev.deskseed.foundation.CommandContext
import dev.deskseed.foundation.RequestSource
import dev.deskseed.outboundmail.MailRecipient
import dev.deskseed.outboundmail.OutboundMailIntent
import dev.deskseed.outboundmail.OutboundMailPort
import dev.deskseed.outboundmail.RequestReceivedMail
import dev.deskseed.settings.CustomerAccessMode
import dev.deskseed.settings.CustomerAccessPolicy
import dev.deskseed.ticketing.ClaimCustomerTicketCommand
import dev.deskseed.ticketing.CanonicalCommentContent
import dev.deskseed.ticketing.CustomerFollowUpCommand
import dev.deskseed.ticketing.CustomerFollowUpResult
import dev.deskseed.ticketing.CustomerRequestStatus
import dev.deskseed.ticketing.CustomerTicketNotFoundException
import dev.deskseed.ticketing.CustomerTicketClaimResult
import dev.deskseed.ticketing.CustomerTicketPageQuery
import dev.deskseed.ticketing.CustomerTicketPortal
import dev.deskseed.ticketing.PublicTicketView
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.io.InputStream
import java.util.UUID

internal data class CustomerRequestListResult(
    val items: List<dev.deskseed.ticketing.CustomerTicketSummary>,
    val nextCursor: String?,
)

internal enum class ClaimMethod { REQUEST_ACCESS_TOKEN, SIGNED_GRANT }
internal enum class ClaimOutcome { CLAIMED, NOT_FOUND, DENIED }

internal data class ClaimCustomerRequestInput(
    val requestAccessToken: String?,
    val claimToken: String?,
)

internal data class CustomerAttachmentRequestContext(
    val requestId: String,
    val correlationId: String,
    val ipAddress: String?,
    val userAgent: String?,
)

@Service
internal class CustomerRequestPortalApplicationService(
    private val ticketPortal: CustomerTicketPortal,
    private val accessTokenStore: RequestAccessTokenStore,
    private val outboundMailPort: OutboundMailPort,
    private val claimGrantStore: CustomerClaimGrantStore,
    private val cursorCodec: CustomerRequestCursorCodec,
    private val customerAccessPolicy: CustomerAccessPolicy,
    private val auditWriter: AdminSecurityAuditWriter,
    private val attachmentUploadService: AttachmentUploadService,
    private val attachmentDownloadService: AttachmentDownloadService,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun list(
        principal: CustomerPrincipal,
        status: CustomerRequestStatus?,
        cursor: String?,
        limit: Int,
    ): CustomerRequestListResult {
        val decoded = cursorCodec.decode(cursor)
        val page = ticketPortal.list(
            CustomerTicketPageQuery(
                requesterId = principal.customerId,
                status = status,
                beforeUpdatedAt = decoded?.updatedAt,
                beforeTicketNumber = decoded?.ticketNumber,
                limit = limit,
            ),
        )
        val next = page.items.lastOrNull()?.takeIf { page.hasMore }?.let {
            cursorCodec.encode(CustomerRequestCursor(it.updatedAt, it.ticketNumber))
        }
        return CustomerRequestListResult(page.items, next)
    }

    @Transactional(readOnly = true)
    fun detail(principal: CustomerPrincipal, ticketNumber: Long): PublicTicketView =
        ticketPortal.detail(principal.customerId, ticketNumber) ?: throw CustomerTicketNotFoundException()

    fun uploadAttachment(
        principal: CustomerPrincipal,
        ticketNumber: Long,
        fileName: String,
        declaredContentType: String?,
        content: InputStream,
        context: CommandContext,
    ): AttachmentUploadResult {
        val ticketId = ticketPortal.findOwnedTicketId(principal.customerId, ticketNumber)
            ?: throw CustomerTicketNotFoundException()
        return attachmentUploadService.upload(
            AttachmentUploadCommand(
                actor = ActorRef(ActorType.CUSTOMER, principal.customerId),
                actorDisplayName = principal.displayName,
                source = RequestSource.CUSTOMER_PORTAL,
                context = context,
                boundTicketId = ticketId,
                allowedVisibility = AttachmentVisibility.PUBLIC,
                fileName = fileName,
                declaredContentType = declaredContentType,
                content = content,
            ),
        )
    }

    fun downloadAttachment(
        principal: CustomerPrincipal,
        ticketNumber: Long,
        attachmentId: UUID,
        requestContext: CustomerAttachmentRequestContext,
    ): AttachmentContent {
        val ticketId = ticketPortal.findOwnedTicketId(principal.customerId, ticketNumber)
            ?: throw CustomerTicketNotFoundException()
        val fingerprint = principal.sessionFingerprint
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Authenticated customer session fingerprint is unavailable")
        return attachmentDownloadService.openForDownload(
            AttachmentDownloadCommand(
                attachmentId = attachmentId,
                ticketId = ticketId,
                ticketNumber = ticketNumber,
                allowedVisibilities = setOf(AttachmentVisibility.PUBLIC),
                accessContext = AccessAuditContext(
                    actorType = ActorType.CUSTOMER,
                    actorId = principal.customerId,
                    actorDisplaySnapshot = principal.displayName,
                    source = RequestSource.CUSTOMER_PORTAL,
                    sessionFingerprint = fingerprint,
                    authType = AccessAuditAuthType.CUSTOMER_SESSION,
                    requestId = requestContext.requestId,
                    correlationId = requestContext.correlationId,
                    ipAddress = requestContext.ipAddress,
                    userAgent = requestContext.userAgent,
                ),
                interactionId = null,
                occurredAt = Instant.now(clock),
            ),
        )
    }

    @Transactional
    fun addFollowUp(
        principal: CustomerPrincipal,
        ticketNumber: Long,
        content: CanonicalCommentContent,
        attachmentIds: List<java.util.UUID>,
        clientCommandId: String,
        context: CommandContext,
    ): CustomerFollowUpResult {
        val result = ticketPortal.addFollowUp(
            CustomerFollowUpCommand(
                ticketNumber = ticketNumber,
                requesterId = principal.customerId,
                requesterEmail = principal.email,
                content = content,
                attachmentIds = uniqueAttachmentIds(attachmentIds),
                clientCommandId = clientCommandId,
                context = context,
            ),
        )
        if (!result.replayed) {
            val rawAccessToken = accessTokenStore.issue(result.ticketId)
            outboundMailPort.enqueue(
                OutboundMailIntent(
                    idempotencyKey = "customer-follow-up-received:${result.comment.id}",
                    recipient = MailRecipient(principal.email),
                    content = RequestReceivedMail(ticketNumber, rawAccessToken),
                    ticketId = result.ticketId,
                    commentId = result.comment.id,
                    customerId = principal.customerId,
                    actor = ActorRef(ActorType.CUSTOMER, principal.customerId),
                    context = context.copy(commandId = clientCommandId),
                ),
            )
        }
        return result
    }

    @Transactional(readOnly = true)
    fun accessMode(): CustomerAccessMode = customerAccessPolicy.currentMode()

    private fun uniqueAttachmentIds(ids: List<java.util.UUID>): Set<java.util.UUID> {
        require(ids.size <= 5 && ids.size == ids.toSet().size) { "attachmentIds must be unique and limited to five" }
        return ids.toSet()
    }

    @Transactional
    fun issueClaimGrant(ticketNumber: Long, rawAccessToken: String): IssuedClaimGrant {
        val ticketId = accessTokenStore.lockTicketIdForClaim(rawAccessToken)
            ?: throw CustomerTicketNotFoundException()
        val claimable = ticketPortal.findClaimable(ticketId, ticketNumber)
            ?: throw CustomerTicketNotFoundException()
        return claimGrantStore.issue(claimable.ticketId, ticketNumber, claimable.requesterEmail)
    }

    @Transactional
    fun claim(
        principal: CustomerPrincipal,
        ticketNumber: Long,
        input: ClaimCustomerRequestInput,
        context: CommandContext,
    ): ClaimOutcome {
        val hasAccess = !input.requestAccessToken.isNullOrBlank()
        val hasGrant = !input.claimToken.isNullOrBlank()
        require(hasAccess.xor(hasGrant)) { "Exactly one customer claim proof is required" }
        val method = if (hasAccess) ClaimMethod.REQUEST_ACCESS_TOKEN else ClaimMethod.SIGNED_GRANT
        val grant = if (hasGrant) {
            claimGrantStore.lockAndValidate(requireNotNull(input.claimToken), ticketNumber, principal.email)
        } else {
            null
        }
        if (grant == LockedClaimGrant.EmailMismatch) {
            appendClaimAudit(principal, ticketNumber, method, AdminSecurityOutcome.DENIED, context)
            return ClaimOutcome.DENIED
        }
        val ticketId = if (hasAccess) {
            accessTokenStore.lockTicketIdForClaim(requireNotNull(input.requestAccessToken))
        } else {
            (grant as? LockedClaimGrant.Valid)?.ticketId
        }
        if (ticketId == null) {
            appendClaimAudit(principal, ticketNumber, method, AdminSecurityOutcome.DENIED, context)
            return ClaimOutcome.NOT_FOUND
        }
        return when (
            ticketPortal.claim(
                ClaimCustomerTicketCommand(
                    ticketId = ticketId,
                    ticketNumber = ticketNumber,
                    accountCustomerId = principal.customerId,
                    accountEmail = principal.email,
                    context = context,
                ),
            )
        ) {
            is CustomerTicketClaimResult.Claimed -> {
                accessTokenStore.revokeAll(ticketId)
                (grant as? LockedClaimGrant.Valid)?.grantId?.let(claimGrantStore::markConsumed)
                appendClaimAudit(principal, ticketNumber, method, AdminSecurityOutcome.SUCCEEDED, context)
                ClaimOutcome.CLAIMED
            }
            CustomerTicketClaimResult.Denied -> {
                appendClaimAudit(principal, ticketNumber, method, AdminSecurityOutcome.DENIED, context)
                ClaimOutcome.DENIED
            }
            CustomerTicketClaimResult.NotFound -> {
                appendClaimAudit(principal, ticketNumber, method, AdminSecurityOutcome.DENIED, context)
                ClaimOutcome.NOT_FOUND
            }
        }
    }

    private fun appendClaimAudit(
        principal: CustomerPrincipal,
        ticketNumber: Long,
        method: ClaimMethod,
        outcome: AdminSecurityOutcome,
        context: CommandContext,
    ) {
        auditWriter.append(
            AdminSecurityAudit(
                eventType = if (outcome == AdminSecurityOutcome.SUCCEEDED) {
                    "CUSTOMER_REQUEST_CLAIMED"
                } else {
                    "CUSTOMER_REQUEST_CLAIM_DENIED"
                },
                actorType = ActorType.CUSTOMER,
                actorId = principal.customerId,
                actorDisplaySnapshot = principal.displayName,
                source = context.source,
                targetType = "TICKET_NUMBER",
                targetId = null,
                outcome = outcome,
                requestId = context.requestId,
                correlationId = context.correlationId,
                metadata = mapOf("ticketNumber" to ticketNumber.toString(), "claimMethod" to method.name),
                occurredAt = Instant.now(clock),
            ),
        )
    }
}
