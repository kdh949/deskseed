package dev.deskseed.portal.internal

import dev.deskseed.customer.CustomerDirectory
import dev.deskseed.outboundmail.MailRecipient
import dev.deskseed.outboundmail.OutboundMailIntent
import dev.deskseed.outboundmail.OutboundMailPort
import dev.deskseed.outboundmail.PublicAgentReplyMail
import dev.deskseed.ticketing.PublicAgentReplyRecorded
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Portal owns customer-capability issuance. Keeping this listener synchronous means a public
 * reply, its audit, the fresh grant, and its durable mail intent either commit together or roll back.
 */
@Component
internal class PublicAgentReplyMailListener(
    private val customerDirectory: CustomerDirectory,
    private val accessTokenStore: RequestAccessTokenStore,
    private val outboundMailPort: OutboundMailPort,
) {
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    fun on(event: PublicAgentReplyRecorded) {
        val customer = customerDirectory.findById(event.requesterId)
            ?: error("Public reply requester is unavailable")
        val rawAccessToken = accessTokenStore.issue(event.ticketId)
        outboundMailPort.enqueue(
            OutboundMailIntent(
                idempotencyKey = "public-agent-reply:${event.commentId}",
                recipient = MailRecipient(customer.email),
                content = PublicAgentReplyMail(
                    ticketNumber = event.ticketNumber,
                    publicBody = event.publicBody,
                    requestAccessToken = rawAccessToken,
                ),
                ticketId = event.ticketId,
                commentId = event.commentId,
                customerId = customer.id,
                actor = event.actor,
                context = event.context,
            ),
        )
    }
}
