package dev.deskseed.foundation

import io.otel.pyroscope.PyroscopeOtelSpanProcessor
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.SdkTracerProviderBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Adds span/profile correlation to Spring Boot's existing OpenTelemetry provider.
 * The Pyroscope Java agent remains the only profiling engine and no second OTel SDK is created.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "deskseed.profiling",
    name = ["span-correlation-enabled"],
    havingValue = "true",
)
class PyroscopeTracingConfiguration {
    @Bean
    fun pyroscopeSpanProcessorCustomizer(): SdkTracerProviderBuilderCustomizer =
        SdkTracerProviderBuilderCustomizer { builder ->
            // 2.1.2 defaults to root-span-only correlation and bounded span-name labels.
            builder.addSpanProcessor(PyroscopeOtelSpanProcessor())
        }
}
