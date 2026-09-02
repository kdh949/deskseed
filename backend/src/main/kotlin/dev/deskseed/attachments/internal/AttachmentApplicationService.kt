package dev.deskseed.attachments.internal

import dev.deskseed.attachments.AttachmentAccessDeniedException
import dev.deskseed.attachments.AttachmentCleanupService
import dev.deskseed.attachments.AttachmentContent
import dev.deskseed.attachments.AttachmentDownloadCommand
import dev.deskseed.attachments.AttachmentDownloadService
import dev.deskseed.attachments.AttachmentInfectedException
import dev.deskseed.attachments.AttachmentLinkInvalidException
import dev.deskseed.attachments.AttachmentLinkLocator
import dev.deskseed.attachments.AttachmentMimeMismatchException
import dev.deskseed.attachments.AttachmentNotFoundException
import dev.deskseed.attachments.AttachmentObjectStore
import dev.deskseed.attachments.AttachmentScanStatus
import dev.deskseed.attachments.AttachmentTooLargeException
import dev.deskseed.attachments.AttachmentUnavailableException
import dev.deskseed.attachments.AttachmentUploadCommand
import dev.deskseed.attachments.AttachmentUploadResult
import dev.deskseed.attachments.AttachmentUploadService
import dev.deskseed.attachments.AttachmentVisibility
import dev.deskseed.attachments.LinkedTicketAttachment
import dev.deskseed.attachments.MalwareScanResult
import dev.deskseed.attachments.MalwareScanSource
import dev.deskseed.attachments.MalwareScanner
import dev.deskseed.attachments.TicketAttachment
import dev.deskseed.attachments.TicketAttachmentLinkCommand
import dev.deskseed.attachments.TicketAttachmentLinker
import dev.deskseed.attachments.TicketAttachmentReadProjection
import dev.deskseed.attachments.TicketDraftAttachmentReferenceValidator
import dev.deskseed.audit.AccessAuditOutcome
import dev.deskseed.audit.AccessAuditWriter
import dev.deskseed.audit.AdminSecurityAudit
import dev.deskseed.audit.AdminSecurityAuditWriter
import dev.deskseed.audit.AdminSecurityOutcome
import dev.deskseed.audit.AttachmentDownloadAccessAudit
import dev.deskseed.foundation.ActorRef
import dev.deskseed.foundation.ActorType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.PushbackInputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
internal class AttachmentApplicationService(
    private val metadata: AttachmentMetadataStore,
    private val transitions: AttachmentStateTransitions,
    private val objectStore: AttachmentObjectStore,
    private val malwareScanner: MalwareScanner,
    private val cleanupTransactions: AttachmentCleanupTransactions,
    private val properties: AttachmentStorageProperties,
    private val clock: Clock,
) : AttachmentUploadService, TicketAttachmentLinker, TicketAttachmentReadProjection, TicketDraftAttachmentReferenceValidator, AttachmentDownloadService, AttachmentCleanupService {
    override fun upload(command: AttachmentUploadCommand): AttachmentUploadResult {
        validateUpload(command)
        val now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS)
        val id = UUID.randomUUID()
        val storageKey = "attachments/quarantine/$id"
        val expiresAt = now.plus(properties.unlinkedTtlHours, ChronoUnit.HOURS)
        val pending = PendingAttachment(
            id = id,
            storageKey = storageKey,
            actorType = command.actor.actorType,
            actorId = requireNotNull(command.actor.actorId) { "Attachment upload actor must be identified" },
            boundTicketId = command.boundTicketId,
            allowedVisibility = command.allowedVisibility,
            initialPublicSubmission = command.initialPublicSubmission,
            fileName = normalizeFileName(command.fileName),
            declaredContentType = command.declaredContentType?.trim()?.lowercase()?.takeIf(String::isNotBlank),
            createdAt = now,
            expiresAt = expiresAt,
        )
        transitions.recordQuarantine(pending, command)

        val stored = try {
            storeWithDigest(pending, command.content)
        } catch (exception: AttachmentTooLargeException) {
            transitions.markTerminal(pending, AttachmentScanStatus.FAILED, "SIZE_LIMIT", command)
            throw exception
        } catch (exception: RuntimeException) {
            transitions.markTerminal(pending, AttachmentScanStatus.FAILED, "STORE_FAILURE", command)
            throw AttachmentUnavailableException(exception)
        }

        val detectedContentType = detectContentType(stored.header)
        if (detectedContentType == null || !isCompatibleContentType(pending.declaredContentType, detectedContentType)) {
            transitions.markTerminal(pending, AttachmentScanStatus.FAILED, "MIME_MISMATCH", command)
            throw AttachmentMimeMismatchException()
        }
        val effectiveContentType = detectedContentType
        val scan = try {
            if (malwareScanner.requiresContent) {
                objectStore.openPrivate(pending.storageKey).use { stream ->
                    malwareScanner.scan(stream, pending.fileName, effectiveContentType)
                }
            } else {
                InputStream.nullInputStream().use { stream ->
                    malwareScanner.scan(stream, pending.fileName, effectiveContentType)
                }
            }
        } catch (exception: RuntimeException) {
            transitions.markTerminal(pending, AttachmentScanStatus.FAILED, "SCANNER_UNAVAILABLE", command)
            throw AttachmentUnavailableException(exception)
        }
        if (scan == MalwareScanResult.INFECTED) {
            transitions.markTerminal(pending, AttachmentScanStatus.INFECTED, "MALWARE_DETECTED", command)
            throw AttachmentInfectedException()
        }

        val attachment = TicketAttachment(id, pending.fileName, stored.sizeBytes, effectiveContentType)
        transitions.markClean(pending, attachment, stored.sha256, malwareScanner.source, command)
        return AttachmentUploadResult(attachment, AttachmentScanStatus.CLEAN, expiresAt)
    }

    @Transactional
    override fun linkCleanAttachments(command: TicketAttachmentLinkCommand): List<LinkedTicketAttachment> {
        if (command.attachmentIds.isEmpty()) return emptyList()
        require(command.attachmentIds.size <= MAX_LINKED_ATTACHMENTS) { "Too many attachments for one comment" }
        val rows = metadata.lockForLink(command.attachmentIds)
        if (rows.size != command.attachmentIds.size) throw AttachmentLinkInvalidException("Attachment does not exist")
        rows.forEach { row ->
            if (row.status != AttachmentScanStatus.CLEAN || row.linkedAt != null || !row.expiresAt.isAfter(command.linkedAt)) {
                throw AttachmentLinkInvalidException("Attachment is not a current clean unlinked upload")
            }
            if (row.uploadedActorType != command.actor.actorType || row.uploadedActorId != command.actor.actorId) {
                throw AttachmentLinkInvalidException("Attachment owner does not match the command actor")
            }
            if (row.boundTicketId != null && row.boundTicketId != command.ticketId) {
                throw AttachmentLinkInvalidException("Attachment is bound to another ticket")
            }
            if (row.allowedVisibility != null && row.allowedVisibility != command.visibility) {
                throw AttachmentLinkInvalidException("Attachment visibility does not match the comment")
            }
        }
        val linkedExpiry = command.linkedAt.plus(properties.linkedTtlDays, ChronoUnit.DAYS)
        val updated = metadata.link(rows.map(StoredAttachment::id), command, linkedExpiry)
        if (updated != rows.size) throw AttachmentLinkInvalidException("Attachment link changed concurrently")
        return rows.map { row ->
            LinkedTicketAttachment(
                TicketAttachment(row.id, row.fileName, row.sizeBytes, checkNotNull(row.contentType)),
                command.visibility,
            )
        }
    }

    @Transactional(readOnly = true)
    override fun validateDraftReferences(
        ticketId: UUID,
        actor: ActorRef,
        visibility: AttachmentVisibility,
        attachmentIds: Set<UUID>,
        now: Instant,
    ) {
        if (attachmentIds.isEmpty()) return
        require(attachmentIds.size <= MAX_LINKED_ATTACHMENTS) { "Too many attachment references for one draft" }
        val rows = metadata.findForDraftReference(attachmentIds)
        if (rows.size != attachmentIds.size) throw AttachmentLinkInvalidException("Attachment does not exist")
        rows.forEach { row ->
            if (row.status != AttachmentScanStatus.CLEAN || row.linkedAt != null || !row.expiresAt.isAfter(now)) {
                throw AttachmentLinkInvalidException("Attachment is not a current clean unlinked upload")
            }
            if (row.uploadedActorType != actor.actorType || row.uploadedActorId != actor.actorId) {
                throw AttachmentLinkInvalidException("Attachment owner does not match the draft actor")
            }
            if (row.boundTicketId != null && row.boundTicketId != ticketId) {
                throw AttachmentLinkInvalidException("Attachment is bound to another ticket")
            }
            if (row.allowedVisibility != null && row.allowedVisibility != visibility) {
                throw AttachmentLinkInvalidException("Attachment visibility does not match the draft channel")
            }
        }
    }

    override fun listForComments(
        commentIds: Collection<UUID>,
        allowedVisibilities: Set<AttachmentVisibility>,
    ): Map<UUID, List<TicketAttachment>> = metadata.listForComments(commentIds, allowedVisibilities)

    override fun locateLinkedAttachment(attachmentId: UUID): AttachmentLinkLocator? = metadata.locate(attachmentId)

    override fun openForDownload(command: AttachmentDownloadCommand): AttachmentContent {
        require(command.allowedVisibilities.isNotEmpty()) { "At least one attachment visibility is required" }
        val row = metadata.findDownloadable(
            attachmentId = command.attachmentId,
            ticketId = command.ticketId,
            allowedVisibilities = command.allowedVisibilities,
            now = command.occurredAt,
        ) ?: throw AttachmentNotFoundException()
        try {
            transitions.recordDownload(row, command)
        } catch (exception: RuntimeException) {
            throw AttachmentUnavailableException(exception)
        }
        val stream = try {
            objectStore.openPrivate(row.storageKey)
        } catch (exception: RuntimeException) {
            throw AttachmentUnavailableException(exception)
        }
        return AttachmentContent(
            TicketAttachment(row.id, row.fileName, row.sizeBytes, checkNotNull(row.contentType)),
            stream,
        )
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    override fun purgeExpired(now: Instant): Int {
        val claims = cleanupTransactions.claimExpired(
            now = now,
            limit = properties.cleanupBatchSize,
            leaseExpiresAt = now.plusSeconds(properties.cleanupLeaseSeconds),
        )
        var completed = 0
        claims.forEach { claim ->
            try {
                objectStore.delete(claim.attachment.storageKey)
            } catch (exception: RuntimeException) {
                runCatching { cleanupTransactions.releaseClaim(claim) }
                throw AttachmentUnavailableException(exception)
            }
            cleanupTransactions.completeClaim(claim, now)
            completed += 1
        }
        return completed
    }

    private fun validateUpload(command: AttachmentUploadCommand) {
        require(command.actor.actorType in setOf(ActorType.STAFF, ActorType.CUSTOMER)) {
            "Only staff or customer attachment uploads are supported"
        }
        require(command.fileName.isNotBlank() && command.fileName.length <= MAX_FILE_NAME_LENGTH) {
            "Attachment filename is invalid"
        }
        require(command.fileName.none(Char::isISOControl) && !command.fileName.contains('/') && !command.fileName.contains('\\')) {
            "Attachment filename is invalid"
        }
        require(command.declaredContentType?.length ?: 0 <= MAX_CONTENT_TYPE_LENGTH) { "Attachment content type is invalid" }
        if (command.actor.actorType == ActorType.CUSTOMER) {
            require(command.allowedVisibility == AttachmentVisibility.PUBLIC &&
                (command.boundTicketId != null || command.initialPublicSubmission)
            ) {
                "Customer attachment uploads must be ticket-bound PUBLIC uploads or initial PUBLIC submissions"
            }
        } else {
            require(!command.initialPublicSubmission) { "Only customer initial submissions may be unbound" }
        }
    }

    private fun storeWithDigest(pending: PendingAttachment, content: InputStream): StoredBytes {
        val input = PushbackInputStream(BufferedInputStream(content), HEADER_LENGTH)
        val header = input.readNBytes(HEADER_LENGTH)
        input.unread(header)
        val digest = MessageDigest.getInstance("SHA-256")
        val bounded = BoundedInputStream(DigestInputStream(input, digest), properties.maxUploadBytes)
        val sizeBytes = objectStore.putQuarantine(pending.storageKey, bounded)
        return StoredBytes(sizeBytes, digest.digest().joinToString("") { "%02x".format(it) }, header)
    }

    private fun normalizeFileName(value: String): String = value.trim().take(MAX_FILE_NAME_LENGTH)

    private fun detectContentType(header: ByteArray): String? = when {
        header.startsWith(byteArrayOf(0x25, 0x50, 0x44, 0x46)) -> "application/pdf"
        header.startsWith(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)) -> "image/png"
        header.startsWith(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())) -> "image/jpeg"
        header.startsWith(byteArrayOf(0x50, 0x4b, 0x03, 0x04)) -> "application/zip"
        else -> null
    }

    private fun isCompatibleContentType(declared: String?, detected: String?): Boolean {
        if (declared == null || declared == OCTET_STREAM) return true
        return declared == detected
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private data class StoredBytes(val sizeBytes: Long, val sha256: String, val header: ByteArray)

    private companion object {
        const val MAX_LINKED_ATTACHMENTS = 5
        const val MAX_FILE_NAME_LENGTH = 255
        const val MAX_CONTENT_TYPE_LENGTH = 127
        const val HEADER_LENGTH = 32
        const val OCTET_STREAM = "application/octet-stream"
    }
}

private class BoundedInputStream(
    private val delegate: InputStream,
    private val maxBytes: Long,
) : InputStream() {
    private var total = 0L

    override fun read(): Int = delegate.read().also { if (it >= 0) count(1) }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = delegate.read(buffer, offset, length).also {
        if (it > 0) count(it)
    }

    override fun close() = delegate.close()

    private fun count(read: Int) {
        total += read
        if (total > maxBytes) throw AttachmentTooLargeException()
    }
}

@Repository
internal class AttachmentMetadataStore(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun insertQuarantine(pending: PendingAttachment) {
        jdbcTemplate.update(
            """
            insert into attachment_objects (
                id, storage_key, uploaded_actor_type, uploaded_actor_id, bound_ticket_id, allowed_visibility, initial_public_submission,
                file_name, declared_content_type, scan_status, created_at, expires_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'QUARANTINED', ?, ?)
            """.trimIndent(),
            pending.id,
            pending.storageKey,
            pending.actorType.name,
            pending.actorId,
            pending.boundTicketId,
            pending.allowedVisibility?.name,
            pending.initialPublicSubmission,
            pending.fileName,
            pending.declaredContentType,
            Timestamp.from(pending.createdAt),
            Timestamp.from(pending.expiresAt),
        )
    }

    fun markClean(id: UUID, attachment: TicketAttachment, sha256: String, scannedAt: Instant) {
        val updated = jdbcTemplate.update(
            """
            update attachment_objects
            set size_bytes = ?, sha256 = ?, detected_content_type = ?, content_type = ?, scan_status = 'CLEAN',
                scan_failure_code = null, scanned_at = ?
            where id = ? and scan_status = 'QUARANTINED'
            """.trimIndent(),
            attachment.sizeBytes,
            sha256,
            attachment.contentType,
            attachment.contentType,
            Timestamp.from(scannedAt),
            id,
        )
        check(updated == 1) { "Attachment quarantine state changed before clean transition" }
    }

    fun markTerminal(id: UUID, status: AttachmentScanStatus, failureCode: String, occurredAt: Instant) {
        val updated = jdbcTemplate.update(
            """
            update attachment_objects
            set scan_status = ?, scan_failure_code = ?, scanned_at = coalesce(scanned_at, ?)
            where id = ? and scan_status = 'QUARANTINED'
            """.trimIndent(),
            status.name,
            failureCode,
            Timestamp.from(occurredAt),
            id,
        )
        check(updated == 1) { "Attachment quarantine state changed before terminal transition" }
    }

    fun lockForLink(ids: Set<UUID>): List<StoredAttachment> = jdbcTemplate.query(
        """
        select id, storage_key, uploaded_actor_type, uploaded_actor_id, bound_ticket_id, allowed_visibility,
               file_name, size_bytes, content_type, scan_status, linked_at, expires_at
        from attachment_objects
        where id in (${ids.joinToString(",") { "?" }})
        order by id
        for update
        """.trimIndent(),
        ::storedAttachment,
        *ids.toTypedArray(),
    )

    fun findForDraftReference(ids: Set<UUID>): List<StoredAttachment> = jdbcTemplate.query(
        """
        select id, storage_key, uploaded_actor_type, uploaded_actor_id, bound_ticket_id, allowed_visibility,
               file_name, size_bytes, content_type, scan_status, linked_at, expires_at
        from attachment_objects
        where id in (${ids.joinToString(",") { "?" }})
        order by id
        """.trimIndent(),
        ::storedAttachment,
        *ids.toTypedArray(),
    )

    fun link(ids: List<UUID>, command: TicketAttachmentLinkCommand, expiresAt: Instant): Int {
        ids.forEach { id ->
            jdbcTemplate.update(
                """
                insert into ticket_comment_attachments (attachment_id, ticket_id, comment_id, visibility, linked_at)
                values (?, ?, ?, ?, ?)
                """.trimIndent(),
                id,
                command.ticketId,
                command.commentId,
                command.visibility.name,
                Timestamp.from(command.linkedAt),
            )
        }
        return jdbcTemplate.update(
            """
            update attachment_objects
            set linked_at = ?, expires_at = ?
            where id in (${ids.joinToString(",") { "?" }})
              and scan_status = 'CLEAN' and linked_at is null
            """.trimIndent(),
            *arrayOf(Timestamp.from(command.linkedAt), Timestamp.from(expiresAt), *ids.toTypedArray()),
        )
    }

    fun listForComments(
        commentIds: Collection<UUID>,
        allowedVisibilities: Set<AttachmentVisibility>,
    ): Map<UUID, List<TicketAttachment>> {
        if (commentIds.isEmpty() || allowedVisibilities.isEmpty()) return emptyMap()
        val rows = jdbcTemplate.query(
            """
            select link.comment_id, object.id, object.file_name, object.size_bytes, object.content_type
            from ticket_comment_attachments link
            join attachment_objects object on object.id = link.attachment_id
            where link.comment_id in (${commentIds.joinToString(",") { "?" }})
              and link.visibility in (${allowedVisibilities.joinToString(",") { "?" }})
              and object.scan_status = 'CLEAN'
              and object.expires_at > clock_timestamp()
            order by link.comment_id, object.file_name, object.id
            """.trimIndent(),
            { result, _ ->
                result.getObject("comment_id", UUID::class.java) to TicketAttachment(
                    result.getObject("id", UUID::class.java),
                    result.getString("file_name"),
                    result.getLong("size_bytes"),
                    result.getString("content_type"),
                )
            },
            *(commentIds.map { it as Any } + allowedVisibilities.map { it.name as Any }).toTypedArray(),
        )
        return rows.groupBy({ it.first }, { it.second })
    }

    fun locate(attachmentId: UUID): AttachmentLinkLocator? = jdbcTemplate.query(
        """
        select link.ticket_id, ticket.ticket_number, link.visibility
        from ticket_comment_attachments link
        join tickets ticket on ticket.id = link.ticket_id
        join attachment_objects object on object.id = link.attachment_id
        where link.attachment_id = ?
          and object.scan_status = 'CLEAN'
          and object.expires_at > clock_timestamp()
        """.trimIndent(),
        { result, _ ->
            AttachmentLinkLocator(
                result.getObject("ticket_id", UUID::class.java),
                result.getLong("ticket_number"),
                AttachmentVisibility.valueOf(result.getString("visibility")),
            )
        },
        attachmentId,
    ).singleOrNull()

    fun findDownloadable(
        attachmentId: UUID,
        ticketId: UUID,
        allowedVisibilities: Set<AttachmentVisibility>,
        now: Instant,
    ): StoredAttachment? = jdbcTemplate.query(
        """
        select object.id, object.storage_key, object.uploaded_actor_type, object.uploaded_actor_id,
               object.bound_ticket_id, object.allowed_visibility, object.file_name, object.size_bytes,
               object.content_type, object.scan_status, object.linked_at, object.expires_at
        from attachment_objects object
        join ticket_comment_attachments link on link.attachment_id = object.id
        where object.id = ? and link.ticket_id = ?
          and link.visibility in (${allowedVisibilities.joinToString(",") { "?" }})
          and object.scan_status = 'CLEAN' and object.linked_at is not null and object.expires_at > ?
        """.trimIndent(),
        ::storedAttachment,
        *arrayOf(attachmentId, ticketId, *allowedVisibilities.map(AttachmentVisibility::name).toTypedArray(), Timestamp.from(now)),
    ).singleOrNull()

    fun lockExpired(now: Instant, limit: Int): List<StoredAttachment> = jdbcTemplate.query(
        """
        select id, storage_key, uploaded_actor_type, uploaded_actor_id, bound_ticket_id, allowed_visibility,
               file_name, size_bytes, content_type, scan_status, linked_at, expires_at
        from attachment_objects
        where scan_status in ('QUARANTINED', 'CLEAN', 'INFECTED', 'FAILED') and expires_at <= ?
          and (cleanup_lease_expires_at is null or cleanup_lease_expires_at <= ?)
        order by expires_at, id
        limit ?
        for update skip locked
        """.trimIndent(),
        ::storedAttachment,
        Timestamp.from(now),
        Timestamp.from(now),
        limit,
    )

    fun claimCleanup(id: UUID, claimId: UUID, claimedAt: Instant, leaseExpiresAt: Instant): Boolean = jdbcTemplate.update(
        """
        update attachment_objects
        set cleanup_claim_id = ?, cleanup_lease_expires_at = ?, cleanup_attempt_count = cleanup_attempt_count + 1
        where id = ? and (cleanup_lease_expires_at is null or cleanup_lease_expires_at <= ?)
        """.trimIndent(),
        claimId,
        Timestamp.from(leaseExpiresAt),
        id,
        Timestamp.from(claimedAt),
    ) == 1

    fun releaseCleanupClaim(id: UUID, claimId: UUID) {
        jdbcTemplate.update(
            """
            update attachment_objects
            set cleanup_claim_id = null, cleanup_lease_expires_at = null
            where id = ? and cleanup_claim_id = ?
            """.trimIndent(),
            id,
            claimId,
        )
    }

    fun markExpired(id: UUID, claimId: UUID, now: Instant): Boolean = jdbcTemplate.update(
        """
        update attachment_objects
        set scan_status = 'EXPIRED', deleted_at = ?, cleanup_claim_id = null, cleanup_lease_expires_at = null
        where id = ? and cleanup_claim_id = ? and scan_status <> 'EXPIRED'
        """.trimIndent(),
        Timestamp.from(now),
        id,
        claimId,
    ) == 1

    private fun storedAttachment(result: java.sql.ResultSet, row: Int): StoredAttachment = StoredAttachment(
        id = result.getObject("id", UUID::class.java),
        storageKey = result.getString("storage_key"),
        uploadedActorType = ActorType.valueOf(result.getString("uploaded_actor_type")),
        uploadedActorId = result.getObject("uploaded_actor_id", UUID::class.java),
        boundTicketId = result.getObject("bound_ticket_id", UUID::class.java),
        allowedVisibility = result.getString("allowed_visibility")?.let(AttachmentVisibility::valueOf),
        fileName = result.getString("file_name"),
        sizeBytes = result.getLong("size_bytes"),
        contentType = result.getString("content_type"),
        status = AttachmentScanStatus.valueOf(result.getString("scan_status")),
        linkedAt = result.getTimestamp("linked_at")?.toInstant(),
        expiresAt = result.getTimestamp("expires_at").toInstant(),
    )
}

internal data class AttachmentCleanupClaim(
    val attachment: StoredAttachment,
    val claimId: UUID,
)

@Service
internal class AttachmentCleanupTransactions(
    private val metadata: AttachmentMetadataStore,
    private val transitions: AttachmentStateTransitions,
) {
    @Transactional
    fun claimExpired(now: Instant, limit: Int, leaseExpiresAt: Instant): List<AttachmentCleanupClaim> =
        metadata.lockExpired(now, limit).map { attachment ->
            val claim = AttachmentCleanupClaim(attachment, UUID.randomUUID())
            check(metadata.claimCleanup(attachment.id, claim.claimId, now, leaseExpiresAt)) {
                "Attachment cleanup claim changed while locked"
            }
            claim
        }

    @Transactional
    fun releaseClaim(claim: AttachmentCleanupClaim) {
        metadata.releaseCleanupClaim(claim.attachment.id, claim.claimId)
    }

    @Transactional
    fun completeClaim(claim: AttachmentCleanupClaim, now: Instant) {
        check(metadata.markExpired(claim.attachment.id, claim.claimId, now)) {
            "Attachment cleanup claim changed before completion"
        }
        transitions.recordCleanup(claim.attachment, now)
    }
}

@Service
internal class AttachmentStateTransitions(
    private val metadata: AttachmentMetadataStore,
    private val securityAuditWriter: AdminSecurityAuditWriter,
    private val accessAuditWriter: AccessAuditWriter,
    private val clock: Clock,
) {
    @Transactional
    fun recordQuarantine(pending: PendingAttachment, command: AttachmentUploadCommand) {
        metadata.insertQuarantine(pending)
        appendSecurity("ATTACHMENT_UPLOAD_QUARANTINED", pending, command, pending.createdAt, mapOf("status" to "QUARANTINED"))
    }

    @Transactional
    fun markClean(
        pending: PendingAttachment,
        attachment: TicketAttachment,
        sha256: String,
        scanSource: MalwareScanSource,
        command: AttachmentUploadCommand,
    ) {
        val now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS)
        metadata.markClean(pending.id, attachment, sha256, now)
        appendSecurity(
            "ATTACHMENT_SCANNED_CLEAN",
            pending,
            command,
            now,
            mapOf(
                "status" to "CLEAN",
                "sizeBytes" to attachment.sizeBytes.toString(),
                "sha256Prefix" to sha256.take(12),
                "scanSource" to scanSource.name,
            ),
        )
    }

    @Transactional
    fun markTerminal(
        pending: PendingAttachment,
        status: AttachmentScanStatus,
        failureCode: String,
        command: AttachmentUploadCommand,
    ) {
        val now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS)
        metadata.markTerminal(pending.id, status, failureCode, now)
        appendSecurity(
            "ATTACHMENT_${status.name}",
            pending,
            command,
            now,
            mapOf("status" to status.name, "failureCode" to failureCode),
        )
    }

    @Transactional
    fun recordDownload(row: StoredAttachment, command: AttachmentDownloadCommand) {
        accessAuditWriter.appendAttachmentDownloaded(
            AttachmentDownloadAccessAudit(
                context = command.accessContext,
                attachmentId = row.id,
                ticketNumber = command.ticketNumber,
                interactionId = command.interactionId,
                outcome = AccessAuditOutcome.SUCCEEDED,
                httpStatus = 200,
                occurredAt = command.occurredAt,
            ),
        )
    }

    @Transactional
    fun recordCleanup(row: StoredAttachment, now: Instant) {
        securityAuditWriter.append(
            AdminSecurityAudit(
                eventType = "ATTACHMENT_EXPIRED_DELETED",
                actorType = ActorType.SYSTEM,
                actorId = null,
                actorDisplaySnapshot = "Deskseed retention",
                source = dev.deskseed.foundation.RequestSource.SYSTEM_JOB,
                targetType = "ATTACHMENT",
                targetId = row.id,
                outcome = AdminSecurityOutcome.SUCCEEDED,
                requestId = "attachment-cleanup",
                correlationId = "attachment-cleanup",
                metadata = mapOf("status" to "EXPIRED"),
                occurredAt = now,
            ),
        )
    }

    private fun appendSecurity(
        eventType: String,
        pending: PendingAttachment,
        command: AttachmentUploadCommand,
        occurredAt: Instant,
        metadata: Map<String, String>,
    ) {
        securityAuditWriter.append(
            AdminSecurityAudit(
                eventType = eventType,
                actorType = command.actor.actorType,
                actorId = command.actor.actorId,
                actorDisplaySnapshot = command.actorDisplayName,
                source = command.source,
                targetType = "ATTACHMENT",
                targetId = pending.id,
                outcome = AdminSecurityOutcome.SUCCEEDED,
                requestId = command.context.requestId,
                correlationId = command.context.correlationId,
                metadata = metadata + mapOf("boundTicket" to (pending.boundTicketId != null).toString()),
                occurredAt = occurredAt,
            ),
        )
    }
}

internal data class PendingAttachment(
    val id: UUID,
    val storageKey: String,
    val actorType: ActorType,
    val actorId: UUID,
    val boundTicketId: UUID?,
    val allowedVisibility: AttachmentVisibility?,
    val initialPublicSubmission: Boolean,
    val fileName: String,
    val declaredContentType: String?,
    val createdAt: Instant,
    val expiresAt: Instant,
)

internal data class StoredAttachment(
    val id: UUID,
    val storageKey: String,
    val uploadedActorType: ActorType,
    val uploadedActorId: UUID,
    val boundTicketId: UUID?,
    val allowedVisibility: AttachmentVisibility?,
    val fileName: String,
    val sizeBytes: Long,
    val contentType: String?,
    val status: AttachmentScanStatus,
    val linkedAt: Instant?,
    val expiresAt: Instant,
)
