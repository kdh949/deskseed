package dev.deskseed.customerconsent

import dev.deskseed.foundation.RequestSource
import dev.deskseed.knowledge.CanonicalKnowledgeDocument
import java.time.Instant
import java.util.UUID

typealias CustomerConsentLifecycle = CustomerConsentPolicyLifecycle

data class CustomerConsentPolicyDraftInput(
    val title: String,
    val document: CanonicalKnowledgeDocument,
    val required: Boolean,
    val displayOrder: Int,
) {
    init {
        if (title.isBlank() || title.length > 200 || title.any(Char::isISOControl) || '<' in title || '>' in title) {
            throw CustomerConsentValidationException("Customer consent policy title is invalid")
        }
        if (displayOrder !in 0..10_000) {
            throw CustomerConsentValidationException("Customer consent policy display order is invalid")
        }
    }
}

data class CreateCustomerConsentPolicy(
    val policyKey: String,
    val context: CustomerConsentContext,
    val draft: CustomerConsentPolicyDraftInput,
) {
    init {
        if (!policyKey.matches(KEY_PATTERN)) {
            throw CustomerConsentValidationException("Customer consent policy key is invalid")
        }
    }

    private companion object {
        val KEY_PATTERN = Regex("^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$")
    }
}

data class CustomerConsentPolicyActor(
    val staffId: UUID,
    val displayName: String,
    val isAdmin: Boolean,
    val authorities: Set<String>,
    val source: RequestSource,
    val requestId: String,
    val correlationId: String,
)

data class CustomerConsentPolicySummary(
    val id: UUID,
    val policyKey: String,
    val context: CustomerConsentContext,
    val lifecycle: CustomerConsentLifecycle,
    val aggregateVersion: Long,
    val publishedVersion: Int?,
    val required: Boolean,
    val displayOrder: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class CustomerConsentPolicyPage(
    val items: List<CustomerConsentPolicySummary>,
    val page: Int,
    val size: Int,
    val totalCount: Long,
    val totalPages: Int,
)

data class CustomerConsentPolicyDetail(
    val id: UUID,
    val policyKey: String,
    val context: CustomerConsentContext,
    val lifecycle: CustomerConsentLifecycle,
    val aggregateVersion: Long,
    val draft: CustomerConsentPolicyDraft,
    val publishedVersion: CustomerConsentPolicyVersion?,
    val versions: List<CustomerConsentPolicyVersion>,
    val createdAt: Instant,
    val updatedAt: Instant,
)

interface CustomerConsentPolicyAdministration {
    fun list(
        context: CustomerConsentContext?,
        lifecycle: CustomerConsentLifecycle?,
        page: Int,
        size: Int,
        actor: CustomerConsentPolicyActor,
    ): CustomerConsentPolicyPage

    fun create(command: CreateCustomerConsentPolicy, actor: CustomerConsentPolicyActor): CustomerConsentPolicyDetail
    fun get(policyId: UUID, actor: CustomerConsentPolicyActor): CustomerConsentPolicyDetail

    fun updateDraft(
        policyId: UUID,
        expectedAggregateVersion: Long,
        draft: CustomerConsentPolicyDraftInput,
        actor: CustomerConsentPolicyActor,
    ): CustomerConsentPolicyDetail

    fun publish(
        policyId: UUID,
        expectedAggregateVersion: Long,
        actor: CustomerConsentPolicyActor,
    ): CustomerConsentPolicyDetail

    fun archive(
        policyId: UUID,
        expectedAggregateVersion: Long,
        actor: CustomerConsentPolicyActor,
    ): CustomerConsentPolicyDetail
}

class CustomerConsentNotFoundException : RuntimeException()
class CustomerConsentConflictException(val code: String) : RuntimeException(code)
class CustomerConsentPreconditionFailedException(val currentVersion: Long) : RuntimeException()
class CustomerConsentValidationException(message: String) : IllegalArgumentException(message)
class CustomerConsentUnavailableException(cause: Throwable) : RuntimeException(cause)
