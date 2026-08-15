package dev.deskseed.audit.internal

import dev.deskseed.audit.AuditActivityFilter
import dev.deskseed.audit.AdminSecurityAudit
import dev.deskseed.audit.AdminSecurityAuditWriter
import dev.deskseed.audit.AdminSecurityOutcome
import dev.deskseed.audit.AuditExplorerOutcome
import dev.deskseed.audit.AuditExportArtifact
import dev.deskseed.audit.AuditExportFormat
import dev.deskseed.audit.AuditExportJob
import dev.deskseed.foundation.ActorType
import dev.deskseed.foundation.RequestSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Instant
import java.time.Clock
import java.util.UUID

internal data class ClaimedAuditExportJob(
    val id: UUID,
    val requesterId: UUID,
    val format: AuditExportFormat,
    val filters: AuditActivityFilter,
    val fields: List<String>,
    val snapshotAt: Instant,
    val leaseOwner: String,
    val leaseAttempt: Int,
)

internal data class AuditExportArtifactHandle(
    val jobId: UUID,
    val format: AuditExportFormat,
    val objectKey: String,
    val contentType: String,
    val checksumSha256: String,
    val expiresAt: Instant,
)

/** Database-owned state transitions for one-at-a-time export generation and short-lived artifact cleanup. */
@Repository
internal class JdbcAuditExportJobStore(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val properties: AuditExportStorageProperties,
    private val auditWriter: AdminSecurityAuditWriter,
    private val clock: Clock,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun claim(workerId: String): ClaimedAuditExportJob? = jdbcTemplate.query(
        """
        with candidate as (
            select id
            from audit_export_jobs
            where status = 'REQUESTED'
               or (status = 'RUNNING' and lease_expires_at <= clock_timestamp())
            order by created_at, id
            for update skip locked
            limit 1
        )
        update audit_export_jobs job
        set status = 'RUNNING',
            lease_owner = :workerId,
            lease_expires_at = clock_timestamp() + make_interval(secs => :leaseSeconds),
            attempt_count = attempt_count + 1,
            started_at = coalesce(started_at, clock_timestamp()),
            completed_at = null,
            failed_at = null,
            failure_code = null
        from candidate
        where job.id = candidate.id
        returning job.id, job.requester_id, job.format, job.filters_json::text, job.fields_json::text,
                  job.snapshot_at, job.lease_owner, job.attempt_count
        """.trimIndent(),
        mapOf("workerId" to workerId, "leaseSeconds" to properties.workerLeaseSeconds.toInt()),
    ) { result, _ ->
        ClaimedAuditExportJob(
            id = result.getObject("id", UUID::class.java),
            requesterId = result.getObject("requester_id", UUID::class.java),
            format = AuditExportFormat.valueOf(result.getString("format")),
            filters = parseFilters(result.getString("filters_json")),
            fields = parseStrings(result.getString("fields_json")),
            snapshotAt = result.getTimestamp("snapshot_at").toInstant(),
            leaseOwner = result.getString("lease_owner"),
            leaseAttempt = result.getInt("attempt_count"),
        )
    }.singleOrNull()

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markReady(
        job: ClaimedAuditExportJob,
        objectKey: String,
        contentType: String,
        rowCount: Long,
        sizeBytes: Long,
        checksumSha256: String,
    ): Boolean {
        val changed = jdbcTemplate.update(
            """
            update audit_export_jobs
            set status = 'READY', lease_owner = null, lease_expires_at = null,
                completed_at = clock_timestamp(), failed_at = null, failure_code = null
            where id = :jobId and status = 'RUNNING' and lease_owner = :workerId
              and attempt_count = :leaseAttempt
            """.trimIndent(),
            mapOf("jobId" to job.id, "workerId" to job.leaseOwner, "leaseAttempt" to job.leaseAttempt),
        )
        if (changed != 1) return false
        jdbcTemplate.update(
            """
            update audit_export_artifacts
            set state = 'READY', generation_available = true, object_key = :objectKey,
                content_type = :contentType, row_count = :rowCount, size_bytes = :sizeBytes,
                checksum_sha256 = :checksumSha256,
                expires_at = clock_timestamp() + make_interval(hours => :ttlHours),
                failure_code = null, deleted_at = null
            where job_id = :jobId
            """.trimIndent(),
            mapOf(
                "jobId" to job.id,
                "objectKey" to objectKey,
                "contentType" to contentType,
                "rowCount" to rowCount,
                "sizeBytes" to sizeBytes,
                "checksumSha256" to checksumSha256,
                "ttlHours" to properties.ttlHours.toInt(),
            ),
        )
        appendLifecycleAudit(job.id, "AUDIT_EXPORT_READY", AdminSecurityOutcome.SUCCEEDED, mapOf(
            "format" to job.format.name,
            "rowCount" to rowCount.toString(),
            "sizeBytes" to sizeBytes.toString(),
            "checksumPrefix" to checksumSha256.take(12),
        ))
        return true
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markFailed(job: ClaimedAuditExportJob, failureCode: String): Boolean {
        val code = failureCode.take(80)
        val changed = jdbcTemplate.update(
            """
            update audit_export_jobs
            set status = 'FAILED', lease_owner = null, lease_expires_at = null,
                failed_at = clock_timestamp(), failure_code = :failureCode
            where id = :jobId and status = 'RUNNING' and lease_owner = :workerId
              and attempt_count = :leaseAttempt
            """.trimIndent(),
            mapOf(
                "jobId" to job.id,
                "workerId" to job.leaseOwner,
                "leaseAttempt" to job.leaseAttempt,
                "failureCode" to code,
            ),
        )
        if (changed != 1) return false
        jdbcTemplate.update(
            """
            update audit_export_artifacts
            set state = 'FAILED', generation_available = false, object_key = null, content_type = null,
                row_count = null, size_bytes = null, checksum_sha256 = null, expires_at = null,
                failure_code = :failureCode
            where job_id = :jobId
            """.trimIndent(),
            mapOf("jobId" to job.id, "failureCode" to code),
        )
        appendLifecycleAudit(job.id, "AUDIT_EXPORT_FAILED", AdminSecurityOutcome.FAILED, mapOf("failureCode" to code))
        return true
    }

    fun getForRequester(jobId: UUID, requesterId: UUID): AuditExportJob? = jdbcTemplate.query(
        """
        select job.id, job.status, job.created_at, job.format, job.fields_json::text,
               artifact.state, artifact.generation_available, artifact.row_count, artifact.size_bytes,
               artifact.checksum_sha256, artifact.expires_at, artifact.content_type, artifact.failure_code
        from audit_export_jobs job
        join audit_export_artifacts artifact on artifact.job_id = job.id
        where job.id = :jobId and job.requester_id = :requesterId
        """.trimIndent(),
        mapOf("jobId" to jobId, "requesterId" to requesterId),
    ) { result, _ -> job(result) }.singleOrNull()

    fun readyForRequester(jobId: UUID, requesterId: UUID, now: Instant): AuditExportArtifactHandle? = jdbcTemplate.query(
        """
        select job.id, job.format, artifact.object_key, artifact.content_type, artifact.checksum_sha256, artifact.expires_at
        from audit_export_jobs job
        join audit_export_artifacts artifact on artifact.job_id = job.id
        where job.id = :jobId and job.requester_id = :requesterId
          and job.status = 'READY' and artifact.state = 'READY' and artifact.expires_at > :now
        """.trimIndent(),
        mapOf("jobId" to jobId, "requesterId" to requesterId, "now" to Timestamp.from(now)),
    ) { result, _ ->
        AuditExportArtifactHandle(
            jobId = result.getObject("id", UUID::class.java),
            format = AuditExportFormat.valueOf(result.getString("format")),
            objectKey = result.getString("object_key"),
            contentType = result.getString("content_type"),
            checksumSha256 = result.getString("checksum_sha256"),
            expiresAt = result.getTimestamp("expires_at").toInstant(),
        )
    }.singleOrNull()

    fun expiredOrMissingForRequester(jobId: UUID, requesterId: UUID, now: Instant): Boolean = jdbcTemplate.queryForObject(
        """
        select exists (
            select 1
            from audit_export_jobs job
            join audit_export_artifacts artifact on artifact.job_id = job.id
            where job.id = :jobId and job.requester_id = :requesterId
              and (job.status = 'EXPIRED' or artifact.state in ('EXPIRED', 'DELETED') or artifact.expires_at <= :now)
        )
        """.trimIndent(),
        mapOf("jobId" to jobId, "requesterId" to requesterId, "now" to Timestamp.from(now)),
        Boolean::class.java,
    ) ?: false

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun claimExpired(limit: Int): List<AuditExportArtifactHandle> = jdbcTemplate.query(
        """
        select job.id, job.format, artifact.object_key, artifact.content_type, artifact.checksum_sha256, artifact.expires_at
        from audit_export_jobs job
        join audit_export_artifacts artifact on artifact.job_id = job.id
        where job.status = 'READY' and artifact.state = 'READY' and artifact.expires_at <= clock_timestamp()
        order by artifact.expires_at, job.id
        limit :limit
        for update skip locked
        """.trimIndent(),
        mapOf("limit" to limit),
    ) { result, _ ->
        AuditExportArtifactHandle(
            jobId = result.getObject("id", UUID::class.java),
            format = AuditExportFormat.valueOf(result.getString("format")),
            objectKey = result.getString("object_key"),
            contentType = result.getString("content_type"),
            checksumSha256 = result.getString("checksum_sha256"),
            expiresAt = result.getTimestamp("expires_at").toInstant(),
        )
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markExpired(jobId: UUID): Boolean {
        val changed = jdbcTemplate.update(
            """
            update audit_export_jobs
            set status = 'EXPIRED', lease_owner = null, lease_expires_at = null
            where id = :jobId and status = 'READY'
            """.trimIndent(),
            mapOf("jobId" to jobId),
        )
        if (changed != 1) return false
        jdbcTemplate.update(
            """
            update audit_export_artifacts
            set state = 'EXPIRED', generation_available = false, object_key = null,
                deleted_at = clock_timestamp(), failure_code = 'EXPIRED'
            where job_id = :jobId
            """.trimIndent(),
            mapOf("jobId" to jobId),
        )
        appendLifecycleAudit(jobId, "AUDIT_EXPORT_EXPIRED", AdminSecurityOutcome.SUCCEEDED, mapOf("state" to "EXPIRED"))
        return true
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun renewLease(job: ClaimedAuditExportJob): Boolean = jdbcTemplate.update(
        """
        update audit_export_jobs
        set lease_expires_at = clock_timestamp() + make_interval(secs => :leaseSeconds)
        where id = :jobId and status = 'RUNNING' and lease_owner = :workerId
          and attempt_count = :leaseAttempt
        """.trimIndent(),
        mapOf(
            "jobId" to job.id,
            "workerId" to job.leaseOwner,
            "leaseAttempt" to job.leaseAttempt,
            "leaseSeconds" to properties.workerLeaseSeconds.toInt(),
        ),
    ) == 1

    @Suppress("UNCHECKED_CAST")
    private fun parseStrings(json: String): List<String> = objectMapper.readValue(json, List::class.java) as List<String>

    @Suppress("UNCHECKED_CAST")
    private fun parseFilters(json: String): AuditActivityFilter {
        val values = objectMapper.readValue(json, Map::class.java) as Map<String, String>
        return AuditActivityFilter(
            from = values["from"]?.let(Instant::parse),
            to = values["to"]?.let(Instant::parse),
            ledger = values["ledger"]?.let { dev.deskseed.audit.AuditLedgerType.valueOf(it) },
            action = values["action"],
            actorType = values["actorType"]?.let(ActorType::valueOf),
            actorId = values["actorId"]?.let(UUID::fromString),
            ticketNumber = values["ticketNumber"]?.toLong(),
            groupId = values["groupId"]?.let(UUID::fromString),
            field = values["field"],
            source = values["source"],
            outcome = values["outcome"]?.let(AuditExplorerOutcome::valueOf),
            requestId = values["requestId"],
            correlationId = values["correlationId"],
            searchFingerprint = values["searchFingerprint"],
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun job(result: java.sql.ResultSet): AuditExportJob = AuditExportJob(
        id = result.getObject("id", UUID::class.java),
        status = result.getString("status"),
        createdAt = result.getTimestamp("created_at").toInstant(),
        format = AuditExportFormat.valueOf(result.getString("format")),
        fields = objectMapper.readValue(result.getString("fields_json"), List::class.java) as List<String>,
        artifact = AuditExportArtifact(
            state = result.getString("state"),
            generationAvailable = result.getBoolean("generation_available"),
            rowCount = result.getObject("row_count")?.let { (it as Number).toLong() },
            sizeBytes = result.getObject("size_bytes")?.let { (it as Number).toLong() },
            checksumSha256 = result.getString("checksum_sha256"),
            expiresAt = result.getTimestamp("expires_at")?.toInstant(),
            contentType = result.getString("content_type"),
            failureCode = result.getString("failure_code"),
        ),
    )

    private fun appendLifecycleAudit(
        jobId: UUID,
        eventType: String,
        outcome: AdminSecurityOutcome,
        metadata: Map<String, String>,
    ) {
        auditWriter.append(
            AdminSecurityAudit(
                eventType = eventType,
                actorType = ActorType.SYSTEM,
                actorId = null,
                actorDisplaySnapshot = "Deskseed audit export worker",
                source = RequestSource.SYSTEM_JOB,
                targetType = "AUDIT_EXPORT_JOB",
                targetId = jobId,
                outcome = outcome,
                requestId = "audit-export-worker",
                correlationId = "audit-export-$jobId",
                metadata = metadata,
                occurredAt = Instant.now(clock),
            ),
        )
    }
}
