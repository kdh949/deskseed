package dev.deskseed.outboundmail.internal

import dev.deskseed.outboundmail.OutboundMailAttemptStatus
import dev.deskseed.outboundmail.OutboundMailAttemptView
import dev.deskseed.outboundmail.OutboundMailIntentListQuery
import dev.deskseed.outboundmail.OutboundMailIntentPage
import dev.deskseed.outboundmail.OutboundMailIntentStatus
import dev.deskseed.outboundmail.OutboundMailIntentView
import dev.deskseed.outboundmail.OutboundMailOperationsSummary
import dev.deskseed.outboundmail.OutboundMailTemplate
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * Read model for the ADMIN mail-operations surface. Query projections intentionally omit sender,
 * recipient, subject, body, protected-content columns, command metadata, and provider message IDs.
 */
@Service
internal class JdbcOutboundMailOperationsReader(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
    private val properties: OutboundMailProperties,
    private val cursorCodec: OutboundMailOperationsCursorCodec,
) {
    @Transactional(readOnly = true)
    fun summary(): OutboundMailOperationsSummary {
        val counts = jdbcTemplate.query(
            """
            select status, count(*) as total
            from outbound_mail_intents
            group by status
            """.trimIndent(),
            MapSqlParameterSource(),
        ) { result, _ -> result.getString("status") to result.getLong("total") }
            .toMap()
        val oldestPendingAt = jdbcTemplate.query(
            """
            select min(queued_at) as oldest_pending_at
            from outbound_mail_intents
            where status in ('QUEUED', 'SENDING', 'RETRY_WAIT')
            """.trimIndent(),
            MapSqlParameterSource(),
        ) { result, _ -> result.getTimestamp("oldest_pending_at")?.toInstant() }
            .single()
        return OutboundMailOperationsSummary(
            deliveryEnabled = properties.deliveryEnabled,
            schedulingEnabled = properties.schedulingEnabled,
            transport = properties.transport.uppercase(),
            queuedCount = counts[MailIntentStatus.QUEUED.name] ?: 0,
            sendingCount = counts[MailIntentStatus.SENDING.name] ?: 0,
            retryWaitCount = counts[MailIntentStatus.RETRY_WAIT.name] ?: 0,
            failedCount = counts[MailIntentStatus.FAILED.name] ?: 0,
            sentCount = counts[MailIntentStatus.SENT.name] ?: 0,
            oldestPendingAt = oldestPendingAt,
        )
    }

    @Transactional(readOnly = true)
    fun list(query: OutboundMailIntentListQuery): OutboundMailIntentPage {
        val position = query.cursor?.let { cursorCodec.decode(query, it) }
        val conditions = mutableListOf<String>()
        val parameters = MapSqlParameterSource().addValue("limit", query.limit + 1)
        query.status?.let {
            conditions += "status = :status"
            parameters.addValue("status", it.name)
        }
        position?.let {
            conditions += "(queued_at, id) < (:queuedAt, :intentId)"
            parameters.addValue("queuedAt", Timestamp.from(it.queuedAt))
            parameters.addValue("intentId", it.intentId)
        }
        val whereClause = conditions.takeIf(List<String>::isNotEmpty)
            ?.joinToString("\n  and ", prefix = "where ")
            .orEmpty()
        val rows = jdbcTemplate.query(
            """
            select id, template_key, template_version, status, recipient_address,
                   attempt_count, max_attempts, retry_cycle, manual_retry_count,
                   next_attempt_at, lease_expires_at, last_error_code, queued_at, sent_at, failed_at
            from outbound_mail_intents
            $whereClause
            order by queued_at desc, id desc
            limit :limit
            """.trimIndent(),
            parameters,
        ) { result, _ -> intentView(result) }
        val items = rows.take(query.limit)
        val nextCursor = if (rows.size > query.limit) {
            items.lastOrNull()?.let { cursorCodec.encode(query, OutboundMailOperationsCursor(it.queuedAt, it.id)) }
        } else {
            null
        }
        return OutboundMailIntentPage(items, nextCursor)
    }

    @Transactional(readOnly = true)
    fun get(intentId: UUID): OutboundMailIntentView? {
        val intent = jdbcTemplate.query(
            """
            select id, template_key, template_version, status, recipient_address,
                   attempt_count, max_attempts, retry_cycle, manual_retry_count,
                   next_attempt_at, lease_expires_at, last_error_code, queued_at, sent_at, failed_at
            from outbound_mail_intents
            where id = :intentId
            """.trimIndent(),
            MapSqlParameterSource("intentId", intentId),
        ) { result, _ -> intentView(result) }.singleOrNull() ?: return null
        val attempts = jdbcTemplate.query(
            """
            select attempt_number, retry_cycle, cycle_attempt_number, status,
                   failure_class, failure_code, started_at, finished_at, next_retry_at
            from outbound_mail_attempts
            where intent_id = :intentId
            order by attempt_number asc
            """.trimIndent(),
            MapSqlParameterSource("intentId", intentId),
        ) { result, _ ->
            OutboundMailAttemptView(
                attemptNumber = result.getInt("attempt_number"),
                retryCycle = result.getInt("retry_cycle"),
                cycleAttemptNumber = result.getInt("cycle_attempt_number"),
                status = OutboundMailAttemptStatus.valueOf(result.getString("status")),
                failureClass = safeFailureClass(result.getString("failure_class")),
                failureCode = result.getString("failure_code")?.let(::safeMailFailureCode),
                startedAt = result.getTimestamp("started_at").toInstant(),
                finishedAt = result.getTimestamp("finished_at")?.toInstant(),
                nextRetryAt = result.getTimestamp("next_retry_at")?.toInstant(),
            )
        }
        return intent.copy(attempts = attempts)
    }

    private fun intentView(result: java.sql.ResultSet): OutboundMailIntentView = OutboundMailIntentView(
        id = result.getObject("id", UUID::class.java),
        template = OutboundMailTemplate.valueOf(result.getString("template_key")),
        templateVersion = result.getInt("template_version"),
        status = OutboundMailIntentStatus.valueOf(result.getString("status")),
        recipientMasked = maskRecipient(result.getString("recipient_address")),
        attemptCount = result.getInt("attempt_count"),
        maxAttempts = result.getInt("max_attempts"),
        retryCycle = result.getInt("retry_cycle"),
        manualRetryCount = result.getInt("manual_retry_count"),
        nextAttemptAt = result.getTimestamp("next_attempt_at")?.toInstant(),
        leaseExpiresAt = result.getTimestamp("lease_expires_at")?.toInstant(),
        lastErrorCode = result.getString("last_error_code")?.let(::safeMailFailureCode),
        queuedAt = result.getTimestamp("queued_at").toInstant(),
        sentAt = result.getTimestamp("sent_at")?.toInstant(),
        failedAt = result.getTimestamp("failed_at")?.toInstant(),
    )

    private fun maskRecipient(value: String): String {
        val domain = value.substringAfter('@', missingDelimiterValue = "")
        return if (domain.isBlank()) "***" else "***@$domain"
    }

    private fun safeFailureClass(value: String?): String? = value?.let {
        it.takeIf { candidate ->
            candidate.length in 1..40 && candidate.all { char -> char.isUpperCase() || char.isDigit() || char == '_' }
        } ?: "UNKNOWN"
    }
}
