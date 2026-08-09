package dev.deskseed.settings

enum class CustomerAccessMode {
    ANONYMOUS_ALLOWED,
    REGISTRATION_OPTIONAL,
    REGISTRATION_REQUIRED,
}

interface CustomerAccessPolicy {
    fun currentMode(): CustomerAccessMode
    fun requireAnonymousSubmissionAllowed()
}

class AnonymousSubmissionDisabledException : RuntimeException()
