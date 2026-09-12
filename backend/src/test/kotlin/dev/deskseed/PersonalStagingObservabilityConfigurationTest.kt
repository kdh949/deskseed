package dev.deskseed

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ClassPathResource

@dev.deskseed.testsupport.category.FastTest
class PersonalStagingObservabilityConfigurationTest {
    private val properties = YamlPropertySourceLoader()
        .load("personal-staging-observability", ClassPathResource("application-personal-staging-observability.yml"))
        .single()

    private val logbackConfiguration = ClassPathResource("logback-spring.xml")
        .inputStream
        .bufferedReader()
        .use { it.readText() }

    @Test
    fun `personal staging retains a production compatible private management surface`() {
        assertThat(properties.getProperty("spring.config.activate.on-profile"))
            .isEqualTo("personal-staging-observability")
        assertThat(properties.getProperty("management.server.port")).isEqualTo(9090)
        assertThat(properties.getProperty("management.endpoints.web.exposure.include"))
            .isEqualTo("health,info,prometheus")
        assertThat(properties.getProperty("management.endpoint.health.probes.enabled")).isEqualTo(true)
        assertThat(properties.getProperty("management.otlp.metrics.export.enabled")).isEqualTo(false)
    }

    @Test
    fun `personal staging sends bounded logs and sampled traces through the internal collector`() {
        assertThat(properties.getProperty("management.opentelemetry.tracing.export.otlp.endpoint"))
            .isEqualTo("\${DESKSEED_PERSONAL_STAGING_OTLP_TRACES_ENDPOINT:http://alloy:4318/v1/traces}")
        assertThat(properties.getProperty("management.opentelemetry.logging.export.otlp.endpoint"))
            .isEqualTo("\${DESKSEED_PERSONAL_STAGING_OTLP_LOGS_ENDPOINT:http://alloy:4318/v1/logs}")
        assertThat(properties.getProperty("management.logging.export.otlp.enabled")).isEqualTo(true)
        assertThat(properties.getProperty("management.tracing.sampling.probability"))
            .isEqualTo("\${DESKSEED_PERSONAL_STAGING_TRACE_SAMPLING_PROBABILITY:0.05}")
        assertThat(properties.getProperty("logging.structured.format.console")).isEqualTo("logstash")
        assertThat(properties.getProperty("management.opentelemetry.logging.limits.max-attributes")).isEqualTo(16)
        assertThat(properties.getProperty("management.opentelemetry.logging.limits.max-attribute-value-length"))
            .isEqualTo(256)
    }

    @Test
    fun `personal staging logback appender captures only bounded request context`() {
        assertThat(logbackConfiguration)
            .contains("name=\"personal-staging-observability\"")
            .contains("io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender")
            .contains("<captureMdcAttributes>requestId,correlationId</captureMdcAttributes>")
            .doesNotContain("<captureMdcAttributes>*</captureMdcAttributes>")
    }
}
