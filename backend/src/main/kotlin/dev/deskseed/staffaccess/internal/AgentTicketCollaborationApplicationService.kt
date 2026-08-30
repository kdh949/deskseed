package dev.deskseed.staffaccess.internal

import dev.deskseed.audit.AccessAuditOutcome
import dev.deskseed.audit.AccessAuditSessionFingerprint
import dev.deskseed.audit.AccessAuditWriter
import dev.deskseed.audit.TicketResourceReadAccessAudit
import dev.deskseed.collaboration.AgentNotificationPage
import dev.deskseed.collaboration.CollaborationCursor
import dev.deskseed.collaboration.CollaborationNotePage
import dev.deskseed.collaboration.CollaborationStaffSummary
import dev.deskseed.collaboration.NewTicketCollaborationNote
import dev.deskseed.collaboration.StaffNotificationCreated
import dev.deskseed.collaboration.TicketCollaborationNote
import dev.deskseed.collaboration.TicketCollaborationStore
import dev.deskseed.foundation.CommandContext
import dev.deskseed.organization.StaffIdentityService
import dev.deskseed.organization.StaffRole
import dev.deskseed.ticketing.AgentTicketCommandService
import dev.deskseed.ticketing.RecordTicketCollaborationNoteCommand
import dev.deskseed.ticketing.StaffTicketCommandActor
import dev.deskseed.ticketing.TicketCommandIdReusedException
import dev.deskseed.ticketing.TicketCommandInvalidException
import dev.deskseed.ticketing.TicketWriteForbiddenException
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID

internal data class CreateCollaborationNoteResult(
    val note: TicketCollaborationNote,
    val auditId: UUID,
    val replayed: Boolean,
)

@Service
internal class AgentTicketCollaborationApplicationService(
    private val ticketRead: AgentTicketReadApplicationService,
    private val writeAuthorization: GroupOrAssigneeTicketWriteAuthorizationPolicy,
    private val staffIdentityService: StaffIdentityService,
    private val ticketCommands: AgentTicketCommandService,
    private val collaboration: TicketCollaborationStore,
    private val accessAuditWriter: AccessAuditWriter,
    private val sessionFingerprint: AccessAuditSessionFingerprint,
    private val eventPublisher: ApplicationEventPublisher,
    private val clock: Clock,
) {
    // The projection is a read, but its required sensitive-read audit must commit
    // in the same transaction. A read-only PostgreSQL transaction rejects that write.
    @Transactional
    fun listNotes(
        principal: StaffPrincipal,
        ticketNumber: Long,
        before: String?,
        limit: Int,
        interactionId: UUID,
        context: AgentReadRequestContext,
    ): Pair<CollaborationNotePage, String?> {
        val detail = ticketRead.requireReadableTicket(principal, ticketNumber)
        val now = Instant.now(clock)
        try {
            accessAuditWriter.appendTicketResourceRead(
                TicketResourceReadAccessAudit(
                    context = context.toAccessAuditContext(
                        principal,
                        sessionFingerprint.fingerprint(context.sessionId),
                    ),
                    ticketId = detail.ticket.id,
                    ticketNumber = ticketNumber,
                    interactionId = interactionId,
                    outcome = AccessAuditOutcome.SUCCEEDED,
                    httpStatus = 200,
                    occurredAt = now,
                ),
            )
        } catch (failure: RuntimeException) {
            throw AccessAuditUnavailableException(failure)
        }
        val page = collaboration.listNotes(detail.ticket.id, decodeCursor(before), limit)
        return page to encodeCursor(page.nextCursor)
    }

    @Transactional
    fun createNote(
        principal: StaffPrincipal,
        ticketNumber: Long,
        rawBody: String,
        mentionedStaffIds: List<UUID>,
        clientCommandId: UUID,
        context: CommandContext,
    ): CreateCollaborationNoteResult {
        val body = normalizeBody(rawBody)
        if (mentionedStaffIds.size > MAX_MENTIONS || mentionedStaffIds.size != mentionedStaffIds.distinct().size) {
            throw TicketCommandInvalidException("mentionedStaffIds must be unique and bounded")
        }
        val detail = ticketRead.requireReadableTicket(principal, ticketNumber)
        if (!writeAuthorization.canUpdate(principal, detail.ticket.group?.id, detail.ticket.assignee?.id)) {
            throw TicketWriteForbiddenException()
        }
        val mentioned = mentionedStaffIds.sorted().map { staffId ->
            val identity = staffIdentityService.findActiveById(staffId)
                ?: throw TicketCommandInvalidException("Every mentioned staff member must be active")
            val target = StaffPrincipal.from(identity)
            runCatching { ticketRead.requireReadableTicket(target, ticketNumber) }
                .getOrElse { throw TicketCommandInvalidException("Every mentioned staff member must be able to read the ticket") }
            CollaborationStaffSummary(identity.id, identity.displayName)
        }
        val fingerprint = sha256(
            buildString {
                append(ticketNumber).append('\n').append(body).append('\n')
                mentioned.forEach { append(it.id).append('\n') }
            },
        )
        collaboration.lockCommand(principal.id, clientCommandId)
        collaboration.findCommand(principal.id, clientCommandId)?.let { replay ->
            if (replay.commandFingerprint != fingerprint) throw TicketCommandIdReusedException()
            return CreateCollaborationNoteResult(replay.note, replay.note.auditId, replayed = true)
        }
        val noteId = UUID.randomUUID()
        val audit = ticketCommands.recordCollaborationNote(
            RecordTicketCollaborationNoteCommand(
                ticketNumber = ticketNumber,
                noteId = noteId,
                contentLength = body.length,
                contentSha256 = sha256(body),
                mentionCount = mentioned.size,
                actor = StaffTicketCommandActor(
                    id = principal.id,
                    displayName = principal.displayName,
                    isAdmin = principal.role == StaffRole.ADMIN,
                ),
                context = context,
            ),
        )
        val createdAt = Instant.now(clock)
        val notificationIds = mentioned.associate { it.id to UUID.randomUUID() }
        val note = collaboration.append(
            NewTicketCollaborationNote(
                id = noteId,
                ticketId = audit.ticketId,
                ticketNumber = audit.ticketNumber,
                author = CollaborationStaffSummary(principal.id, principal.displayName),
                body = body,
                mentionedStaff = mentioned,
                clientCommandId = clientCommandId,
                commandFingerprint = fingerprint,
                auditId = audit.auditId,
                createdAt = createdAt,
                notificationIds = notificationIds,
            ),
        )
        notificationIds.forEach { (recipientId, notificationId) ->
            eventPublisher.publishEvent(StaffNotificationCreated(recipientId, notificationId, createdAt))
        }
        return CreateCollaborationNoteResult(note, audit.auditId, replayed = false)
    }

    @Transactional(readOnly = true)
    fun listNotifications(principal: StaffPrincipal, before: String?, limit: Int): Pair<AgentNotificationPage, String?> {
        requireActive(principal)
        val page = collaboration.listNotifications(principal.id, decodeCursor(before), limit)
        return page to encodeCursor(page.nextCursor)
    }

    @Transactional
    fun markNotificationRead(principal: StaffPrincipal, notificationId: UUID) {
        requireActive(principal)
        if (!collaboration.markNotificationRead(principal.id, notificationId, Instant.now(clock))) {
            throw AgentNotificationNotFoundException()
        }
    }

    private fun requireActive(principal: StaffPrincipal) {
        if (staffIdentityService.findActiveById(principal.id) == null) throw AgentNotificationNotFoundException()
    }

    private fun normalizeBody(value: String): String {
        val normalized = value.trim()
        if (normalized.length !in 1..MAX_BODY || normalized.any {
                it.isISOControl() && it !in setOf('\n', '\r', '\t')
            }
        ) {
            throw TicketCommandInvalidException("Collaboration note body is invalid")
        }
        return normalized
    }

    private fun encodeCursor(cursor: CollaborationCursor?): String? = cursor?.let {
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            "${it.createdAt}|${it.id}".toByteArray(StandardCharsets.UTF_8),
        )
    }

    private fun decodeCursor(value: String?): CollaborationCursor? {
        if (value == null) return null
        val decoded = runCatching {
            String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split('|', limit = 2)
        }.getOrElse { throw IllegalArgumentException("collaboration cursor is invalid") }
        if (decoded.size != 2) throw IllegalArgumentException("collaboration cursor is invalid")
        return runCatching { CollaborationCursor(Instant.parse(decoded[0]), UUID.fromString(decoded[1])) }
            .getOrElse { throw IllegalArgumentException("collaboration cursor is invalid") }
    }

    private fun sha256(value: String): String = java.util.HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)),
    )

    private companion object {
        const val MAX_BODY = 4_000
        const val MAX_MENTIONS = 20
    }
}

internal class AgentNotificationNotFoundException : RuntimeException()
