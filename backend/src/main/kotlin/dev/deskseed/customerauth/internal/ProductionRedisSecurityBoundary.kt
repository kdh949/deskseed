package dev.deskseed.customerauth.internal

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

internal data class ProductionRedisSecurityBoundary(
    val host: String,
    val port: Int,
    val tlsEnabled: Boolean,
) {
    fun validate() {
        require(
            tlsEnabled ||
                (host == "redis" && port == 6379),
        ) {
            "production Redis must use TLS or the Compose-internal redis:6379 service"
        }
    }
}

@Configuration(proxyBeanMethods = false)
@Profile("production")
internal class ProductionRedisSecurityConfiguration {
    @Bean
    fun productionRedisSecurityBoundary(
        @Value("\${spring.data.redis.host}") host: String,
        @Value("\${spring.data.redis.port}") port: Int,
        @Value("\${spring.data.redis.ssl.enabled}") tlsEnabled: Boolean,
    ): ProductionRedisSecurityBoundary = ProductionRedisSecurityBoundary(
        host = host,
        port = port,
        tlsEnabled = tlsEnabled,
    ).also(ProductionRedisSecurityBoundary::validate)
}
