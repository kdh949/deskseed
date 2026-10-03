package dev.deskseed.aiassistance

import java.util.UUID

/** Current staff/feature policy only; callers must also authorize the ticket and PUBLIC context. */
fun interface AiFeatureAvailability {
    fun enabledFeatures(actorId: UUID): Set<AiFeature>
}
