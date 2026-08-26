package dev.deskseed.outboundmail

import dev.deskseed.foundation.ActorRef
import dev.deskseed.foundation.CommandContext
import java.time.Instant
import java.util.UUID

enum class OutboundMailTemplate(val version: Int) {
    CUSTOMER_MAGIC_LINK(1),
    CUSTOMER_REGISTRATION_VERIFICATION(1),
    CUSTOMER_PASSWORD_RESET(1),
    REQUEST_RECEIVED(1),
    PUBLIC_AGENT_REPLY(1),
}

data class MailRecipient(val address: String)

enum class RenderedMailSensitivity {
    STANDARD,
    PROTECTED,
}

sealed interface OutboundMailContent {
    val template: OutboundMailTemplate
    val renderedSensitivity: RenderedMailSensitivity
}

data class MagicLinkMail(val magicLink: String) : OutboundMailContent {
    override val template = OutboundMailTemplate.CUSTOMER_MAGIC_LINK
    override val renderedSensitivity = RenderedMailSensitivity.PROTECTED

    override fun toString(): String = "[PROTECTED CUSTOMER MAGIC LINK MAIL]"
}

data class RegistrationVerificationMail(val verificationLink: String) : OutboundMailContent {
    override val template = OutboundMailTemplate.CUSTOMER_REGISTRATION_VERIFICATION
    override val renderedSensitivity = RenderedMailSensitivity.PROTECTED

    override fun toString(): String = "[PROTECTED CUSTOMER REGISTRATION VERIFICATION MAIL]"
}

data class PasswordResetMail(val resetLink: String) : OutboundMailContent {
    override val template = OutboundMailTemplate.CUSTOMER_PASSWORD_RESET
    override val renderedSensitivity = RenderedMailSensitivity.PROTECTED

    override fun toString(): String = "[PROTECTED CUSTOMER PASSWORD RESET MAIL]"
}

data class RequestReceivedMail(
    val ticketNumber: Long,
    val requestAccessToken: String,
) : OutboundMailContent {
    override val template = OutboundMailTemplate.REQUEST_RECEIVED
    override val renderedSensitivity = RenderedMailSensitivity.PROTECTED
}

data class PublicAgentReplyMail(
    val ticketNumber: Long,
    val publicBody: String,
    val requestAccessToken: String,
) : OutboundMailContent {
    override val template = OutboundMailTemplate.PUBLIC_AGENT_REPLY
    override val renderedSensitivity = RenderedMailSensitivity.PROTECTED
}

data class OutboundMailIntent(
    val idempotencyKey: String,
    val recipient: MailRecipient,
    val content: OutboundMailContent,
    val ticketId: UUID? = null,
    val commentId: UUID? = null,
    val customerId: UUID? = null,
    val actor: ActorRef,
    val context: CommandContext,
)

interface OutboundMailPort {
    /** Persists a provider-neutral delivery intent in the caller's transaction. */
    fun enqueue(intent: OutboundMailIntent): UUID
}

data class ManualMailRetryCommand(
    val intentId: UUID,
    val actor: ActorRef,
    val context: CommandContext,
    val reason: String,
    val actorDisplayName: String? = null,
)

/**
 * Deliberately safe projection for the ADMIN mail-operations surface. It never carries a
 * recipient address, rendered body, protected-content material, provider response, or command context.
 */
enum class OutboundMailIntentStatus {
    QUEUED,
    SENDING,
    RETRY_WAIT,
    SENT,
    FAILED,
}

enum class OutboundMailAttemptStatus {
    IN_PROGRESS,
    SUCCEEDED,
    RETRYABLE_FAILED,
    PERMANENT_FAILED,
    ABANDONED,
}

data class OutboundMailIntentListQuery(
    val status: OutboundMailIntentStatus? = null,
    val cursor: String? = null,
    val limit: Int = 50,
) {
    init {
        require(limit in 1..100) { "Outbound mail intent page size must be between 1 and 100" }
        require(cursor == null || cursor.length <= 2_000) { "Outbound mail intent cursor is too long" }
    }
}

data class OutboundMailAttemptView(
    val attemptNumber: Int,
    val retryCycle: Int,
    val cycleAttemptNumber: Int,
    val status: OutboundMailAttemptStatus,
    val failureClass: String?,
    val failureCode: String?,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val nextRetryAt: Instant?,
)

data class OutboundMailIntentView(
    val id: UUID,
    val template: OutboundMailTemplate,
    val templateVersion: Int,
    val status: OutboundMailIntentStatus,
    val recipientMasked: String,
    val attemptCount: Int,
    val maxAttempts: Int,
    val retryCycle: Int,
    val manualRetryCount: Int,
    val nextAttemptAt: Instant?,
    val leaseExpiresAt: Instant?,
    val lastErrorCode: String?,
    val queuedAt: Instant,
    val sentAt: Instant?,
    val failedAt: Instant?,
    val attempts: List<OutboundMailAttemptView> = emptyList(),
)

data class OutboundMailIntentPage(
    val items: List<OutboundMailIntentView>,
    val nextCursor: String?,
)

data class OutboundMailOperationsSummary(
    val deliveryEnabled: Boolean,
    val schedulingEnabled: Boolean,
    val transport: String,
    val queuedCount: Long,
    val sendingCount: Long,
    val retryWaitCount: Long,
    val failedCount: Long,
    val sentCount: Long,
    val oldestPendingAt: Instant?,
)

interface OutboundMailOperations {
    /** Returns bounded operational metadata only; it is safe to render to an ADMIN user. */
    fun summary(): OutboundMailOperationsSummary

    /** Lists intentionally masked mail intents with a signed, filter-bound cursor. */
    fun listIntents(query: OutboundMailIntentListQuery): OutboundMailIntentPage

    /** Returns a masked intent and safe attempt lifecycle fields, never content or provider output. */
    fun getIntent(intentId: UUID): OutboundMailIntentView

    /** Re-queues the same terminal intent; it never creates a second business comment or intent. */
    fun retryTerminal(command: ManualMailRetryCommand): OutboundMailIntentView
}

class OutboundMailIntentConflictException : RuntimeException()

class OutboundMailIntentNotFoundException : RuntimeException()

class OutboundMailRetryInvalidException : RuntimeException()
