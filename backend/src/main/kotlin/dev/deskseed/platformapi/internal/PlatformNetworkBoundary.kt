package dev.deskseed.platformapi.internal

import dev.deskseed.integration.IntegrationNetworkPolicy
import jakarta.annotation.PostConstruct
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.stereotype.Component

@Component
internal class PlatformNetworkBoundary(
    private val addressPolicy: IntegrationNetworkPolicy,
    private val environment: Environment,
    @Value("\${deskseed.platform.network.allowed-client-cidrs:}") allowedClientCidrs: String,
    @Value("\${deskseed.platform.network.trusted-proxy-cidrs:}") trustedProxyCidrs: String,
) {
    private val productionProfile = environment.acceptsProfiles(Profiles.of("production"))
    private val configuredAllowed = splitCidrs(allowedClientCidrs)
    private val configuredTrusted = splitCidrs(trustedProxyCidrs)
    private val allowedClientCidrs = if (productionProfile) configuredAllowed else configuredAllowed.ifEmpty { LOCAL_ALLOWED }
    private val trustedProxyCidrs = if (productionProfile) configuredTrusted else configuredTrusted.ifEmpty { LOCAL_TRUSTED }

    @PostConstruct
    fun validate() {
        if (productionProfile) {
            require(configuredAllowed.isNotEmpty() && configuredTrusted.isNotEmpty()) {
                "Production Platform API requires allowed-client-cidrs and trusted-proxy-cidrs"
            }
        }
        addressPolicy.validateCidrs(allowedClientCidrs)
        addressPolicy.validateCidrs(trustedProxyCidrs)
    }

    fun resolveAllowedClient(request: HttpServletRequest): String? {
        val peer = addressPolicy.normalizeLiteral(request.remoteAddr) ?: return null
        val effective = if (addressPolicy.isAllowed(peer, trustedProxyCidrs)) {
            forwardedClient(request.getHeader(FORWARDED_FOR), peer) ?: return null
        } else {
            peer
        }
        return effective.takeIf { addressPolicy.isAllowed(it, allowedClientCidrs) }
    }

    private fun forwardedClient(header: String?, peer: String): String? {
        if (header.isNullOrBlank()) return peer
        val chain = header.split(',').map(String::trim)
        if (chain.isEmpty() || chain.size > MAX_FORWARDED_HOPS) return null
        val normalized = chain.map { addressPolicy.normalizeLiteral(it) ?: return null }
        return normalized.asReversed().firstOrNull { !addressPolicy.isAllowed(it, trustedProxyCidrs) }
            ?: normalized.first()
    }

    private fun splitCidrs(value: String): List<String> = value.split(',').map(String::trim).filter(String::isNotEmpty)

    private companion object {
        const val FORWARDED_FOR = "X-Forwarded-For"
        const val MAX_FORWARDED_HOPS = 10
        val LOCAL_ALLOWED = listOf("127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")
        val LOCAL_TRUSTED = listOf("127.0.0.0/8", "::1/128")
    }
}
