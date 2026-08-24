package dev.deskseed.ticketing.internal

import dev.deskseed.ticketing.StaffSlaDisplayState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

@dev.deskseed.testsupport.category.FastTest
class StaffSlaDisplayStateTest {
    @Test
    fun `active SLA classification uses inclusive now and risk boundaries`() {
        val now = Instant.parse("2026-08-17T00:00:00Z")
        val riskAt = now.plusSeconds(30 * 60)

        assertThat(classifyFirstReplySlaState("ACTIVE", now, now, riskAt))
            .isEqualTo(StaffSlaDisplayState.BREACHED)
        assertThat(classifyFirstReplySlaState("ACTIVE", riskAt, now, riskAt))
            .isEqualTo(StaffSlaDisplayState.AT_RISK)
    }
}
