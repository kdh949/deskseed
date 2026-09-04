package dev.deskseed.customerauth.internal

import dev.deskseed.customerauth.AuthenticationAttempt
import dev.deskseed.customerauth.AuthenticationAttemptDecision
import dev.deskseed.customerauth.AuthenticationAttemptLimiter
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.TimeUnit

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
    private val meterRegistry: MeterRegistry,
) : AuthenticationAttemptLimiter {
    init {
        properties.validate()
    }

    override fun acquire(attempt: AuthenticationAttempt): AuthenticationAttemptDecision {
        val startedAt = System.nanoTime()
        try {
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
            return when (status) {
                "ALLOW" -> decision(attempt, allowed = true).also {
                    record(attempt, "allowed", startedAt)
                }
                "DENY" -> decision(
                    attempt,
                    allowed = false,
                    retryAfter = Duration.ofMillis(retryAfterMillis.coerceAtLeast(1)),
                ).also {
                    record(attempt, "denied", startedAt)
                }
                else -> error("customer authentication rate-limit script returned an unknown decision")
            }
        } catch (exception: RuntimeException) {
            record(attempt, "unavailable", startedAt)
            if (exception is AuthenticationAttemptLimiterUnavailableException) throw exception
            throw AuthenticationAttemptLimiterUnavailableException()
        }
    }

    private fun record(attempt: AuthenticationAttempt, outcome: String, startedAt: Long) {
        val tags = arrayOf("purpose", attempt.purpose.keySegment, "outcome", outcome)
        Counter.builder("deskseed.customer.auth.limiter.decisions")
            .tags(*tags)
            .register(meterRegistry)
            .increment()
        Timer.builder("deskseed.customer.auth.limiter.duration")
            .tags(*tags)
            .publishPercentileHistogram()
            .serviceLevelObjectives(
                Duration.ofMillis(5),
                Duration.ofMillis(10),
                Duration.ofMillis(20),
                Duration.ofMillis(50),
                Duration.ofMillis(100),
            )
            .register(meterRegistry)
            .record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS)
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
