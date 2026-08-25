package dev.deskseed.collaboration.internal

import dev.deskseed.collaboration.NewTicketDraft
import dev.deskseed.collaboration.TicketDraft
import dev.deskseed.collaboration.TicketDraftChannel
import dev.deskseed.collaboration.TicketDraftMaintenance
import dev.deskseed.collaboration.TicketDraftStore
import dev.deskseed.collaboration.UpdatedTicketDraft
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
internal class JdbcTicketDraftStore(
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
) : TicketDraftStore, TicketDraftMaintenance {
    @Transactional(readOnly = true)
    override fun find(ownerStaffId: UUID, ticketId: UUID, channel: TicketDraftChannel): TicketDraft? =
        jdbc.query(
            """
            select draft.*, ticket.ticket_number
              from ticket_drafts draft
              join tickets ticket on ticket.id = draft.ticket_id
             where draft.owner_staff_id = ? and draft.ticket_id = ? and draft.composer_channel = ?
               and draft.expires_at > ?
            """.trimIndent(),
            ::mapDraft,
            ownerStaffId,
            ticketId,
            channel.name,
            Timestamp.from(Instant.now(clock)),
        ).singleOrNull()

    @Transactional
    override fun create(draft: NewTicketDraft): TicketDraft? {
        validate(draft.body, draft.attachmentIds, draft.baseTicketVersion)
        val now = Instant.now(clock)
        return jdbc.query(
            """
            insert into ticket_drafts (
                owner_staff_id, ticket_id, composer_channel, body, attachment_ids,
                client_device_id, base_ticket_version, draft_version,
                created_at, updated_at, expires_at
            ) values (?, ?, ?, ?, cast(? as uuid[]), ?, ?, 1, ?, ?, ?)
            on conflict (owner_staff_id, ticket_id, composer_channel) do nothing
            returning *, (select ticket_number from tickets where id = ticket_id) as ticket_number
            """.trimIndent(),
            ::mapDraft,
            draft.ownerStaffId,
            draft.ticketId,
            draft.channel.name,
            draft.body,
            attachmentIdArray(draft.attachmentIds),
            draft.clientDeviceId,
            draft.baseTicketVersion,
            Timestamp.from(now),
            Timestamp.from(now),
            Timestamp.from(now.plus(DRAFT_RETENTION)),
        ).singleOrNull()
    }

    @Transactional
    override fun update(
        ownerStaffId: UUID,
        ticketId: UUID,
        channel: TicketDraftChannel,
        draft: UpdatedTicketDraft,
    ): TicketDraft? {
        validate(draft.body, draft.attachmentIds, draft.baseTicketVersion)
        require(draft.expectedDraftVersion > 0) { "expectedDraftVersion must be positive" }
        val now = Instant.now(clock)
        return jdbc.query(
            """
            update ticket_drafts
               set body = ?, attachment_ids = cast(? as uuid[]), client_device_id = ?, base_ticket_version = ?,
                   draft_version = draft_version + 1, updated_at = ?, expires_at = ?
             where owner_staff_id = ? and ticket_id = ? and composer_channel = ?
               and draft_version = ?
            returning *, (select ticket_number from tickets where id = ticket_id) as ticket_number
            """.trimIndent(),
            ::mapDraft,
            draft.body,
            attachmentIdArray(draft.attachmentIds),
            draft.clientDeviceId,
            draft.baseTicketVersion,
            Timestamp.from(now),
            Timestamp.from(now.plus(DRAFT_RETENTION)),
            ownerStaffId,
            ticketId,
            channel.name,
            draft.expectedDraftVersion,
        ).singleOrNull()
    }

    @Transactional
    override fun delete(
        ownerStaffId: UUID,
        ticketId: UUID,
        channel: TicketDraftChannel,
        expectedDraftVersion: Long,
    ): Boolean {
        require(expectedDraftVersion > 0) { "expectedDraftVersion must be positive" }
        return jdbc.update(
            """
            delete from ticket_drafts
             where owner_staff_id = ? and ticket_id = ? and composer_channel = ?
               and draft_version = ?
            """.trimIndent(),
            ownerStaffId,
            ticketId,
            channel.name,
            expectedDraftVersion,
        ) == 1
    }

    @Transactional(readOnly = true)
    override fun listRecoverable(ownerStaffId: UUID, limit: Int): List<TicketDraft> {
        require(limit in 1..100) { "limit must be between 1 and 100" }
        return jdbc.query(
            """
            select draft.*, ticket.ticket_number
              from ticket_drafts draft
              join tickets ticket on ticket.id = draft.ticket_id
             where draft.owner_staff_id = ? and draft.expires_at > ?
             order by draft.updated_at desc, draft.ticket_id, draft.composer_channel
             limit ?
            """.trimIndent(),
            ::mapDraft,
            ownerStaffId,
            Timestamp.from(Instant.now(clock)),
            limit,
        )
    }

    @Transactional
    override fun purgeExpired(workerId: String, limit: Int): Int {
        require(WORKER_ID.matches(workerId)) { "workerId must be bounded" }
        require(limit in 1..500) { "limit must be between 1 and 500" }
        val now = Instant.now(clock)
        val leased = jdbc.query(
            """
            update ticket_draft_cleanup_lease
               set lease_owner = ?, lease_expires_at = ?
             where lease_name = 'ticket-draft-expiry'
               and (lease_expires_at is null or lease_expires_at < ? or lease_owner = ?)
            returning lease_name
            """.trimIndent(),
            { result, _ -> result.getString("lease_name") },
            workerId,
            Timestamp.from(now.plus(LEASE_DURATION)),
            Timestamp.from(now),
            workerId,
        ).singleOrNull()
        if (leased == null) return 0
        return jdbc.update(
            """
            delete from ticket_drafts
             where ctid in (
                select ctid from ticket_drafts
                 where expires_at <= ?
                 order by expires_at, owner_staff_id, ticket_id, composer_channel
                 limit ?
             )
            """.trimIndent(),
            Timestamp.from(now),
            limit,
        )
    }

    private fun attachmentIdArray(values: List<UUID>) = values.joinToString(prefix = "{", postfix = "}")

    private fun mapDraft(result: java.sql.ResultSet, _rowIndex: Int): TicketDraft = TicketDraft(
        ownerStaffId = result.getObject("owner_staff_id", UUID::class.java),
        ticketId = result.getObject("ticket_id", UUID::class.java),
        ticketNumber = result.getLong("ticket_number"),
        channel = TicketDraftChannel.valueOf(result.getString("composer_channel")),
        body = result.getString("body"),
        attachmentIds = (result.getArray("attachment_ids").array as Array<*>).map { UUID.fromString(it.toString()) },
        clientDeviceId = result.getObject("client_device_id", UUID::class.java),
        baseTicketVersion = result.getLong("base_ticket_version"),
        draftVersion = result.getLong("draft_version"),
        createdAt = result.getTimestamp("created_at").toInstant(),
        updatedAt = result.getTimestamp("updated_at").toInstant(),
        expiresAt = result.getTimestamp("expires_at").toInstant(),
    )

    private fun validate(body: String, attachmentIds: List<UUID>, baseTicketVersion: Long) {
        require(
            body.length <= MAX_BODY_LENGTH &&
                body.none { character ->
                    character.isISOControl() && character !in setOf('\n', '\r', '\t')
                },
        ) { "Draft body is invalid" }
        require(attachmentIds.size <= MAX_ATTACHMENTS && attachmentIds.distinct().size == attachmentIds.size) {
            "Draft attachment references are invalid"
        }
        require(body.isNotBlank() || attachmentIds.isNotEmpty()) { "Draft content must not be empty" }
        require(baseTicketVersion >= 0) { "baseTicketVersion must not be negative" }
    }

    private companion object {
        const val MAX_BODY_LENGTH = 20_000
        const val MAX_ATTACHMENTS = 5
        val DRAFT_RETENTION = java.time.Duration.ofDays(30)
        val LEASE_DURATION = java.time.Duration.ofMinutes(1)
        val WORKER_ID = Regex("[A-Za-z0-9._:-]{1,100}")
    }
}

@Component
internal class TicketDraftExpiryWorker(
    private val maintenance: TicketDraftMaintenance,
) {
    @Scheduled(fixedDelayString = "\${deskseed.drafts.expiry-cleanup-delay-ms:300000}")
    fun purgeExpiredDrafts() {
        maintenance.purgeExpired("ticket-draft-expiry", 200)
    }
}
