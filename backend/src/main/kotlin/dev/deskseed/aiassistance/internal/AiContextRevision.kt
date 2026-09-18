package dev.deskseed.aiassistance.internal

import dev.deskseed.aiassistance.AiFeature
import dev.deskseed.ticketing.AiPublicTicketContext
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal const val LEGACY_AI_CONTEXT_POLICY_VERSION = "public-comments-v1"
internal const val AI_CONTEXT_POLICY_VERSION = "public-comments-v2"

internal fun inputPolicyVersion(feature: AiFeature): String = when (feature) {
    AiFeature.TICKET_SUMMARY -> "summary-input-v1"
    AiFeature.TICKET_TRIAGE -> "triage-input-v1"
    AiFeature.TICKET_REPLY_DRAFT -> "reply-input-v1"
}

internal fun computeAiInputRevision(
    context: AiPublicTicketContext,
    feature: AiFeature,
    policyVersion: String = inputPolicyVersion(feature),
): String {
    require(policyVersion == inputPolicyVersion(feature)) { "Unsupported AI input policy version" }
    val digest = MessageDigest.getInstance("SHA-256")
    digest.updateCanonicalField("deskseed-ai-input-revision-v1")
    digest.updateCanonicalField(feature.value)
    digest.updateCanonicalField(policyVersion)
    context.comments.forEach { comment ->
        digest.updateCanonicalField(comment.id.toString())
        digest.updateCanonicalField(comment.sequence.toString())
        digest.updateCanonicalField(comment.authorRole.name)
        digest.updateCanonicalField(comment.createdAt.toString())
        digest.updateCanonicalField(sha256(comment.body))
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

internal fun computeAiContextRevision(
    context: AiPublicTicketContext,
    policyVersion: String = AI_CONTEXT_POLICY_VERSION,
): String = sha256(
    buildString {
        require(policyVersion == LEGACY_AI_CONTEXT_POLICY_VERSION || policyVersion == AI_CONTEXT_POLICY_VERSION) {
            "Unsupported AI context policy version"
        }
        append(policyVersion).append('\u001f')
        append(context.ticketId).append('\u001f').append(context.ticketVersion)
        context.comments.forEach { comment ->
            append('\u001e').append(comment.id)
            if (policyVersion == AI_CONTEXT_POLICY_VERSION) {
                append('\u001f').append(comment.sequence)
                    .append('\u001f').append(comment.authorRole.name)
            }
            append('\u001f').append(comment.createdAt)
                .append('\u001f').append(sha256(comment.body))
        }
    },
)

internal fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }

private fun MessageDigest.updateCanonicalField(value: String) {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
    update(bytes)
}
