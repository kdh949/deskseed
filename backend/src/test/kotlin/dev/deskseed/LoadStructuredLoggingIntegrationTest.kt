package dev.deskseed

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.test.context.ActiveProfiles
import tools.jackson.databind.ObjectMapper

@dev.deskseed.testsupport.integration.DeskseedSpringIntegrationTest(
    properties = [
        "deskseed.staff-auth.bootstrap.enabled=false",
        "management.server.port=0",
        "management.otlp.metrics.export.enabled=false",
    ],
)
@ActiveProfiles("load")
@ExtendWith(OutputCaptureExtension::class)
@dev.deskseed.testsupport.category.IntegrationTest
class LoadStructuredLoggingIntegrationTest {
    @Test
    fun `load console event remains parseable JSON with correlation ID`(output: CapturedOutput) {
        val correlationId = "load-json-correlation-001"
        val marker = "load-json-structured-log-marker"
        MDC.put("correlationId", correlationId)
        try {
            LoggerFactory.getLogger(javaClass).info(marker)
        } finally {
            MDC.remove("correlationId")
        }

        val line = output.out.lineSequence().single { it.contains(marker) }
        val event = ObjectMapper().readTree(line)
        assertThat(event["message"].asText()).isEqualTo(marker)
        assertThat(event["correlationId"].asText()).isEqualTo(correlationId)
    }
}
