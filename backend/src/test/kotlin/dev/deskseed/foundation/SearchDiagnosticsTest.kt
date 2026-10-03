package dev.deskseed.foundation

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.config.MeterFilter
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

@dev.deskseed.testsupport.category.FastTest
class SearchDiagnosticsTest {
    private val spans = mutableListOf<SpanData>()
    private val exporter = object : SpanExporter {
        override fun export(items: Collection<SpanData>): CompletableResultCode {
            spans.addAll(items)
            return CompletableResultCode.ofSuccess()
        }
        override fun flush() = CompletableResultCode.ofSuccess()
        override fun shutdown() = CompletableResultCode.ofSuccess()
    }
    private val provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()
    private val telemetry = OpenTelemetrySdk.builder().setTracerProvider(provider).build()
    private val meters = SimpleMeterRegistry()
    private val diagnostics = SearchDiagnostics(telemetry, meters, true, "personal-staging")

    @AfterEach fun cleanup() {
        RequestContextHolder.resetRequestAttributes()
        telemetry.close()
        meters.close()
    }

    @Test fun `SQL phases inherit the request trace with bounded class and trace-only run identifier`() {
        request("phrase", "preflight-20260919")
        val parent = telemetry.getTracer("test").spanBuilder("POST agent search").startSpan()
        parent.makeCurrent().use {
            diagnostics.measure(SearchPhase.HTTP_OVERALL) {
                diagnostics.measure(SearchPhase.OVERALL) {
                    assertThat(diagnostics.measure(SearchPhase.COUNT) { 12L }).isEqualTo(12L)
                    diagnostics.measure(SearchPhase.PAGE) { listOf(1, 2) }
                    diagnostics.measure(SearchPhase.AUDIT) { Unit }
                }
                diagnostics.measure(SearchPhase.RESPONSE_ASSEMBLY) { Unit }
            }
        }
        parent.end()
        val phases = spans.filter { it.name.startsWith("deskseed.search.") }
        assertThat(phases).hasSize(6)
        val byName = phases.associateBy { it.name }
        assertThat(byName.getValue("deskseed.search.http_overall").parentSpanId).isEqualTo(parent.spanContext.spanId)
        assertThat(byName.getValue("deskseed.search.overall").parentSpanId)
            .isEqualTo(byName.getValue("deskseed.search.http_overall").spanId)
        listOf("count", "page", "audit").forEach { phase ->
            assertThat(byName.getValue("deskseed.search.$phase").parentSpanId)
                .isEqualTo(byName.getValue("deskseed.search.overall").spanId)
        }
        assertThat(byName.getValue("deskseed.search.response_assembly").parentSpanId)
            .isEqualTo(byName.getValue("deskseed.search.http_overall").spanId)
        phases.forEach {
            assertThat(it.traceId).isEqualTo(parent.spanContext.traceId)
            assertThat(it.attributes.get(AttributeKey.stringKey("deskseed.search.query_class"))).isEqualTo("phrase")
            assertThat(it.attributes.get(AttributeKey.stringKey("deskseed.test_run_id"))).isEqualTo("preflight-20260919")
            assertThat(it.attributes.get(AttributeKey.longKey("deskseed.search.case_index"))).isEqualTo(4)
            assertThat(it.attributes.get(AttributeKey.stringKey("deployment.environment.name")))
                .isEqualTo("personal-staging")
        }
        assertThat(meters.meters).allSatisfy { meter ->
            assertThat(meter.id.tags.map { it.key }).containsExactlyInAnyOrder("phase", "query_class", "outcome")
        }
        assertThat(byName.getValue("deskseed.search.count").attributes.asMap())
            .containsEntry(AttributeKey.stringKey("code.namespace"), "dev.deskseed.ticketing.internal")
            .containsEntry(AttributeKey.stringKey("code.function.name"), "StaffTicketQueryRepository.search")
            .containsEntry(AttributeKey.stringKey("db.query.summary"), "deskseed.staff_ticket_search.count")
        assertThat(byName.getValue("deskseed.search.page").attributes.asMap())
            .containsEntry(AttributeKey.stringKey("db.query.summary"), "deskseed.staff_ticket_search.page")
        assertThat(byName.getValue("deskseed.search.audit").attributes.asMap())
            .doesNotContainKey(AttributeKey.stringKey("db.query.summary"))
        SearchPhase.entries.forEach { phase ->
            val attributes = byName.getValue("deskseed.search.${phase.label}").attributes.asMap()
            assertThat(attributes)
                .containsEntry(AttributeKey.stringKey("code.namespace"), phase.codeNamespace)
                .containsEntry(AttributeKey.stringKey("code.function.name"), phase.codeFunctionName)
                .containsEntry(AttributeKey.stringKey("code.file.path"), phase.codeFilePath)
                .containsEntry(AttributeKey.stringKey("deskseed.search.outcome"), "success")
        }
    }

    @Test fun `malformed headers and database exception contents never enter diagnostic exports`() {
        request("customer@example.test", "bad\nrun")
        val failure = IllegalStateException("sensitive-search-text in SQL exception")
        assertThatThrownBy { diagnostics.measure(SearchPhase.COUNT) { throw failure } }.isSameAs(failure)
        val span = spans.single()
        assertThat(span.status.statusCode).isEqualTo(StatusCode.ERROR)
        assertThat(span.events).isEmpty()
        assertThat(span.attributes.get(AttributeKey.stringKey("deskseed.search.query_class"))).isEqualTo("unclassified")
        assertThat(span.attributes.asMap().toString()).doesNotContain("customer@", "sensitive-search-text", "bad\nrun")
        assertThat(meters.get("deskseed.search.phase").tag("outcome", "error").timer().count()).isEqualTo(1)
    }

    @Test fun `disabled diagnostics do not export or change action behavior`() {
        val disabled = SearchDiagnostics(telemetry, meters, false, "test")
        assertThat(disabled.measure(SearchPhase.COUNT) { 7 }).isEqualTo(7)
        assertThat(spans).isEmpty()
        assertThat(meters.meters).isEmpty()
    }

    @Test fun `metric registration failure cannot change the business result or mask its failure`() {
        meters.config().meterFilter(object : MeterFilter {
            override fun map(id: Meter.Id): Meter.Id = throw IllegalStateException("diagnostic registry failure")
        })
        var calls = 0
        assertThat(diagnostics.measure(SearchPhase.COUNT) { ++calls }).isEqualTo(1)
        assertThat(calls).isEqualTo(1)
        val failure = IllegalArgumentException("business failure")
        assertThatThrownBy { diagnostics.measure(SearchPhase.AUDIT) { throw failure } }.isSameAs(failure)
        assertThat(spans).hasSize(2)
    }

    @Test fun `HTTP filter measures only agent search and closes the span on failure`() {
        val filter = SearchHttpDiagnosticsFilter(diagnostics)
        val search = MockHttpServletRequest("POST", "/api/v1/agent/search").apply {
            addHeader(SearchDiagnostics.CLASS_HEADER, "single-smoke")
            addHeader(SearchDiagnostics.RUN_HEADER, "bounded-smoke")
        }
        val failure = IllegalStateException("response conversion failed with sensitive body")

        assertThatThrownBy {
            filter.doFilter(search, MockHttpServletResponse()) { _, _ -> throw failure }
        }.isSameAs(failure)

        val span = spans.single()
        assertThat(span.name).isEqualTo("deskseed.search.http_overall")
        assertThat(span.status.statusCode).isEqualTo(StatusCode.ERROR)
        assertThat(span.events).isEmpty()
        assertThat(span.attributes.asMap().toString()).doesNotContain("sensitive body")

        filter.doFilter(
            MockHttpServletRequest("POST", "/api/v1/agent/customers/search"),
            MockHttpServletResponse(),
        ) { _, _ -> Unit }
        assertThat(spans).hasSize(1)
    }

    private fun request(queryClass: String, runId: String) {
        val request = MockHttpServletRequest().apply {
            addHeader(SearchDiagnostics.CLASS_HEADER, queryClass)
            addHeader(SearchDiagnostics.RUN_HEADER, runId)
            addHeader(SearchDiagnostics.CASE_HEADER, "4")
        }
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(request))
    }
}
