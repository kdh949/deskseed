package dev.deskseed.outboundmail.internal

import dev.deskseed.foundation.ActorRef
import dev.deskseed.foundation.ActorType
import dev.deskseed.foundation.CommandContext
import dev.deskseed.foundation.RequestSource
import dev.deskseed.outboundmail.ManualMailRetryCommand
import dev.deskseed.outboundmail.OutboundMailIntent
import dev.deskseed.outboundmail.OutboundMailIntentConflictException
import dev.deskseed.outboundmail.OutboundMailOperations
import dev.deskseed.outboundmail.OutboundMailPort
import dev.deskseed.outboundmail.OutboundMailRetryInvalidException
import dev.deskseed.outboundmail.MagicLinkMail
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
internal class JpaOutboundMailService(
    private val intentRepository: OutboundMailIntentRepository,
    private val eventRepository: OutboundMailDeliveryEventRepository,
    private val jdbcTemplate: JdbcTemplate,
    private val renderer: MailTemplateRenderer,
    private val retryPolicy: MailRetryPolicy,
    private val protectedContentCipher: ProtectedMailContentCipher,
    private val clock: Clock,
) : OutboundMailPort, OutboundMailOperations {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun enqueue(intent: OutboundMailIntent): UUID {
        val rendered = renderer.render(intent)
        jdbcTemplate.queryForObject(
            "select pg_advisory_xact_lock(hashtextextended(?, 0))",
            { _, _ -> Unit },
            intent.idempotencyKey,
        )
        intentRepository.findByIdempotencyKey(intent.idempotencyKey)?.let { existing ->
            if (!existing.matches(intent, rendered)) throw OutboundMailIntentConflictException()
            return existing.id
        }

        val now = Instant.now(clock)
        val intentId = UUID.randomUUID()
        val protectedBody = if (intent.content is MagicLinkMail) {
            protectedContentCipher.encrypt(rendered.textBody, intentId)
        } else {
            null
        }
        val entity = OutboundMailIntentEntity(
            id = intentId,
            idempotencyKey = intent.idempotencyKey,
            stableMessageId = "<deskseed-$intentId@outbound.deskseed.local>",
            templateKey = rendered.template,
            templateVersion = rendered.templateVersion,
            senderAddress = rendered.fromAddress,
            recipientAddress = rendered.recipient,
            subject = rendered.subject,
            textBody = protectedBody?.let { PROTECTED_BODY_PLACEHOLDER } ?: rendered.textBody,
            protectedBodyCiphertext = protectedBody?.ciphertext,
            protectedBodyNonce = protectedBody?.nonce,
            protectedBodyKeyVersion = protectedBody?.keyVersion,
            ticketId = intent.ticketId,
            commentId = intent.commentId,
            customerId = intent.customerId,
            actorType = intent.actor.actorType.name,
            actorId = intent.actor.actorId,
            source = intent.context.source.name,
            requestId = intent.context.requestId,
            correlationId = intent.context.correlationId,
            commandId = intent.context.commandId,
            status = MailIntentStatus.QUEUED,
            attemptCount = 0,
            cycleAttemptCount = 0,
            maxAttempts = retryPolicy.maxAttempts,
            retryCycle = 0,
            manualRetryCount = 0,
            nextAttemptAt = now,
            leaseExpiresAt = null,
            lastErrorCode = null,
            queuedAt = now,
            sentAt = null,
            failedAt = null,
        )
        intentRepository.saveAndFlush(entity)
        eventRepository.saveAndFlush(
            deliveryEvent(
                intentId = intentId,
                attemptId = null,
                eventType = "MAIL_QUEUED",
                actor = intent.actor,
                context = intent.context,
                now = now,
            ),
        )
        return intentId
    }

    @Transactional
    override fun retryTerminal(command: ManualMailRetryCommand) {
        require(command.actor.actorType == ActorType.STAFF || command.actor.actorType == ActorType.SYSTEM) {
            "manual retry requires a staff or system actor"
        }
        val reason = command.reason.trim()
        require(reason.isNotEmpty() && reason.length <= 500 && reason.none(Char::isISOControl)) {
            "manual retry reason is invalid"
        }
        val intent = intentRepository.lockById(command.intentId)
            ?: throw OutboundMailRetryInvalidException("Outbound mail intent was not found")
        if (intent.status != MailIntentStatus.FAILED) {
            throw OutboundMailRetryInvalidException("Only a terminal failed intent can be retried")
        }
        val now = Instant.now(clock)
        intent.status = MailIntentStatus.QUEUED
        intent.retryCycle += 1
        intent.manualRetryCount += 1
        intent.cycleAttemptCount = 0
        intent.maxAttempts += retryPolicy.maxAttempts
        intent.nextAttemptAt = now
        intent.leaseExpiresAt = null
        intent.failedAt = null
        intent.lastErrorCode = null
        intentRepository.saveAndFlush(intent)
        eventRepository.saveAndFlush(
            deliveryEvent(
                intentId = intent.id,
                attemptId = null,
                eventType = "MAIL_MANUAL_RETRY_REQUESTED",
                actor = command.actor,
                context = command.context,
                now = now,
                reasonCode = "MANUAL_RETRY",
                reasonText = reason,
            ),
        )
    }

    private fun OutboundMailIntentEntity.matches(intent: OutboundMailIntent, rendered: RenderedMail): Boolean =
        templateKey == rendered.template &&
            templateVersion == rendered.templateVersion &&
            senderAddress == rendered.fromAddress &&
            recipientAddress == rendered.recipient &&
            subject == rendered.subject &&
            resolvedBody() == rendered.textBody &&
            ticketId == intent.ticketId &&
            commentId == intent.commentId &&
            customerId == intent.customerId

    private fun OutboundMailIntentEntity.resolvedBody(): String =
        if (protectedBodyCiphertext == null) {
            textBody
        } else {
            val ciphertext = requireNotNull(protectedBodyCiphertext)
            protectedContentCipher.decrypt(
                ProtectedMailContent(
                    ciphertext = ciphertext,
                    nonce = requireNotNull(protectedBodyNonce),
                    keyVersion = requireNotNull(protectedBodyKeyVersion),
                ),
                id,
            )
        }

    companion object {
        const val PROTECTED_BODY_PLACEHOLDER = "[protected customer authentication content]"
    }
}

internal fun deliveryEvent(
    intentId: UUID,
    attemptId: UUID?,
    eventType: String,
    actor: ActorRef,
    context: CommandContext,
    now: Instant,
    reasonCode: String? = null,
    reasonText: String? = null,
): OutboundMailDeliveryEventEntity = OutboundMailDeliveryEventEntity(
    id = UUID.randomUUID(),
    intentId = intentId,
    attemptId = attemptId,
    eventType = eventType,
    actorType = actor.actorType.name,
    actorId = actor.actorId,
    source = context.source.name,
    requestId = context.requestId,
    correlationId = context.correlationId,
    reasonCode = reasonCode,
    reasonText = reasonText,
    occurredAt = now,
)

internal fun systemDeliveryEvent(
    intent: OutboundMailIntentEntity,
    attemptId: UUID?,
    eventType: String,
    now: Instant,
    reasonCode: String? = null,
): OutboundMailDeliveryEventEntity = deliveryEvent(
    intentId = intent.id,
    attemptId = attemptId,
    eventType = eventType,
    actor = ActorRef(ActorType.SYSTEM, null),
    context = CommandContext(
        source = RequestSource.SYSTEM_JOB,
        requestId = "mail-${attemptId ?: intent.id}",
        correlationId = intent.correlationId,
        commandId = "mail-${attemptId ?: intent.id}",
    ),
    now = now,
    reasonCode = reasonCode,
)
