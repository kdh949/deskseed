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
        outboundMailPort.enqueue(
            OutboundMailIntent(
                idempotencyKey = "request-received:${ticket.ticketId}",
                recipient = MailRecipient(customer.email),
                content = RequestReceivedMail(ticket.ticketNumber),
                ticketId = ticket.ticketId,
                customerId = customer.id,
                actor = ActorRef(ActorType.CUSTOMER, customer.id),
                context = command.context,
            ),
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
