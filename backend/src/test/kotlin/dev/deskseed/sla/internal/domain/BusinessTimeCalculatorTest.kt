package dev.deskseed.sla.internal.domain

import dev.deskseed.sla.BusinessInterval
import dev.deskseed.sla.BusinessScheduleDefinition
import dev.deskseed.sla.BusinessScheduleValidationException
import dev.deskseed.sla.DeterministicBusinessTimeCalculator
import dev.deskseed.sla.ExceptionMode
import dev.deskseed.sla.ScheduleDateException
import dev.deskseed.sla.WeekdaySchedule
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.TimeZone

@dev.deskseed.testsupport.category.FastTest
class BusinessTimeCalculatorTest {
    @Test
    fun `Seoul Friday addition crosses the closed weekend and elapsed minutes match`() {
        val calculator = DeterministicBusinessTimeCalculator(defaultSeoulSchedule())
        val fridayAtFive = Instant.parse("2026-08-14T08:00:00Z")
        val mondayAtTen = Instant.parse("2026-08-17T01:00:00Z")

        assertThat(calculator.addBusinessMinutes(fridayAtFive, 120)).isEqualTo(mondayAtTen)
        assertThat(calculator.elapsedBusinessMinutes(fridayAtFive, mondayAtTen)).isEqualTo(120)
        assertThat(calculator.nextOpenInstant(Instant.parse("2026-08-14T09:00:00Z")))
            .isEqualTo(Instant.parse("2026-08-17T00:00:00Z"))
    }

    @Test
    fun `weekend hours split intervals and date exceptions replace the weekly rule`() {
        val definition = schedule(
            zone = "Asia/Seoul",
            intervals = mapOf(
                DayOfWeek.SATURDAY to listOf(interval("09:00", "12:00"), interval("13:00", "16:00")),
            ),
            exceptions = listOf(
                ScheduleDateException(LocalDate.parse("2026-08-15"), ExceptionMode.CLOSED, emptyList(), "광복절"),
                ScheduleDateException(
                    LocalDate.parse("2026-08-16"),
                    ExceptionMode.OPEN,
                    listOf(interval("10:00", "12:00")),
                    "특별 운영",
                ),
            ),
        )
        val calculator = DeterministicBusinessTimeCalculator(definition)

        assertThat(calculator.nextOpenInstant(Instant.parse("2026-08-15T00:00:00Z")))
            .isEqualTo(Instant.parse("2026-08-16T01:00:00Z"))
        assertThat(
            calculator.elapsedBusinessMinutes(
                Instant.parse("2026-08-16T00:00:00Z"),
                Instant.parse("2026-08-16T04:00:00Z"),
            ),
        ).isEqualTo(120)
        assertThat(calculator.nextCloseInstant(Instant.parse("2026-08-16T01:30:00Z")))
            .isEqualTo(Instant.parse("2026-08-16T03:00:00Z"))
        assertThat(
            calculator.elapsedBusinessMinutes(
                Instant.parse("2026-08-22T00:00:00Z"),
                Instant.parse("2026-08-22T07:00:00Z"),
            ),
        ).isEqualTo(360)
        assertThat(calculator.addBusinessMinutes(Instant.parse("2026-08-22T02:30:00Z"), 120))
            .isEqualTo(Instant.parse("2026-08-22T05:30:00Z"))
    }

    @Test
    fun `DST gap shifts forward and overlap includes both repeated wall-clock occurrences`() {
        val spring = DeterministicBusinessTimeCalculator(
            schedule(
                zone = "America/New_York",
                intervals = mapOf(DayOfWeek.SUNDAY to listOf(interval("02:30", "04:00"))),
            ),
        )
        assertThat(
            spring.elapsedBusinessMinutes(
                Instant.parse("2026-03-08T05:00:00Z"),
                Instant.parse("2026-03-08T09:00:00Z"),
            ),
        ).isEqualTo(30)

        val fall = DeterministicBusinessTimeCalculator(
            schedule(
                zone = "America/New_York",
                intervals = mapOf(DayOfWeek.SUNDAY to listOf(interval("01:00", "02:00"))),
            ),
        )
        assertThat(
            fall.elapsedBusinessMinutes(
                Instant.parse("2026-11-01T04:00:00Z"),
                Instant.parse("2026-11-01T08:00:00Z"),
            ),
        ).isEqualTo(120)
    }

    @Test
    fun `effective intervals are ordered by instant and DST overlap is counted once`() {
        val reversed = DeterministicBusinessTimeCalculator(
            schedule(
                zone = "Asia/Seoul",
                intervals = mapOf(
                    DayOfWeek.MONDAY to listOf(interval("13:00", "18:00"), interval("09:00", "12:00")),
                ),
            ),
        )
        assertThat(reversed.nextOpenInstant(Instant.parse("2026-08-16T22:00:00Z")))
            .isEqualTo(Instant.parse("2026-08-17T00:00:00Z"))

        val fall = DeterministicBusinessTimeCalculator(
            schedule(
                zone = "America/New_York",
                intervals = mapOf(
                    DayOfWeek.SUNDAY to listOf(interval("00:00", "01:30"), interval("01:30", "02:00")),
                ),
            ),
        )
        assertThat(
            fall.elapsedBusinessMinutes(
                Instant.parse("2026-11-01T04:00:00Z"),
                Instant.parse("2026-11-01T08:00:00Z"),
            ),
        ).isEqualTo(180)
    }

    @Test
    fun `next opening remains discoverable after consecutive weekly closed exceptions`() {
        val firstClosedMonday = LocalDate.parse("2026-01-05")
        val calculator = DeterministicBusinessTimeCalculator(
            schedule(
                zone = "Asia/Seoul",
                intervals = mapOf(DayOfWeek.MONDAY to listOf(interval("09:00", "10:00"))),
                exceptions = (0L until 30L).map { week ->
                    ScheduleDateException(
                        firstClosedMonday.plusWeeks(week),
                        ExceptionMode.CLOSED,
                        emptyList(),
                        "closed $week",
                    )
                },
            ),
        )

        assertThat(calculator.nextOpenInstant(Instant.parse("2026-01-04T00:00:00Z")))
            .isEqualTo(Instant.parse("2026-08-03T00:00:00Z"))
    }

    @Test
    fun `server default timezone cannot change the schedule result`() {
        val original = TimeZone.getDefault()
        try {
            val calculator = DeterministicBusinessTimeCalculator(defaultSeoulSchedule())
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"))
            val honoluluDefault = calculator.addBusinessMinutes(Instant.parse("2026-08-14T08:00:00Z"), 120)
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"))
            val londonDefault = calculator.addBusinessMinutes(Instant.parse("2026-08-14T08:00:00Z"), 120)

            assertThat(honoluluDefault).isEqualTo(Instant.parse("2026-08-17T01:00:00Z"))
            assertThat(londonDefault).isEqualTo(honoluluDefault)
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun `invalid range overlap disabled hours and exception shapes return field issues`() {
        assertThatThrownBy {
            schedule(
                zone = "Asia/Seoul",
                intervals = mapOf(
                    DayOfWeek.MONDAY to listOf(
                        interval("09:00", "12:00"),
                        interval("11:00", "13:00"),
                        interval("14:00", "14:00"),
                    ),
                ),
            )
        }.isInstanceOfSatisfying(BusinessScheduleValidationException::class.java) { error ->
                assertThat(error.issues.map { it.code })
                    .contains("OVERLAPPING_INTERVALS", "INVALID_INTERVAL_RANGE")
            }

        assertThatThrownBy {
            BusinessScheduleDefinition.create(
                name = "Invalid",
                timeZone = ZoneId.of("Asia/Seoul"),
                weekdays = DayOfWeek.entries.map { day ->
                    WeekdaySchedule(
                        day,
                        enabled = false,
                        intervals = if (day == DayOfWeek.MONDAY) listOf(interval("09:00", "18:00")) else emptyList(),
                    )
                },
                exceptions = listOf(
                    ScheduleDateException(
                        LocalDate.parse("2026-08-15"),
                        ExceptionMode.OPEN,
                        emptyList(),
                        null,
                    ),
                ),
            )
        }.isInstanceOfSatisfying(BusinessScheduleValidationException::class.java) { error ->
                assertThat(error.issues.map { it.code })
                    .contains("DISABLED_DAY_HAS_INTERVALS", "OPEN_EXCEPTION_REQUIRES_INTERVAL")
            }
    }

    private fun defaultSeoulSchedule(): BusinessScheduleDefinition = schedule(
        zone = "Asia/Seoul",
        intervals = DayOfWeek.entries.associateWith { day ->
            if (day.value <= DayOfWeek.FRIDAY.value) listOf(interval("09:00", "18:00")) else emptyList()
        },
    )

    private fun schedule(
        zone: String,
        intervals: Map<DayOfWeek, List<BusinessInterval>>,
        exceptions: List<ScheduleDateException> = emptyList(),
    ): BusinessScheduleDefinition = BusinessScheduleDefinition.create(
        name = "Test schedule",
        timeZone = ZoneId.of(zone),
        weekdays = DayOfWeek.entries.map { day ->
            val dayIntervals = intervals[day].orEmpty()
            WeekdaySchedule(day, enabled = dayIntervals.isNotEmpty(), intervals = dayIntervals)
        },
        exceptions = exceptions,
    )

    private fun interval(start: String, end: String) = BusinessInterval(
        LocalTime.parse(start),
        LocalTime.parse(end),
    )
}
