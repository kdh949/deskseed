package dev.deskseed.webhook

import dev.deskseed.integration.IntegrationNetworkPolicy
import java.net.IDN
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Signs exactly the timestamp, a dot, and the unmodified request bytes. Callers must keep the
 * raw body unchanged from signing through transport; this class never persists or logs a secret.
 */
class WebhookSignatureSigner {
    fun sign(secret: ByteArray, timestamp: Instant, rawBody: ByteArray): String {
        require(secret.isNotEmpty()) { "Webhook secret must not be empty" }
        val mac = Mac.getInstance(HMAC_SHA256)
        mac.init(SecretKeySpec(secret, HMAC_SHA256))
        mac.update(timestamp.epochSecond.toString().toByteArray(StandardCharsets.US_ASCII))
        mac.update(DOT)
        mac.update(rawBody)
        return VERSION_PREFIX + Base64.getEncoder().encodeToString(mac.doFinal())
    }

    fun verify(secret: ByteArray, timestamp: Instant, rawBody: ByteArray, signature: String): Boolean {
        if (!signature.startsWith(VERSION_PREFIX)) return false
        val actual = signature.toByteArray(StandardCharsets.US_ASCII)
        val expected = sign(secret, timestamp, rawBody).toByteArray(StandardCharsets.US_ASCII)
        return MessageDigest.isEqual(expected, actual)
    }

    private companion object {
        const val HMAC_SHA256 = "HmacSHA256"
        const val VERSION_PREFIX = "v1="
        val DOT = byteArrayOf('.'.code.toByte())
    }
}

enum class WebhookTargetClass { PUBLIC, PRIVATE_APPROVED }

/**
 * The private form is constructed only after the administrative high-risk permission check.
 * Its hostname, port and CIDR approvals are an endpoint snapshot, never a process-wide switch.
 */
class WebhookTargetPolicy private constructor(
    val targetClass: WebhookTargetClass,
    val allowedHostnames: Set<String>,
    val allowedPorts: Set<Int>,
    val allowedCidrs: Set<String>,
) {
    init {
        require(allowedPorts.all { it in 1..65_535 }) { "Webhook port allowlist is invalid" }
        require(allowedHostnames.all(HOSTNAME::matches)) { "Webhook hostname allowlist is invalid" }
    }

    companion object {
        fun publicDefault(): WebhookTargetPolicy = WebhookTargetPolicy(
            targetClass = WebhookTargetClass.PUBLIC,
            allowedHostnames = emptySet(),
            allowedPorts = setOf(443),
            allowedCidrs = emptySet(),
        )

        fun privateApproved(hostname: String, port: Int, cidrs: Set<String>): WebhookTargetPolicy {
            val normalizedHostname = normalizeHostname(hostname)
            require(cidrs.isNotEmpty()) { "Private target requires a CIDR allowlist" }
            return WebhookTargetPolicy(
                targetClass = WebhookTargetClass.PRIVATE_APPROVED,
                allowedHostnames = setOf(normalizedHostname),
                allowedPorts = setOf(port),
                allowedCidrs = cidrs,
            )
        }

        private val HOSTNAME = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+")
    }
}

data class ValidatedWebhookTarget(
    val uri: URI,
    val hostname: String,
    val port: Int,
    val addresses: List<InetAddress>,
)

fun interface WebhookAddressResolver {
    fun resolve(hostname: String): List<InetAddress>
}

class WebhookTargetRejectedException(code: String) : IllegalArgumentException(code)

/**
 * Validates every DNS answer before transport. A delivery adapter must connect only to the
 * returned addresses (and not perform another hostname lookup), otherwise DNS-rebinding
 * protection is not preserved.
 */
class WebhookTargetValidator(
    private val resolver: WebhookAddressResolver = WebhookAddressResolver { hostname ->
        InetAddress.getAllByName(hostname).toList()
    },
    private val networkPolicy: IntegrationNetworkPolicy = IntegrationNetworkPolicy(),
) {
    fun validate(url: String, policy: WebhookTargetPolicy): ValidatedWebhookTarget {
        val uri = try {
            URI(url)
        } catch (_: IllegalArgumentException) {
            throw WebhookTargetRejectedException("WEBHOOK_TARGET_URL_INVALID")
        }
        if (uri.scheme?.lowercase(Locale.ROOT) != HTTPS || uri.rawUserInfo != null || uri.rawFragment != null) {
            throw WebhookTargetRejectedException("WEBHOOK_TARGET_URL_INVALID")
        }
        val hostname = uri.host?.let(::normalizeHostname)
            ?: throw WebhookTargetRejectedException("WEBHOOK_TARGET_URL_INVALID")
        val port = if (uri.port == -1) HTTPS_PORT else uri.port
        if (port !in policy.allowedPorts) throw WebhookTargetRejectedException("WEBHOOK_TARGET_PORT_NOT_ALLOWED")
        if (policy.targetClass == WebhookTargetClass.PRIVATE_APPROVED && hostname !in policy.allowedHostnames) {
            throw WebhookTargetRejectedException("WEBHOOK_TARGET_HOST_NOT_ALLOWED")
        }
        val addresses = try {
            resolver.resolve(hostname)
        } catch (_: Exception) {
            throw WebhookTargetRejectedException("WEBHOOK_TARGET_DNS_UNAVAILABLE")
        }
        if (addresses.isEmpty()) throw WebhookTargetRejectedException("WEBHOOK_TARGET_DNS_UNAVAILABLE")
        if (addresses.any { !isAllowed(it, policy) }) {
            throw WebhookTargetRejectedException("WEBHOOK_TARGET_ADDRESS_NOT_ALLOWED")
        }
        return ValidatedWebhookTarget(uri, hostname, port, addresses.distinctBy(InetAddress::getHostAddress))
    }

    private fun isAllowed(address: InetAddress, policy: WebhookTargetPolicy): Boolean {
        if (isAlwaysForbidden(address)) return false
        return when (policy.targetClass) {
            WebhookTargetClass.PUBLIC -> !isPrivate(address)
            WebhookTargetClass.PRIVATE_APPROVED -> networkPolicy.isAllowed(address.hostAddress, policy.allowedCidrs)
        }
    }

    private fun isAlwaysForbidden(address: InetAddress): Boolean =
        address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isMulticastAddress || isCarrierGradeNat(address)

    private fun isPrivate(address: InetAddress): Boolean = address.isSiteLocalAddress ||
        (address is Inet6Address && address.address.first().toInt() and 0xfe == 0xfc)

    private fun isCarrierGradeNat(address: InetAddress): Boolean {
        val bytes = address.address
        return bytes.size == 4 && bytes[0].toInt() and 0xff == 100 && bytes[1].toInt() and 0xc0 == 0x40
    }

    private companion object {
        const val HTTPS = "https"
        const val HTTPS_PORT = 443
    }
}

private fun normalizeHostname(value: String): String {
    if (value.any { it.isISOControl() } || value.length !in 1..253) {
        throw WebhookTargetRejectedException("WEBHOOK_TARGET_URL_INVALID")
    }
    val normalized = try {
        IDN.toASCII(value.trim().removeSuffix("."), IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
    } catch (_: IllegalArgumentException) {
        throw WebhookTargetRejectedException("WEBHOOK_TARGET_URL_INVALID")
    }
    if (!Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+").matches(normalized)) {
        throw WebhookTargetRejectedException("WEBHOOK_TARGET_URL_INVALID")
    }
    return normalized
}
