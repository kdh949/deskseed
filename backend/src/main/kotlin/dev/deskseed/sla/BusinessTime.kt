package dev.deskseed.sla

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

data class BusinessInterval(
    val start: LocalTime,
    val end: LocalTime,
)

data class WeekdaySchedule(
    val weekday: DayOfWeek,
    val enabled: Boolean,
    val intervals: List<BusinessInterval>,
)

enum class ExceptionMode {
    CLOSED,
    OPEN,
}

data class ScheduleDateException(
    val date: LocalDate,
    val mode: ExceptionMode,
    val intervals: List<BusinessInterval>,
    val label: String?,
)

data class ScheduleValidationIssue(
    val field: String,
    val code: String,
    val message: String,
)

class BusinessScheduleValidationException(
    val issues: List<ScheduleValidationIssue>,
) : IllegalArgumentException("Business schedule definition is invalid")

class BusinessScheduleDefinition private constructor(
    val name: String,
    val timeZone: ZoneId,
    val weekdays: Map<DayOfWeek, WeekdaySchedule>,
    val exceptions: Map<LocalDate, ScheduleDateException>,
) {
    companion object {
        fun create(
            name: String,
            timeZone: ZoneId,
            weekdays: List<WeekdaySchedule>,
            exceptions: List<ScheduleDateException>,
        ): BusinessScheduleDefinition {
            val normalizedName = name.trim()
            val issues = mutableListOf<ScheduleValidationIssue>()
            if (normalizedName.isEmpty() || normalizedName.length > MAX_NAME_LENGTH) {
                issues += issue("name", "INVALID_NAME", "Name must contain 1 to 100 characters.")
            }
            if (weekdays.size != DayOfWeek.entries.size || weekdays.map { it.weekday }.toSet().size != 7) {
                issues += issue("weekdays", "WEEKDAYS_REQUIRED", "Each weekday must appear exactly once.")
            }
            weekdays.forEachIndexed { index, weekday ->
                val path = "weekdays[$index]"
                if (!weekday.enabled && weekday.intervals.isNotEmpty()) {
                    issues += issue(
                        "$path.intervals",
                        "DISABLED_DAY_HAS_INTERVALS",
                        "A disabled weekday cannot contain intervals.",
                    )
                }
                issues += validateIntervals(path, weekday.intervals)
            }
            if (exceptions.map { it.date }.toSet().size != exceptions.size) {
                issues += issue("exceptions", "DUPLICATE_EXCEPTION_DATE", "Exception dates must be unique.")
            }
            exceptions.forEachIndexed { index, exception ->
                val path = "exceptions[$index]"
                if (exception.label != null && exception.label.length > MAX_LABEL_LENGTH) {
                    issues += issue("$path.label", "LABEL_TOO_LONG", "Exception label is too long.")
                }
                if (exception.label?.any(Char::isISOControl) == true) {
                    issues += issue("$path.label", "INVALID_LABEL", "Exception label cannot contain control characters.")
                }
                when (exception.mode) {
                    ExceptionMode.CLOSED -> if (exception.intervals.isNotEmpty()) {
                        issues += issue(
                            "$path.intervals",
                            "CLOSED_EXCEPTION_HAS_INTERVALS",
                            "A closed exception cannot contain intervals.",
                        )
                    }

                    ExceptionMode.OPEN -> if (exception.intervals.isEmpty()) {
                        issues += issue(
                            "$path.intervals",
                            "OPEN_EXCEPTION_REQUIRES_INTERVAL",
                            "An open exception requires at least one interval.",
                        )
                    }
                }
                issues += validateIntervals(path, exception.intervals)
            }
            if (exceptions.size > MAX_EXCEPTIONS) {
                issues += issue("exceptions", "TOO_MANY_EXCEPTIONS", "At most 366 exceptions are allowed.")
            }
            if (issues.isNotEmpty()) throw BusinessScheduleValidationException(issues)

            return BusinessScheduleDefinition(
                name = normalizedName,
                timeZone = timeZone,
                weekdays = weekdays.associateBy { it.weekday },
                exceptions = exceptions.associateBy { it.date },
            )
        }

        private fun validateIntervals(path: String, intervals: List<BusinessInterval>): List<ScheduleValidationIssue> {
            val issues = mutableListOf<ScheduleValidationIssue>()
            if (intervals.size > MAX_INTERVALS_PER_DAY) {
                issues += issue("$path.intervals", "TOO_MANY_INTERVALS", "At most 12 intervals are allowed per day.")
            }
            intervals.forEachIndexed { index, interval ->
                if (interval.start >= interval.end) {
                    issues += issue(
                        "$path.intervals[$index]",
                        "INVALID_INTERVAL_RANGE",
                        "Interval start must be earlier than end.",
                    )
                }
            }
            intervals.sortedBy { it.start }.zipWithNext().forEach { (previous, current) ->
                if (current.start < previous.end) {
                    issues += issue(
                        "$path.intervals",
                        "OVERLAPPING_INTERVALS",
                        "Intervals on the same date cannot overlap.",
                    )
                }
            }
            return issues
        }

        private fun issue(field: String, code: String, message: String) =
            ScheduleValidationIssue(field, code, message)

        private const val MAX_NAME_LENGTH = 100
        private const val MAX_LABEL_LENGTH = 200
        private const val MAX_INTERVALS_PER_DAY = 12
        private const val MAX_EXCEPTIONS = 366
    }
}

class DeterministicBusinessTimeCalculator(
    private val schedule: BusinessScheduleDefinition,
) {
    fun addBusinessMinutes(from: Instant, businessMinutes: Long): Instant? {
        require(businessMinutes >= 0) { "Business minutes cannot be negative" }
        if (businessMinutes == 0L) return from

        var remaining = Duration.ofMinutes(businessMinutes)
        var cursor = from
        while (!remaining.isZero) {
            val interval = nextEffectiveInterval(cursor) ?: return null
            val effectiveStart = maxOf(cursor, interval.start)
            val available = Duration.between(effectiveStart, interval.end)
            if (available >= remaining) return effectiveStart.plus(remaining)
            remaining = remaining.minus(available)
            cursor = interval.end
        }
        return cursor
    }

    fun elapsedBusinessMinutes(from: Instant, to: Instant): Long {
        require(from <= to) { "Elapsed business time requires from <= to" }
        if (from == to) return 0

        var elapsed = Duration.ZERO
        var date = from.atZone(schedule.timeZone).toLocalDate()
        val finalDate = to.atZone(schedule.timeZone).toLocalDate()
        while (!date.isAfter(finalDate)) {
            effectiveIntervals(date).forEach { interval ->
                val overlapStart = maxOf(from, interval.start)
                val overlapEnd = minOf(to, interval.end)
                if (overlapStart < overlapEnd) elapsed = elapsed.plus(Duration.between(overlapStart, overlapEnd))
            }
            date = date.plusDays(1)
        }
        return elapsed.toMinutes()
    }

    fun nextOpenInstant(from: Instant): Instant? {
        val interval = nextEffectiveInterval(from) ?: return null
        return maxOf(from, interval.start)
    }

    fun nextCloseInstant(from: Instant): Instant? = nextEffectiveInterval(from)?.end

    private fun nextEffectiveInterval(from: Instant): EffectiveInterval? {
        val startDate = from.atZone(schedule.timeZone).toLocalDate()
        val weeklyOpeningExists = schedule.weekdays.values.any { it.enabled && it.intervals.isNotEmpty() }
        val futureExceptionalOpenDates = schedule.exceptions.values
            .filter { it.mode == ExceptionMode.OPEN && !it.date.isBefore(startDate) }
            .map { it.date }
        if (!weeklyOpeningExists && futureExceptionalOpenDates.isEmpty()) return null

        if (!weeklyOpeningExists) {
            futureExceptionalOpenDates.sorted().forEach { date ->
                effectiveIntervals(date).forEach { interval ->
                    if (from < interval.end) return interval
                }
            }
            return null
        }

        // At most 366 exception dates can mask weekly openings. The fixed tail
        // finds the next weekly opening without iterating toward a remote date.
        val searchEnd = startDate.plusDays((schedule.exceptions.size + 14).toLong())
        var date = startDate
        while (!date.isAfter(searchEnd)) {
            effectiveIntervals(date).forEach { interval ->
                if (from < interval.end) return interval
            }
            date = date.plusDays(1)
        }
        return null
    }

    private fun effectiveIntervals(date: LocalDate): List<EffectiveInterval> {
        val exception = schedule.exceptions[date]
        val localIntervals = when (exception?.mode) {
            ExceptionMode.CLOSED -> emptyList()
            ExceptionMode.OPEN -> exception.intervals
            null -> schedule.weekdays[date.dayOfWeek]
                ?.takeIf { it.enabled }
                ?.intervals
                .orEmpty()
        }
        return localIntervals.mapNotNull { interval ->
            val start = resolveBoundary(date, interval.start, startBoundary = true)
            val end = resolveBoundary(date, interval.end, startBoundary = false)
            if (start < end) EffectiveInterval(start, end) else null
        }
    }

    private fun resolveBoundary(date: LocalDate, time: LocalTime, startBoundary: Boolean): Instant {
        val localDateTime = LocalDateTime.of(date, time)
        val rules = schedule.timeZone.rules
        val offsets = rules.getValidOffsets(localDateTime)
        return when (offsets.size) {
            0 -> {
                val transition = requireNotNull(rules.getTransition(localDateTime))
                localDateTime.plus(transition.duration).atOffset(transition.offsetAfter).toInstant()
            }

            1 -> localDateTime.atOffset(offsets.single()).toInstant()
            else -> localDateTime.atOffset(if (startBoundary) offsets.first() else offsets.last()).toInstant()
        }
    }

    private data class EffectiveInterval(val start: Instant, val end: Instant)

    companion object {
        const val DST_POLICY = "GAP_SHIFT_FORWARD_OVERLAP_INCLUDE_BOTH"
    }
}
