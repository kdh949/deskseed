package dev.deskseed.aiassistance.internal

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant

internal data class AiReplyAttributionPurgeResult(
    val bindings: Int,
    val attempts: Int,
)

@Service
internal class AiReplyAttributionRetentionStore(
    private val jdbcTemplate: JdbcTemplate,
) {
    @Transactional
    fun purge(now: Instant, limit: Int = DEFAULT_BATCH_SIZE): AiReplyAttributionPurgeResult {
        require(limit in 1..MAX_BATCH_SIZE)
        val cutoff = Timestamp.from(now.minus(RETENTION))
        val attempts = jdbcTemplate.update(
            """
            with candidates as (
                select attempt.comment_id
                from ai_reply_attribution_attempts attempt
                where attempt.created_at <= ?
                  and not exists (
                      select 1 from ai_integration_outbox outbox
                      where outbox.event_type = 'REPLY_SENT'
                        and outbox.usage_comment_id = attempt.comment_id
                        and outbox.status in ('PENDING', 'LEASED', 'DEAD')
                  )
                order by attempt.created_at, attempt.comment_id
                for update of attempt skip locked
                limit ?
            ), deleted_outbox as (
                delete from ai_integration_outbox outbox
                using candidates
                where outbox.event_type = 'REPLY_SENT'
                  and outbox.usage_comment_id = candidates.comment_id
                  and outbox.status = 'DELIVERED'
            )
            delete from ai_reply_attribution_attempts attempt
            using candidates
            where attempt.comment_id = candidates.comment_id
            """.trimIndent(),
            cutoff,
            limit,
        )
        val bindings = jdbcTemplate.update(
            """
            delete from ai_reply_candidate_bindings binding
            using (
                select job_id, candidate_id
                from ai_reply_candidate_bindings
                where last_authorized_at <= ?
                order by last_authorized_at, job_id, candidate_id
                for update skip locked
                limit ?
            ) candidates
            where binding.job_id = candidates.job_id
              and binding.candidate_id = candidates.candidate_id
            """.trimIndent(),
            cutoff,
            limit,
        )
        return AiReplyAttributionPurgeResult(bindings = bindings, attempts = attempts)
    }

    private companion object {
        val RETENTION: Duration = Duration.ofDays(30)
        const val DEFAULT_BATCH_SIZE = 1_000
        const val MAX_BATCH_SIZE = 10_000
    }
}

@Component
internal class AiReplyAttributionRetentionJob(
    private val store: AiReplyAttributionRetentionStore,
    private val clock: Clock,
) {
    @Scheduled(
        fixedDelayString = "\${deskseed.ai.reply-attribution-retention.interval:1h}",
        initialDelayString = "\${deskseed.ai.reply-attribution-retention.initial-delay:10m}",
    )
    fun purgeExpiredMetadata() {
        store.purge(Instant.now(clock))
    }
}
