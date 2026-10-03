package dev.deskseed.customerconsent

import dev.deskseed.foundation.CommandContext
import java.util.UUID

data class CustomerRequestPolicySelection(val policyKey: String, val version: Int)

interface CustomerRequestConsentAcceptance {
    /** Revalidates under the existing consent publication lock; called before upload and again at finalization. */
    fun validate(selections: List<CustomerRequestPolicySelection>)
    fun append(customerId: UUID, ticketId: UUID, selections: List<CustomerRequestPolicySelection>, context: CommandContext)
}

class CustomerRequestConsentConflictException : RuntimeException()
