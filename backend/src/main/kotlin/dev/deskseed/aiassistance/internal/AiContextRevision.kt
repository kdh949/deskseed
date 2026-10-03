package dev.deskseed.aiassistance.internal

import dev.deskseed.ticketing.AiPublicTicketContext
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal const val AI_CONTEXT_POLICY_VERSION = "public-comments-v1"

internal fun computeAiContextRevision(context: AiPublicTicketContext): String = sha256(
    buildString {
        append(AI_CONTEXT_POLICY_VERSION).append('\u001f')
        append(context.ticketId).append('\u001f').append(context.ticketVersion)
        context.comments.forEach { comment ->
            append('\u001e').append(comment.id)
                .append('\u001f').append(comment.createdAt)
                .append('\u001f').append(sha256(comment.body))
        }
    },
)

internal fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }
