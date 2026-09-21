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
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.time.Duration

/** Bounded diagnostic vocabulary. Never accepts SQL, bind values, bodies or exception messages. */
enum class SearchPhase(
    val label: String,
    val codeNamespace: String,
    val codeFunctionName: String,
    val codeFilePath: String,
    val querySummary: String? = null,
) {
    OVERALL(
        label = "overall",
        codeNamespace = "dev.deskseed.staffaccess.internal",
        codeFunctionName = "AgentTicketSearchApplicationService.search",
        codeFilePath = "backend/src/main/kotlin/dev/deskseed/staffaccess/internal/AgentTicketSearchApplicationService.kt",
    ),
    COUNT(
        label = "count",
        codeNamespace = "dev.deskseed.ticketing.internal",
        codeFunctionName = "StaffTicketQueryRepository.search",
        codeFilePath = "backend/src/main/kotlin/dev/deskseed/ticketing/internal/StaffTicketQueryRepository.kt",
        querySummary = "deskseed.staff_ticket_search.count",
    ),
    PAGE(
        label = "page",
        codeNamespace = "dev.deskseed.ticketing.internal",
        codeFunctionName = "StaffTicketQueryRepository.search",
        codeFilePath = "backend/src/main/kotlin/dev/deskseed/ticketing/internal/StaffTicketQueryRepository.kt",
        querySummary = "deskseed.staff_ticket_search.page",
    ),
    AUDIT(
        label = "audit",
        codeNamespace = "dev.deskseed.staffaccess.internal",
        codeFunctionName = "AgentTicketSearchApplicationService.search",
        codeFilePath = "backend/src/main/kotlin/dev/deskseed/staffaccess/internal/AgentTicketSearchApplicationService.kt",
    ),
    HTTP_OVERALL(
        label = "http_overall",
        codeNamespace = "dev.deskseed.foundation",
        codeFunctionName = "SearchHttpDiagnosticsFilter.doFilterInternal",
        codeFilePath = "backend/src/main/kotlin/dev/deskseed/foundation/SearchHttpDiagnosticsFilter.kt",
    ),
    RESPONSE_ASSEMBLY(
        label = "response_assembly",
        codeNamespace = "dev.deskseed.staffaccess.internal",
        codeFunctionName = "AgentTicketReadController.search",
        codeFilePath = "backend/src/main/kotlin/dev/deskseed/staffaccess/internal/AgentTicketReadController.kt",
    ),
}

@Component
class SearchDiagnostics(
    openTelemetry: OpenTelemetry,
    private val meters: MeterRegistry,
    @param:Value("\${deskseed.search-diagnostics.enabled:false}") private val enabled: Boolean,
    @param:Value("\${management.opentelemetry.resource-attributes.deployment.environment.name:unknown}")
    private val environment: String,
) {
    private val tracer = openTelemetry.getTracer("dev.deskseed.search")
    private val logger = LoggerFactory.getLogger(SearchDiagnostics::class.java)

    fun <T> measure(phase: SearchPhase, action: () -> T): T {
        val request = (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request
        return measure(phase, request, { "success" }, action)
    }

    fun <T> measure(phase: SearchPhase, request: HttpServletRequest?, action: () -> T): T {
        return measure(phase, request, { "success" }, action)
    }

    fun <T> measureHttp(
        request: HttpServletRequest,
        response: HttpServletResponse,
        action: () -> T,
    ): T = measure(SearchPhase.HTTP_OVERALL, request, {
        if (response.status >= 500) "error" else "success"
    }, action)

    private fun <T> measure(
        phase: SearchPhase,
        request: HttpServletRequest?,
        successOutcome: () -> String,
        action: () -> T,
    ): T {
        if (!enabled) return action()
        val queryClass = request?.getHeader(CLASS_HEADER)?.takeIf { it in QUERY_CLASSES } ?: "unclassified"
        val span = runCatching {
            tracer.spanBuilder("deskseed.search.${phase.label}")
                .setSpanKind(if (phase in setOf(SearchPhase.COUNT, SearchPhase.PAGE)) SpanKind.CLIENT else SpanKind.INTERNAL)
                .setAttribute("code.namespace", phase.codeNamespace)
                .setAttribute("code.function.name", phase.codeFunctionName)
                .setAttribute("code.file.path", phase.codeFilePath)
                .setAttribute("deskseed.search.phase", phase.label)
                .setAttribute("deskseed.search.query_class", queryClass)
                .setAttribute("deployment.environment.name", environment)
                .also { builder -> phase.querySummary?.let { builder.setAttribute("db.query.summary", it) } }
                .startSpan()
                .also { created ->
                    if (phase.querySummary != null) {
                        created.setAttribute("db.system.name", "postgresql")
                        created.setAttribute("db.operation.name", "SELECT")
                    }
                    request?.getHeader(RUN_HEADER)?.takeIf(RequestIdFilter::isValidIdentifier)?.let {
                        created.setAttribute("deskseed.test_run_id", it)
                    }
                    request?.getHeader(CASE_HEADER)?.takeIf { it.matches(Regex("[0-9]{1,4}")) }?.toLongOrNull()?.let {
                        created.setAttribute("deskseed.search.case_index", it)
                    }
                    listOf("requestId", "correlationId").forEach { key ->
                        MDC.get(key)?.takeIf(RequestIdFilter::isValidIdentifier)?.let { created.setAttribute(key, it) }
                    }
                }
        }.getOrNull()
        val started = runCatching { Timer.start(meters) }.getOrNull()
        val scope = runCatching { span?.makeCurrent() }.getOrNull()
        var outcome = "success"
        try {
            val result = action()
            outcome = successOutcome()
            if (outcome == "error") {
                runCatching { span?.setStatus(StatusCode.ERROR) }
            }
            return result
        } catch (failure: Throwable) {
            outcome = "error"
            // SQL exceptions can contain bind values. Do not record the exception or its message.
            runCatching { span?.setStatus(StatusCode.ERROR) }
            throw failure
        } finally {
            runCatching { scope?.close() }
            finish(span, started, phase, queryClass, outcome)
        }
    }

    private fun finish(span: Span?, started: Timer.Sample?, phase: SearchPhase, queryClass: String, outcome: String) {
        try {
            val elapsed = started?.stop(Timer.builder("deskseed.search.phase")
                .tags("phase", phase.label, "query_class", queryClass, "outcome", outcome)
                .description("Search phase wall time; JDBC includes client wait and row mapping, audit excludes transaction commit")
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofSeconds(30))
                .publishPercentileHistogram()
                .register(meters))
            span?.setAttribute("deskseed.search.outcome", outcome)
            if (span?.spanContext?.isSampled == true) {
                span.makeCurrent().use {
                    logger.info("Search diagnostic phase={} query_class={} outcome={} duration_ns={}",
                        phase.label, queryClass, outcome, elapsed)
                }
            }
        } catch (_: Exception) {
            // Operational telemetry must not change the search/audit result or mask its exception.
        } finally {
            runCatching { span?.end() }
        }
    }

    companion object {
        const val CLASS_HEADER = "X-Deskseed-Search-Class"
        const val RUN_HEADER = "X-Deskseed-Test-Run-Id"
        const val CASE_HEADER = "X-Deskseed-Search-Case"
        private val QUERY_CLASSES = setOf("ticket-number", "requester", "phrase", "topic", "common", "short", "internal", "absent", "single-smoke")
    }
}
