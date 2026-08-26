package dev.deskseed.customerauth.internal

import dev.deskseed.integration.IntegrationNetworkPolicy
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Component

@Component
internal class CustomerAuthClientAddressResolver(
    private val addressPolicy: IntegrationNetworkPolicy,
    private val properties: CustomerAuthProperties,
) {
    fun resolve(request: HttpServletRequest): String {
        val peer = addressPolicy.normalizeLiteral(request.remoteAddr)
            ?: throw IllegalArgumentException("customer authentication peer address is invalid")
        val trustedProxies = properties.trustedProxyNetworks()
        if (!addressPolicy.isAllowed(peer, trustedProxies)) return peer

        val forwardedHeaders = request.getHeaders(FORWARDED_FOR).asSequence().toList()
        if (forwardedHeaders.isEmpty()) return peer
        require(forwardedHeaders.size == 1) { "customer authentication forwarded chain is invalid" }
        if (forwardedHeaders.single().isBlank()) return peer

        val chain = forwardedHeaders.single().split(',').map(String::trim)
        require(chain.isNotEmpty() && chain.size <= properties.maxForwardedHops && chain.none(String::isEmpty)) {
            "customer authentication forwarded chain is invalid"
        }
        val normalized = chain.map {
            addressPolicy.normalizeLiteral(it)
                ?: throw IllegalArgumentException("customer authentication forwarded address is invalid")
        }
        return normalized.asReversed().firstOrNull { !addressPolicy.isAllowed(it, trustedProxies) }
            ?: normalized.first()
    }

    private companion object {
        const val FORWARDED_FOR = "X-Forwarded-For"
    }
}
