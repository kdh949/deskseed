package dev.deskseed.sla.internal.domain

import dev.deskseed.sla.BusinessInterval
import dev.deskseed.sla.BusinessScheduleDefinition
import dev.deskseed.sla.FirstReplySlaStateMachine
import dev.deskseed.sla.FirstReplySlaTargetClock
import dev.deskseed.sla.FirstReplyPolicyConditions
import dev.deskseed.sla.FirstReplySlaPolicyDefinition
import dev.deskseed.sla.SlaTargetState
import dev.deskseed.sla.WeekdaySchedule
import dev.deskseed.ticketing.TicketPriority
import dev.deskseed.ticketing.TicketStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

@dev.deskseed.testsupport.category.FastTest
class FirstReplySlaStateMachineTest {
    private val machine = FirstReplySlaStateMachine(
        schedule = seoulWeekdays(),
        targetMinutes = 60,
        pauseStatuses = setOf(TicketStatus.PENDING),
    )

    @Test
    fun `outside-hours Friday start produces Monday due instant`() {
        val target = machine.start(
            startAt = Instant.parse("2026-08-14T09:30:00Z"), // Fri 18:30 KST
            currentStatus = TicketStatus.NEW,
        )

        assertThat(target.state).isEqualTo(SlaTargetState.ACTIVE)
        assertThat(target.dueAt).isEqualTo(Instant.parse("2026-08-17T01:00:00Z"))
        assertThat(target.remainingBusinessMinutes).isEqualTo(60)
    }

    @Test
    fun `pending stores remaining business minutes and resume calculates a new due instant`() {
        val started = machine.start(
            Instant.parse("2026-08-17T00:00:00Z"), // Mon 09:00 KST
            TicketStatus.NEW,
        )

        val paused = machine.onStatusChanged(
            started,
            previousStatus = TicketStatus.NEW,
            newStatus = TicketStatus.PENDING,
            occurredAt = Instant.parse("2026-08-17T00:30:00Z"),
        )
        val resumed = machine.onStatusChanged(
            paused,
            previousStatus = TicketStatus.PENDING,
            newStatus = TicketStatus.OPEN,
            occurredAt = Instant.parse("2026-08-17T03:00:00Z"), // Mon 12:00 KST, lunch closed
        )

        assertThat(paused.state).isEqualTo(SlaTargetState.PAUSED)
        assertThat(paused.remainingBusinessMinutes).isEqualTo(30)
        assertThat(paused.dueAt).isNull()
        assertThat(resumed.state).isEqualTo(SlaTargetState.ACTIVE)
        assertThat(resumed.dueAt).isEqualTo(Instant.parse("2026-08-17T04:30:00Z")) // 13:30 KST
    }

    @Test
    fun `human public reply while paused achieves without inventing a due instant`() {
        val started = machine.start(Instant.parse("2026-08-17T00:00:00Z"), TicketStatus.NEW)
        val paused = machine.onStatusChanged(
            started,
            previousStatus = TicketStatus.NEW,
            newStatus = TicketStatus.PENDING,
            occurredAt = Instant.parse("2026-08-17T00:30:00Z"),
        )

        val achieved = machine.onStaffComment(
            paused,
            public = true,
            humanStaff = true,
            occurredAt = Instant.parse("2026-08-17T02:00:00Z"),
        )

        assertThat(achieved.state).isEqualTo(SlaTargetState.ACHIEVED)
        assertThat(achieved.dueAt).isNull()
        assertThat(achieved.remainingBusinessMinutes).isEqualTo(30)
        assertThat(achieved.achievedAt).isEqualTo(Instant.parse("2026-08-17T02:00:00Z"))
    }

    @Test
    fun `internal note cannot achieve but an on-time human public reply achieves once`() {
        val started = machine.start(Instant.parse("2026-08-17T00:00:00Z"), TicketStatus.NEW)

        val afterInternal = machine.onStaffComment(
            started,
            public = false,
            humanStaff = true,
            occurredAt = Instant.parse("2026-08-17T00:20:00Z"),
        )
        val achieved = machine.onStaffComment(
            afterInternal,
            public = true,
            humanStaff = true,
            occurredAt = Instant.parse("2026-08-17T00:40:00Z"),
        )
        val replayed = machine.onStaffComment(
            achieved,
            public = true,
            humanStaff = true,
            occurredAt = Instant.parse("2026-08-17T00:50:00Z"),
        )

        assertThat(afterInternal).isEqualTo(started)
        assertThat(achieved.state).isEqualTo(SlaTargetState.ACHIEVED)
        assertThat(achieved.dueAt).isEqualTo(started.dueAt)
        assertThat(achieved.achievedAt).isEqualTo(Instant.parse("2026-08-17T00:40:00Z"))
        assertThat(replayed).isEqualTo(achieved)
    }

    @Test
    fun `late human reply loses deterministically to breach even before scanner runs`() {
        val started = machine.start(Instant.parse("2026-08-17T00:00:00Z"), TicketStatus.NEW)

        val result = machine.onStaffComment(
            started,
            public = true,
            humanStaff = true,
            occurredAt = Instant.parse("2026-08-17T01:00:00Z"),
        )

        assertThat(result.state).isEqualTo(SlaTargetState.BREACHED)
        assertThat(result.dueAt).isEqualTo(started.dueAt)
        assertThat(result.breachedAt).isEqualTo(Instant.parse("2026-08-17T01:00:00Z"))
        assertThat(result.achievedAt).isNull()
    }

    @Test
    fun `scanner transition is idempotent and solving an unfinished target cancels it`() {
        val started = machine.start(Instant.parse("2026-08-17T00:00:00Z"), TicketStatus.NEW)
        val breached = machine.materializeBreach(started, Instant.parse("2026-08-17T02:00:00Z"))
        val rescanned = machine.materializeBreach(breached, Instant.parse("2026-08-17T03:00:00Z"))
        val cancelled = machine.onStatusChanged(
            started,
            previousStatus = TicketStatus.NEW,
            newStatus = TicketStatus.SOLVED,
            occurredAt = Instant.parse("2026-08-17T00:30:00Z"),
        )

        assertThat(breached.state).isEqualTo(SlaTargetState.BREACHED)
        assertThat(rescanned).isEqualTo(breached)
        assertThat(cancelled.state).isEqualTo(SlaTargetState.CANCELLED)
        assertThat(cancelled.dueAt).isEqualTo(started.dueAt)
        assertThat(cancelled.cancelledAt).isEqualTo(Instant.parse("2026-08-17T00:30:00Z"))
    }

    @Test
    fun `due instant wins over solve and close terminal cancellation`() {
        val started = machine.start(Instant.parse("2026-08-17T00:00:00Z"), TicketStatus.NEW)

        val solvedAtDue = machine.onStatusChanged(
            started,
            previousStatus = TicketStatus.NEW,
            newStatus = TicketStatus.SOLVED,
            occurredAt = checkNotNull(started.dueAt),
        )
        val closedAfterDue = machine.onStatusChanged(
            started,
            previousStatus = TicketStatus.NEW,
            newStatus = TicketStatus.CLOSED,
            occurredAt = checkNotNull(started.dueAt).plusSeconds(1),
        )

        assertThat(solvedAtDue.state).isEqualTo(SlaTargetState.BREACHED)
        assertThat(closedAfterDue.state).isEqualTo(SlaTargetState.BREACHED)
    }

    @Test
    fun `terminal ticket statuses cannot be configured as pause statuses`() {
        assertThatThrownBy {
            FirstReplySlaPolicyDefinition(
                name = "Invalid pauses",
                position = 1,
                scheduleId = java.util.UUID.randomUUID(),
                conditions = FirstReplyPolicyConditions(),
                targets = mapOf(TicketPriority.NORMAL to 60),
                pauseStatuses = setOf(TicketStatus.SOLVED, TicketStatus.CLOSED),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("terminal")
    }

    private fun seoulWeekdays(): BusinessScheduleDefinition = BusinessScheduleDefinition.create(
        name = "SLA fixture",
        timeZone = ZoneId.of("Asia/Seoul"),
        weekdays = DayOfWeek.entries.map { day ->
            val enabled = day.value <= DayOfWeek.FRIDAY.value
            WeekdaySchedule(
                day,
                enabled,
                if (enabled) {
                    listOf(
                        BusinessInterval(LocalTime.of(9, 0), LocalTime.of(12, 0)),
                        BusinessInterval(LocalTime.of(13, 0), LocalTime.of(18, 0)),
                    )
                } else {
                    emptyList()
                },
            )
        },
        exceptions = emptyList(),
    )
}
