package dev.deskseed.foundation

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

@dev.deskseed.testsupport.category.FastTest
class ServiceVersionMetricsTest {
    @Test
    fun `only a full deployment sha becomes a metric label`() {
        val registry = SimpleMeterRegistry()
        ServiceVersionMetrics(registry, "0123456789abcdef0123456789abcdef01234567")
        assertThat(registry.get("deskseed.build.info").gauge().value()).isEqualTo(1.0)
        assertThat(registry.get("deskseed.build.info").gauge().id.getTag("revision"))
            .isEqualTo("0123456789abcdef0123456789abcdef01234567")
    }

    @Test
    fun `non sha versions are not exported`() {
        val registry = SimpleMeterRegistry()
        ServiceVersionMetrics(registry, "local")
        assertThat(registry.find("deskseed.build.info").gauge()).isNull()
    }
}
