package dev.deskseed.audit.internal

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

@dev.deskseed.testsupport.category.FastTest
class AuditPersistenceMetricsTest {
    @Test
    fun `failed persistence is counted with bounded ledger and operation labels and rethrown`() {
        val registry = SimpleMeterRegistry()
        val metrics = AuditPersistenceMetrics(registry)
        val failure = IllegalStateException("persistence unavailable")

        assertThatThrownBy {
            metrics.record(
                AuditPersistenceMetrics.Ledger.ACCESS,
                AuditPersistenceMetrics.Operation.TICKET_RESOURCE_READ,
            ) { throw failure }
        }.isSameAs(failure)

        assertThat(
            registry.get("deskseed.audit.persistence.failures")
                .tag("ledger", "access")
                .tag("operation", "ticket-resource-read")
                .counter()
                .count(),
        ).isEqualTo(1.0)
    }

    @Test
    fun `successful persistence does not increment the failure counter`() {
        val registry = SimpleMeterRegistry()
        val metrics = AuditPersistenceMetrics(registry)

        val result = metrics.record(
            AuditPersistenceMetrics.Ledger.ADMIN_SECURITY,
            AuditPersistenceMetrics.Operation.APPEND,
        ) { "stored" }

        assertThat(result).isEqualTo("stored")
        assertThat(
            registry.get("deskseed.audit.persistence.failures")
                .tag("ledger", "admin-security")
                .tag("operation", "append")
                .counter()
                .count(),
        ).isZero()
    }
}
