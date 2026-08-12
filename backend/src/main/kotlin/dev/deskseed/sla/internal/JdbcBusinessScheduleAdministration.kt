package dev.deskseed.sla.internal

import dev.deskseed.audit.AdminSecurityAudit
import dev.deskseed.audit.AdminSecurityAuditWriter
import dev.deskseed.audit.AdminSecurityOutcome
import dev.deskseed.foundation.ActorType
import dev.deskseed.sla.BusinessInterval
import dev.deskseed.sla.BusinessIntervalView
import dev.deskseed.sla.BusinessScheduleAdminActor
import dev.deskseed.sla.BusinessScheduleAdministration
import dev.deskseed.sla.BusinessScheduleConflictException
import dev.deskseed.sla.BusinessScheduleDefinition
import dev.deskseed.sla.BusinessScheduleNotFoundException
import dev.deskseed.sla.BusinessSchedulePreconditionFailedException
import dev.deskseed.sla.BusinessScheduleValidationException
import dev.deskseed.sla.BusinessScheduleView
import dev.deskseed.sla.BusinessTimePreview
import dev.deskseed.sla.DeterministicBusinessTimeCalculator
import dev.deskseed.sla.ExceptionMode
import dev.deskseed.sla.ScheduleDateException
import dev.deskseed.sla.ScheduleExceptionView
import dev.deskseed.sla.ScheduleValidationIssue
import dev.deskseed.sla.ScheduleVersionActorView
import dev.deskseed.sla.WeekdaySchedule
import dev.deskseed.sla.WeekdayScheduleView
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

@Service
internal class JdbcBusinessScheduleAdministration(
    private val jdbc: JdbcTemplate,
    private val auditWriter: AdminSecurityAuditWriter,
    private val clock: Clock,
) : BusinessScheduleAdministration {
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    override fun list(): List<BusinessScheduleView> = jdbc.query(
        "select id from business_schedules order by created_at, id",
    ) { result, _ -> result.getObject("id", UUID::class.java) }
        .map(::latestView)

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    override fun get(scheduleId: UUID): BusinessScheduleView = latestView(scheduleId)

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    override fun listVersions(scheduleId: UUID): List<BusinessScheduleView> {
        val root = root(scheduleId)
        return jdbc.query(
            """
            select version from business_schedule_versions
            where schedule_id = ? order by version desc
            """.trimIndent(),
            { result, _ -> result.getInt("version") },
            scheduleId,
        ).map { version -> versionView(root, version) }
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    override fun create(
        definition: BusinessScheduleDefinition,
        actor: BusinessScheduleAdminActor,
    ): BusinessScheduleView {
        val scheduleId = UUID.randomUUID()
        val now = Instant.now(clock)
        val databaseTimestamp = now.atOffset(ZoneOffset.UTC)
        try {
            jdbc.update(
                """
                insert into business_schedules
                    (id, name_normalized, current_version, active_version, aggregate_version, created_at, updated_at)
                values (?, ?, 1, null, 0, ?, ?)
                """.trimIndent(),
                scheduleId,
                normalizeName(definition.name),
                databaseTimestamp,
                databaseTimestamp,
            )
        } catch (_: DuplicateKeyException) {
            throw BusinessScheduleConflictException("DUPLICATE_SCHEDULE_NAME")
        }
        insertDefinition(scheduleId, 1, definition, actor, now)
        audit(
            "BUSINESS_SCHEDULE_CREATED",
            scheduleId,
            actor,
            mapOf("version" to "1", "timeZone" to definition.timeZone.id),
            now,
        )
        return versionView(root(scheduleId), 1)
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    override fun createVersion(
        scheduleId: UUID,
        expectedAggregateVersion: Long,
        definition: BusinessScheduleDefinition,
        actor: BusinessScheduleAdminActor,
    ): BusinessScheduleView {
        val root = lockedRoot(scheduleId)
        requireExpected(root, expectedAggregateVersion)
        val newVersion = root.currentVersion + 1
        val newAggregateVersion = root.aggregateVersion + 1
        val now = Instant.now(clock)
        val databaseTimestamp = now.atOffset(ZoneOffset.UTC)
        try {
            jdbc.update(
                """
                update business_schedules
                   set name_normalized = ?, current_version = ?, aggregate_version = ?, updated_at = ?
                 where id = ?
                """.trimIndent(),
                normalizeName(definition.name),
                newVersion,
                newAggregateVersion,
                databaseTimestamp,
                scheduleId,
            )
        } catch (_: DuplicateKeyException) {
            throw BusinessScheduleConflictException("DUPLICATE_SCHEDULE_NAME")
        }
        insertDefinition(scheduleId, newVersion, definition, actor, now)
        audit(
            "BUSINESS_SCHEDULE_VERSION_CREATED",
            scheduleId,
            actor,
            mapOf(
                "previousVersion" to root.currentVersion.toString(),
                "version" to newVersion.toString(),
                "timeZone" to definition.timeZone.id,
            ),
            now,
        )
        return versionView(root(scheduleId), newVersion)
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    override fun activate(
        scheduleId: UUID,
        scheduleVersion: Int,
        expectedAggregateVersion: Long,
        actor: BusinessScheduleAdminActor,
    ): BusinessScheduleView {
        val root = lockedRoot(scheduleId)
        requireExpected(root, expectedAggregateVersion)
        if (!versionExists(scheduleId, scheduleVersion)) throw BusinessScheduleNotFoundException()
        if (root.activeVersion == scheduleVersion) return versionView(root, scheduleVersion)

        val now = Instant.now(clock)
        val databaseTimestamp = now.atOffset(ZoneOffset.UTC)
        val newAggregateVersion = root.aggregateVersion + 1
        jdbc.update(
            """
            update business_schedules
               set active_version = ?, aggregate_version = ?, updated_at = ?
             where id = ?
            """.trimIndent(),
            scheduleVersion,
            newAggregateVersion,
            databaseTimestamp,
            scheduleId,
        )
        jdbc.update(
            """
            insert into business_schedule_activations
                (id, schedule_id, schedule_version, actor_type, actor_id, actor_display_snapshot,
                 request_id, correlation_id, activated_at)
            values (?, ?, ?, 'STAFF', ?, ?, ?, ?, ?)
            """.trimIndent(),
            UUID.randomUUID(),
            scheduleId,
            scheduleVersion,
            actor.staffId,
            actor.displayName.take(100),
            actor.requestId,
            actor.correlationId,
            databaseTimestamp,
        )
        audit(
            "BUSINESS_SCHEDULE_ACTIVATED",
            scheduleId,
            actor,
            mapOf(
                "previousVersion" to (root.activeVersion?.toString() ?: "none"),
                "version" to scheduleVersion.toString(),
            ),
            now,
        )
        return versionView(root(scheduleId), scheduleVersion)
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    override fun preview(
        definition: BusinessScheduleDefinition,
        startAt: Instant,
        endAt: Instant,
        businessMinutes: Long,
    ): BusinessTimePreview {
        require(startAt <= endAt) { "Preview endAt must not precede startAt" }
        require(businessMinutes in 0..MAX_PREVIEW_MINUTES)
        if (Duration.between(startAt, endAt) > MAX_PREVIEW_ELAPSED_RANGE) {
            throw BusinessScheduleValidationException(
                listOf(
                    ScheduleValidationIssue(
                        "endAt",
                        "PREVIEW_RANGE_TOO_LARGE",
                        "Preview elapsed range cannot exceed 366 days.",
                    ),
                ),
            )
        }
        val calculator = DeterministicBusinessTimeCalculator(definition)
        return BusinessTimePreview(
            dueAt = calculator.addBusinessMinutes(startAt, businessMinutes),
            elapsedBusinessMinutes = calculator.elapsedBusinessMinutes(startAt, endAt),
            nextOpenAt = calculator.nextOpenInstant(startAt),
            nextCloseAt = calculator.nextCloseInstant(startAt),
        )
    }

    private fun latestView(scheduleId: UUID): BusinessScheduleView {
        val root = root(scheduleId)
        return versionView(root, root.currentVersion)
    }

    private fun root(scheduleId: UUID): ScheduleRoot = roots(
        """
        select id, current_version, active_version, aggregate_version
        from business_schedules where id = ?
        """.trimIndent(),
        scheduleId,
    ).singleOrNull() ?: throw BusinessScheduleNotFoundException()

    private fun lockedRoot(scheduleId: UUID): ScheduleRoot = roots(
        """
        select id, current_version, active_version, aggregate_version
        from business_schedules where id = ? for update
        """.trimIndent(),
        scheduleId,
    ).singleOrNull() ?: throw BusinessScheduleNotFoundException()

    private fun roots(sql: String, scheduleId: UUID): List<ScheduleRoot> = jdbc.query(
        sql,
        { result, _ ->
            ScheduleRoot(
                id = result.getObject("id", UUID::class.java),
                currentVersion = result.getInt("current_version"),
                activeVersion = result.getInt("active_version").takeUnless { result.wasNull() },
                aggregateVersion = result.getLong("aggregate_version"),
            )
        },
        scheduleId,
    )

    private fun versionView(root: ScheduleRoot, version: Int): BusinessScheduleView {
        val metadata = jdbc.query(
            """
            select name, timezone, created_by_actor_type, created_by_staff_id,
                   created_by_display, created_at
            from business_schedule_versions
            where schedule_id = ? and version = ?
            """.trimIndent(),
            { result, _ -> versionMetadata(result) },
            root.id,
            version,
        ).singleOrNull() ?: throw BusinessScheduleNotFoundException()
        val weekdayIntervals = jdbc.query(
            """
            select weekday, start_time, end_time
            from business_schedule_weekday_intervals
            where schedule_id = ? and schedule_version = ?
            order by weekday, ordinal
            """.trimIndent(),
            { result, _ ->
                DayOfWeek.valueOf(result.getString("weekday")) to BusinessInterval(
                    result.getObject("start_time", LocalTime::class.java),
                    result.getObject("end_time", LocalTime::class.java),
                )
            },
            root.id,
            version,
        ).groupBy({ it.first }, { it.second })
        val weekdays = jdbc.query(
            """
            select weekday, enabled from business_schedule_weekdays
            where schedule_id = ? and schedule_version = ?
            order by case weekday
                when 'MONDAY' then 1 when 'TUESDAY' then 2 when 'WEDNESDAY' then 3
                when 'THURSDAY' then 4 when 'FRIDAY' then 5 when 'SATURDAY' then 6 else 7 end
            """.trimIndent(),
            { result, _ ->
                val day = DayOfWeek.valueOf(result.getString("weekday"))
                WeekdaySchedule(day, result.getBoolean("enabled"), weekdayIntervals[day].orEmpty())
            },
            root.id,
            version,
        )
        val exceptionIntervals = jdbc.query(
            """
            select exception_date, start_time, end_time
            from business_schedule_exception_intervals
            where schedule_id = ? and schedule_version = ?
            order by exception_date, ordinal
            """.trimIndent(),
            { result, _ ->
                result.getObject("exception_date", LocalDate::class.java) to BusinessInterval(
                    result.getObject("start_time", LocalTime::class.java),
                    result.getObject("end_time", LocalTime::class.java),
                )
            },
            root.id,
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
            root.id,
            version,
        )
        val definition = BusinessScheduleDefinition.create(
            metadata.name,
            java.time.ZoneId.of(metadata.timeZone),
            weekdays,
            exceptions,
        )
        return BusinessScheduleView(
            id = root.id,
            name = definition.name,
            timeZone = definition.timeZone.id,
            weekdays = definition.weekdays.values.sortedBy { it.weekday.value }.map { weekday ->
                WeekdayScheduleView(
                    weekday.weekday,
                    weekday.enabled,
                    weekday.intervals.map(::intervalView),
                )
            },
            exceptions = definition.exceptions.values.sortedBy { it.date }.map { exception ->
                ScheduleExceptionView(
                    exception.date,
                    exception.mode,
                    exception.intervals.map(::intervalView),
                    exception.label,
                )
            },
            version = version,
            activeVersion = root.activeVersion,
            aggregateVersion = root.aggregateVersion,
            active = root.activeVersion == version,
            createdAt = metadata.createdAt,
            createdBy = ScheduleVersionActorView(
                ActorType.valueOf(metadata.actorType),
                metadata.actorId,
                metadata.actorDisplay,
            ),
        )
    }

    private fun insertDefinition(
        scheduleId: UUID,
        version: Int,
        definition: BusinessScheduleDefinition,
        actor: BusinessScheduleAdminActor,
        now: Instant,
    ) {
        jdbc.update(
            """
            insert into business_schedule_versions
                (schedule_id, version, name, timezone, created_by_actor_type,
                 created_by_staff_id, created_by_display, created_at)
            values (?, ?, ?, ?, 'STAFF', ?, ?, ?)
            """.trimIndent(),
            scheduleId,
            version,
            definition.name,
            definition.timeZone.id,
            actor.staffId,
            actor.displayName.take(100),
            now.atOffset(ZoneOffset.UTC),
        )
        definition.weekdays.values.sortedBy { it.weekday.value }.forEach { weekday ->
            jdbc.update(
                """
                insert into business_schedule_weekdays (schedule_id, schedule_version, weekday, enabled)
                values (?, ?, ?, ?)
                """.trimIndent(),
                scheduleId,
                version,
                weekday.weekday.name,
                weekday.enabled,
            )
            weekday.intervals.forEachIndexed { ordinal, interval ->
                jdbc.update(
                    """
                    insert into business_schedule_weekday_intervals
                        (schedule_id, schedule_version, weekday, ordinal, start_time, end_time)
                    values (?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    scheduleId,
                    version,
                    weekday.weekday.name,
                    ordinal,
                    interval.start,
                    interval.end,
                )
            }
        }
        definition.exceptions.values.sortedBy { it.date }.forEach { exception ->
            jdbc.update(
                """
                insert into business_schedule_exceptions
                    (schedule_id, schedule_version, exception_date, mode, label)
                values (?, ?, ?, ?, ?)
                """.trimIndent(),
                scheduleId,
                version,
                exception.date,
                exception.mode.name,
                exception.label,
            )
            exception.intervals.forEachIndexed { ordinal, interval ->
                jdbc.update(
                    """
                    insert into business_schedule_exception_intervals
                        (schedule_id, schedule_version, exception_date, ordinal, start_time, end_time)
                    values (?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    scheduleId,
                    version,
                    exception.date,
                    ordinal,
                    interval.start,
                    interval.end,
                )
            }
        }
    }

    private fun audit(
        eventType: String,
        scheduleId: UUID,
        actor: BusinessScheduleAdminActor,
        metadata: Map<String, String>,
        now: Instant,
    ) {
        auditWriter.append(
            AdminSecurityAudit(
                eventType = eventType,
                actorType = ActorType.STAFF,
                actorId = actor.staffId,
                actorDisplaySnapshot = actor.displayName,
                source = actor.source,
                targetType = "BUSINESS_SCHEDULE",
                targetId = scheduleId,
                outcome = AdminSecurityOutcome.SUCCEEDED,
                requestId = actor.requestId,
                correlationId = actor.correlationId,
                metadata = metadata,
                occurredAt = now,
            ),
        )
    }

    private fun versionExists(scheduleId: UUID, version: Int): Boolean = jdbc.queryForObject(
        "select count(*) from business_schedule_versions where schedule_id = ? and version = ?",
        Long::class.java,
        scheduleId,
        version,
    ) == 1L

    private fun requireExpected(root: ScheduleRoot, expected: Long) {
        if (root.aggregateVersion != expected) {
            throw BusinessSchedulePreconditionFailedException(root.aggregateVersion)
        }
    }

    private fun versionMetadata(result: ResultSet) = VersionMetadata(
        name = result.getString("name"),
        timeZone = result.getString("timezone"),
        actorType = result.getString("created_by_actor_type"),
        actorId = result.getObject("created_by_staff_id", UUID::class.java),
        actorDisplay = result.getString("created_by_display"),
        createdAt = result.getObject("created_at", OffsetDateTime::class.java).toInstant(),
    )

    private fun intervalView(interval: BusinessInterval) = BusinessIntervalView(
        TIME_FORMAT.format(interval.start),
        TIME_FORMAT.format(interval.end),
    )

    private fun normalizeName(name: String) = name.trim().lowercase(Locale.ROOT)

    private data class ScheduleRoot(
        val id: UUID,
        val currentVersion: Int,
        val activeVersion: Int?,
        val aggregateVersion: Long,
    )

    private data class VersionMetadata(
        val name: String,
        val timeZone: String,
        val actorType: String,
        val actorId: UUID?,
        val actorDisplay: String,
        val createdAt: Instant,
    )

    private companion object {
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        const val MAX_PREVIEW_MINUTES = 525_600L
        val MAX_PREVIEW_ELAPSED_RANGE: Duration = Duration.ofDays(366)
    }
}
