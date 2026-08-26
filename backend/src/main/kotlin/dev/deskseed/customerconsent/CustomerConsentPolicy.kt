package dev.deskseed.customerconsent

import dev.deskseed.knowledge.CanonicalKnowledgeDocument
import java.time.Instant
import java.util.UUID

enum class CustomerConsentContext {
    REGISTRATION,
    REQUEST_SUBMISSION,
}

enum class CustomerConsentPolicyLifecycle {
    DRAFT,
    PUBLISHED,
    ARCHIVED,
}

data class CustomerConsentPolicyDraft(
    val title: String,
    val document: CanonicalKnowledgeDocument,
    val plainText: String,
    val checksumSha256: String,
    val required: Boolean,
    val displayOrder: Int,
    val version: Int,
) {
    init {
        require(title.isValidCustomerConsentText(MAX_TITLE_LENGTH)) { "Customer consent policy title is invalid" }
        require(checksumSha256.matches(SHA_256_HEX)) { "Customer consent policy checksum is invalid" }
        require(displayOrder in 0..MAX_DISPLAY_ORDER) { "Customer consent policy display order is invalid" }
        require(version >= 1) { "Customer consent policy draft version is invalid" }
    }

    private companion object {
        const val MAX_TITLE_LENGTH = 200
        const val MAX_DISPLAY_ORDER = 10_000
        val SHA_256_HEX = Regex("^[0-9a-f]{64}$")
    }
}

data class CustomerConsentPolicy(
    val id: UUID,
    val key: String,
    val context: CustomerConsentContext,
    val lifecycle: CustomerConsentPolicyLifecycle,
    val draft: CustomerConsentPolicyDraft,
    val publishedVersion: Int?,
    val aggregateVersion: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(key.matches(KEY_PATTERN)) { "Customer consent policy key is invalid" }
        require(aggregateVersion >= 0) { "Customer consent policy aggregate version is invalid" }
        require(!updatedAt.isBefore(createdAt)) { "Customer consent policy timestamps are invalid" }
        require(lifecycle == CustomerConsentPolicyLifecycle.DRAFT || publishedVersion != null) {
            "Published or archived customer consent policies require a published version"
        }
        require(lifecycle != CustomerConsentPolicyLifecycle.DRAFT || publishedVersion == null) {
            "Draft customer consent policies cannot reference a published version"
        }
    }

    private companion object {
        val KEY_PATTERN = Regex("^[a-z0-9][a-z0-9-]{0,79}$")
    }
}

data class CustomerConsentPolicyVersion(
    val policyId: UUID,
    val version: Int,
    val title: String,
    val document: CanonicalKnowledgeDocument,
    val plainText: String,
    val checksumSha256: String,
    val required: Boolean,
    val displayOrder: Int,
    val effectiveAt: Instant,
    val publishedByStaffId: UUID,
    val publishedByDisplay: String,
    val publishedAt: Instant,
) {
    init {
        require(version >= 1) { "Customer consent policy version is invalid" }
        require(effectiveAt == publishedAt) { "Customer consent policy publication must be immediately effective" }
    }
}

data class CustomerConsentAcceptance(
    val id: UUID,
    val customerId: UUID,
    val accountId: UUID?,
    val ticketId: UUID?,
    val policyId: UUID,
    val policyVersion: Int,
    val context: CustomerConsentContext,
    val acceptedAt: Instant,
    val source: String,
    val requestId: String,
    val correlationId: String,
) {
    init {
        require(
            when (context) {
                CustomerConsentContext.REGISTRATION -> accountId != null && ticketId == null
                CustomerConsentContext.REQUEST_SUBMISSION -> ticketId != null
            },
        ) { "Customer consent acceptance resource linkage is invalid" }
    }
}

private fun String.isValidCustomerConsentText(maxLength: Int): Boolean =
    isNotBlank() && length <= maxLength && none(Char::isISOControl) && '<' !in this && '>' !in this
