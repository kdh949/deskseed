package dev.deskseed.customerauth.internal

import dev.deskseed.customerauth.AuthenticationAttempt
import dev.deskseed.customerauth.AuthenticationAttemptDecision
import dev.deskseed.customerauth.AuthenticationAttemptLimiter
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Component
import java.time.Duration

internal class AuthenticationAttemptLimiterUnavailableException : RuntimeException(
    "customer authentication limiter is unavailable",
)

internal fun interface RedisAuthenticationRateLimitCommand {
    fun execute(keys: List<String>, arguments: List<String>): String
}

@Component
internal class SpringDataRedisAuthenticationRateLimitCommand(
    private val redisTemplate: StringRedisTemplate,
    private val script: RedisScript<String>,
) : RedisAuthenticationRateLimitCommand {
    override fun execute(keys: List<String>, arguments: List<String>): String =
        requireNotNull(redisTemplate.execute(script, keys, *arguments.toTypedArray())) {
            "customer authentication rate-limit script returned no decision"
        }
}

@Component
internal class RedisAuthenticationAttemptLimiter(
    private val command: RedisAuthenticationRateLimitCommand,
    private val properties: CustomerAuthProperties,
) : AuthenticationAttemptLimiter {
    init {
        properties.validate()
    }

    override fun acquire(attempt: AuthenticationAttempt): AuthenticationAttemptDecision = try {
        val result = command.execute(
            keys = keys(attempt),
            arguments = listOf(
                properties.globalRequestLimit.toString(),
                properties.requestLimit.toString(),
                properties.networkRequestLimit.toString(),
                properties.requestWindow.toMillis().toString(),
            ),
        )
        val (status, retryAfterMillis) = result.split(':', limit = 2).let { parts ->
            require(parts.size == 2) { "customer authentication rate-limit script returned an invalid decision" }
            parts[0] to parts[1].toLong()
        }
        when (status) {
            "ALLOW" -> decision(attempt, allowed = true)
            "DENY" -> decision(
                attempt,
                allowed = false,
                retryAfter = Duration.ofMillis(retryAfterMillis.coerceAtLeast(1)),
            )
            else -> error("customer authentication rate-limit script returned an unknown decision")
        }
    } catch (exception: RuntimeException) {
        if (exception is AuthenticationAttemptLimiterUnavailableException) throw exception
        throw AuthenticationAttemptLimiterUnavailableException()
    }

    private fun keys(attempt: AuthenticationAttempt): List<String> {
        val purposeSlot = "{${attempt.purpose.keySegment}}"
        val base = "${properties.limiterKeyPrefix}:$purposeSlot"
        return listOf(
            "$base:global",
            "$base:destination:${attempt.destinationFingerprint}",
            "$base:network:${attempt.requesterNetworkFingerprint}",
        )
    }

    private fun decision(
        attempt: AuthenticationAttempt,
        allowed: Boolean,
        retryAfter: Duration? = null,
    ) = AuthenticationAttemptDecision(
        allowed = allowed,
        destinationFingerprint = attempt.destinationFingerprint,
        requesterNetworkFingerprint = attempt.requesterNetworkFingerprint,
        retryAfter = retryAfter,
    )
}
