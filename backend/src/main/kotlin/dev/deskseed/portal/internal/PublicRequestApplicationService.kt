package dev.deskseed.portal.internal

import dev.deskseed.customer.CustomerDirectory
import dev.deskseed.attachments.AttachmentContent
import dev.deskseed.attachments.AttachmentDownloadCommand
import dev.deskseed.attachments.AttachmentDownloadService
import dev.deskseed.attachments.AttachmentUploadCommand
import dev.deskseed.attachments.AttachmentUploadResult
import dev.deskseed.attachments.AttachmentUploadService
import dev.deskseed.attachments.AttachmentVisibility
import dev.deskseed.audit.AccessAuditContext
import dev.deskseed.foundation.ActorRef
import dev.deskseed.foundation.ActorType
import dev.deskseed.foundation.CommandContext
import dev.deskseed.portal.RequestNotFoundException
import dev.deskseed.outboundmail.MailRecipient
import dev.deskseed.outboundmail.OutboundMailIntent
import dev.deskseed.outboundmail.OutboundMailPort
import dev.deskseed.outboundmail.RequestReceivedMail
import dev.deskseed.settings.CustomerAccessPolicy
import dev.deskseed.ticketing.CustomerRequestStatus
import dev.deskseed.ticketing.CustomerFollowUpResult
import dev.deskseed.ticketing.CustomerTicketNotFoundException
import dev.deskseed.ticketing.CustomerTicketPortal
import dev.deskseed.ticketing.AnonymousCustomerFollowUpCommand
import dev.deskseed.ticketing.PublicTicketView
import dev.deskseed.ticketing.SubmitPublicRequestCommand
import dev.deskseed.ticketing.TicketingFacade
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.io.InputStream
import java.util.Locale
import java.util.UUID

@Service
internal class PublicRequestApplicationService(
    private val customerAccessPolicy: CustomerAccessPolicy,
    private val customerDirectory: CustomerDirectory,
    private val ticketingFacade: TicketingFacade,
    private val ticketPortal: CustomerTicketPortal,
    private val accessTokenStore: RequestAccessTokenStore,
    private val outboundMailPort: OutboundMailPort,
    private val rateLimiter: PublicRequestRateLimiter,
    private val attachmentUploadService: AttachmentUploadService,
    private val attachmentDownloadService: AttachmentDownloadService,
) {
    @Transactional
    fun submit(command: SubmitAnonymousRequest): AnonymousRequestSubmitted {
        rateLimiter.consume(
            destination = command.authenticatedEmail ?: command.email,
            clientAddress = command.effectiveClientAddress,
        )
        val customer = if (command.authenticatedCustomerId == null) {
            customerAccessPolicy.requireAnonymousSubmissionAllowed()
            customerDirectory.createUnverified(name = command.name, email = command.email)
        } else {
            customerDirectory.findById(command.authenticatedCustomerId)
                ?.takeIf {
                    it.verifiedAt != null && it.email.trim().lowercase(Locale.ROOT) ==
                        command.authenticatedEmail?.trim()?.lowercase(Locale.ROOT)
                }
                ?: throw IllegalArgumentException("Authenticated customer is unavailable")
        }
        val ticket = ticketingFacade.submitPublicRequest(
            SubmitPublicRequestCommand(
                requesterId = customer.id,
                subject = command.subject,
                message = command.message,
                actor = ActorRef(
                    actorType = ActorType.CUSTOMER,
                    actorId = customer.id,
                ),
                context = command.context,
            ),
        )
        val rawAccessToken = accessTokenStore.issue(ticket.ticketId)
        enqueueRequestReceivedMail(
            ticketId = ticket.ticketId,
            ticketNumber = ticket.ticketNumber,
            commentId = null,
            customerId = customer.id,
            customerEmail = customer.email,
            rawAccessToken = rawAccessToken,
            idempotencyKey = "request-received:${ticket.ticketId}",
            context = command.context,
        )

        return AnonymousRequestSubmitted(
            ticketNumber = ticket.ticketNumber,
            status = ticket.status,
            accessToken = rawAccessToken,
            createdAt = ticket.createdAt,
        )
    }

    @Transactional(readOnly = true)
    fun view(ticketNumber: Long, rawAccessToken: String): PublicTicketView {
        val ticketId = accessTokenStore.resolveTicketId(rawAccessToken)
            ?: throw RequestNotFoundException()
        val ticket = ticketingFacade.findPublicTicket(ticketId, ticketNumber)
            ?: throw RequestNotFoundException()
        return ticket
    }

    @Transactional
    fun addComment(
        ticketNumber: Long,
        rawAccessToken: String,
        body: String,
        attachmentIds: List<UUID>,
        clientCommandId: String,
        context: CommandContext,
    ): CustomerFollowUpResult {
        val ticketId = accessTokenStore.lockActiveTicketId(rawAccessToken)
            ?: throw RequestNotFoundException()
        val result = try {
            ticketPortal.addAnonymousFollowUp(
                AnonymousCustomerFollowUpCommand(
                    ticketId = ticketId,
                    ticketNumber = ticketNumber,
                    body = body,
                    attachmentIds = uniqueAttachmentIds(attachmentIds),
                    clientCommandId = clientCommandId,
                    context = context,
                ),
            )
        } catch (_: CustomerTicketNotFoundException) {
            throw RequestNotFoundException()
        }
        if (!result.replayed) {
            val customer = customerDirectory.findById(result.requesterId) ?: throw RequestNotFoundException()
            val freshAccessToken = accessTokenStore.issue(result.ticketId)
            enqueueRequestReceivedMail(
                ticketId = result.ticketId,
                ticketNumber = ticketNumber,
                commentId = result.comment.id,
                customerId = result.requesterId,
                customerEmail = customer.email,
                rawAccessToken = freshAccessToken,
                idempotencyKey = "customer-follow-up-received:${result.comment.id}",
                context = context.copy(commandId = clientCommandId),
            )
        }
        return result
    }

    /** Resolves the capability to its exact ticket before the private attachment boundary is entered. */
    fun authorizeAttachmentTicket(ticketNumber: Long, rawAccessToken: String): PublicAttachmentAuthorizedTicket {
        val ticketId = accessTokenStore.resolveTicketId(rawAccessToken) ?: throw RequestNotFoundException()
        val requesterId = ticketPortal.findRequesterId(ticketId, ticketNumber) ?: throw RequestNotFoundException()
        return PublicAttachmentAuthorizedTicket(ticketId, ticketNumber, requesterId)
    }

    fun uploadAttachment(
        ticket: PublicAttachmentAuthorizedTicket,
        fileName: String,
        declaredContentType: String?,
        content: InputStream,
        context: CommandContext,
    ): AttachmentUploadResult = attachmentUploadService.upload(
        AttachmentUploadCommand(
            actor = ActorRef(ActorType.CUSTOMER, ticket.requesterId),
            actorDisplayName = "고객",
            source = dev.deskseed.foundation.RequestSource.CUSTOMER_PORTAL,
            context = context,
            boundTicketId = ticket.ticketId,
            allowedVisibility = AttachmentVisibility.PUBLIC,
            fileName = fileName,
            declaredContentType = declaredContentType,
            content = content,
        ),
    )

    /**
     * The first request comment has no ticket id yet. This preparation transaction intentionally commits only the
     * customer/rate-limit decision, then the controller scans files outside a ticket transaction before finalizing.
     */
    @Transactional
    fun prepareInitialSubmission(command: SubmitAnonymousRequest): PreparedInitialSubmission {
        rateLimiter.consume(
            destination = command.authenticatedEmail ?: command.email,
            clientAddress = command.effectiveClientAddress,
        )
        val customer = if (command.authenticatedCustomerId == null) {
            customerAccessPolicy.requireAnonymousSubmissionAllowed()
            customerDirectory.createUnverified(name = command.name, email = command.email)
        } else {
            customerDirectory.findById(command.authenticatedCustomerId)
                ?.takeIf {
                    it.verifiedAt != null && it.email.trim().lowercase(Locale.ROOT) ==
                        command.authenticatedEmail?.trim()?.lowercase(Locale.ROOT)
                }
                ?: throw IllegalArgumentException("Authenticated customer is unavailable")
        }
        return PreparedInitialSubmission(customer.id, customer.name, customer.email)
    }

    fun uploadInitialAttachment(
        prepared: PreparedInitialSubmission,
        fileName: String,
        declaredContentType: String?,
        content: InputStream,
        context: CommandContext,
    ): AttachmentUploadResult = attachmentUploadService.upload(
        AttachmentUploadCommand(
            actor = ActorRef(ActorType.CUSTOMER, prepared.customerId),
            actorDisplayName = prepared.customerName,
            source = dev.deskseed.foundation.RequestSource.CUSTOMER_PORTAL,
            context = context,
            boundTicketId = null,
            allowedVisibility = AttachmentVisibility.PUBLIC,
            initialPublicSubmission = true,
            fileName = fileName,
            declaredContentType = declaredContentType,
            content = content,
        ),
    )

    @Transactional
    fun finishInitialSubmission(
        prepared: PreparedInitialSubmission,
        subject: String,
        message: String,
        attachmentIds: Set<UUID>,
        context: CommandContext,
    ): AnonymousRequestSubmitted {
        require(attachmentIds.size <= 5) { "Initial request can link at most five attachments" }
        val ticket = ticketingFacade.submitPublicRequest(
            SubmitPublicRequestCommand(
                requesterId = prepared.customerId,
                subject = subject,
                message = message,
                attachmentIds = attachmentIds,
                actor = ActorRef(ActorType.CUSTOMER, prepared.customerId),
                context = context,
            ),
        )
        val rawAccessToken = accessTokenStore.issue(ticket.ticketId)
        enqueueRequestReceivedMail(
            ticketId = ticket.ticketId,
            ticketNumber = ticket.ticketNumber,
            commentId = null,
            customerId = prepared.customerId,
            customerEmail = prepared.customerEmail,
            rawAccessToken = rawAccessToken,
            idempotencyKey = "request-received:${ticket.ticketId}",
            context = context,
        )
        return AnonymousRequestSubmitted(ticket.ticketNumber, ticket.status, rawAccessToken, ticket.createdAt)
    }

    fun downloadAttachment(
        ticket: PublicAttachmentAuthorizedTicket,
        attachmentId: UUID,
        accessContext: AccessAuditContext,
        occurredAt: Instant,
    ): AttachmentContent = attachmentDownloadService.openForDownload(
        AttachmentDownloadCommand(
            attachmentId = attachmentId,
            ticketId = ticket.ticketId,
            ticketNumber = ticket.ticketNumber,
            allowedVisibilities = setOf(AttachmentVisibility.PUBLIC),
            accessContext = accessContext,
            interactionId = null,
            occurredAt = occurredAt,
        ),
    )

    private fun enqueueRequestReceivedMail(
        ticketId: UUID,
        ticketNumber: Long,
        commentId: UUID?,
        customerId: UUID,
        customerEmail: String,
        rawAccessToken: String,
        idempotencyKey: String,
        context: CommandContext,
    ) {
        outboundMailPort.enqueue(
            OutboundMailIntent(
                idempotencyKey = idempotencyKey,
                recipient = MailRecipient(customerEmail),
                content = RequestReceivedMail(ticketNumber, rawAccessToken),
                ticketId = ticketId,
                commentId = commentId,
                customerId = customerId,
                actor = ActorRef(ActorType.CUSTOMER, customerId),
                context = context,
            ),
        )
    }

    private fun uniqueAttachmentIds(ids: List<UUID>): Set<UUID> {
        require(ids.size <= 5 && ids.size == ids.toSet().size) { "attachmentIds must be unique and limited to five" }
        return ids.toSet()
    }
}

internal data class SubmitAnonymousRequest(
    val name: String,
    val email: String,
    val subject: String,
    val message: String,
    val authenticatedCustomerId: UUID? = null,
    val authenticatedEmail: String? = null,
    /** HTTP callers resolve a trusted effective address; loopback keeps direct application tests deterministic. */
    val effectiveClientAddress: String = "127.0.0.1",
    val context: CommandContext,
)

internal data class AnonymousRequestSubmitted(
    val ticketNumber: Long,
    val status: CustomerRequestStatus,
    val accessToken: String,
    val createdAt: Instant,
)

internal data class PublicAttachmentAuthorizedTicket(
    val ticketId: UUID,
    val ticketNumber: Long,
    val requesterId: UUID,
)

internal data class PreparedInitialSubmission(
    val customerId: UUID,
    val customerName: String,
    val customerEmail: String,
)
