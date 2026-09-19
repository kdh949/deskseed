package dev.deskseed.foundation

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.time.Duration

/** Bounded diagnostic vocabulary. Never accepts SQL, bind values, bodies or exception messages. */
enum class SearchPhase(val label: String) { COUNT("count"), PAGE("page"), AUDIT("audit") }

@Component
class SearchDiagnostics(
    openTelemetry: OpenTelemetry,
    private val meters: MeterRegistry,
    @param:Value("\${deskseed.search-diagnostics.enabled:false}") private val enabled: Boolean,
) {
    private val tracer = openTelemetry.getTracer("dev.deskseed.search")
    private val logger = LoggerFactory.getLogger(SearchDiagnostics::class.java)

    fun <T> measure(phase: SearchPhase, action: () -> T): T {
        if (!enabled) return action()
        val request = (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request
        val queryClass = request?.getHeader(CLASS_HEADER)?.takeIf { it in QUERY_CLASSES } ?: "unclassified"
        val span = tracer.spanBuilder("deskseed.search.${phase.label}")
            .setSpanKind(if (phase == SearchPhase.AUDIT) SpanKind.INTERNAL else SpanKind.CLIENT)
            .setAttribute("deskseed.search.phase", phase.label)
            .setAttribute("deskseed.search.query_class", queryClass)
            .startSpan()
        if (phase != SearchPhase.AUDIT) {
            span.setAttribute("db.system.name", "postgresql")
            span.setAttribute("db.operation.name", "SELECT")
        }
        request?.getHeader(RUN_HEADER)?.takeIf(RequestIdFilter::isValidIdentifier)?.let {
            span.setAttribute("deskseed.test_run_id", it)
        }
        request?.getHeader(CASE_HEADER)?.takeIf { it.matches(Regex("[0-9]{1,4}")) }?.toLongOrNull()?.let {
            span.setAttribute("deskseed.search.case_index", it)
        }
        listOf("requestId", "correlationId").forEach { key ->
            MDC.get(key)?.takeIf(RequestIdFilter::isValidIdentifier)?.let { span.setAttribute(key, it) }
        }
        val started = Timer.start(meters)
        var outcome = "success"
        try {
            span.makeCurrent().use { return action() }
        } catch (failure: Throwable) {
            outcome = "error"
            // SQL exceptions can contain bind values. Do not record the exception or its message.
            span.setStatus(StatusCode.ERROR)
            throw failure
        } finally {
            finish(span, started, phase, queryClass, outcome)
        }
    }

    private fun finish(span: Span, started: Timer.Sample, phase: SearchPhase, queryClass: String, outcome: String) {
        try {
            val elapsed = started.stop(Timer.builder("deskseed.search.phase")
                .tags("phase", phase.label, "query_class", queryClass, "outcome", outcome)
                .description("Search phase wall time; JDBC includes client wait and row mapping, audit excludes transaction commit")
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofSeconds(30))
                .publishPercentileHistogram()
                .register(meters))
            span.setAttribute("deskseed.search.outcome", outcome)
            if (span.spanContext.isSampled) {
                span.makeCurrent().use {
                    logger.info("Search diagnostic phase={} query_class={} outcome={} duration_ns={}",
                        phase.label, queryClass, outcome, elapsed)
                }
            }
        } catch (_: Exception) {
            // Operational telemetry must not change the search/audit result or mask its exception.
        } finally {
            span.end()
        }
    }

    companion object {
        const val CLASS_HEADER = "X-Deskseed-Search-Class"
        const val RUN_HEADER = "X-Deskseed-Test-Run-Id"
        const val CASE_HEADER = "X-Deskseed-Search-Case"
        private val QUERY_CLASSES = setOf("ticket-number", "requester", "phrase", "topic", "common", "short", "internal", "absent", "single-smoke")
    }
}
