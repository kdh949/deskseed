package dev.deskseed.customerauth

import java.time.Instant
import java.util.UUID

enum class CustomerCredentialState {
    PASSWORDLESS,
    PASSWORD,
}

enum class CustomerRegistrationState {
    REGISTRATION_REQUIRED,
    COMPLETE,
}

enum class CustomerAuthenticationMethod {
    MAGIC_LINK,
    PASSWORD,
}

data class CustomerPrincipal(
    val accountId: UUID,
    val customerId: UUID,
    val email: String,
    val displayName: String,
    val verifiedAt: Instant,
    val companyName: String? = null,
    val credentialState: CustomerCredentialState = CustomerCredentialState.PASSWORDLESS,
    val registrationState: CustomerRegistrationState = CustomerRegistrationState.REGISTRATION_REQUIRED,
    val availableAuthenticationMethods: List<CustomerAuthenticationMethod> =
        listOf(CustomerAuthenticationMethod.MAGIC_LINK),
    /** Purpose-bound HMAC identity for access audit. Never contains the raw customer session token. */
    val sessionFingerprint: String? = null,
) {
    override fun toString(): String =
        "CustomerPrincipal(accountId=$accountId, customerId=$customerId, " +
            "credentialState=$credentialState, registrationState=$registrationState, " +
            "availableAuthenticationMethods=$availableAuthenticationMethods, sessionFingerprint=[PROTECTED])"
}
