package dev.deskseed.outboundmail.internal

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.DependsOn
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

internal data class MailTransportMessage(
    val intentId: UUID,
    val idempotencyKey: String,
    val stableMessageId: String,
    val fromAddress: String,
    val recipientAddress: String,
    val subject: String,
    val textBody: String,
)

internal data class MailTransportReceipt(
    val provider: String,
    val providerMessageId: String,
)

internal class MailTransportException(
    val retryable: Boolean,
    val failureCode: String,
    cause: Throwable? = null,
) : RuntimeException(failureCode, cause)

internal fun safeMailFailureCode(value: String): String =
    value.takeIf { it.length in 1..80 && it.all { char -> char.isUpperCase() || char.isDigit() || char == '_' } }
        ?: "MAIL_DELIVERY_FAILURE"

internal fun interface MailTransport {
    fun send(message: MailTransportMessage): MailTransportReceipt
}

internal data class ClaimedMail(
    val attemptId: UUID,
    val attemptNumber: Int,
    val cycleAttemptNumber: Int,
    val message: MailTransportMessage,
)

internal sealed interface MailClaimResult {
    data class Deliverable(val claim: ClaimedMail) : MailClaimResult

    data class ProtectedContentUnreadable(
        val intentId: UUID,
        val attemptNumber: Int,
    ) : MailClaimResult
}

@Service
internal class MailDeliveryClaimService(
    private val intentRepository: OutboundMailIntentRepository,
    private val attemptRepository: OutboundMailAttemptRepository,
    private val eventRepository: OutboundMailDeliveryEventRepository,
    private val properties: OutboundMailProperties,
    private val protectedContentCipher: ProtectedMailContentCipher,
    private val clock: Clock,
) {
    @Transactional
    fun claimNext(): MailClaimResult? {
        while (true) {
            val now = Instant.now(clock)
            val intent = intentRepository.lockNextDue(now) ?: return null
            if (intent.status == MailIntentStatus.SENDING) {
                abandonExpiredAttempt(intent, now)
                if (intent.attemptCount >= intent.maxAttempts) {
                    markTerminal(intent, now, "WORKER_LEASE_EXPIRED")
                    continue
                }
            }

            val attemptId = UUID.randomUUID()
            intent.status = MailIntentStatus.SENDING
            intent.attemptCount += 1
            intent.cycleAttemptCount += 1
            intent.nextAttemptAt = null
            intent.leaseExpiresAt = now.plus(properties.leaseDuration)
            intent.lastErrorCode = null
            intentRepository.saveAndFlush(intent)
            val attempt = OutboundMailAttemptEntity(
                id = attemptId,
                intentId = intent.id,
                attemptNumber = intent.attemptCount,
                retryCycle = intent.retryCycle,
                cycleAttemptNumber = intent.cycleAttemptCount,
                provider = properties.transport.uppercase(),
                status = MailAttemptStatus.IN_PROGRESS,
                providerMessageId = null,
                failureClass = null,
                failureCode = null,
                startedAt = now,
                finishedAt = null,
                nextRetryAt = null,
            )
            attemptRepository.saveAndFlush(attempt)
            eventRepository.saveAndFlush(
                systemDeliveryEvent(intent, attemptId, "MAIL_ATTEMPT_STARTED", now),
            )
            val textBody = try {
                resolveTextBody(intent)
            } catch (_: ProtectedMailContentUnreadableException) {
                markProtectedContentUnreadable(intent, attempt, now)
                return MailClaimResult.ProtectedContentUnreadable(intent.id, intent.attemptCount)
            }
            return MailClaimResult.Deliverable(
                ClaimedMail(
                    attemptId = attemptId,
                    attemptNumber = intent.attemptCount,
                    cycleAttemptNumber = intent.cycleAttemptCount,
                    message = MailTransportMessage(
                        intentId = intent.id,
                        idempotencyKey = intent.idempotencyKey,
                        stableMessageId = intent.stableMessageId,
                        fromAddress = intent.senderAddress,
                        recipientAddress = intent.recipientAddress,
                        subject = intent.subject,
                        textBody = textBody,
                    ),
                ),
            )
        }
    }

    private fun resolveTextBody(intent: OutboundMailIntentEntity): String {
        val ciphertext = intent.protectedBodyCiphertext
        val nonce = intent.protectedBodyNonce
        val keyVersion = intent.protectedBodyKeyVersion
        if (ciphertext == null && nonce == null && keyVersion == null) return intent.textBody
        return protectedContentCipher.decrypt(
            ProtectedMailContent(
                ciphertext = ciphertext ?: throw ProtectedMailContentUnreadableException(),
                nonce = nonce ?: throw ProtectedMailContentUnreadableException(),
                keyVersion = keyVersion ?: throw ProtectedMailContentUnreadableException(),
            ),
            intent.id,
        )
    }

    private fun markProtectedContentUnreadable(
        intent: OutboundMailIntentEntity,
        attempt: OutboundMailAttemptEntity,
        now: Instant,
    ) {
        val code = "PROTECTED_CONTENT_UNREADABLE"
        attempt.status = MailAttemptStatus.PERMANENT_FAILED
        attempt.failureClass = "PROTECTED_CONTENT"
        attempt.failureCode = code
        attempt.finishedAt = now
        attemptRepository.saveAndFlush(attempt)
        intent.status = MailIntentStatus.FAILED
        intent.nextAttemptAt = null
        intent.leaseExpiresAt = null
        intent.failedAt = now
        intent.lastErrorCode = code
        intentRepository.saveAndFlush(intent)
        eventRepository.saveAndFlush(
            systemDeliveryEvent(intent, attempt.id, "MAIL_ATTEMPT_FAILED", now, code),
        )
        eventRepository.saveAndFlush(
            systemDeliveryEvent(intent, attempt.id, "MAIL_TERMINAL_FAILED", now, code),
        )
    }

    private fun abandonExpiredAttempt(intent: OutboundMailIntentEntity, now: Instant) {
        val previous = attemptRepository.findFirstByIntentIdAndStatusOrderByAttemptNumberDesc(
            intent.id,
            MailAttemptStatus.IN_PROGRESS,
        ) ?: return
        previous.status = MailAttemptStatus.ABANDONED
        previous.failureClass = "WORKER_LEASE"
        previous.failureCode = "WORKER_LEASE_EXPIRED"
        previous.finishedAt = now
        attemptRepository.saveAndFlush(previous)
        eventRepository.saveAndFlush(
            systemDeliveryEvent(
                intent,
                previous.id,
                "MAIL_ATTEMPT_ABANDONED",
                now,
                "WORKER_LEASE_EXPIRED",
            ),
        )
    }

    private fun markTerminal(intent: OutboundMailIntentEntity, now: Instant, code: String) {
        intent.status = MailIntentStatus.FAILED
        intent.nextAttemptAt = null
        intent.leaseExpiresAt = null
        intent.failedAt = now
        intent.lastErrorCode = code
        intentRepository.saveAndFlush(intent)
        eventRepository.saveAndFlush(
            systemDeliveryEvent(intent, null, "MAIL_TERMINAL_FAILED", now, code),
        )
    }
}

@Service
internal class MailDeliveryFinalizer(
    private val intentRepository: OutboundMailIntentRepository,
    private val attemptRepository: OutboundMailAttemptRepository,
    private val eventRepository: OutboundMailDeliveryEventRepository,
    private val retryPolicy: MailRetryPolicy,
    private val clock: Clock,
) {
    @Transactional
    fun succeeded(claim: ClaimedMail, receipt: MailTransportReceipt) {
        val now = Instant.now(clock)
        val intent = requireClaimedIntent(claim)
        val attempt = requireAttempt(claim)
        attempt.status = MailAttemptStatus.SUCCEEDED
        attempt.providerMessageId = bounded(receipt.providerMessageId, 200, "PROVIDER_MESSAGE_ID")
        attempt.finishedAt = now
        attemptRepository.saveAndFlush(attempt)
        intent.status = MailIntentStatus.SENT
        intent.nextAttemptAt = null
        intent.leaseExpiresAt = null
        intent.sentAt = now
        intent.failedAt = null
        intent.lastErrorCode = null
        intentRepository.saveAndFlush(intent)
        eventRepository.saveAndFlush(
            systemDeliveryEvent(intent, attempt.id, "MAIL_ATTEMPT_SUCCEEDED", now),
        )
    }

    @Transactional
    fun failed(claim: ClaimedMail, failure: MailTransportException) {
        val now = Instant.now(clock)
        val intent = requireClaimedIntent(claim)
        val attempt = requireAttempt(claim)
        val code = safeMailFailureCode(failure.failureCode)
        val nextDelay = if (failure.retryable) retryPolicy.nextDelay(intent.cycleAttemptCount) else null
        intent.leaseExpiresAt = null
        if (nextDelay != null && intent.attemptCount < intent.maxAttempts) {
            val nextAttemptAt = now.plus(nextDelay)
            attempt.status = MailAttemptStatus.RETRYABLE_FAILED
            attempt.nextRetryAt = nextAttemptAt
            intent.status = MailIntentStatus.RETRY_WAIT
            intent.nextAttemptAt = nextAttemptAt
            intent.failedAt = null
        } else {
            attempt.status = MailAttemptStatus.PERMANENT_FAILED
            intent.status = MailIntentStatus.FAILED
            intent.nextAttemptAt = null
            intent.failedAt = now
        }
        attempt.failureClass = if (failure.retryable) "TRANSIENT" else "PERMANENT"
        attempt.failureCode = code
        attempt.finishedAt = now
        attemptRepository.save(attempt)
        intent.lastErrorCode = code
        intentRepository.saveAndFlush(intent)
        eventRepository.saveAndFlush(
            systemDeliveryEvent(intent, attempt.id, "MAIL_ATTEMPT_FAILED", now, code),
        )
        if (intent.status == MailIntentStatus.FAILED) {
            eventRepository.saveAndFlush(
                systemDeliveryEvent(intent, attempt.id, "MAIL_TERMINAL_FAILED", now, code),
            )
        }
    }

    private fun requireClaimedIntent(claim: ClaimedMail): OutboundMailIntentEntity {
        val intent = intentRepository.lockById(claim.message.intentId)
            ?: error("Claimed outbound mail intent disappeared")
        check(intent.status == MailIntentStatus.SENDING && intent.attemptCount == claim.attemptNumber) {
            "Outbound mail claim is no longer current"
        }
        return intent
    }

    private fun requireAttempt(claim: ClaimedMail): OutboundMailAttemptEntity {
        val attempt = attemptRepository.findById(claim.attemptId).orElseThrow()
        check(attempt.status == MailAttemptStatus.IN_PROGRESS) { "Outbound mail attempt is no longer current" }
        return attempt
    }

    private fun bounded(value: String, max: Int, fallback: String): String =
        value.takeIf { it.length in 1..max && it.none(Char::isISOControl) } ?: fallback
}

@Component
@DependsOn("mailDeliveryConfigurationValidator")
@ConditionalOnProperty(prefix = "deskseed.mail", name = ["delivery-enabled"], havingValue = "true")
internal class MailDeliveryWorker(
    private val claimService: MailDeliveryClaimService,
    private val finalizer: MailDeliveryFinalizer,
    private val transport: MailTransport,
    private val properties: OutboundMailProperties,
    meterRegistry: MeterRegistry,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val succeededCounter = Counter.builder("deskseed.mail.delivery.succeeded").register(meterRegistry)
    private val failedCounter = Counter.builder("deskseed.mail.delivery.failed").register(meterRegistry)

    fun runDueBatch(): Int {
        var processed = 0
        repeat(properties.batchSize.coerceIn(1, 1_000)) {
            val result = claimService.claimNext() ?: return processed
            if (result is MailClaimResult.ProtectedContentUnreadable) {
                failedCounter.increment()
                logger.warn(
                    "Outbound mail delivery failed intentId={} attempt={} code=PROTECTED_CONTENT_UNREADABLE",
                    result.intentId,
                    result.attemptNumber,
                )
                processed += 1
                return@repeat
            }
            val claim = (result as MailClaimResult.Deliverable).claim
            try {
                val receipt = transport.send(claim.message)
                finalizer.succeeded(claim, receipt)
                succeededCounter.increment()
            } catch (failure: MailTransportException) {
                val safeFailure = MailTransportException(
                    retryable = failure.retryable,
                    failureCode = safeMailFailureCode(failure.failureCode),
                    cause = failure,
                )
                finalizer.failed(claim, safeFailure)
                failedCounter.increment()
                logger.warn(
                    "Outbound mail delivery failed intentId={} attempt={} code={}",
                    claim.message.intentId,
                    claim.attemptNumber,
                    safeFailure.failureCode,
                )
            } catch (failure: RuntimeException) {
                val safeFailure = MailTransportException(true, "UNEXPECTED_TRANSPORT_FAILURE", failure)
                finalizer.failed(claim, safeFailure)
                failedCounter.increment()
                logger.warn(
                    "Outbound mail delivery failed intentId={} attempt={} code=UNEXPECTED_TRANSPORT_FAILURE",
                    claim.message.intentId,
                    claim.attemptNumber,
                )
            }
            processed += 1
        }
        return processed
    }
}

@Component
@ConditionalOnProperty(
    prefix = "deskseed.mail",
    name = ["delivery-enabled", "scheduling-enabled"],
    havingValue = "true",
)
internal class MailDeliveryScheduler(private val worker: MailDeliveryWorker) {
    // Fixed delay is measured from completion, avoiding overlapping work from one scheduler thread.
    // Source: https://docs.spring.io/spring-framework/reference/integration/scheduling.html#scheduling-annotation-support-scheduled
    @Scheduled(
        fixedDelayString = "\${deskseed.mail.worker-fixed-delay:5s}",
        initialDelayString = "\${deskseed.mail.worker-initial-delay:5s}",
    )
    fun deliverDue() {
        worker.runDueBatch()
    }
}

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
    prefix = "deskseed.mail",
    name = ["delivery-enabled", "scheduling-enabled"],
    havingValue = "true",
)
internal class OutboundMailSchedulingConfiguration

@Component
internal class OutboundMailBacklogMetrics(
    intentRepository: OutboundMailIntentRepository,
    meterRegistry: MeterRegistry,
) {
    init {
        Gauge.builder("deskseed.mail.outbox.backlog") {
            intentRepository.countByStatusIn(
                listOf(MailIntentStatus.QUEUED, MailIntentStatus.RETRY_WAIT, MailIntentStatus.SENDING),
            )
        }.register(meterRegistry)
    }
}
