package dev.deskseed.sla.internal

import dev.deskseed.sla.BusinessInterval
import dev.deskseed.sla.BusinessScheduleDefinition
import dev.deskseed.sla.BusinessScheduleProvider
import dev.deskseed.sla.ExceptionMode
import dev.deskseed.sla.ScheduleDateException
import dev.deskseed.sla.VersionedBusinessSchedule
import dev.deskseed.sla.WeekdaySchedule
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

@Component
internal class JdbcBusinessScheduleProvider(
    private val jdbc: JdbcTemplate,
) : BusinessScheduleProvider {
    @Transactional(readOnly = true)
    override fun active(scheduleId: UUID): VersionedBusinessSchedule? {
        val version = jdbc.query(
            "select active_version from business_schedules where id = ?",
            { result, _ -> result.getInt(1).takeUnless { result.wasNull() } },
            scheduleId,
        ).singleOrNull() ?: return null
        return exact(scheduleId, version)
    }

    @Transactional(readOnly = true)
    override fun exact(scheduleId: UUID, version: Int): VersionedBusinessSchedule? {
        val metadata = jdbc.query(
            "select name, timezone from business_schedule_versions where schedule_id = ? and version = ?",
            { result, _ -> result.getString("name") to result.getString("timezone") },
            scheduleId,
            version,
        ).singleOrNull() ?: return null
        val weekdayIntervals = jdbc.query(
            """
            select weekday, start_time, end_time from business_schedule_weekday_intervals
             where schedule_id = ? and schedule_version = ? order by weekday, ordinal
            """.trimIndent(),
            { result, _ ->
                DayOfWeek.valueOf(result.getString("weekday")) to BusinessInterval(
                    result.getObject("start_time", LocalTime::class.java),
                    result.getObject("end_time", LocalTime::class.java),
                )
            },
            scheduleId,
            version,
        ).groupBy({ it.first }, { it.second })
        val weekdays = jdbc.query(
            """
            select weekday, enabled from business_schedule_weekdays
             where schedule_id = ? and schedule_version = ?
            """.trimIndent(),
            { result, _ ->
                val day = DayOfWeek.valueOf(result.getString("weekday"))
                WeekdaySchedule(day, result.getBoolean("enabled"), weekdayIntervals[day].orEmpty())
            },
            scheduleId,
            version,
        )
        val exceptionIntervals = jdbc.query(
            """
            select exception_date, start_time, end_time from business_schedule_exception_intervals
             where schedule_id = ? and schedule_version = ? order by exception_date, ordinal
            """.trimIndent(),
            { result, _ ->
                result.getObject("exception_date", LocalDate::class.java) to BusinessInterval(
                    result.getObject("start_time", LocalTime::class.java),
                    result.getObject("end_time", LocalTime::class.java),
                )
            },
            scheduleId,
            version,
        ).groupBy({ it.first }, { it.second })
        val exceptions = jdbc.query(
            """
            select exception_date, mode, label from business_schedule_exceptions
             where schedule_id = ? and schedule_version = ? order by exception_date
            """.trimIndent(),
            { result, _ ->
                val date = result.getObject("exception_date", LocalDate::class.java)
                ScheduleDateException(
                    date,
                    ExceptionMode.valueOf(result.getString("mode")),
                    exceptionIntervals[date].orEmpty(),
                    result.getString("label"),
                )
            },
            scheduleId,
            version,
        )
        return VersionedBusinessSchedule(
            scheduleId,
            version,
            BusinessScheduleDefinition.create(
                metadata.first,
                ZoneId.of(metadata.second),
                weekdays,
                exceptions,
            ),
        )
    }
}
