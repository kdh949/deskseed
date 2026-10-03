package dev.deskseed.foundation

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicInteger

/** Exposes one bounded deployment identity series; invalid or local versions are deliberately omitted. */
@Component
class ServiceVersionMetrics(
    registry: MeterRegistry,
    @param:Value("\${DESKSEED_SERVICE_VERSION:unknown}") serviceVersion: String,
) {
    init {
        if (DEPLOYMENT_SHA.matches(serviceVersion)) {
            Gauge.builder("deskseed.build.info", AtomicInteger(1)) { value -> value.get().toDouble() }
                .description("Deskseed backend build identity; value is always one")
                .tag("revision", serviceVersion)
                .register(registry)
        }
    }

    companion object {
        val DEPLOYMENT_SHA = Regex("[0-9a-f]{40}")
    }
}
