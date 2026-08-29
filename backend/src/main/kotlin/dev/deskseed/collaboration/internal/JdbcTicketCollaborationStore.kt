package dev.deskseed.collaboration.internal

import dev.deskseed.collaboration.AgentNotification
import dev.deskseed.collaboration.AgentNotificationPage
import dev.deskseed.collaboration.AgentNotificationType
import dev.deskseed.collaboration.CollaborationCommandReplay
import dev.deskseed.collaboration.CollaborationCursor
import dev.deskseed.collaboration.CollaborationNotePage
import dev.deskseed.collaboration.CollaborationStaffSummary
import dev.deskseed.collaboration.NewTicketCollaborationNote
import dev.deskseed.collaboration.TicketCollaborationNote
import dev.deskseed.collaboration.TicketCollaborationStore
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
internal class JdbcTicketCollaborationStore(
    private val jdbc: JdbcTemplate,
    private val namedJdbc: NamedParameterJdbcTemplate,
) : TicketCollaborationStore {
    override fun lockCommand(actorStaffId: UUID, clientCommandId: UUID) {
        jdbc.queryForObject(
            "select pg_advisory_xact_lock(hashtextextended(?, 0))",
            { _, _ -> Unit },
            "ticket-collaboration-note:$actorStaffId:$clientCommandId",
        )
    }

    override fun findCommand(actorStaffId: UUID, clientCommandId: UUID): CollaborationCommandReplay? {
        val rows = jdbc.query(
            """
            select note.id, note.ticket_id, ticket.ticket_number, note.author_staff_id,
                   author.display_name as author_display_name, note.body, note.audit_id,
                   note.command_fingerprint, note.created_at
              from ticket_collaboration_notes note
              join tickets ticket on ticket.id = note.ticket_id
              join staff_accounts author on author.id = note.author_staff_id
             where note.author_staff_id = ? and note.client_command_id = ?
            """.trimIndent(),
            { result, _ ->
                ReplayRow(
                    note = mapNote(result, emptyList()),
                    fingerprint = result.getString("command_fingerprint"),
                )
            },
            actorStaffId,
            clientCommandId,
        )
        val row = rows.singleOrNull() ?: return null
        return CollaborationCommandReplay(row.note.copy(mentionedStaff = mentions(listOf(row.note.id))[row.note.id].orEmpty()), row.fingerprint)
    }

    override fun append(note: NewTicketCollaborationNote): TicketCollaborationNote {
        require(note.commandFingerprint.matches(Regex("[0-9a-f]{64}"))) { "command fingerprint is invalid" }
        require(note.mentionedStaff.map { it.id }.toSet() == note.notificationIds.keys) {
            "every mentioned staff member must have one notification id"
        }
        jdbc.update(
            """
            insert into ticket_collaboration_notes (
                id, ticket_id, author_staff_id, body, client_command_id,
                command_fingerprint, audit_id, created_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            note.id,
            note.ticketId,
            note.author.id,
            note.body,
            note.clientCommandId,
            note.commandFingerprint,
            note.auditId,
            Timestamp.from(note.createdAt),
        )
        note.mentionedStaff.forEach { mentioned ->
            jdbc.update(
                "insert into ticket_collaboration_note_mentions (note_id, staff_id) values (?, ?)",
                note.id,
                mentioned.id,
            )
            jdbc.update(
                """
                insert into staff_notifications (
                    id, recipient_staff_id, notification_type, ticket_id, note_id, created_at, read_at
                ) values (?, ?, 'COLLABORATION_MENTION', ?, ?, ?, null)
                """.trimIndent(),
                checkNotNull(note.notificationIds[mentioned.id]),
                mentioned.id,
                note.ticketId,
                note.id,
                Timestamp.from(note.createdAt),
            )
        }
        return TicketCollaborationNote(
            id = note.id,
            ticketId = note.ticketId,
            ticketNumber = note.ticketNumber,
            author = note.author,
            body = note.body,
            mentionedStaff = note.mentionedStaff,
            auditId = note.auditId,
            createdAt = note.createdAt,
        )
    }

    override fun listNotes(ticketId: UUID, before: CollaborationCursor?, limit: Int): CollaborationNotePage {
        require(limit in 1..100) { "limit must be between 1 and 100" }
        val cursorClause = if (before == null) "" else "and (note.created_at, note.id) < (?, ?)"
        val arguments = mutableListOf<Any>(ticketId)
        if (before != null) {
            arguments += Timestamp.from(before.createdAt)
            arguments += before.id
        }
        arguments += limit + 1
        val rows = jdbc.query(
            """
            select note.id, note.ticket_id, ticket.ticket_number, note.author_staff_id,
                   author.display_name as author_display_name, note.body, note.audit_id, note.created_at
              from ticket_collaboration_notes note
              join tickets ticket on ticket.id = note.ticket_id
              join staff_accounts author on author.id = note.author_staff_id
             where note.ticket_id = ?
               $cursorClause
             order by note.created_at desc, note.id desc
             limit ?
            """.trimIndent(),
            { result, _ -> mapNote(result, emptyList()) },
            *arguments.toTypedArray(),
        )
        val pageRows = rows.take(limit)
        val mentionsByNote = mentions(pageRows.map(TicketCollaborationNote::id))
        return CollaborationNotePage(
            items = pageRows.map { it.copy(mentionedStaff = mentionsByNote[it.id].orEmpty()) },
            nextCursor = rows.getOrNull(limit)?.let { CollaborationCursor(it.createdAt, it.id) },
        )
    }

    override fun listNotifications(
        recipientStaffId: UUID,
        before: CollaborationCursor?,
        limit: Int,
    ): AgentNotificationPage {
        require(limit in 1..100) { "limit must be between 1 and 100" }
        val cursorClause = if (before == null) "" else "and (notification.created_at, notification.id) < (?, ?)"
        val arguments = mutableListOf<Any>(recipientStaffId)
        if (before != null) {
            arguments += Timestamp.from(before.createdAt)
            arguments += before.id
        }
        arguments += limit + 1
        val rows = jdbc.query(
            """
            select notification.id, notification.recipient_staff_id, notification.notification_type,
                   ticket.ticket_number, notification.note_id, note.author_staff_id,
                   author.display_name as author_display_name, notification.created_at, notification.read_at
              from staff_notifications notification
              join tickets ticket on ticket.id = notification.ticket_id
              join ticket_collaboration_notes note on note.id = notification.note_id
              join staff_accounts author on author.id = note.author_staff_id
             where notification.recipient_staff_id = ?
               $cursorClause
             order by notification.created_at desc, notification.id desc
             limit ?
            """.trimIndent(),
            { result, _ ->
                AgentNotification(
                    id = result.getObject("id", UUID::class.java),
                    recipientStaffId = result.getObject("recipient_staff_id", UUID::class.java),
                    type = AgentNotificationType.valueOf(result.getString("notification_type")),
                    ticketNumber = result.getLong("ticket_number"),
                    noteId = result.getObject("note_id", UUID::class.java),
                    actor = CollaborationStaffSummary(
                        result.getObject("author_staff_id", UUID::class.java),
                        result.getString("author_display_name"),
                    ),
                    createdAt = result.getTimestamp("created_at").toInstant(),
                    readAt = result.getTimestamp("read_at")?.toInstant(),
                )
            },
            *arguments.toTypedArray(),
        )
        val unreadCount = jdbc.queryForObject(
            "select count(*) from staff_notifications where recipient_staff_id = ? and read_at is null",
            Int::class.java,
            recipientStaffId,
        ) ?: 0
        return AgentNotificationPage(
            items = rows.take(limit),
            unreadCount = unreadCount,
            nextCursor = rows.getOrNull(limit)?.let { CollaborationCursor(it.createdAt, it.id) },
        )
    }

    override fun markNotificationRead(recipientStaffId: UUID, notificationId: UUID, readAt: Instant): Boolean =
        jdbc.update(
            """
            update staff_notifications
               set read_at = coalesce(read_at, ?)
             where id = ? and recipient_staff_id = ?
            """.trimIndent(),
            Timestamp.from(readAt),
            notificationId,
            recipientStaffId,
        ) == 1

    private fun mentions(noteIds: List<UUID>): Map<UUID, List<CollaborationStaffSummary>> {
        if (noteIds.isEmpty()) return emptyMap()
        return namedJdbc.query(
            """
            select mention.note_id, staff.id as staff_id, staff.display_name
              from ticket_collaboration_note_mentions mention
              join staff_accounts staff on staff.id = mention.staff_id
             where mention.note_id in (:noteIds)
             order by mention.note_id, staff.display_name, staff.id
            """.trimIndent(),
            MapSqlParameterSource("noteIds", noteIds),
        ) { result, _ ->
            result.getObject("note_id", UUID::class.java) to CollaborationStaffSummary(
                result.getObject("staff_id", UUID::class.java),
                result.getString("display_name"),
            )
        }.groupBy({ it.first }, { it.second })
    }

    private fun mapNote(
        result: java.sql.ResultSet,
        mentionedStaff: List<CollaborationStaffSummary>,
    ) = TicketCollaborationNote(
        id = result.getObject("id", UUID::class.java),
        ticketId = result.getObject("ticket_id", UUID::class.java),
        ticketNumber = result.getLong("ticket_number"),
        author = CollaborationStaffSummary(
            result.getObject("author_staff_id", UUID::class.java),
            result.getString("author_display_name"),
        ),
        body = result.getString("body"),
        mentionedStaff = mentionedStaff,
        auditId = result.getObject("audit_id", UUID::class.java),
        createdAt = result.getTimestamp("created_at").toInstant(),
    )

    private data class ReplayRow(val note: TicketCollaborationNote, val fingerprint: String)
}
