package dev.deskseed

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ClassPathResource
import io.micrometer.core.instrument.Timer
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration
import org.springframework.boot.micrometer.metrics.autoconfigure.export.prometheus.PrometheusMetricsExportAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Duration

@dev.deskseed.testsupport.category.FastTest
class LoadObservabilityConfigurationTest {
    private val properties = YamlPropertySourceLoader()
        .load("load-observability", ClassPathResource("application-load.yml"))
        .single()

    @Test
    fun `load histogram configuration supplies the actual GC and Hikari panel series`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                MetricsAutoConfiguration::class.java,
                PrometheusMetricsExportAutoConfiguration::class.java,
            ))
            .withInitializer { it.environment.propertySources.addFirst(properties) }
            .run { context ->
                val registry = context.getBean(PrometheusMeterRegistry::class.java)
                for (name in listOf("jvm.gc.pause", "hikaricp.connections.acquire", "hikaricp.connections.usage")) {
                    Timer.builder(name).register(registry).record(Duration.ofMillis(25))
                }
                val scrape = registry.scrape()
                for (name in listOf("jvm_gc_pause", "hikaricp_connections_acquire", "hikaricp_connections_usage")) {
                    assertThat(scrape).contains("${name}_seconds_bucket{")
                    assertThat(scrape).contains("${name}_seconds_count{")
                }
                assertThat(scrape).contains("environment=\"load\"", "service=\"deskseed-backend\"")
            }
    }

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
