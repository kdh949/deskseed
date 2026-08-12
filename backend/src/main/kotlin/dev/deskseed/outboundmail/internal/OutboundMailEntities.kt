package dev.deskseed.outboundmail.internal

import dev.deskseed.outboundmail.OutboundMailTemplate
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

internal enum class MailIntentStatus { QUEUED, SENDING, RETRY_WAIT, SENT, FAILED }
internal enum class MailAttemptStatus { IN_PROGRESS, SUCCEEDED, RETRYABLE_FAILED, PERMANENT_FAILED, ABANDONED }

@Entity
@Table(name = "outbound_mail_intents")
internal class OutboundMailIntentEntity(
    @Id val id: UUID,
    @Column(name = "idempotency_key", nullable = false, length = 200) val idempotencyKey: String,
    @Column(name = "stable_message_id", nullable = false, length = 200) val stableMessageId: String,
    @Enumerated(EnumType.STRING)
    @Column(name = "template_key", nullable = false, length = 60) val templateKey: OutboundMailTemplate,
    @Column(name = "template_version", nullable = false) val templateVersion: Int,
    @Column(name = "sender_address", nullable = false, length = 254) val senderAddress: String,
    @Column(name = "recipient_address", nullable = false, length = 254) val recipientAddress: String,
    @Column(name = "subject", nullable = false, length = 200) val subject: String,
    @Column(name = "text_body", nullable = false, columnDefinition = "text") val textBody: String,
    @Column(name = "ticket_id") val ticketId: UUID?,
    @Column(name = "comment_id") val commentId: UUID?,
    @Column(name = "customer_id") val customerId: UUID?,
    @Column(name = "actor_type", nullable = false, length = 30) val actorType: String,
    @Column(name = "actor_id") val actorId: UUID?,
    @Column(name = "source", nullable = false, length = 40) val source: String,
    @Column(name = "request_id", nullable = false, length = 100) val requestId: String,
    @Column(name = "correlation_id", nullable = false, length = 100) val correlationId: String,
    @Column(name = "command_id", nullable = false, length = 100) val commandId: String,
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30) var status: MailIntentStatus,
    @Column(name = "attempt_count", nullable = false) var attemptCount: Int,
    @Column(name = "cycle_attempt_count", nullable = false) var cycleAttemptCount: Int,
    @Column(name = "max_attempts", nullable = false) var maxAttempts: Int,
    @Column(name = "retry_cycle", nullable = false) var retryCycle: Int,
    @Column(name = "manual_retry_count", nullable = false) var manualRetryCount: Int,
    @Column(name = "next_attempt_at") var nextAttemptAt: Instant?,
    @Column(name = "lease_expires_at") var leaseExpiresAt: Instant?,
    @Column(name = "last_error_code", length = 80) var lastErrorCode: String?,
    @Column(name = "queued_at", nullable = false, updatable = false) val queuedAt: Instant,
    @Column(name = "sent_at") var sentAt: Instant?,
    @Column(name = "failed_at") var failedAt: Instant?,
    @Version @Column(name = "version", nullable = false) var version: Long = 0,
)

@Entity
@Table(name = "outbound_mail_attempts")
internal class OutboundMailAttemptEntity(
    @Id val id: UUID,
    @Column(name = "intent_id", nullable = false) val intentId: UUID,
    @Column(name = "attempt_number", nullable = false) val attemptNumber: Int,
    @Column(name = "retry_cycle", nullable = false) val retryCycle: Int,
    @Column(name = "cycle_attempt_number", nullable = false) val cycleAttemptNumber: Int,
    @Column(name = "provider", nullable = false, length = 40) val provider: String,
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30) var status: MailAttemptStatus,
    @Column(name = "provider_message_id", length = 200) var providerMessageId: String?,
    @Column(name = "failure_class", length = 40) var failureClass: String?,
    @Column(name = "failure_code", length = 80) var failureCode: String?,
    @Column(name = "started_at", nullable = false, updatable = false) val startedAt: Instant,
    @Column(name = "finished_at") var finishedAt: Instant?,
    @Column(name = "next_retry_at") var nextRetryAt: Instant?,
)

@Entity
@Table(name = "outbound_mail_delivery_events")
internal class OutboundMailDeliveryEventEntity(
    @Id val id: UUID,
    @Column(name = "intent_id", nullable = false) val intentId: UUID,
    @Column(name = "attempt_id") val attemptId: UUID?,
    @Column(name = "event_type", nullable = false, length = 60) val eventType: String,
    @Column(name = "actor_type", nullable = false, length = 30) val actorType: String,
    @Column(name = "actor_id") val actorId: UUID?,
    @Column(name = "source", nullable = false, length = 40) val source: String,
    @Column(name = "request_id", nullable = false, length = 100) val requestId: String,
    @Column(name = "correlation_id", nullable = false, length = 100) val correlationId: String,
    @Column(name = "reason_code", length = 80) val reasonCode: String?,
    @Column(name = "reason_text", length = 500) val reasonText: String?,
    @Column(name = "occurred_at", nullable = false, updatable = false) val occurredAt: Instant,
)
