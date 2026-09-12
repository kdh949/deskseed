package dev.deskseed.foundation

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender
import org.springframework.beans.factory.InitializingBean
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * The Logback appender is only enabled for the private personal-staging overlay.
 * It forwards the already allowlisted application log events to Spring Boot's OTLP log exporter.
 */
@Component
@Profile("personal-staging-observability")
class PersonalStagingOpenTelemetryLogAppenderInitializer(
    private val openTelemetry: OpenTelemetry,
) : InitializingBean {
    override fun afterPropertiesSet() {
        OpenTelemetryAppender.install(openTelemetry)
    }
}
