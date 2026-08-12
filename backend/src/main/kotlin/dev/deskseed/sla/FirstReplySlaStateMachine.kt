package dev.deskseed.sla

import dev.deskseed.ticketing.TicketStatus
import java.time.Instant

enum class SlaTargetState {
    ACTIVE,
    PAUSED,
    ACHIEVED,
    BREACHED,
    CANCELLED,
}

data class FirstReplySlaTargetClock(
    val state: SlaTargetState,
    val startedAt: Instant,
    val activeSegmentStartedAt: Instant?,
    val dueAt: Instant?,
    val remainingBusinessMinutes: Long,
    val achievedAt: Instant? = null,
    val breachedAt: Instant? = null,
    val cancelledAt: Instant? = null,
)

class FirstReplySlaStateMachine(
    schedule: BusinessScheduleDefinition,
    private val targetMinutes: Long,
    private val pauseStatuses: Set<TicketStatus>,
) {
    private val calculator = DeterministicBusinessTimeCalculator(schedule)

    init {
        require(targetMinutes > 0) { "First Reply target minutes must be positive" }
    }

    fun start(startAt: Instant, currentStatus: TicketStatus): FirstReplySlaTargetClock =
        if (currentStatus in pauseStatuses) {
            FirstReplySlaTargetClock(
                state = SlaTargetState.PAUSED,
                startedAt = startAt,
                activeSegmentStartedAt = null,
                dueAt = null,
                remainingBusinessMinutes = targetMinutes,
            )
        } else {
            FirstReplySlaTargetClock(
                state = SlaTargetState.ACTIVE,
                startedAt = startAt,
                activeSegmentStartedAt = startAt,
                dueAt = calculator.addBusinessMinutes(startAt, targetMinutes),
                remainingBusinessMinutes = targetMinutes,
            )
        }

    fun onStatusChanged(
        target: FirstReplySlaTargetClock,
        previousStatus: TicketStatus,
        newStatus: TicketStatus,
        occurredAt: Instant,
    ): FirstReplySlaTargetClock {
        if (target.state.isTerminal()) return target
        if (newStatus == TicketStatus.SOLVED || newStatus == TicketStatus.CLOSED) {
            return target.copy(
                state = SlaTargetState.CANCELLED,
                activeSegmentStartedAt = null,
                cancelledAt = occurredAt,
            )
        }
        if (target.state == SlaTargetState.ACTIVE && target.isDueAtOrBefore(occurredAt)) {
            return target.breached(occurredAt)
        }

        val wasPaused = previousStatus in pauseStatuses
        val isPaused = newStatus in pauseStatuses
        return when {
            target.state == SlaTargetState.ACTIVE && !wasPaused && isPaused -> {
                val segmentStart = checkNotNull(target.activeSegmentStartedAt)
                val consumed = calculator.elapsedBusinessMinutes(segmentStart, occurredAt)
                target.copy(
                    state = SlaTargetState.PAUSED,
                    activeSegmentStartedAt = null,
                    dueAt = null,
                    remainingBusinessMinutes = (target.remainingBusinessMinutes - consumed).coerceAtLeast(0),
                )
            }

            target.state == SlaTargetState.PAUSED && wasPaused && !isPaused -> target.copy(
                state = SlaTargetState.ACTIVE,
                activeSegmentStartedAt = occurredAt,
                dueAt = calculator.addBusinessMinutes(occurredAt, target.remainingBusinessMinutes),
            )

            else -> target
        }
    }

    fun onStaffComment(
        target: FirstReplySlaTargetClock,
        public: Boolean,
        humanStaff: Boolean,
        occurredAt: Instant,
    ): FirstReplySlaTargetClock {
        if (!public || !humanStaff || target.state.isTerminal()) return target
        if (target.state == SlaTargetState.ACTIVE && target.isDueAtOrBefore(occurredAt)) {
            return target.breached(occurredAt)
        }
        return target.copy(
            state = SlaTargetState.ACHIEVED,
            activeSegmentStartedAt = null,
            achievedAt = occurredAt,
        )
    }

    fun materializeBreach(target: FirstReplySlaTargetClock, occurredAt: Instant): FirstReplySlaTargetClock =
        if (target.state == SlaTargetState.ACTIVE && target.isDueAtOrBefore(occurredAt)) {
            target.breached(occurredAt)
        } else {
            target
        }

    private fun FirstReplySlaTargetClock.isDueAtOrBefore(instant: Instant): Boolean =
        dueAt?.let { !it.isAfter(instant) } == true

    private fun FirstReplySlaTargetClock.breached(at: Instant) = copy(
        state = SlaTargetState.BREACHED,
        activeSegmentStartedAt = null,
        breachedAt = at,
    )

    private fun SlaTargetState.isTerminal() =
        this == SlaTargetState.ACHIEVED || this == SlaTargetState.BREACHED || this == SlaTargetState.CANCELLED
}
