package dev.deskseed.ticketing.internal

import dev.deskseed.ticketing.CommentVisibility
import dev.deskseed.ticketing.PublicCommentView
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
    private val ticketNumberGenerator: TicketNumberGenerator,
    private val eventPublisher: ApplicationEventPublisher,
    private val clock: Clock,
) : TicketingFacade {
    @Transactional
    override fun submitPublicRequest(command: SubmitPublicRequestCommand): SubmittedTicket {
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
                actorType = "CUSTOMER",
                actorId = command.requesterId,
                source = "WEB_FORM",
                createdAt = now,
            ),
        )

        auditEventRepository.saveAll(
            listOf(
                TicketAuditEventEntity(
                    id = UUID.randomUUID(),
                    auditId = auditId,
                    eventOrder = 1,
                    eventType = "TICKET_CREATED",
                    metadataJson = "{\"channel\":\"WEB\",\"kind\":\"CUSTOMER_REQUEST\"}",
                ),
                TicketAuditEventEntity(
                    id = UUID.randomUUID(),
                    auditId = auditId,
                    eventOrder = 2,
                    eventType = "COMMENT_CREATED",
                    fieldName = "comments",
                    newValueJson = jsonString(ticket.firstComment.id.toString()),
                    metadataJson = "{\"visibility\":\"PUBLIC\",\"authorType\":\"CUSTOMER\"}",
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
            createdAt = ticket.createdAt,
        )
    }

    @Transactional(readOnly = true)
    override fun findPublicTicket(ticketId: UUID): PublicTicketView? {
        val ticket = ticketRepository.findById(ticketId).orElse(null) ?: return null
        val comments = commentRepository
            .findAllByTicketIdAndVisibilityOrderByCreatedAtAscIdAsc(
                ticketId = ticket.id,
                visibility = CommentVisibility.PUBLIC,
            )
            .map {
                PublicCommentView(
                    id = it.id,
                    authorType = it.authorType,
                    body = it.body,
                    createdAt = it.createdAt,
                )
            }

        return PublicTicketView(
            ticketId = ticket.id,
            ticketNumber = ticket.ticketNumber,
            subject = ticket.subject,
            status = ticket.status,
            createdAt = ticket.createdAt,
            updatedAt = ticket.updatedAt,
            comments = comments,
        )
    }

    private fun jsonString(value: String): String =
        "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
}
