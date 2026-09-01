package dev.deskseed.customerauth.internal

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

internal data class ProductionRedisSecurityBoundary(
    val host: String,
    val tlsEnabled: Boolean,
    val plaintextInternalNetworkAcknowledged: Boolean,
) {
    fun validate() {
        require(
            tlsEnabled ||
                (host == "redis" && plaintextInternalNetworkAcknowledged),
        ) {
            "production Redis must use TLS or the acknowledged Compose-internal redis service"
        }
    }
}

@Configuration(proxyBeanMethods = false)
@Profile("production")
internal class ProductionRedisSecurityConfiguration {
    @Bean
    fun productionRedisSecurityBoundary(
        @Value("\${spring.data.redis.host}") host: String,
        @Value("\${spring.data.redis.ssl.enabled}") tlsEnabled: Boolean,
        @Value("\${deskseed.customer-auth.redis-security.plaintext-internal-network-ack:false}")
        plaintextInternalNetworkAcknowledged: Boolean,
    ): ProductionRedisSecurityBoundary = ProductionRedisSecurityBoundary(
        host = host,
        tlsEnabled = tlsEnabled,
        plaintextInternalNetworkAcknowledged = plaintextInternalNetworkAcknowledged,
    ).also(ProductionRedisSecurityBoundary::validate)
}
