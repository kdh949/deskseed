package dev.deskseed.customerconsent

import dev.deskseed.knowledge.CanonicalKnowledgeDocument
import java.time.Instant
import java.util.UUID

data class CurrentCustomerConsentPolicy(
    val policyId: UUID,
    val policyKey: String,
    val version: Int,
    val title: String,
    val document: CanonicalKnowledgeDocument,
    val checksumSha256: String,
    val required: Boolean,
    val displayOrder: Int,
    val effectiveAt: Instant,
)

data class CurrentCustomerConsentPolicies(
    val context: CustomerConsentContext,
    val policies: List<CurrentCustomerConsentPolicy>,
)

fun interface CustomerConsentPolicyProjection {
    fun current(context: CustomerConsentContext): CurrentCustomerConsentPolicies
}
