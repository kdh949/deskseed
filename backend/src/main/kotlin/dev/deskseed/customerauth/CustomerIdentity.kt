package dev.deskseed.customerauth

import java.time.Instant
import java.util.UUID

data class CustomerPrincipal(
    val accountId: UUID,
    val customerId: UUID,
    val email: String,
    val displayName: String,
    val verifiedAt: Instant,
    /** Purpose-bound HMAC identity for access audit. Never contains the raw customer session token. */
    val sessionFingerprint: String? = null,
)
