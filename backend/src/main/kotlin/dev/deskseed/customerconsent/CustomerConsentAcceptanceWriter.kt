package dev.deskseed.customerconsent

import dev.deskseed.foundation.CommandContext
import java.util.UUID

data class CustomerRegistrationConsentSelection(
    val policyId: UUID,
    val policyVersion: Int,
) {
    init {
        require(policyVersion >= 1) { "registration consent policy version must be positive" }
    }
}

data class RecordCustomerRegistrationConsents(
    val customerId: UUID,
    val accountId: UUID,
    val selections: List<CustomerRegistrationConsentSelection>,
    val context: CommandContext,
) {
    override fun toString(): String = "[PROTECTED CUSTOMER REGISTRATION CONSENTS]"
}

fun interface CustomerConsentAcceptanceWriter {
    fun appendRegistration(command: RecordCustomerRegistrationConsents)
}
