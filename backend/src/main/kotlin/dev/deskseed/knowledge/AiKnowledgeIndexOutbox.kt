package dev.deskseed.knowledge

import java.time.Instant
import java.util.UUID

enum class AiKnowledgeIndexAction { UPSERT, DELETE }

fun interface AiKnowledgeIndexOutbox {
    /** Must be called inside the same transaction as the publication/audience mutation. */
    fun append(
        articleId: UUID,
        revisionId: UUID,
        action: AiKnowledgeIndexAction,
        occurredAt: Instant,
    )
}
