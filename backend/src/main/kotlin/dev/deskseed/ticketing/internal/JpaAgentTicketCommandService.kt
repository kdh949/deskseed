package dev.deskseed.ticketing.internal

import dev.deskseed.foundation.ActorType
import dev.deskseed.foundation.RequestSource
import dev.deskseed.ticketing.AgentCommentDraft
import dev.deskseed.ticketing.AgentTicketCommandService
import dev.deskseed.ticketing.AgentTicketNotFoundException
import dev.deskseed.ticketing.CommentAuthorType
import dev.deskseed.ticketing.CreateAgentTicketCommand
import dev.deskseed.ticketing.TicketAssignmentInvalidException
import dev.deskseed.ticketing.TicketAssignmentPolicy
import dev.deskseed.ticketing.TicketAuditUnavailableException
import dev.deskseed.ticketing.TicketCommandInvalidException
import dev.deskseed.ticketing.TicketCommandResult
import dev.deskseed.ticketing.TicketField
import dev.deskseed.ticketing.TicketFieldConflictException
import dev.deskseed.ticketing.TicketStatus
import dev.deskseed.ticketing.TicketTransitionInvalidException
import dev.deskseed.ticketing.TicketUpdateContentionException
import dev.deskseed.ticketing.TicketWriteAuthorizationPolicy
import dev.deskseed.ticketing.TicketWriteForbiddenException
import dev.deskseed.ticketing.UpdateAgentTicketCommand
import dev.deskseed.ticketing.internal.domain.Ticket
import dev.deskseed.ticketing.internal.domain.TicketStatusTransitions
import jakarta.persistence.OptimisticLockException
import org.springframework.dao.DataAccessException
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
internal class JpaAgentTicketCommandService(
    private val transaction: AgentTicketCommandTransaction,
) : AgentTicketCommandService {
    override fun create(command: CreateAgentTicketCommand): TicketCommandResult = translateStorageFailure {
        transaction.create(command)
    }

    override fun update(command: UpdateAgentTicketCommand): TicketCommandResult {
        var optimisticFailure: RuntimeException? = null
        repeat(MAX_OPTIMISTIC_ATTEMPTS) {
            try {
                return transaction.update(command)
            } catch (failure: RuntimeException) {
                if (failure.isOptimisticFailure()) {
                    optimisticFailure = failure
                } else if (failure is DataAccessException) {
                    throw TicketAuditUnavailableException(failure)
                } else {
                    throw failure
                }
            }
        }
        throw TicketUpdateContentionException(checkNotNull(optimisticFailure))
    }

    private fun <T> translateStorageFailure(block: () -> T): T = try {
        block()
    } catch (failure: DataAccessException) {
        throw TicketAuditUnavailableException(failure)
    }

    private fun Throwable.isOptimisticFailure(): Boolean = generateSequence(this) { it.cause }
        .any { it is ObjectOptimisticLockingFailureException || it is OptimisticLockException }

    private companion object {
        const val MAX_OPTIMISTIC_ATTEMPTS = 5
    }
}

@Component
internal class AgentTicketCommandTransaction(
    private val ticketRepository: TicketRepository,
    private val commentRepository: TicketCommentRepository,
    private val auditRepository: TicketAuditRepository,
    private val auditEventRepository: TicketAuditEventRepository,
    private val ticketNumberGenerator: TicketNumberGenerator,
    private val assignmentPolicy: TicketAssignmentPolicy,
    private val authorizationPolicy: TicketWriteAuthorizationPolicy,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    @Transactional
    fun create(command: CreateAgentTicketCommand): TicketCommandResult {
        validateStaffContext(command.actor.id, command.context.source)
        validateText(command.subject, "subject", 200)
        validateComment(command.firstComment)
        validateAssignment(command.groupId, command.assigneeId)

        val now = Instant.now(clock)
        val ticket = Ticket.createByAgent(
            ticketNumber = ticketNumberGenerator.next(),
            requesterId = command.requesterId,
            subject = command.subject,
            firstCommentBody = command.firstComment.body,
            firstCommentVisibility = command.firstComment.visibility,
            priority = command.priority,
            groupId = command.groupId,
            assigneeId = command.assigneeId,
            actorId = command.actor.id,
            now = now,
        )
        val ticketEntity = ticketRepository.saveAndFlush(
            TicketEntity(
                id = ticket.id,
                ticketNumber = ticket.ticketNumber,
                requesterId = ticket.requesterId,
                kind = ticket.kind,
                subject = ticket.subject,
                status = ticket.status,
                priority = ticket.priority,
                groupId = ticket.groupId,
                assigneeId = ticket.assigneeId,
                channel = ticket.channel,
                createdAt = ticket.createdAt,
                updatedAt = ticket.updatedAt,
            ),
        )
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

        val auditId = appendAudit(
            ticket = ticketEntity,
            expectedVersion = 0,
            resultVersion = ticketEntity.version,
            actorId = command.actor.id,
            context = AuditCommandContext(
                source = command.context.source.name,
                requestId = command.context.requestId,
                correlationId = command.context.correlationId,
                commandId = command.context.commandId,
            ),
            now = now,
            events = listOf(
                NewAuditEvent(
                    type = "TICKET_CREATED",
                    metadata = mapOf(
                        "kind" to ticket.kind.name,
                        "channel" to ticket.channel.name,
                        "priority" to ticket.priority.name,
                        "groupId" to ticket.groupId?.toString(),
                        "assigneeId" to ticket.assigneeId?.toString(),
                    ),
                ),
                commentAuditEvent(ticket.firstComment.id, command.firstComment, now),
            ),
        )
        return TicketCommandResult(ticket.ticketNumber, ticketEntity.version, auditId)
    }

    @Transactional
    fun update(command: UpdateAgentTicketCommand): TicketCommandResult {
        validateUpdateCommand(command)
        val ticket = ticketRepository.findByTicketNumber(command.ticketNumber)
            ?: throw AgentTicketNotFoundException()
        if (!authorizationPolicy.canUpdate(command.actor, ticket.groupId, ticket.assigneeId)) {
            throw TicketWriteForbiddenException()
        }
        if (command.expectedVersion > ticket.version) {
            throw TicketCommandInvalidException("expectedVersion cannot be newer than the ticket")
        }

        val requestedFieldNames = command.changedFields.map(TicketField::externalName)
        if (command.expectedVersion < ticket.version && requestedFieldNames.isNotEmpty()) {
            val conflicts = auditRepository.findConflictingFields(
                ticketId = ticket.id,
                expectedVersion = command.expectedVersion,
                fieldNames = requestedFieldNames,
            ).sorted()
            if (conflicts.isNotEmpty()) {
                throw TicketFieldConflictException(ticket.version, conflicts)
            }
        }

        val now = Instant.now(clock)
        val oldStatus = ticket.status
        val oldPriority = ticket.priority
        val oldGroupId = ticket.groupId
        val oldAssigneeId = ticket.assigneeId
        val newStatus = if (TicketField.STATUS in command.changedFields) checkNotNull(command.status) else oldStatus
        val newPriority = if (TicketField.PRIORITY in command.changedFields) checkNotNull(command.priority) else oldPriority
        val newGroupId = if (TicketField.GROUP_ID in command.changedFields) command.groupId else oldGroupId
        val newAssigneeId = if (TicketField.ASSIGNEE_ID in command.changedFields) command.assigneeId else oldAssigneeId

        validateStatusChange(oldStatus, newStatus, TicketField.STATUS in command.changedFields)
        validateAssignmentChange(command, oldAssigneeId, newGroupId, newAssigneeId)

        val events = mutableListOf<NewAuditEvent>()
        command.comment?.let { draft ->
            val commentId = UUID.randomUUID()
            commentRepository.saveAndFlush(
                TicketCommentEntity(
                    id = commentId,
                    ticketId = ticket.id,
                    authorType = CommentAuthorType.AGENT,
                    authorId = command.actor.id,
                    visibility = draft.visibility,
                    body = draft.body.trim(),
                    createdAt = now,
                ),
            )
            events += commentAuditEvent(commentId, draft, now)
        }
        if (newStatus != oldStatus) {
            events += fieldAuditEvent("STATUS_CHANGED", TicketField.STATUS, oldStatus.name, newStatus.name)
        }
        if (newPriority != oldPriority) {
            events += fieldAuditEvent("PRIORITY_CHANGED", TicketField.PRIORITY, oldPriority.name, newPriority.name)
        }
        if (newGroupId != oldGroupId) {
            events += referenceAuditEvent("GROUP_CHANGED", TicketField.GROUP_ID, oldGroupId, newGroupId)
        }
        if (newAssigneeId != oldAssigneeId) {
            events += referenceAuditEvent("ASSIGNEE_CHANGED", TicketField.ASSIGNEE_ID, oldAssigneeId, newAssigneeId)
        }

        val hasMutation = events.isNotEmpty()
        if (hasMutation) {
            ticket.status = newStatus
            ticket.priority = newPriority
            ticket.groupId = newGroupId
            ticket.assigneeId = newAssigneeId
            ticket.updatedAt = now
            ticket.solvedAt = when {
                oldStatus != TicketStatus.SOLVED && newStatus == TicketStatus.SOLVED -> now
                oldStatus == TicketStatus.SOLVED && newStatus == TicketStatus.OPEN -> null
                else -> ticket.solvedAt
            }
            ticketRepository.saveAndFlush(ticket)
        }

        val auditId = appendAudit(
            ticket = ticket,
            expectedVersion = command.expectedVersion,
            resultVersion = ticket.version,
            actorId = command.actor.id,
            context = AuditCommandContext(
                source = command.context.source.name,
                requestId = command.context.requestId,
                correlationId = command.context.correlationId,
                commandId = command.context.commandId,
            ),
            now = now,
            events = events,
        )
        return TicketCommandResult(ticket.ticketNumber, ticket.version, auditId)
    }

    private fun validateUpdateCommand(command: UpdateAgentTicketCommand) {
        validateStaffContext(command.actor.id, command.context.source)
        if (command.expectedVersion < 0) throw TicketCommandInvalidException("expectedVersion must be non-negative")
        if (command.changedFields.isEmpty() && command.comment == null) {
            throw TicketCommandInvalidException("A field or comment is required")
        }
        if (TicketField.STATUS in command.changedFields && command.status == null) {
            throw TicketCommandInvalidException("status must be provided when changedFields contains status")
        }
        if (TicketField.PRIORITY in command.changedFields && command.priority == null) {
            throw TicketCommandInvalidException("priority must be provided when changedFields contains priority")
        }
        if (TicketField.STATUS !in command.changedFields && command.status != null) {
            throw TicketCommandInvalidException("status must be declared in changedFields")
        }
        if (TicketField.PRIORITY !in command.changedFields && command.priority != null) {
            throw TicketCommandInvalidException("priority must be declared in changedFields")
        }
        command.comment?.let(::validateComment)
    }

    private fun validateStaffContext(actorId: UUID, source: RequestSource) {
        if (actorId == UUID(0, 0) || source != RequestSource.AGENT_UI) {
            throw TicketCommandInvalidException("An authenticated staff actor and AGENT_UI source are required")
        }
    }

    private fun validateText(value: String, field: String, maxLength: Int) {
        if (value.isBlank() || value.length > maxLength) {
            throw TicketCommandInvalidException("$field must contain 1 to $maxLength characters")
        }
    }

    private fun validateComment(comment: AgentCommentDraft) {
        validateText(comment.body, "comment.body", 20_000)
    }

    private fun validateStatusChange(old: TicketStatus, new: TicketStatus, requested: Boolean) {
        if (!requested) return
        if (new == TicketStatus.CLOSED || !TicketStatusTransitions.isAllowed(old, new)) {
            throw TicketTransitionInvalidException("The requested staff status transition is not allowed")
        }
    }

    private fun validateAssignment(groupId: UUID?, assigneeId: UUID?) {
        if (groupId != null && !assignmentPolicy.isActiveGroup(groupId)) {
            throw TicketAssignmentInvalidException("The target group is not active")
        }
        if (assigneeId != null && (groupId == null || !assignmentPolicy.isActiveMember(groupId, assigneeId))) {
            throw TicketAssignmentInvalidException("The assignee must be an active member of the target group")
        }
    }

    private fun validateAssignmentChange(
        command: UpdateAgentTicketCommand,
        oldAssigneeId: UUID?,
        newGroupId: UUID?,
        newAssigneeId: UUID?,
    ) {
        val groupRequested = TicketField.GROUP_ID in command.changedFields
        val assigneeRequested = TicketField.ASSIGNEE_ID in command.changedFields
        if (groupRequested && newGroupId != null && !assignmentPolicy.isActiveGroup(newGroupId)) {
            throw TicketAssignmentInvalidException("The target group is not active")
        }
        if (groupRequested && !assigneeRequested && oldAssigneeId != null &&
            (newGroupId == null || !assignmentPolicy.isActiveMember(newGroupId, oldAssigneeId))
        ) {
            throw TicketAssignmentInvalidException("Changing group requires an explicit compatible assignee or clear")
        }
        if ((groupRequested || assigneeRequested) && newAssigneeId != null &&
            (newGroupId == null || !assignmentPolicy.isActiveMember(newGroupId, newAssigneeId))
        ) {
            throw TicketAssignmentInvalidException("The assignee must be an active member of the target group")
        }
    }

    private fun appendAudit(
        ticket: TicketEntity,
        expectedVersion: Long,
        resultVersion: Long,
        actorId: UUID,
        context: AuditCommandContext,
        now: Instant,
        events: List<NewAuditEvent>,
    ): UUID {
        val auditId = UUID.randomUUID()
        auditRepository.saveAndFlush(
            TicketAuditEntity(
                id = auditId,
                ticketId = ticket.id,
                ticketVersion = resultVersion,
                expectedVersion = expectedVersion,
                actorType = ActorType.STAFF.name,
                actorId = actorId,
                source = context.source,
                requestId = context.requestId,
                correlationId = context.correlationId,
                commandId = context.commandId,
                createdAt = now,
            ),
        )
        if (events.isNotEmpty()) {
            auditEventRepository.saveAllAndFlush(
                events.mapIndexed { index, event ->
                    TicketAuditEventEntity(
                        id = UUID.randomUUID(),
                        auditId = auditId,
                        eventOrder = index + 1,
                        eventType = event.type,
                        fieldName = event.field?.externalName,
                        oldValueJson = event.before,
                        newValueJson = event.after,
                        metadataJson = objectMapper.writeValueAsString(event.metadata),
                        occurredAt = event.occurredAt ?: now,
                    )
                },
            )
        }
        return auditId
    }

    private fun commentAuditEvent(commentId: UUID, draft: AgentCommentDraft, now: Instant): NewAuditEvent =
        NewAuditEvent(
            type = "COMMENT_CREATED",
            after = objectMapper.writeValueAsString(mapOf("id" to commentId.toString())),
            metadata = mapOf(
                "visibility" to draft.visibility.name,
                "authorType" to "STAFF",
                "contentLength" to draft.body.trim().length,
                "contentSha256" to sha256(draft.body.trim()),
            ),
            occurredAt = now,
        )

    private fun fieldAuditEvent(
        type: String,
        field: TicketField,
        before: String,
        after: String,
    ) = NewAuditEvent(
        type = type,
        field = field,
        before = objectMapper.writeValueAsString(before),
        after = objectMapper.writeValueAsString(after),
    )

    private fun referenceAuditEvent(
        type: String,
        field: TicketField,
        before: UUID?,
        after: UUID?,
    ) = NewAuditEvent(
        type = type,
        field = field,
        before = before?.let { objectMapper.writeValueAsString(mapOf("id" to it.toString())) },
        after = after?.let { objectMapper.writeValueAsString(mapOf("id" to it.toString())) },
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private data class AuditCommandContext(
        val source: String,
        val requestId: String,
        val correlationId: String,
        val commandId: String,
    )

    private data class NewAuditEvent(
        val type: String,
        val field: TicketField? = null,
        val before: String? = null,
        val after: String? = null,
        val metadata: Map<String, Any?> = emptyMap(),
        val occurredAt: Instant? = null,
    )
}
