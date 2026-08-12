package dev.deskseed.ticketing.internal

import dev.deskseed.customer.CustomerDirectory
import dev.deskseed.foundation.ActorRef
import dev.deskseed.foundation.ActorType
import dev.deskseed.foundation.CommandContext
import dev.deskseed.foundation.RequestSource
import dev.deskseed.integration.CreateExternalReference
import dev.deskseed.integration.ExternalReferenceMutation
import dev.deskseed.integration.ExternalReferenceStore
import dev.deskseed.outboundmail.MailRecipient
import dev.deskseed.outboundmail.OutboundMailIntent
import dev.deskseed.outboundmail.OutboundMailPort
import dev.deskseed.outboundmail.PublicAgentReplyMail
import dev.deskseed.ticketing.AgentCommentDraft
import dev.deskseed.ticketing.AgentTicketCommandService
import dev.deskseed.ticketing.AgentTicketNotFoundException
import dev.deskseed.ticketing.CommentAuthorType
import dev.deskseed.ticketing.CommentVisibility
import dev.deskseed.ticketing.CreateAgentTicketCommand
import dev.deskseed.ticketing.CreateChildTicketCommand
import dev.deskseed.ticketing.CreateChildTicketResult
import dev.deskseed.ticketing.CreateTicketExternalReferenceCommand
import dev.deskseed.ticketing.DeleteTicketExternalReferenceCommand
import dev.deskseed.ticketing.TicketAssignmentInvalidException
import dev.deskseed.ticketing.TicketAssignmentPolicy
import dev.deskseed.ticketing.TicketAuditUnavailableException
import dev.deskseed.ticketing.TicketCommandInvalidException
import dev.deskseed.ticketing.TicketCommandIdReusedException
import dev.deskseed.ticketing.TicketCommandResult
import dev.deskseed.ticketing.TicketCommandWarning
import dev.deskseed.ticketing.TicketField
import dev.deskseed.ticketing.TicketFieldConflictException
import dev.deskseed.ticketing.TicketExternalReferenceCommandResult
import dev.deskseed.ticketing.TicketKind
import dev.deskseed.ticketing.TicketOrganizationConsistencyGuard
import dev.deskseed.ticketing.TicketRelationInvalidException
import dev.deskseed.ticketing.TicketStatus
import dev.deskseed.ticketing.TicketSlaLifecycleChanged
import dev.deskseed.ticketing.TicketSubmitted
import dev.deskseed.ticketing.TicketTransitionInvalidException
import dev.deskseed.ticketing.TicketUpdateContentionException
import dev.deskseed.ticketing.TicketVersionPreconditionFailedException
import dev.deskseed.ticketing.TicketWriteAuthorizationPolicy
import dev.deskseed.ticketing.TicketWriteForbiddenException
import dev.deskseed.ticketing.UpdateAgentTicketCommand
import dev.deskseed.ticketing.TransferTicketCommand
import dev.deskseed.ticketing.internal.domain.ParentChildRelationRules
import dev.deskseed.ticketing.internal.domain.Ticket
import dev.deskseed.ticketing.internal.domain.TicketStatusTransitions
import jakarta.persistence.OptimisticLockException
import org.springframework.dao.DataAccessException
import org.springframework.context.ApplicationEventPublisher
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

    override fun update(command: UpdateAgentTicketCommand): TicketCommandResult = executeRetriable {
        transaction.update(command)
    }

    override fun transfer(command: TransferTicketCommand): TicketCommandResult = executeRetriable {
        transaction.transfer(command)
    }

    override fun createChild(command: CreateChildTicketCommand): CreateChildTicketResult = executeRetriable {
        transaction.createChild(command)
    }

    override fun createExternalReference(
        command: CreateTicketExternalReferenceCommand,
    ): TicketExternalReferenceCommandResult = executeRetriable {
        transaction.createExternalReference(command)
    }

    override fun deleteExternalReference(
        command: DeleteTicketExternalReferenceCommand,
    ): TicketExternalReferenceCommandResult = executeRetriable {
        transaction.deleteExternalReference(command)
    }

    private fun <T> executeRetriable(block: () -> T): T {
        var optimisticFailure: RuntimeException? = null
        repeat(MAX_OPTIMISTIC_ATTEMPTS) {
            try {
                return block()
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
    private val relationRepository: TicketRelationRepository,
    private val auditRepository: TicketAuditRepository,
    private val auditEventRepository: TicketAuditEventRepository,
    private val commandReplayStore: StaffTicketCommandReplayStore,
    private val ticketNumberGenerator: TicketNumberGenerator,
    private val organizationConsistencyGuard: TicketOrganizationConsistencyGuard,
    private val assignmentPolicy: TicketAssignmentPolicy,
    private val authorizationPolicy: TicketWriteAuthorizationPolicy,
    private val externalReferenceStore: ExternalReferenceStore,
    private val customerDirectory: CustomerDirectory,
    private val outboundMailPort: OutboundMailPort,
    private val objectMapper: ObjectMapper,
    private val eventPublisher: ApplicationEventPublisher,
    private val clock: Clock,
) {
    @Transactional
    fun create(command: CreateAgentTicketCommand): TicketCommandResult {
        validateStaffContext(command.actor.id, command.context.source)
        validateText(command.subject, "subject", 200)
        validateComment(command.firstComment)
        organizationConsistencyGuard.acquire()
        validateAssignment(command.groupId, command.assigneeId)
        commandReplayStore.lock(command.actor.id, command.context.commandId)
        if (commandReplayStore.find(command.actor.id, command.context.commandId) != null) {
            throw TicketCommandIdReusedException()
        }

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
        if (command.firstComment.visibility == CommentVisibility.PUBLIC) {
            enqueuePublicReply(
                ticket = ticketEntity,
                commentId = ticket.firstComment.id,
                publicBody = ticket.firstComment.body,
                actorId = command.actor.id,
                context = command.context,
            )
        }

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
                actorType = "STAFF",
                actorId = command.actor.id,
                source = command.context.source.name,
                requestId = command.context.requestId,
                correlationId = command.context.correlationId,
                startsFirstReplySla = false,
                occurredAt = now,
            ),
        )
        return TicketCommandResult(ticket.ticketNumber, ticketEntity.version, auditId)
    }

    @Transactional
    fun update(command: UpdateAgentTicketCommand): TicketCommandResult {
        validateUpdateCommand(command)
        val requestDescriptor = updateRequestDescriptor(command)
        organizationConsistencyGuard.acquire()
        commandReplayStore.lock(command.actor.id, command.context.commandId)
        commandReplayStore.find(command.actor.id, command.context.commandId)?.let { original ->
            if (
                original.result.ticketNumber != command.ticketNumber ||
                original.operation != UPDATE_TICKET_OPERATION ||
                original.requestDescriptor != requestDescriptor
            ) {
                throw TicketCommandIdReusedException()
            }
            return original.result
        }
        val ticket = ticketRepository.findByTicketNumber(command.ticketNumber)
            ?: throw AgentTicketNotFoundException()
        if (!authorizationPolicy.canUpdate(command.actor, ticket.groupId, ticket.assigneeId)) {
            throw TicketWriteForbiddenException()
        }
        if (ticket.status == TicketStatus.CLOSED) {
            throw TicketTransitionInvalidException("Closed tickets are immutable")
        }
        if (
            ticket.kind == TicketKind.INTERNAL_CHILD &&
            command.comment?.visibility == CommentVisibility.PUBLIC
        ) {
            throw TicketCommandInvalidException("Internal child tickets cannot contain public comments")
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
            if (draft.visibility == CommentVisibility.PUBLIC) {
                enqueuePublicReply(
                    ticket = ticket,
                    commentId = commentId,
                    publicBody = draft.body.trim(),
                    actorId = command.actor.id,
                    context = command.context,
                )
            }
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
        if (events.isEmpty()) {
            events += NewAuditEvent(type = "UPDATE_COMMAND_RECEIVED")
        }

        val warnings = if (oldStatus != TicketStatus.SOLVED && newStatus == TicketStatus.SOLVED) {
            relationRepository.findOpenChildTicketNumbers(ticket.id).takeIf { it.isNotEmpty() }?.let {
                listOf(
                    TicketCommandWarning(
                        code = "OPEN_CHILD_TICKETS",
                        message = "${it.size}개의 열린 child ticket이 있지만 parent 해결은 저장되었습니다.",
                        relatedTicketNumbers = it,
                    ),
                )
            } ?: emptyList()
        } else {
            emptyList()
        }
        val eventsWithResult = events.mapIndexed { index, event ->
            if (index != 0) {
                event
            } else {
                event.copy(
                    metadata = event.metadata + mapOf(
                        "commandOperation" to UPDATE_TICKET_OPERATION,
                        "commandRequestDescriptor" to requestDescriptor,
                        "commandWarnings" to warnings,
                    ),
                )
            }
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
            events = eventsWithResult,
        )
        eventPublisher.publishEvent(
            TicketSlaLifecycleChanged(
                ticketId = ticket.id,
                previousStatus = oldStatus,
                currentStatus = newStatus,
                humanStaffPublicReply = command.comment?.visibility == CommentVisibility.PUBLIC,
                ticketAuditId = auditId,
                actorId = command.actor.id,
                source = command.context.source.name,
                requestId = command.context.requestId,
                correlationId = command.context.correlationId,
                occurredAt = now,
            ),
        )
        return TicketCommandResult(ticket.ticketNumber, ticket.version, auditId, warnings)
    }

    @Transactional
    fun transfer(command: TransferTicketCommand): TicketCommandResult {
        validateStaffContext(command.actor.id, command.context.source)
        if (command.expectedVersion < 0) throw TicketCommandInvalidException("expectedVersion must be non-negative")
        command.reason?.let { reason ->
            if (reason.length > 2_000) throw TicketCommandInvalidException("reason must not exceed 2000 characters")
        }
        organizationConsistencyGuard.acquire()
        commandReplayStore.lock(command.actor.id, command.context.commandId)
        if (commandReplayStore.find(command.actor.id, command.context.commandId) != null) {
            throw TicketCommandIdReusedException()
        }
        val ticket = ticketRepository.findByTicketNumber(command.ticketNumber)
            ?: throw AgentTicketNotFoundException()
        if (!authorizationPolicy.canUpdate(command.actor, ticket.groupId, ticket.assigneeId)) {
            throw TicketWriteForbiddenException()
        }
        requireExactVersion(command.expectedVersion, ticket.version)
        if (ticket.status == TicketStatus.CLOSED) {
            throw TicketTransitionInvalidException("Closed tickets cannot be transferred")
        }
        validateAssignment(command.groupId, command.assigneeId)

        val oldGroupId = ticket.groupId
        val oldAssigneeId = ticket.assigneeId
        if (oldGroupId == command.groupId && oldAssigneeId == command.assigneeId) {
            throw TicketCommandInvalidException("Transfer must change group or assignee ownership")
        }

        val now = Instant.now(clock)
        val events = mutableListOf<NewAuditEvent>()
        command.reason?.trim()?.takeIf(String::isNotEmpty)?.let { reason ->
            val draft = AgentCommentDraft(CommentVisibility.INTERNAL, reason)
            val commentId = UUID.randomUUID()
            commentRepository.saveAndFlush(
                TicketCommentEntity(
                    id = commentId,
                    ticketId = ticket.id,
                    authorType = CommentAuthorType.AGENT,
                    authorId = command.actor.id,
                    visibility = CommentVisibility.INTERNAL,
                    body = reason,
                    createdAt = now,
                ),
            )
            events += commentAuditEvent(commentId, draft, now)
        }
        if (oldGroupId != command.groupId) {
            events += referenceAuditEvent("GROUP_CHANGED", TicketField.GROUP_ID, oldGroupId, command.groupId)
        }
        if (oldAssigneeId != command.assigneeId) {
            events += referenceAuditEvent(
                "ASSIGNEE_CHANGED",
                TicketField.ASSIGNEE_ID,
                oldAssigneeId,
                command.assigneeId,
            )
        }

        ticket.groupId = command.groupId
        ticket.assigneeId = command.assigneeId
        ticket.updatedAt = now
        ticketRepository.saveAndFlush(ticket)
        val auditId = appendAudit(
            ticket = ticket,
            expectedVersion = command.expectedVersion,
            resultVersion = ticket.version,
            actorId = command.actor.id,
            context = command.context.toAuditContext(),
            now = now,
            events = events,
        )
        return TicketCommandResult(ticket.ticketNumber, ticket.version, auditId)
    }

    @Transactional
    fun createChild(command: CreateChildTicketCommand): CreateChildTicketResult {
        validateStaffContext(command.actor.id, command.context.source)
        if (command.expectedVersion < 0) throw TicketCommandInvalidException("expectedVersion must be non-negative")
        validateText(command.subject, "subject", 200)
        validateText(command.body, "body", 20_000)
        organizationConsistencyGuard.acquire()
        validateAssignment(command.groupId, command.assigneeId)
        commandReplayStore.lock(command.actor.id, command.context.commandId)
        if (commandReplayStore.find(command.actor.id, command.context.commandId) != null) {
            throw TicketCommandIdReusedException()
        }

        val parent = ticketRepository.findByTicketNumber(command.parentTicketNumber)
            ?: throw AgentTicketNotFoundException()
        if (!authorizationPolicy.canUpdate(command.actor, parent.groupId, parent.assigneeId)) {
            throw TicketWriteForbiddenException()
        }
        requireExactVersion(command.expectedVersion, parent.version)
        if (parent.status == TicketStatus.CLOSED) {
            throw TicketRelationInvalidException("Closed tickets cannot create child tickets")
        }

        val now = Instant.now(clock)
        val child = Ticket.createInternalChild(
            ticketNumber = ticketNumberGenerator.next(),
            requesterId = parent.requesterId
                ?: throw TicketRelationInvalidException("Requesterless work items cannot create child tickets"),
            subject = command.subject,
            firstCommentBody = command.body,
            priority = command.priority,
            groupId = command.groupId,
            assigneeId = command.assigneeId,
            actorId = command.actor.id,
            now = now,
        )
        try {
            ParentChildRelationRules.requireValid(
                sourceTicketId = parent.id,
                targetTicketId = child.id,
                sourceAlreadyHasParent = parent.kind == TicketKind.INTERNAL_CHILD ||
                    relationRepository.existsByTargetTicketIdAndRelationType(
                        parent.id,
                        TicketRelationType.PARENT_CHILD,
                    ),
                targetAlreadyHasParent = relationRepository.existsByTargetTicketIdAndRelationType(
                    child.id,
                    TicketRelationType.PARENT_CHILD,
                ),
                wouldCreateCycle = relationRepository.wouldCreateParentChildCycle(parent.id, child.id),
            )
        } catch (exception: IllegalArgumentException) {
            throw TicketRelationInvalidException(exception.message ?: "Invalid parent-child relation")
        }

        val childEntity = ticketRepository.saveAndFlush(
            TicketEntity(
                id = child.id,
                ticketNumber = child.ticketNumber,
                requesterId = child.requesterId,
                kind = child.kind,
                subject = child.subject,
                status = child.status,
                priority = child.priority,
                groupId = child.groupId,
                assigneeId = child.assigneeId,
                channel = child.channel,
                createdAt = child.createdAt,
                updatedAt = child.updatedAt,
            ),
        )
        commentRepository.saveAndFlush(
            TicketCommentEntity(
                id = child.firstComment.id,
                ticketId = child.firstComment.ticketId,
                authorType = child.firstComment.authorType,
                authorId = child.firstComment.authorId,
                visibility = child.firstComment.visibility,
                body = child.firstComment.body,
                createdAt = child.firstComment.createdAt,
            ),
        )
        val relationId = UUID.randomUUID()
        relationRepository.saveAndFlush(
            TicketRelationEntity(
                id = relationId,
                sourceTicketId = parent.id,
                targetTicketId = child.id,
                relationType = TicketRelationType.PARENT_CHILD,
                createdByActorType = ActorType.STAFF,
                createdByActorId = command.actor.id,
                createdAt = now,
            ),
        )

        parent.updatedAt = now
        ticketRepository.saveAndFlush(parent)
        val auditContext = command.context.toAuditContext()
        val relationMetadata = mapOf(
            "relationId" to relationId.toString(),
            "relationType" to TicketRelationType.PARENT_CHILD.name,
            "parentTicketNumber" to parent.ticketNumber,
            "childTicketNumber" to child.ticketNumber,
        )
        val childAuditId = appendAudit(
            ticket = childEntity,
            expectedVersion = 0,
            resultVersion = childEntity.version,
            actorId = command.actor.id,
            context = auditContext,
            now = now,
            events = listOf(
                NewAuditEvent(
                    type = "TICKET_CREATED",
                    metadata = mapOf(
                        "kind" to child.kind.name,
                        "channel" to child.channel.name,
                        "priority" to child.priority.name,
                        "groupId" to child.groupId.toString(),
                        "assigneeId" to child.assigneeId?.toString(),
                    ),
                ),
                commentAuditEvent(
                    child.firstComment.id,
                    AgentCommentDraft(CommentVisibility.INTERNAL, child.firstComment.body),
                    now,
                ),
                NewAuditEvent(type = "TICKET_RELATION_CREATED", metadata = relationMetadata),
            ),
        )
        val parentAuditId = appendAudit(
            ticket = parent,
            expectedVersion = command.expectedVersion,
            resultVersion = parent.version,
            actorId = command.actor.id,
            context = auditContext,
            now = now,
            events = listOf(
                NewAuditEvent(
                    type = "CHILD_TICKET_CREATED",
                    after = objectMapper.writeValueAsString(
                        mapOf("id" to child.id.toString(), "ticketNumber" to child.ticketNumber),
                    ),
                    metadata = mapOf(
                        "groupId" to child.groupId.toString(),
                        "assigneeId" to child.assigneeId?.toString(),
                    ),
                ),
                NewAuditEvent(type = "TICKET_RELATION_CREATED", metadata = relationMetadata),
            ),
        )
        eventPublisher.publishEvent(
            TicketSubmitted(
                ticketId = child.id,
                ticketNumber = child.ticketNumber,
                requesterId = child.requesterId,
                kind = child.kind,
                priority = child.priority,
                groupId = child.groupId,
                channel = child.channel,
                status = child.status,
                ticketAuditId = childAuditId,
                actorType = "STAFF",
                actorId = command.actor.id,
                source = command.context.source.name,
                requestId = command.context.requestId,
                correlationId = command.context.correlationId,
                startsFirstReplySla = false,
                occurredAt = now,
            ),
        )
        return CreateChildTicketResult(
            parentTicketNumber = parent.ticketNumber,
            parentVersion = parent.version,
            childTicketNumber = child.ticketNumber,
            parentAuditId = parentAuditId,
            childAuditId = childAuditId,
        )
    }

    @Transactional
    fun createExternalReference(
        command: CreateTicketExternalReferenceCommand,
    ): TicketExternalReferenceCommandResult {
        validateStaffContext(command.actor.id, command.context.source)
        if (command.expectedVersion < 0) throw TicketCommandInvalidException("expectedVersion must be non-negative")
        val ticket = ticketRepository.findByTicketNumber(command.ticketNumber)
            ?: throw AgentTicketNotFoundException()
        if (!authorizationPolicy.canUpdate(command.actor, ticket.groupId, ticket.assigneeId)) {
            throw TicketWriteForbiddenException()
        }
        requireExactVersion(command.expectedVersion, ticket.version)
        if (ticket.status == TicketStatus.CLOSED) {
            throw TicketTransitionInvalidException("Closed tickets cannot change external references")
        }
        val now = Instant.now(clock)
        val mutation = externalReferenceStore.create(
            CreateExternalReference(
                ticketId = ticket.id,
                externalSystemId = command.externalSystemId,
                objectType = command.objectType,
                externalId = command.externalId,
                displayLabel = command.displayLabel,
                safeDeepLink = command.safeDeepLink,
                metadata = command.metadata,
                metadataObservedAt = command.metadataObservedAt,
                actorId = command.actor.id,
                actorDisplayName = command.actor.displayName,
            ),
        )
        ticket.updatedAt = now
        ticketRepository.saveAndFlush(ticket)
        val auditId = appendExternalReferenceAudit(
            ticket = ticket,
            expectedVersion = command.expectedVersion,
            actorId = command.actor.id,
            context = command.context,
            mutation = mutation,
            eventType = "EXTERNAL_REFERENCE_CREATED",
            isCreate = true,
            now = now,
        )
        return TicketExternalReferenceCommandResult(ticket.ticketNumber, ticket.version, auditId, mutation.reference)
    }

    @Transactional
    fun deleteExternalReference(
        command: DeleteTicketExternalReferenceCommand,
    ): TicketExternalReferenceCommandResult {
        validateStaffContext(command.actor.id, command.context.source)
        if (command.expectedVersion < 0) throw TicketCommandInvalidException("expectedVersion must be non-negative")
        val ticket = ticketRepository.findByTicketNumber(command.ticketNumber)
            ?: throw AgentTicketNotFoundException()
        if (!authorizationPolicy.canUpdate(command.actor, ticket.groupId, ticket.assigneeId)) {
            throw TicketWriteForbiddenException()
        }
        requireExactVersion(command.expectedVersion, ticket.version)
        if (ticket.status == TicketStatus.CLOSED) {
            throw TicketTransitionInvalidException("Closed tickets cannot change external references")
        }
        val now = Instant.now(clock)
        val mutation = externalReferenceStore.delete(ticket.id, command.referenceId)
        ticket.updatedAt = now
        ticketRepository.saveAndFlush(ticket)
        val auditId = appendExternalReferenceAudit(
            ticket = ticket,
            expectedVersion = command.expectedVersion,
            actorId = command.actor.id,
            context = command.context,
            mutation = mutation,
            eventType = "EXTERNAL_REFERENCE_REMOVED",
            isCreate = false,
            now = now,
        )
        return TicketExternalReferenceCommandResult(ticket.ticketNumber, ticket.version, auditId, mutation.reference)
    }

    private fun appendExternalReferenceAudit(
        ticket: TicketEntity,
        expectedVersion: Long,
        actorId: UUID,
        context: CommandContext,
        mutation: ExternalReferenceMutation,
        eventType: String,
        isCreate: Boolean,
        now: Instant,
    ): UUID {
        val referenceIdentity = objectMapper.writeValueAsString(mapOf("id" to mutation.reference.id.toString()))
        return appendAudit(
            ticket = ticket,
            expectedVersion = expectedVersion,
            resultVersion = ticket.version,
            actorId = actorId,
            context = context.toAuditContext(),
            now = now,
            events = listOf(
                NewAuditEvent(
                    type = eventType,
                    before = referenceIdentity.takeUnless { isCreate },
                    after = referenceIdentity.takeIf { isCreate },
                    metadata = mapOf(
                        "externalSystemId" to mutation.reference.system.id.toString(),
                        "systemKey" to mutation.reference.system.systemKey,
                        "objectType" to mutation.reference.objectType.name,
                        "externalId" to mutation.reference.externalId,
                        "hostname" to mutation.auditHostname,
                        "metadataKeys" to mutation.auditMetadataKeys.sorted(),
                    ),
                ),
            ),
        )
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

    private fun updateRequestDescriptor(command: UpdateAgentTicketCommand): String {
        val requestedValues = linkedMapOf<String, Any?>()
        if (TicketField.STATUS in command.changedFields) requestedValues["status"] = command.status?.name
        if (TicketField.PRIORITY in command.changedFields) requestedValues["priority"] = command.priority?.name
        if (TicketField.GROUP_ID in command.changedFields) requestedValues["groupId"] = command.groupId?.toString()
        if (TicketField.ASSIGNEE_ID in command.changedFields) {
            requestedValues["assigneeId"] = command.assigneeId?.toString()
        }
        val comment = command.comment?.let {
            linkedMapOf(
                "visibility" to it.visibility.name,
                "contentSha256" to sha256(it.body.trim()),
            )
        }
        return objectMapper.writeValueAsString(
            linkedMapOf(
                "operation" to UPDATE_TICKET_OPERATION,
                "ticketNumber" to command.ticketNumber,
                "expectedVersion" to command.expectedVersion,
                "changedFields" to command.changedFields.map(TicketField::externalName).sorted(),
                "requestedValues" to requestedValues,
                "comment" to comment,
            ),
        )
    }

    private fun requireExactVersion(expectedVersion: Long, currentVersion: Long) {
        if (expectedVersion != currentVersion) {
            throw TicketVersionPreconditionFailedException(currentVersion)
        }
    }

    private fun CommandContext.toAuditContext() = AuditCommandContext(
        source = source.name,
        requestId = requestId,
        correlationId = correlationId,
        commandId = commandId,
    )

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

    private fun enqueuePublicReply(
        ticket: TicketEntity,
        commentId: UUID,
        publicBody: String,
        actorId: UUID,
        context: CommandContext,
    ) {
        val requesterId = ticket.requesterId
            ?: throw TicketCommandInvalidException("Ticket requester is unavailable")
        val customer = customerDirectory.findById(requesterId)
            ?: throw TicketCommandInvalidException("Ticket requester is unavailable")
        outboundMailPort.enqueue(
            OutboundMailIntent(
                idempotencyKey = "public-agent-reply:$commentId",
                recipient = MailRecipient(customer.email),
                content = PublicAgentReplyMail(
                    ticketNumber = ticket.ticketNumber,
                    publicBody = publicBody,
                ),
                ticketId = ticket.id,
                commentId = commentId,
                customerId = customer.id,
                actor = ActorRef(ActorType.STAFF, actorId),
                context = context,
            ),
        )
    }

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

    private companion object {
        const val UPDATE_TICKET_OPERATION = "UPDATE_TICKET"
    }
}
