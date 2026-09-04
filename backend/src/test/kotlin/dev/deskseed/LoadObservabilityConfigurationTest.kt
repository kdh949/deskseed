package dev.deskseed

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ClassPathResource

@dev.deskseed.testsupport.category.FastTest
class LoadObservabilityConfigurationTest {
    private val properties = YamlPropertySourceLoader()
        .load("load-observability", ClassPathResource("application-load.yml"))
        .single()

    @Test
    fun `load profile exposes only private health info and prometheus management endpoints`() {
        assertThat(properties.getProperty("management.server.port")).isEqualTo(9090)
        assertThat(properties.getProperty("management.endpoints.web.exposure.include"))
            .isEqualTo("health,info,prometheus")
        assertThat(properties.getProperty("management.endpoint.health.probes.enabled")).isEqualTo(true)
    }

    @Test
    fun `load profile exports sampled traces and structured logs without per request metric percentiles`() {
        assertThat(properties.getProperty("management.opentelemetry.tracing.export.otlp.endpoint"))
            .isEqualTo("\${DESKSEED_OTLP_TRACES_ENDPOINT:http://alloy:4318/v1/traces}")
        assertThat(properties.getProperty("management.tracing.sampling.probability"))
            .isEqualTo("\${DESKSEED_TRACE_SAMPLING_PROBABILITY:0.05}")
        assertThat(properties.getProperty("logging.structured.format.console")).isEqualTo("logstash")
        assertThat(properties.getProperty("management.metrics.distribution.percentiles.http.server.requests")).isNull()
        assertThat(properties.getProperty("management.metrics.distribution.percentiles-histogram.http.server.requests"))
            .isEqualTo(true)
    }
}
