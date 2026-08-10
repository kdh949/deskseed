package dev.deskseed.staffaccess.internal

import java.time.Duration

internal sealed class StaffLoginException : RuntimeException()

internal class InvalidStaffCredentialsException : StaffLoginException()

internal class StaffLoginRateLimitedException(
    val retryAfter: Duration,
) : StaffLoginException()
