package dev.deskseed.integration.internal

import dev.deskseed.integration.IntegrationResourceConstraints
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder
import org.springframework.stereotype.Component
import java.net.InetAddress
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
internal class IpAllowlistPolicy {
    fun validate(constraints: IntegrationResourceConstraints) {
        constraints.ipAllowlist?.forEach { parseCidr(it) }
    }

    fun normalize(ip: String): String? = parseLiteral(ip)?.hostAddress

    fun isAllowed(ip: String, allowlist: Set<String>?): Boolean {
        val address = parseLiteral(ip) ?: return false
        if (allowlist == null) return true
        return allowlist.any { contains(parseCidr(it), address.address) }
    }

    private fun contains(cidr: Cidr, address: ByteArray): Boolean {
        if (cidr.address.size != address.size) return false
        val fullBytes = cidr.prefixLength / 8
        val remainingBits = cidr.prefixLength % 8
        for (index in 0 until fullBytes) if (cidr.address[index] != address[index]) return false
        if (remainingBits == 0) return true
        val mask = (0xff shl (8 - remainingBits)) and 0xff
        return (cidr.address[fullBytes].toInt() and mask) == (address[fullBytes].toInt() and mask)
    }

    private fun parseCidr(value: String): Cidr {
        val parts = value.trim().split('/')
        require(parts.size in 1..2) { "Invalid IP allowlist entry" }
        val address = parseLiteral(parts[0]) ?: throw IllegalArgumentException("Invalid IP allowlist entry")
        val maxPrefix = address.address.size * 8
        val prefix = if (parts.size == 2) parts[1].toIntOrNull() else maxPrefix
        require(prefix != null && prefix in 0..maxPrefix) { "Invalid IP allowlist entry" }
        return Cidr(address.address, prefix)
    }

    private fun parseLiteral(value: String): InetAddress? {
        val candidate = value.trim()
        if (candidate.isEmpty() || candidate.any { !(it.isDigit() || it in "abcdefABCDEF:.") }) return null
        return runCatching { InetAddress.getByName(candidate) }.getOrNull()
    }

    private data class Cidr(val address: ByteArray, val prefixLength: Int)
}
