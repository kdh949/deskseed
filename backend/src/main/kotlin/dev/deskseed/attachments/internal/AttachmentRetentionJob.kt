package dev.deskseed.attachments.internal

import dev.deskseed.attachments.AttachmentCleanupService
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

/** Bounded, lock-skipping cleanup so an unavailable private store never silently marks objects deleted. */
@Component
internal class AttachmentRetentionJob(
    private val cleanupService: AttachmentCleanupService,
    private val clock: Clock,
) {
    @Scheduled(
        fixedDelayString = "\${deskseed.attachments.cleanup-interval:1h}",
        initialDelayString = "\${deskseed.attachments.cleanup-initial-delay:10m}",
    )
    fun purgeExpiredScheduled() {
        cleanupService.purgeExpired(Instant.now(clock))
    }

    fun purgeExpired(now: Instant): Int = cleanupService.purgeExpired(now)
}
