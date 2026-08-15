package dev.deskseed.ticketing.internal

import dev.deskseed.customer.CustomerDirectory
import dev.deskseed.attachments.AttachmentVisibility
import dev.deskseed.attachments.TicketAttachmentLinkCommand
import dev.deskseed.attachments.TicketAttachmentLinker
import dev.deskseed.attachments.TicketAttachmentReadProjection
import dev.deskseed.foundation.ActorRef
import dev.deskseed.foundation.ActorType
import dev.deskseed.foundation.RequestSource
import dev.deskseed.ticketing.AnonymousCustomerFollowUpCommand
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
    private val attachmentLinker: TicketAttachmentLinker,
    private val attachmentReadProjection: TicketAttachmentReadProjection,
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

    @Transactional(readOnly = true)
    override fun findRequesterId(ticketId: UUID, ticketNumber: Long): UUID? = jdbcTemplate.query(
        """
        select requester_id
        from tickets
        where id = ? and ticket_number = ? and kind = 'CUSTOMER_REQUEST'
        """.trimIndent(),
        { result, _ -> result.getObject("requester_id", UUID::class.java) },
        ticketId,
        ticketNumber,
    ).singleOrNull()

    @Transactional
    override fun addFollowUp(command: CustomerFollowUpCommand): CustomerFollowUpResult {
        val body = validateFollowUp(command.context, command.clientCommandId, command.body)
        val attachmentIds = validateAttachmentIds(command.attachmentIds)
        val ticket = lockCustomerRequestTicket(command.ticketNumber)
            ?.takeIf { it.requesterId == command.requesterId }
            ?: throw CustomerTicketNotFoundException()
        lockCommand(command.requesterId, command.clientCommandId)
        findReplay(command.requesterId, command.clientCommandId)?.let { replay ->
            if (replay.ticketNumber != command.ticketNumber || replay.requestDescriptor != followUpDescriptor(command.ticketNumber, body, attachmentIds)) {
                throw CustomerCommandIdReusedException()
            }
            return CustomerFollowUpResult(replay.comment, replay.auditId, replay.ticketId, command.requesterId, replayed = true)
        }

        if (ticket.status in setOf(TicketStatus.SOLVED, TicketStatus.CLOSED)) {
            throw CustomerFollowUpConflictException()
        }
        val customer = customerDirectory.findById(command.requesterId)
            ?.takeIf { it.verifiedAt != null && normalize(it.email) == normalize(command.requesterEmail) }
            ?: throw CustomerTicketNotFoundException()
        return persistFollowUp(
            ticket = ticket,
            requesterId = customer.id,
            authorDisplayName = customer.name,
            body = body,
            attachmentIds = attachmentIds,
            clientCommandId = command.clientCommandId,
            context = command.context,
        )
    }

    @Transactional
    override fun addAnonymousFollowUp(command: AnonymousCustomerFollowUpCommand): CustomerFollowUpResult {
        val body = validateFollowUp(command.context, command.clientCommandId, command.body)
        val attachmentIds = validateAttachmentIds(command.attachmentIds)
        val ticket = lockCustomerRequestTicket(command.ticketNumber)
            ?.takeIf { it.id == command.ticketId }
            ?: throw CustomerTicketNotFoundException()
        val requesterId = ticket.requesterId ?: throw CustomerTicketNotFoundException()
        lockCommand(requesterId, command.clientCommandId)
        findReplay(requesterId, command.clientCommandId)?.let { replay ->
            if (replay.ticketNumber != command.ticketNumber || replay.requestDescriptor != followUpDescriptor(command.ticketNumber, body, attachmentIds)) {
                throw CustomerCommandIdReusedException()
            }
            return CustomerFollowUpResult(replay.comment, replay.auditId, replay.ticketId, requesterId, replayed = true)
        }
        if (ticket.status in setOf(TicketStatus.SOLVED, TicketStatus.CLOSED)) {
            throw CustomerFollowUpConflictException()
        }
        val customer = customerDirectory.findById(requesterId) ?: throw CustomerTicketNotFoundException()
        return persistFollowUp(
            ticket = ticket,
            requesterId = customer.id,
            authorDisplayName = customer.name,
            body = body,
            attachmentIds = attachmentIds,
            clientCommandId = command.clientCommandId,
            context = command.context,
        )
    }

    private fun persistFollowUp(
        ticket: TicketEntity,
        requesterId: UUID,
        authorDisplayName: String,
        body: String,
        attachmentIds: Set<UUID>,
        clientCommandId: String,
        context: dev.deskseed.foundation.CommandContext,
    ): CustomerFollowUpResult {
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
                authorId = requesterId,
                visibility = CommentVisibility.PUBLIC,
                body = body,
                createdAt = now,
            ),
        )
        val linkedAttachments = attachmentLinker.linkCleanAttachments(
            TicketAttachmentLinkCommand(
                ticketId = ticket.id,
                commentId = commentId,
                visibility = AttachmentVisibility.PUBLIC,
                actor = ActorRef(ActorType.CUSTOMER, requesterId),
                attachmentIds = attachmentIds,
                linkedAt = now,
            ),
        )
        val auditId = appendFollowUpAudit(
            ticket = ticket,
            previousStatus = previousStatus,
            commentId = commentId,
            body = body,
            linkedAttachmentIds = linkedAttachments.map { it.attachment.id },
            requesterId = requesterId,
            clientCommandId = clientCommandId,
            context = context,
            now = now,
        )
        return CustomerFollowUpResult(
            PublicCommentView(commentId, authorDisplayName, body, now, linkedAttachments.map { it.attachment }),
            auditId,
            ticket.id,
            requesterId,
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
        val oldRequesterId = ticket.requesterId ?: return CustomerTicketClaimResult.NotFound
        val oldRequester = customerDirectory.findById(oldRequesterId)
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
        linkedAttachmentIds: List<UUID>,
        requesterId: UUID,
        clientCommandId: String,
        context: dev.deskseed.foundation.CommandContext,
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
                actorId = requesterId,
                source = context.source.name,
                requestId = context.requestId,
                correlationId = context.correlationId,
                commandId = clientCommandId,
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
                        "commandRequestDescriptor" to followUpDescriptor(ticket.ticketNumber, body, linkedAttachmentIds.toSet()),
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
        linkedAttachmentIds.forEachIndexed { index, attachmentId ->
            events += TicketAuditEventEntity(
                id = UUID.randomUUID(),
                auditId = auditId,
                eventOrder = events.size + 1,
                eventType = "ATTACHMENT_LINKED",
                fieldName = "attachments",
                newValueJson = objectMapper.writeValueAsString(mapOf("id" to attachmentId.toString())),
                metadataJson = objectMapper.writeValueAsString(mapOf("visibility" to "PUBLIC")),
                occurredAt = now,
            )
        }
        auditEventRepository.saveAllAndFlush(events)
        return auditId
    }

    private fun validateFollowUp(
        context: dev.deskseed.foundation.CommandContext,
        clientCommandId: String,
        submittedBody: String,
    ): String {
        require(context.source == RequestSource.CUSTOMER_PORTAL)
        require(clientCommandId.matches(Regex("[A-Za-z0-9._:-]{1,100}"))) {
            "Customer command ID is invalid"
        }
        return submittedBody.trim().also { body ->
            require(body.isNotEmpty() && body.length <= 20_000) { "Customer follow-up body is invalid" }
        }
    }

    private fun lockCommand(actorId: UUID, commandId: String) {
        jdbcTemplate.queryForObject(
            "select pg_advisory_xact_lock(hashtextextended(?, 0))",
            { _, _ -> Unit },
            "customer-follow-up:$actorId:$commandId",
        )
    }

    /**
     * Both authenticated and token-capability follow-ups must lock the ticket before the command advisory lock.
     * Reversing either path can deadlock when the same customer submits the same command through both surfaces.
     */
    private fun lockCustomerRequestTicket(ticketNumber: Long): TicketEntity? = ticketRepository.lockByTicketNumber(ticketNumber)
        ?.takeIf { it.kind == TicketKind.CUSTOMER_REQUEST }

    private fun findReplay(actorId: UUID, commandId: String): CustomerReplay? {
        val matches = jdbcTemplate.query(
            """
            select audit.id as audit_id, ticket.id as ticket_id, ticket.ticket_number,
                   comment.id as comment_id, comment.body, comment.created_at,
                   coalesce(customer.name, '고객') as author_display_name,
                   first_event.metadata_json::jsonb ->> 'commandRequestDescriptor' as request_descriptor
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
                    ticketId = result.getObject("ticket_id", UUID::class.java),
                    ticketNumber = result.getLong("ticket_number"),
                    requestDescriptor = result.getString("request_descriptor") ?: "${result.getLong("ticket_number")}:${sha256(result.getString("body"))}",
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
        val replay = matches.singleOrNull() ?: return null
        val attachments = attachmentReadProjection.listForComments(
            listOf(replay.comment.id),
            setOf(AttachmentVisibility.PUBLIC),
        )[replay.comment.id].orEmpty()
        return replay.copy(comment = replay.comment.copy(attachments = attachments))
    }

    private fun normalize(email: String) = email.trim().lowercase(Locale.ROOT)

    private fun sha256(value: String): String = java.util.HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()),
    )

    private fun validateAttachmentIds(ids: Set<UUID>): Set<UUID> {
        require(ids.size <= MAX_ATTACHMENTS) { "A customer comment can link at most five attachments" }
        return ids
    }

    private fun followUpDescriptor(ticketNumber: Long, body: String, attachmentIds: Set<UUID>): String {
        val base = "$ticketNumber:${sha256(body)}"
        return attachmentIds.sortedBy(UUID::toString).takeIf { it.isNotEmpty() }
            ?.joinToString(prefix = "$base:", separator = ",")
            ?: base
    }

    private data class CustomerReplay(
        val auditId: UUID,
        val ticketId: UUID,
        val ticketNumber: Long,
        val requestDescriptor: String,
        val comment: PublicCommentView,
    )

    private companion object {
        const val MAX_ATTACHMENTS = 5
    }
}
