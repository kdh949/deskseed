package dev.deskseed.ticketing.internal

import dev.deskseed.attachments.AttachmentVisibility
import dev.deskseed.attachments.TicketAttachmentLinkCommand
import dev.deskseed.attachments.TicketAttachmentLinker
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
    private val attachmentLinker: TicketAttachmentLinker,
    private val ticketNumberGenerator: TicketNumberGenerator,
    private val ticketIntegrationEvents: TicketIntegrationEventPublisher,
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

        // The attachment linker uses JDBC and its link row has a comment FK. Flush the
        // first comment before crossing that persistence boundary, while preserving the
        // encompassing ticket transaction's all-or-nothing behavior.
        commentRepository.saveAndFlush(
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
        require(command.attachmentIds.size <= MAX_ATTACHMENTS) { "A request can link at most five attachments" }
        val linkedAttachments = attachmentLinker.linkCleanAttachments(
            TicketAttachmentLinkCommand(
                ticketId = ticket.id,
                commentId = ticket.firstComment.id,
                visibility = AttachmentVisibility.PUBLIC,
                actor = command.actor,
                attachmentIds = command.attachmentIds,
                linkedAt = now,
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
            buildList {
                add(
                TicketAuditEventEntity(
                    id = UUID.randomUUID(),
                    auditId = auditId,
                    eventOrder = 1,
                    eventType = "TICKET_CREATED",
                    metadataJson = "{\"channel\":\"WEB\",\"kind\":\"CUSTOMER_REQUEST\"}",
                    occurredAt = now,
                ),
                )
                add(
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
                )
                linkedAttachments.forEach { linked ->
                    add(
                        TicketAuditEventEntity(
                            id = UUID.randomUUID(),
                            auditId = auditId,
                            eventOrder = size + 1,
                            eventType = "ATTACHMENT_LINKED",
                            fieldName = "attachments",
                            newValueJson = jsonString(linked.attachment.id.toString()),
                            metadataJson = "{\"visibility\":\"PUBLIC\"}",
                            occurredAt = now,
                        ),
                    )
                }
            },
        )

        ticketIntegrationEvents.ticketCreated(
            ticketId = ticket.id,
            ticketNumber = ticket.ticketNumber,
            kind = ticket.kind,
            priority = ticket.priority,
            channel = ticket.channel,
            status = ticket.status,
            firstCommentId = ticket.firstComment.id,
            firstCommentVisibility = ticket.firstComment.visibility,
            actor = command.actor,
            context = command.context,
            occurredAt = now,
        )

        eventPublisher.publishEvent(
            TicketSubmitted(
                ticketId = ticket.id,
                ticketNumber = ticket.ticketNumber,
                requesterId = ticket.requesterId,
                kind = ticket.kind,
                priority = ticket.priority,
                groupId = ticket.groupId,
                channel = ticket.channel,
                status = ticket.status,
                ticketAuditId = auditId,
                actorType = command.actor.actorType.name,
                actorId = command.actor.actorId,
                source = command.context.source.name,
                requestId = command.context.requestId,
                correlationId = command.context.correlationId,
                startsFirstReplySla = true,
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

    private companion object {
        const val MAX_ATTACHMENTS = 5
    }
}
