package dev.deskseed.foundation

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

@dev.deskseed.testsupport.category.IntegrationTest
class PyroscopeSpanCorrelationIntegrationTest {
    @Test
    fun `Boot customizer adds profile id to a sampled root span`() {
        val spans = mutableListOf<SpanData>()
        val exporter = object : SpanExporter {
            override fun export(items: Collection<SpanData>): CompletableResultCode {
                spans.addAll(items)
                return CompletableResultCode.ofSuccess()
            }
            override fun flush() = CompletableResultCode.ofSuccess()
            override fun shutdown() = CompletableResultCode.ofSuccess()
        }
        val builder = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
        PyroscopeTracingConfiguration().pyroscopeSpanProcessorCustomizer().customize(builder)
        val provider = builder.build()
        val telemetry = OpenTelemetrySdk.builder().setTracerProvider(provider).build()
        try {
            val span = telemetry.getTracer("test").spanBuilder("sampled-root").startSpan()
            span.end()
            provider.forceFlush().join(5, TimeUnit.SECONDS)
            assertThat(spans).hasSize(1)
            assertThat(spans.single().attributes.get(AttributeKey.stringKey("pyroscope.profile.id")))
                .isEqualTo(spans.single().spanId)
        } finally {
            telemetry.close()
        }
    }
}
