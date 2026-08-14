package dev.deskseed.outboundmail

import dev.deskseed.foundation.ActorRef
import dev.deskseed.foundation.CommandContext
import java.util.UUID

enum class OutboundMailTemplate(val version: Int) {
    CUSTOMER_MAGIC_LINK(1),
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
)

interface OutboundMailOperations {
    /** Re-queues the same terminal intent; it never creates a second business comment or intent. */
    fun retryTerminal(command: ManualMailRetryCommand)
}

class OutboundMailIntentConflictException : RuntimeException()

class OutboundMailRetryInvalidException(message: String) : RuntimeException(message)
