package dev.deskseed.audit.internal

import dev.deskseed.audit.AuditExportArtifactStore
import dev.deskseed.audit.AuditExportArtifactStoreUnavailableException
import dev.deskseed.audit.AuditExportFormat
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.DigestOutputStream
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Claims one job with `FOR UPDATE SKIP LOCKED`, streams only the frozen projection snapshot, and makes READY
 * durable only after the private object and lifecycle audit have both succeeded.
 */
@Component
internal class AuditExportWorker(
    private val jobStore: JdbcAuditExportJobStore,
    private val projectionReader: AuditExportProjectionReader,
    private val artifactStore: AuditExportArtifactStore,
    private val properties: AuditExportStorageProperties,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    private val workerId = "audit-export-${UUID.randomUUID()}"

    @Scheduled(
        fixedDelayString = "\${deskseed.audit-export.worker-interval:5s}",
        initialDelayString = "\${deskseed.audit-export.worker-initial-delay:5s}",
    )
    fun processScheduled() {
        processAvailable()
    }

    @Scheduled(
        fixedDelayString = "\${deskseed.audit-export.cleanup-interval:1h}",
        initialDelayString = "\${deskseed.audit-export.cleanup-initial-delay:10m}",
    )
    fun cleanupScheduled() {
        purgeExpired()
    }

    fun processAvailable(): Int {
        var processed = 0
        repeat(properties.workerBatchSize) {
            val job = jobStore.claim(workerId) ?: return processed
            process(job)
            processed += 1
        }
        return processed
    }

    fun purgeExpired(): Int {
        var purged = 0
        jobStore.claimExpired(properties.workerBatchSize).forEach { artifact ->
            try {
                artifactStore.delete(artifact.objectKey)
                if (jobStore.markExpired(artifact.jobId)) purged += 1
            } catch (_: AuditExportArtifactStoreUnavailableException) {
                // The expired timestamp already blocks downloads. Leave the READY row for a later cleanup retry.
            }
        }
        return purged
    }

    private fun process(job: ClaimedAuditExportJob) {
        val key = artifactKey(job.id, job.format, job.leaseAttempt)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var rowCount = 0L
            val sizeBytes = artifactStore.writePrivate(key) { output ->
                val digestOutput = DigestOutputStream(output, digest)
                val writer = BufferedWriter(OutputStreamWriter(digestOutput, StandardCharsets.UTF_8))
                if (job.format == AuditExportFormat.CSV) {
                    writer.write(job.fields.joinToString(",") { csv(it) })
                    writer.write("\r\n")
                }
                projectionReader.forEach(job.filters, job.snapshotAt) { row ->
                    val values = exportValues(job.fields, row)
                    when (job.format) {
                        AuditExportFormat.CSV -> {
                            writer.write(job.fields.joinToString(",") { field -> csv(values[field]) })
                            writer.write("\r\n")
                        }
                        AuditExportFormat.JSONL -> {
                            writer.write(objectMapper.writeValueAsString(values))
                            writer.write("\n")
                        }
                    }
                    rowCount += 1
                    if (rowCount % LEASE_RENEWAL_ROWS == 0L && !jobStore.renewLease(job)) {
                        throw AuditExportLeaseLostException()
                    }
                }
                writer.flush()
            }
            val checksum = digest.digest().joinToString("") { "%02x".format(it) }
            if (!jobStore.markReady(job, key, contentType(job.format), rowCount, sizeBytes, checksum)) {
                artifactStore.delete(key)
            }
        } catch (exception: Exception) {
            runCatching { artifactStore.delete(key) }
            // The local private-store adapter wraps callback failures while removing its
            // temporary file. A lease loss is not a generation failure: another worker
            // may already own the next attempt, so leave the job for lease recovery.
            if (!exception.wasCausedByLeaseLoss()) {
                jobStore.markFailed(job, failureCode(exception))
            }
        }
    }

    private fun exportValues(fields: List<String>, row: ExportProjectionRow): LinkedHashMap<String, Any?> = linkedMapOf<String, Any?>().apply {
        fields.forEach { field ->
            put(
                field,
                when (field) {
                    "occurredAt" -> row.occurredAt.toString()
                    "ledger" -> row.ledger
                    "action" -> row.action
                    "actor" -> linkedMapOf(
                        "type" to row.actorType,
                        "id" to row.actorId?.toString(),
                        "displayName" to row.actorDisplayName,
                    )
                    "ticketNumber" -> row.ticketNumber
                    "groupId" -> row.groupId?.toString()
                    "field" -> row.field
                    "source" -> row.source
                    "outcome" -> row.outcome
                    "requestId" -> row.requestId
                    "correlationId" -> row.correlationId
                    "searchFingerprint" -> row.searchFingerprint
                    else -> error("Unallowlisted export field")
                },
            )
        }
    }

    private fun csv(value: Any?): String {
        val raw = when (value) {
            null -> ""
            is String -> value
            else -> objectMapper.writeValueAsString(value)
        }
        // Spreadsheets evaluate leading formula tokens after CSV import; preserve the text as literal data.
        val formulaSafe = if (raw.firstOrNull() in FORMULA_PREFIXES) "'$raw" else raw
        return if (formulaSafe.any { it == ',' || it == '"' || it == '\r' || it == '\n' }) {
            "\"${formulaSafe.replace("\"", "\"\"")}\""
        } else {
            formulaSafe
        }
    }

    private fun artifactKey(jobId: UUID, format: AuditExportFormat, leaseAttempt: Int): String =
        "audit-exports/$jobId/attempt-$leaseAttempt.${if (format == AuditExportFormat.CSV) "csv" else "jsonl"}"

    private fun contentType(format: AuditExportFormat): String = when (format) {
        AuditExportFormat.CSV -> "text/csv"
        AuditExportFormat.JSONL -> "application/x-ndjson"
    }

    private fun failureCode(exception: Exception): String = when (exception) {
        is AuditExportArtifactStoreUnavailableException -> "ARTIFACT_STORE_UNAVAILABLE"
        else -> "GENERATION_FAILED"
    }

    private fun Exception.wasCausedByLeaseLoss(): Boolean = generateSequence<Throwable>(this) { it.cause }
        .any { it is AuditExportLeaseLostException }

    private companion object {
        const val LEASE_RENEWAL_ROWS = 500L
        val FORMULA_PREFIXES = setOf('=', '+', '-', '@')
    }
}

private class AuditExportLeaseLostException : IllegalStateException()

@Configuration(proxyBeanMethods = false)
@EnableScheduling
internal class AuditExportSchedulingConfiguration
