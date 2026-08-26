package dev.deskseed.customerconsent

interface CustomerConsentPolicyContextLock {
    fun lock(context: CustomerConsentContext)
}
