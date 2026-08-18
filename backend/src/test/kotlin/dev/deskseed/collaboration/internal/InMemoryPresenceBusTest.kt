package dev.deskseed.collaboration.internal

import dev.deskseed.collaboration.PresenceConnection
import dev.deskseed.collaboration.TicketPresenceChangeAction
import dev.deskseed.collaboration.TicketPresenceState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class InMemoryPresenceBusTest {
    private val clock = MutableClock(Instant.parse("2026-08-18T00:00:00Z"))
    private val bus = InMemoryPresenceBus(clock, Duration.ofSeconds(60))
    private val staffId = UUID.fromString("018f7c2c-7348-7a32-a971-4c9a845b3311")

    @Test
    fun `multiple tabs aggregate one staff presence and retain the remaining tab state`() {
        val firstTab = connection("first-tab")
        val secondTab = connection("second-tab")

        val first = bus.subscribe(firstTab, 1042)
        val second = bus.subscribe(secondTab, 1042)

        assertThat(first.change?.action).isEqualTo(TicketPresenceChangeAction.JOINED)
        val secondSnapshotMember = second.snapshot.members.single()
        assertThat(secondSnapshotMember.staffId).isEqualTo(staffId)
        assertThat(secondSnapshotMember.state).isEqualTo(TicketPresenceState.VIEWING)
        assertThat(second.change).isNull()

        val stateChange = bus.updateState("second-tab", 1042, TicketPresenceState.EDITING_INTERNAL)
        assertThat(stateChange?.action).isEqualTo(TicketPresenceChangeAction.UPDATED)
        assertThat(stateChange?.member?.state).isEqualTo(TicketPresenceState.EDITING_INTERNAL)
        val remainingTabChange = bus.disconnect("second-tab").single()
        assertThat(remainingTabChange.action).isEqualTo(TicketPresenceChangeAction.UPDATED)
        assertThat(remainingTabChange.member.state).isEqualTo(TicketPresenceState.VIEWING)
    }

    @Test
    fun `stale presence expires without granting or retaining ticket access`() {
        bus.subscribe(connection("stale-tab"), 1042)
        clock.advance(Duration.ofSeconds(60))

        val expiry = bus.expireStale().single()
        assertThat(expiry.action).isEqualTo(TicketPresenceChangeAction.EXPIRED)
        assertThat(expiry.member.staffId).isEqualTo(staffId)
        assertThat(bus.expireStale()).isEmpty()
    }

    private fun connection(connectionId: String) = PresenceConnection(
        connectionId = connectionId,
        staffId = staffId,
        displayName = "Presence Agent",
    )

    private class MutableClock(
        private var now: Instant,
    ) : Clock() {
        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId): Clock = this

        override fun instant(): Instant = now

        fun advance(duration: Duration) {
            now = now.plus(duration)
        }
    }
}
