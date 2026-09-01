package dev.deskseed.audit.internal

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

@Component
internal class AuditPersistenceMetrics(meterRegistry: MeterRegistry) {
    private val failures = Ledger.entries.flatMap { ledger ->
        Operation.entries.map { operation ->
            (ledger to operation) to Counter.builder("deskseed.audit.persistence.failures")
                .description("Required audit persistence failures")
                .tag("ledger", ledger.tagValue)
                .tag("operation", operation.tagValue)
                .register(meterRegistry)
        }
    }.toMap()

    fun <T> record(ledger: Ledger, operation: Operation, block: () -> T): T = try {
        block()
    } catch (exception: Exception) {
        failures.getValue(ledger to operation).increment()
        throw exception
    }

    internal enum class Ledger(val tagValue: String) {
        ACCESS("access"),
        ADMIN_SECURITY("admin-security"),
    }

    internal enum class Operation(val tagValue: String) {
        TICKET_RESOURCE_READ("ticket-resource-read"),
        SAVED_VIEW_EXECUTED("saved-view-executed"),
        MACRO_PREVIEWED("macro-previewed"),
        ATTACHMENT_DOWNLOADED("attachment-downloaded"),
        TICKET_VIEWED("ticket-viewed"),
        SEARCH_EXECUTED("search-executed"),
        CUSTOMER_SEARCH_EXECUTED("customer-search-executed"),
        SEARCH_RESULT_OPENED("search-result-opened"),
        APPEND("append"),
    }
}
