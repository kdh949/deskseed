package dev.deskseed.ticketing.internal

import dev.deskseed.foundation.ActorType
import dev.deskseed.foundation.RequestSource
import dev.deskseed.ticketing.AddPlatformInternalCommentCommand
import dev.deskseed.ticketing.CommentAuthorType
import dev.deskseed.ticketing.CommentVisibility
import dev.deskseed.ticketing.CreatePlatformTicketCommand
import dev.deskseed.ticketing.PlatformInternalCommentView
import dev.deskseed.ticketing.PlatformTicketAuditUnavailableException
import dev.deskseed.ticketing.PlatformTicketInvalidException
import dev.deskseed.ticketing.PlatformTicketKind
import dev.deskseed.ticketing.PlatformTicketNotFoundException
import dev.deskseed.ticketing.PlatformTicketService
import dev.deskseed.ticketing.PlatformTicketVersionException
import dev.deskseed.ticketing.PlatformTicketView
import dev.deskseed.ticketing.TicketAssignmentPolicy
import dev.deskseed.ticketing.TicketChannel
import dev.deskseed.ticketing.TicketField
import dev.deskseed.ticketing.TicketKind
import dev.deskseed.ticketing.TicketOrganizationConsistencyGuard
import dev.deskseed.ticketing.TicketStatus
import dev.deskseed.ticketing.TicketSubmitted
import dev.deskseed.ticketing.UpdatePlatformTicketCommand
import dev.deskseed.ticketing.ValidatePlatformTicketCreateCommand
import dev.deskseed.ticketing.internal.domain.TicketStatusTransitions
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
internal class JpaPlatformTicketService(
    private val ticketRepository: TicketRepository,
    private val commentRepository: TicketCommentRepository,
    private val auditRepository: TicketAuditRepository,
    private val auditEventRepository: TicketAuditEventRepository,
    private val ticketNumberGenerator: TicketNumberGenerator,
    private val organizationConsistencyGuard: TicketOrganizationConsistencyGuard,
    private val assignmentPolicy: TicketAssignmentPolicy,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
    private val eventPublisher: ApplicationEventPublisher,
) : PlatformTicketService {
    @Transactional(noRollbackFor = [PlatformTicketInvalidException::class])
    override fun validateCreate(command: ValidatePlatformTicketCreateCommand) {
        validateContext(command.source)
        validateText(command.subject, "SUBJECT_INVALID", 200)
        validateText(command.message, "MESSAGE_INVALID", 50_000)
        if (command.kind == PlatformTicketKind.CUSTOMER_REQUEST && !command.requesterProvided) {
            throw PlatformTicketInvalidException("REQUESTER_REQUIRED")
        }
        organizationConsistencyGuard.acquire()
        validateAssignment(command.groupId, command.assigneeId)
    }

    @Transactional
    override fun create(command: CreatePlatformTicketCommand): PlatformTicketView = translateStorageFailure {
        validateContext(command.context.source)
        validateText(command.subject, "SUBJECT_INVALID", 200)
        validateText(command.message, "MESSAGE_INVALID", 50_000)
        validateRequesterShape(command)
        organizationConsistencyGuard.acquire()
        validateAssignment(command.groupId, command.assigneeId)

        val now = Instant.now(clock)
        val ticketId = UUID.randomUUID()
        val ticket = ticketRepository.saveAndFlush(
            TicketEntity(
                id = ticketId,
                ticketNumber = ticketNumberGenerator.next(),
                requesterId = command.requesterId,
                kind = command.kind.toStoredKind(),
                subject = command.subject.trim(),
                status = TicketStatus.NEW,
                priority = command.priority,
                groupId = command.groupId,
                assigneeId = command.assigneeId,
                channel = TicketChannel.API,
                createdAt = now,
                updatedAt = now,
            ),
        )
        val visibility = if (command.kind == PlatformTicketKind.CUSTOMER_REQUEST) {
            CommentVisibility.PUBLIC
        } else {
            CommentVisibility.INTERNAL
        }
        val commentId = UUID.randomUUID()
        commentRepository.saveAndFlush(
            TicketCommentEntity(
                id = commentId,
                ticketId = ticket.id,
                authorType = if (command.kind == PlatformTicketKind.CUSTOMER_REQUEST) {
                    CommentAuthorType.CUSTOMER
                } else {
                    CommentAuthorType.INTEGRATION_CLIENT
                },
                authorId = command.requesterId ?: command.actor.id,
                visibility = visibility,
                body = command.message.trim(),
                createdAt = now,
            ),
        )
        val auditId = appendAudit(
            ticket,
            expectedVersion = 0,
            command.actor.id,
            command.context.requestId,
            command.context.correlationId,
            command.context.commandId,
            now,
            listOf(
                NewEvent(
                    "TICKET_CREATED",
                    metadata = mapOf(
                        "kind" to command.kind.name,
                        "channel" to TicketChannel.API.name,
                        "priority" to command.priority.name,
                        "groupId" to command.groupId?.toString(),
                        "assigneeId" to command.assigneeId?.toString(),
                    ),
                ),
                NewEvent(
                    "COMMENT_CREATED",
                    metadata = mapOf(
                        "commentId" to commentId.toString(),
                        "visibility" to visibility.name,
                        "contentSha256" to sha256(command.message.trim()),
                    ),
                ),
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
                actorType = ActorType.INTEGRATION_CLIENT.name,
                actorId = command.actor.id,
                source = RequestSource.PLATFORM_API.name,
                requestId = command.context.requestId,
                correlationId = command.context.correlationId,
                startsFirstReplySla = ticket.kind == TicketKind.CUSTOMER_REQUEST,
                occurredAt = now,
            ),
        )
        ticket.toPlatformView()
    }

    @Transactional(readOnly = true)
    override fun find(ticketNumber: Long): PlatformTicketView? =
        ticketRepository.findByTicketNumber(ticketNumber)?.toPlatformViewOrNull()

    @Transactional(
        noRollbackFor = [PlatformTicketInvalidException::class, PlatformTicketVersionException::class],
    )
    override fun update(command: UpdatePlatformTicketCommand): PlatformTicketView = translateStorageFailure {
        validateContext(command.context.source)
        if (command.changedFields.isEmpty()) throw PlatformTicketInvalidException("UPDATE_FIELDS_REQUIRED")
        organizationConsistencyGuard.acquire()
        val ticket = ticketRepository.findByTicketNumber(command.ticketNumber)
            ?: throw PlatformTicketNotFoundException()
        if (ticket.version != command.expectedVersion) throw PlatformTicketVersionException(ticket.version)

        val oldStatus = ticket.status
        val oldPriority = ticket.priority
        val oldGroupId = ticket.groupId
        val oldAssigneeId = ticket.assigneeId
        val nextGroup = if (TicketField.GROUP_ID in command.changedFields) command.groupId else oldGroupId
        val nextAssignee = if (TicketField.ASSIGNEE_ID in command.changedFields) command.assigneeId else oldAssigneeId
        validateAssignment(nextGroup, nextAssignee)
        val nextStatus = if (TicketField.STATUS in command.changedFields) {
            command.status ?: throw PlatformTicketInvalidException("STATUS_REQUIRED")
        } else {
            oldStatus
        }
        if (nextStatus == TicketStatus.CLOSED || !TicketStatusTransitions.isAllowed(oldStatus, nextStatus)) {
            throw PlatformTicketInvalidException("STATUS_TRANSITION_INVALID")
        }
        val nextPriority = if (TicketField.PRIORITY in command.changedFields) {
            command.priority ?: throw PlatformTicketInvalidException("PRIORITY_REQUIRED")
        } else {
            oldPriority
        }
        val events = mutableListOf<NewEvent>()

        if (TicketField.STATUS in command.changedFields) {
            ticket.status = nextStatus
            ticket.solvedAt = if (nextStatus == TicketStatus.SOLVED) Instant.now(clock) else null
            if (oldStatus != nextStatus) {
                events += changed("STATUS_CHANGED", TicketField.STATUS, oldStatus.name, nextStatus.name)
            }
        }
        if (TicketField.PRIORITY in command.changedFields) {
            ticket.priority = nextPriority
            if (oldPriority != nextPriority) {
                events += changed("PRIORITY_CHANGED", TicketField.PRIORITY, oldPriority.name, nextPriority.name)
            }
        }
        if (TicketField.GROUP_ID in command.changedFields) {
            ticket.groupId = command.groupId
            if (oldGroupId != command.groupId) {
                events += changed("GROUP_CHANGED", TicketField.GROUP_ID, oldGroupId?.toString(), command.groupId?.toString())
            }
        }
        if (TicketField.ASSIGNEE_ID in command.changedFields) {
            ticket.assigneeId = command.assigneeId
            if (oldAssigneeId != command.assigneeId) {
                events += changed(
                    "ASSIGNEE_CHANGED",
                    TicketField.ASSIGNEE_ID,
                    oldAssigneeId?.toString(),
                    command.assigneeId?.toString(),
                )
            }
        }
        if (events.isEmpty()) events += NewEvent("UPDATE_COMMAND_RECEIVED")
        val now = Instant.now(clock)
        ticket.updatedAt = now
        val saved = ticketRepository.saveAndFlush(ticket)
        appendAudit(
            saved,
            command.expectedVersion,
            command.actor.id,
            command.context.requestId,
            command.context.correlationId,
            command.context.commandId,
            now,
            events,
        )
        saved.toPlatformView()
    }

    @Transactional
    override fun addInternalComment(command: AddPlatformInternalCommentCommand): PlatformInternalCommentView =
        translateStorageFailure {
            validateContext(command.context.source)
            validateText(command.body, "COMMENT_BODY_INVALID", 50_000)
            val ticket = ticketRepository.findByTicketNumber(command.ticketNumber)
                ?: throw PlatformTicketNotFoundException()
            val expectedVersion = ticket.version
            val now = Instant.now(clock)
            val comment = commentRepository.saveAndFlush(
                TicketCommentEntity(
                    id = UUID.randomUUID(),
                    ticketId = ticket.id,
                    authorType = CommentAuthorType.INTEGRATION_CLIENT,
                    authorId = command.actor.id,
                    visibility = CommentVisibility.INTERNAL,
                    body = command.body.trim(),
                    createdAt = now,
                ),
            )
            ticket.updatedAt = now
            val saved = ticketRepository.saveAndFlush(ticket)
            appendAudit(
                saved,
                expectedVersion,
                command.actor.id,
                command.context.requestId,
                command.context.correlationId,
                command.context.commandId,
                now,
                listOf(
                    NewEvent(
                        "COMMENT_CREATED",
                        metadata = mapOf(
                            "commentId" to comment.id.toString(),
                            "visibility" to CommentVisibility.INTERNAL.name,
                            "contentSha256" to sha256(comment.body),
                        ),
                    ),
                ),
            )
            PlatformInternalCommentView(
                comment.id,
                ticket.id,
                ticket.ticketNumber,
                saved.version,
                comment.visibility,
                comment.body,
                comment.createdAt,
            )
        }

    private fun appendAudit(
        ticket: TicketEntity,
        expectedVersion: Long,
        actorId: UUID,
        requestId: String,
        correlationId: String,
        commandId: String,
        now: Instant,
        events: List<NewEvent>,
    ): UUID {
        val auditId = UUID.randomUUID()
        auditRepository.saveAndFlush(
            TicketAuditEntity(
                id = auditId,
                ticketId = ticket.id,
                ticketVersion = ticket.version,
                expectedVersion = expectedVersion,
                actorType = ActorType.INTEGRATION_CLIENT.name,
                actorId = actorId,
                source = RequestSource.PLATFORM_API.name,
                requestId = requestId,
                correlationId = correlationId,
                commandId = commandId,
                createdAt = now,
            ),
        )
        auditEventRepository.saveAllAndFlush(
            events.mapIndexed { index, event ->
                TicketAuditEventEntity(
                    id = UUID.randomUUID(),
                    auditId = auditId,
                    eventOrder = index,
                    eventType = event.type,
                    fieldName = event.field?.externalName,
                    oldValueJson = event.oldValue?.let(objectMapper::writeValueAsString),
                    newValueJson = event.newValue?.let(objectMapper::writeValueAsString),
                    metadataJson = objectMapper.writeValueAsString(event.metadata),
                    occurredAt = now,
                )
            },
        )
        return auditId
    }

    private fun validateRequesterShape(command: CreatePlatformTicketCommand) {
        if (command.kind == PlatformTicketKind.CUSTOMER_REQUEST && command.requesterId == null) {
            throw PlatformTicketInvalidException("REQUESTER_REQUIRED")
        }
    }

    private fun validateAssignment(groupId: UUID?, assigneeId: UUID?) {
        if (groupId != null && !assignmentPolicy.isActiveGroup(groupId)) {
            throw PlatformTicketInvalidException("GROUP_NOT_ACTIVE")
        }
        if (assigneeId != null && (groupId == null || !assignmentPolicy.isActiveMember(groupId, assigneeId))) {
            throw PlatformTicketInvalidException("ASSIGNEE_NOT_ACTIVE_GROUP_MEMBER")
        }
    }

    private fun validateContext(source: RequestSource) {
        if (source != RequestSource.PLATFORM_API) throw PlatformTicketInvalidException("PLATFORM_CONTEXT_REQUIRED")
    }

    private fun validateText(value: String, code: String, maxLength: Int) {
        if (value.isBlank() || value.length > maxLength) throw PlatformTicketInvalidException(code)
        if (code == "SUBJECT_INVALID" && value.any(Char::isISOControl)) throw PlatformTicketInvalidException(code)
    }

    private fun changed(type: String, field: TicketField, old: String?, new: String?) =
        NewEvent(type, field, old, new)

    private fun TicketEntity.toPlatformViewOrNull(): PlatformTicketView? = when (kind) {
        TicketKind.CUSTOMER_REQUEST -> toPlatformView(PlatformTicketKind.CUSTOMER_REQUEST)
        TicketKind.INTERNAL_WORK_ITEM -> toPlatformView(PlatformTicketKind.INTERNAL_WORK_ITEM)
        else -> null
    }

    private fun TicketEntity.toPlatformView(kind: PlatformTicketKind = this.kind.toPlatformKind()) = PlatformTicketView(
        id,
        ticketNumber,
        kind,
        subject,
        status,
        priority,
        groupId,
        assigneeId,
        version,
        createdAt,
        updatedAt,
    )

    private fun TicketKind.toPlatformKind(): PlatformTicketKind = when (this) {
        TicketKind.CUSTOMER_REQUEST -> PlatformTicketKind.CUSTOMER_REQUEST
        TicketKind.INTERNAL_WORK_ITEM -> PlatformTicketKind.INTERNAL_WORK_ITEM
        else -> throw PlatformTicketInvalidException("TICKET_KIND_NOT_PLATFORM_VISIBLE")
    }

    private fun PlatformTicketKind.toStoredKind(): TicketKind = when (this) {
        PlatformTicketKind.CUSTOMER_REQUEST -> TicketKind.CUSTOMER_REQUEST
        PlatformTicketKind.INTERNAL_WORK_ITEM -> TicketKind.INTERNAL_WORK_ITEM
    }

    private fun sha256(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun <T> translateStorageFailure(block: () -> T): T = try {
        block()
    } catch (failure: DataAccessException) {
        throw PlatformTicketAuditUnavailableException(failure)
    }

    private data class NewEvent(
        val type: String,
        val field: TicketField? = null,
        val oldValue: String? = null,
        val newValue: String? = null,
        val metadata: Map<String, String?> = emptyMap(),
    )
}
