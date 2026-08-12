package dev.deskseed.platformapi.internal

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

@Service
internal class PlatformIdempotencyRetentionJob(
    private val store: PlatformIdempotencyStore,
    private val clock: Clock,
    meterRegistry: MeterRegistry,
    @Value("\${deskseed.platform.idempotency.cleanup-batch-size:500}") private val batchSize: Int,
) {
    private val deletedCounter = Counter.builder("deskseed.platform.idempotency.cleanup.deleted")
        .description("Deleted expired Platform idempotency receipts")
        .register(meterRegistry)
    private val failureCounter = Counter.builder("deskseed.platform.idempotency.cleanup.failures")
        .description("Failed Platform idempotency cleanup executions")
        .register(meterRegistry)
    private val backlogAgeSeconds = AtomicLong()

    init {
        require(batchSize > 0) { "Platform idempotency cleanup batch size must be positive" }
        Gauge.builder("deskseed.platform.idempotency.cleanup.backlog.age", backlogAgeSeconds) { it.get().toDouble() }
            .description("Age in seconds of the oldest expired Platform idempotency receipt")
            .baseUnit("seconds")
            .register(meterRegistry)
    }

    @Scheduled(
        fixedDelayString = "\${deskseed.platform.idempotency.cleanup-interval:1h}",
        initialDelayString = "\${deskseed.platform.idempotency.cleanup-initial-delay:10m}",
    )
    @Transactional
    fun purgeExpiredScheduled() {
        runCleanup(Instant.now(clock))
    }

    @Transactional
    fun purgeExpired(now: Instant): PlatformIdempotencyCleanupResult = runCleanup(now)

    private fun runCleanup(now: Instant): PlatformIdempotencyCleanupResult = try {
        store.deleteExpiredBatch(now, batchSize).also { result ->
            deletedCounter.increment(result.deletedCount.toDouble())
            backlogAgeSeconds.set(result.backlogAgeSeconds)
        }
    } catch (failure: RuntimeException) {
        failureCounter.increment()
        throw failure
    }
}
