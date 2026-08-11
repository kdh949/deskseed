package dev.deskseed.ticketing.internal

import dev.deskseed.foundation.ActorType
import dev.deskseed.foundation.RequestSource
import dev.deskseed.ticketing.CustomerRequestStatus
import dev.deskseed.ticketing.PublicTicketView
import dev.deskseed.ticketing.SubmitPublicRequestCommand
import dev.deskseed.ticketing.SubmittedTicket
import dev.deskseed.ticketing.TicketSubmitted
import dev.deskseed.ticketing.TicketingFacade
import dev.deskseed.ticketing.internal.domain.Ticket
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
internal class JpaTicketingFacade(
    private val ticketRepository: TicketRepository,
    private val commentRepository: TicketCommentRepository,
    private val auditRepository: TicketAuditRepository,
    private val auditEventRepository: TicketAuditEventRepository,
    private val publicTicketQueryRepository: PublicTicketQueryRepository,
    private val ticketNumberGenerator: TicketNumberGenerator,
    private val eventPublisher: ApplicationEventPublisher,
    private val clock: Clock,
) : TicketingFacade {
    @Transactional
    override fun submitPublicRequest(command: SubmitPublicRequestCommand): SubmittedTicket {
        require(command.actor.actorType == ActorType.CUSTOMER) {
            "Public requests must be attributed to a customer actor"
        }
        require(command.actor.actorId == command.requesterId) {
            "Public request actor must match the requester"
        }
        require(command.context.source == RequestSource.CUSTOMER_PORTAL) {
            "Public requests must originate from the customer portal"
        }

        val now = Instant.now(clock)
        val ticket = Ticket.submitFromWeb(
            ticketNumber = ticketNumberGenerator.next(),
            requesterId = command.requesterId,
            subject = command.subject,
            message = command.message,
            now = now,
        )

        ticketRepository.saveAndFlush(
            TicketEntity(
                id = ticket.id,
                ticketNumber = ticket.ticketNumber,
                requesterId = ticket.requesterId,
                kind = ticket.kind,
                subject = ticket.subject,
                status = ticket.status,
                priority = ticket.priority,
                channel = ticket.channel,
                createdAt = ticket.createdAt,
                updatedAt = ticket.updatedAt,
            ),
        )

        commentRepository.save(
            TicketCommentEntity(
                id = ticket.firstComment.id,
                ticketId = ticket.firstComment.ticketId,
                authorType = ticket.firstComment.authorType,
                authorId = ticket.firstComment.authorId,
                visibility = ticket.firstComment.visibility,
                body = ticket.firstComment.body,
                createdAt = ticket.firstComment.createdAt,
            ),
        )

        val auditId = UUID.randomUUID()
        auditRepository.saveAndFlush(
            TicketAuditEntity(
                id = auditId,
                ticketId = ticket.id,
                ticketVersion = 0,
                expectedVersion = 0,
                actorType = command.actor.actorType.name,
                actorId = command.actor.actorId,
                source = command.context.source.name,
                requestId = command.context.requestId,
                correlationId = command.context.correlationId,
                commandId = command.context.commandId,
                createdAt = now,
            ),
        )

        auditEventRepository.saveAllAndFlush(
            listOf(
                TicketAuditEventEntity(
                    id = UUID.randomUUID(),
                    auditId = auditId,
                    eventOrder = 1,
                    eventType = "TICKET_CREATED",
                    metadataJson = "{\"channel\":\"WEB\",\"kind\":\"CUSTOMER_REQUEST\"}",
                    occurredAt = now,
                ),
                TicketAuditEventEntity(
                    id = UUID.randomUUID(),
                    auditId = auditId,
                    eventOrder = 2,
                    eventType = "COMMENT_CREATED",
                    fieldName = "comments",
                    newValueJson = jsonString(ticket.firstComment.id.toString()),
                    metadataJson = "{\"visibility\":\"PUBLIC\",\"authorType\":\"CUSTOMER\"}",
                    occurredAt = now,
                ),
            ),
        )

        eventPublisher.publishEvent(
            TicketSubmitted(
                ticketId = ticket.id,
                ticketNumber = ticket.ticketNumber,
                requesterId = ticket.requesterId,
                occurredAt = now,
            ),
        )

        return SubmittedTicket(
            ticketId = ticket.id,
            ticketNumber = ticket.ticketNumber,
            status = CustomerRequestStatus.NEW,
            createdAt = ticket.createdAt,
        )
    }

    @Transactional(readOnly = true)
    override fun findPublicTicket(ticketId: UUID, ticketNumber: Long): PublicTicketView? =
        publicTicketQueryRepository.find(ticketId, ticketNumber)

    private fun jsonString(value: String): String =
        "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
}
