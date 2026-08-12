package dev.deskseed.ticketing.internal

import dev.deskseed.customer.CustomerDirectory
import dev.deskseed.foundation.ActorRef
import dev.deskseed.foundation.ActorType
import dev.deskseed.foundation.RequestSource
import dev.deskseed.outboundmail.MailRecipient
import dev.deskseed.outboundmail.OutboundMailIntent
import dev.deskseed.outboundmail.OutboundMailPort
import dev.deskseed.outboundmail.RequestReceivedMail
import dev.deskseed.ticketing.ClaimCustomerTicketCommand
import dev.deskseed.ticketing.ClaimableCustomerTicket
import dev.deskseed.ticketing.CommentAuthorType
import dev.deskseed.ticketing.CommentVisibility
import dev.deskseed.ticketing.CustomerCommandIdReusedException
import dev.deskseed.ticketing.CustomerFollowUpCommand
import dev.deskseed.ticketing.CustomerFollowUpConflictException
import dev.deskseed.ticketing.CustomerFollowUpResult
import dev.deskseed.ticketing.CustomerRequestStatus
import dev.deskseed.ticketing.CustomerTicketClaimDeniedException
import dev.deskseed.ticketing.CustomerTicketClaimResult
import dev.deskseed.ticketing.CustomerTicketNotFoundException
import dev.deskseed.ticketing.CustomerTicketPage
import dev.deskseed.ticketing.CustomerTicketPageQuery
import dev.deskseed.ticketing.CustomerTicketPortal
import dev.deskseed.ticketing.PublicCommentView
import dev.deskseed.ticketing.PublicTicketView
import dev.deskseed.ticketing.TicketKind
import dev.deskseed.ticketing.TicketStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

@Service
internal class JpaCustomerTicketPortal(
    private val ticketRepository: TicketRepository,
    private val commentRepository: TicketCommentRepository,
    private val auditRepository: TicketAuditRepository,
    private val auditEventRepository: TicketAuditEventRepository,
    private val publicTicketQueryRepository: PublicTicketQueryRepository,
    private val customerTicketQueryRepository: CustomerTicketQueryRepository,
    private val customerDirectory: CustomerDirectory,
    private val outboundMailPort: OutboundMailPort,
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) : CustomerTicketPortal {
    @Transactional(readOnly = true)
    override fun list(query: CustomerTicketPageQuery): CustomerTicketPage {
        require(query.limit in 1..100) { "Customer request page size is invalid" }
        require((query.beforeUpdatedAt == null) == (query.beforeTicketNumber == null)) {
            "Customer request cursor is incomplete"
        }
        return customerTicketQueryRepository.list(query)
    }

    @Transactional(readOnly = true)
    override fun detail(requesterId: UUID, ticketNumber: Long): PublicTicketView? {
        val ticketId = jdbcTemplate.query(
            """
            select id from tickets
            where requester_id = ? and ticket_number = ? and kind = 'CUSTOMER_REQUEST'
            """.trimIndent(),
            { result, _ -> result.getObject("id", UUID::class.java) },
            requesterId,
            ticketNumber,
        ).singleOrNull() ?: return null
        return publicTicketQueryRepository.find(ticketId, ticketNumber)
    }

    @Transactional
    override fun addFollowUp(command: CustomerFollowUpCommand): CustomerFollowUpResult {
        require(command.context.source == RequestSource.CUSTOMER_PORTAL)
        require(command.clientCommandId.matches(Regex("[A-Za-z0-9._:-]{1,100}"))) {
            "Customer command ID is invalid"
        }
        val body = command.body.trim()
        require(body.isNotEmpty() && body.length <= 20_000) { "Customer follow-up body is invalid" }
        lockCommand(command.requesterId, command.clientCommandId)
        findReplay(command.requesterId, command.clientCommandId)?.let { replay ->
            if (replay.ticketNumber != command.ticketNumber || replay.bodyDigest != sha256(body)) {
                throw CustomerCommandIdReusedException()
            }
            return CustomerFollowUpResult(replay.comment, replay.auditId)
        }

        val ticket = ticketRepository.lockByTicketNumber(command.ticketNumber)
            ?.takeIf { it.kind == TicketKind.CUSTOMER_REQUEST && it.requesterId == command.requesterId }
            ?: throw CustomerTicketNotFoundException()
        if (ticket.status in setOf(TicketStatus.SOLVED, TicketStatus.CLOSED)) {
            throw CustomerFollowUpConflictException()
        }
        val customer = customerDirectory.findById(command.requesterId)
            ?.takeIf { it.verifiedAt != null && normalize(it.email) == normalize(command.requesterEmail) }
            ?: throw CustomerTicketNotFoundException()
        val now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS)
        val previousStatus = ticket.status
        if (ticket.status == TicketStatus.PENDING) ticket.status = TicketStatus.OPEN
        ticket.updatedAt = now
        ticketRepository.saveAndFlush(ticket)

        val commentId = UUID.randomUUID()
        commentRepository.saveAndFlush(
            TicketCommentEntity(
                id = commentId,
                ticketId = ticket.id,
                authorType = CommentAuthorType.CUSTOMER,
                authorId = customer.id,
                visibility = CommentVisibility.PUBLIC,
                body = body,
                createdAt = now,
            ),
        )
        val auditId = appendFollowUpAudit(ticket, previousStatus, commentId, body, command, now)
        outboundMailPort.enqueue(
            OutboundMailIntent(
                idempotencyKey = "customer-follow-up-received:$commentId",
                recipient = MailRecipient(customer.email),
                content = RequestReceivedMail(ticket.ticketNumber),
                ticketId = ticket.id,
                commentId = commentId,
                customerId = customer.id,
                actor = ActorRef(ActorType.CUSTOMER, customer.id),
                context = command.context.copy(commandId = command.clientCommandId),
            ),
        )
        return CustomerFollowUpResult(
            PublicCommentView(commentId, customer.name, body, now),
            auditId,
        )
    }

    @Transactional(readOnly = true)
    override fun findClaimable(ticketId: UUID, ticketNumber: Long): ClaimableCustomerTicket? = jdbcTemplate.query(
        """
        select ticket.id, ticket.ticket_number, customer.id as requester_id,
               customer.email_normalized, customer.verified_at
        from tickets ticket
        join customers customer on customer.id = ticket.requester_id
        where ticket.id = ? and ticket.ticket_number = ? and ticket.kind = 'CUSTOMER_REQUEST'
        """.trimIndent(),
        { result, _ ->
            if (result.getTimestamp("verified_at") != null) null else ClaimableCustomerTicket(
                ticketId = result.getObject("id", UUID::class.java),
                ticketNumber = result.getLong("ticket_number"),
                requesterId = result.getObject("requester_id", UUID::class.java),
                requesterEmail = result.getString("email_normalized"),
            )
        },
        ticketId,
        ticketNumber,
    ).singleOrNull()

    @Transactional
    override fun claim(command: ClaimCustomerTicketCommand): CustomerTicketClaimResult {
        require(command.context.source == RequestSource.CUSTOMER_PORTAL)
        val ticket = ticketRepository.lockByTicketNumber(command.ticketNumber)
            ?.takeIf { it.id == command.ticketId && it.kind == TicketKind.CUSTOMER_REQUEST }
            ?: return CustomerTicketClaimResult.NotFound
        val oldRequester = customerDirectory.findById(ticket.requesterId)
            ?.takeIf { it.verifiedAt == null }
            ?: return CustomerTicketClaimResult.NotFound
        val newRequester = customerDirectory.findById(command.accountCustomerId)
            ?.takeIf { it.verifiedAt != null }
            ?: return CustomerTicketClaimResult.Denied
        if (normalize(oldRequester.email) != normalize(command.accountEmail) ||
            normalize(newRequester.email) != normalize(command.accountEmail)
        ) {
            return CustomerTicketClaimResult.Denied
        }
        val oldVersion = ticket.version
        val now = Instant.now(clock)
        ticket.requesterId = command.accountCustomerId
        ticket.updatedAt = now
        ticketRepository.saveAndFlush(ticket)
        val auditId = UUID.randomUUID()
        auditRepository.saveAndFlush(
            TicketAuditEntity(
                id = auditId,
                ticketId = ticket.id,
                ticketVersion = ticket.version,
                expectedVersion = oldVersion,
                actorType = ActorType.CUSTOMER.name,
                actorId = command.accountCustomerId,
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
                    eventType = "REQUESTER_CHANGED",
                    fieldName = "requesterId",
                    oldValueJson = objectMapper.writeValueAsString(mapOf("id" to oldRequester.id.toString())),
                    newValueJson = objectMapper.writeValueAsString(mapOf("id" to newRequester.id.toString())),
                    metadataJson = objectMapper.writeValueAsString(mapOf("claimMethod" to "EXPLICIT_PROOF")),
                    occurredAt = now,
                ),
            ),
        )
        return CustomerTicketClaimResult.Claimed(auditId)
    }

    private fun appendFollowUpAudit(
        ticket: TicketEntity,
        previousStatus: TicketStatus,
        commentId: UUID,
        body: String,
        command: CustomerFollowUpCommand,
        now: Instant,
    ): UUID {
        val auditId = UUID.randomUUID()
        auditRepository.saveAndFlush(
            TicketAuditEntity(
                id = auditId,
                ticketId = ticket.id,
                ticketVersion = ticket.version,
                expectedVersion = ticket.version - 1,
                actorType = ActorType.CUSTOMER.name,
                actorId = command.requesterId,
                source = command.context.source.name,
                requestId = command.context.requestId,
                correlationId = command.context.correlationId,
                commandId = command.clientCommandId,
                createdAt = now,
            ),
        )
        val events = mutableListOf(
            TicketAuditEventEntity(
                id = UUID.randomUUID(),
                auditId = auditId,
                eventOrder = 1,
                eventType = "COMMENT_CREATED",
                fieldName = "comments",
                newValueJson = objectMapper.writeValueAsString(mapOf("id" to commentId.toString())),
                metadataJson = objectMapper.writeValueAsString(
                    mapOf(
                        "visibility" to "PUBLIC",
                        "authorType" to "CUSTOMER",
                        "contentLength" to body.length,
                        "contentSha256" to sha256(body),
                        "commandOperation" to "CUSTOMER_FOLLOW_UP",
                        "commandRequestDescriptor" to "${ticket.ticketNumber}:${sha256(body)}",
                    ),
                ),
                occurredAt = now,
            ),
        )
        if (previousStatus != ticket.status) {
            events += TicketAuditEventEntity(
                id = UUID.randomUUID(),
                auditId = auditId,
                eventOrder = 2,
                eventType = "STATUS_CHANGED",
                fieldName = "status",
                oldValueJson = objectMapper.writeValueAsString(previousStatus.name),
                newValueJson = objectMapper.writeValueAsString(ticket.status.name),
                occurredAt = now,
            )
        }
        auditEventRepository.saveAllAndFlush(events)
        return auditId
    }

    private fun lockCommand(actorId: UUID, commandId: String) {
        jdbcTemplate.queryForObject(
            "select pg_advisory_xact_lock(hashtextextended(?, 0))",
            { _, _ -> Unit },
            "customer-follow-up:$actorId:$commandId",
        )
    }

    private fun findReplay(actorId: UUID, commandId: String): CustomerReplay? {
        val matches = jdbcTemplate.query(
            """
            select audit.id as audit_id, ticket.ticket_number,
                   comment.id as comment_id, comment.body, comment.created_at,
                   coalesce(customer.name, '고객') as author_display_name,
                   first_event.metadata_json::jsonb ->> 'contentSha256' as body_digest
            from ticket_audits audit
            join tickets ticket on ticket.id = audit.ticket_id
            join lateral (
                select event.metadata_json, event.new_value_json::jsonb ->> 'id' as comment_id
                from ticket_audit_events event
                where event.audit_id = audit.id and event.event_type = 'COMMENT_CREATED'
                order by event.event_order limit 1
            ) first_event on true
            join ticket_comments comment on comment.id = first_event.comment_id::uuid
            left join customers customer on customer.id = comment.author_id
            where audit.actor_type = 'CUSTOMER' and audit.actor_id = ? and audit.command_id = ?
            order by audit.created_at, audit.id limit 2
            """.trimIndent(),
            { result, _ ->
                CustomerReplay(
                    auditId = result.getObject("audit_id", UUID::class.java),
                    ticketNumber = result.getLong("ticket_number"),
                    bodyDigest = result.getString("body_digest"),
                    comment = PublicCommentView(
                        id = result.getObject("comment_id", UUID::class.java),
                        authorDisplayName = result.getString("author_display_name"),
                        body = result.getString("body"),
                        createdAt = result.getTimestamp("created_at").toInstant(),
                    ),
                )
            },
            actorId,
            commandId,
        )
        if (matches.size > 1) throw CustomerCommandIdReusedException()
        return matches.singleOrNull()
    }

    private fun normalize(email: String) = email.trim().lowercase(Locale.ROOT)

    private fun sha256(value: String): String = java.util.HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()),
    )

    private data class CustomerReplay(
        val auditId: UUID,
        val ticketNumber: Long,
        val bodyDigest: String,
        val comment: PublicCommentView,
    )
}
