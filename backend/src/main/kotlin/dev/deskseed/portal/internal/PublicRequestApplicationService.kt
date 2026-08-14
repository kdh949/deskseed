package dev.deskseed.portal.internal

import dev.deskseed.customer.CustomerDirectory
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
) {
    @Transactional
    fun submit(command: SubmitAnonymousRequest): AnonymousRequestSubmitted {
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
}

internal data class SubmitAnonymousRequest(
    val name: String,
    val email: String,
    val subject: String,
    val message: String,
    val authenticatedCustomerId: UUID? = null,
    val authenticatedEmail: String? = null,
    val context: CommandContext,
)

internal data class AnonymousRequestSubmitted(
    val ticketNumber: Long,
    val status: CustomerRequestStatus,
    val accessToken: String,
    val createdAt: Instant,
)
