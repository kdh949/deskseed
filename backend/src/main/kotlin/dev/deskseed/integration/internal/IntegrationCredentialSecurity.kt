package dev.deskseed.integration.internal

import dev.deskseed.integration.IntegrationResourceConstraints
import dev.deskseed.integration.IntegrationNetworkPolicy
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.Base64

@Component
internal class IntegrationSecretHasher {
    private val encoder = Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()
    private val dummyHash = requireNotNull(encoder.encode("deskseed-integration-dummy-verifier"))

    fun hash(secret: String): String = requireNotNull(encoder.encode(secret))

    fun matches(secret: String, hash: String?): Boolean = encoder.matches(secret, hash ?: dummyHash)
}

@Component
internal class IntegrationApiKeyGenerator {
    private val random = SecureRandom()
    private val base64 = Base64.getUrlEncoder().withoutPadding()

    fun generate(): GeneratedApiKey {
        val publicBytes = ByteArray(12).also(random::nextBytes)
        val secretBytes = ByteArray(32).also(random::nextBytes)
        val publicKeyId = base64.encodeToString(publicBytes)
        val secret = base64.encodeToString(secretBytes)
        return GeneratedApiKey(publicKeyId, secret, "dsk_live_$publicKeyId.$secret")
    }
}

internal data class GeneratedApiKey(val publicKeyId: String, val secret: String, val apiKey: String)

@Component
internal class IpAllowlistPolicy(
    private val networkPolicy: IntegrationNetworkPolicy,
) {
    fun validate(constraints: IntegrationResourceConstraints) {
        constraints.ipAllowlist?.let(networkPolicy::validateCidrs)
    }

    fun normalize(ip: String): String? = networkPolicy.normalizeLiteral(ip)

    fun isAllowed(ip: String, allowlist: Set<String>?): Boolean = networkPolicy.isAllowed(ip, allowlist)
}
