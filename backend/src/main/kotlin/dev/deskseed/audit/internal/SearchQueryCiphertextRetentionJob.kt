package dev.deskseed.audit.internal

import dev.deskseed.audit.AdminSecurityAudit
import dev.deskseed.audit.AdminSecurityAuditWriter
import dev.deskseed.audit.AdminSecurityOutcome
import dev.deskseed.foundation.ActorType
import dev.deskseed.foundation.RequestSource
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Service
internal class SearchQueryCiphertextRetentionJob(
    private val jdbcTemplate: JdbcTemplate,
    private val auditWriter: AdminSecurityAuditWriter,
    private val properties: SearchQueryAuditProperties,
) {
    @Scheduled(
        fixedDelayString = "\${deskseed.audit.access.retention-job-interval:1h}",
        initialDelayString = "\${deskseed.audit.access.retention-job-initial-delay:10m}",
    )
    @Transactional
    fun purgeExpiredScheduled() {
        if (properties.enabled) purgeExpiredBatch(Instant.now())
    }

    @Transactional
    fun purgeExpired(now: Instant): Int = purgeExpiredBatch(now)

    private fun purgeExpiredBatch(now: Instant): Int {
        val deletedCount = jdbcTemplate.queryForObject(
            """
            with eligible as (
                select access_event_id
                from search_audit_query_ciphertexts
                where expires_at <= ?
                order by expires_at, access_event_id
                limit ?
                for update skip locked
            ), deleted as (
                delete from search_audit_query_ciphertexts ciphertext
                using eligible
                where ciphertext.access_event_id = eligible.access_event_id
                returning ciphertext.access_event_id
            )
            select count(*) as deleted_count from deleted
            """.trimIndent(),
            { resultSet, _ -> resultSet.getInt("deleted_count") },
            Timestamp.from(now),
            properties.retentionBatchSize,
        )
        val executionId = UUID.randomUUID().toString()
        auditWriter.append(
            AdminSecurityAudit(
                eventType = "RETENTION_JOB_EXECUTED",
                actorType = ActorType.SYSTEM,
                actorId = null,
                actorDisplaySnapshot = "Deskseed retention job",
                source = RequestSource.SYSTEM_JOB,
                targetType = "SEARCH_QUERY_CIPHERTEXT",
                targetId = null,
                outcome = AdminSecurityOutcome.SUCCEEDED,
                requestId = executionId,
                correlationId = executionId,
                metadata = mapOf(
                    "policyVersion" to POLICY_VERSION,
                    "eligibleBefore" to now.toString(),
                    "batchLimit" to properties.retentionBatchSize.toString(),
                    "deletedCount" to deletedCount.toString(),
                ),
                occurredAt = now,
            ),
        )
        return deletedCount
    }

    private companion object {
        const val POLICY_VERSION = "search-query-ciphertext-v1"
    }
}

@Configuration(proxyBeanMethods = false)
@EnableScheduling
internal class AuditRetentionSchedulingConfiguration
