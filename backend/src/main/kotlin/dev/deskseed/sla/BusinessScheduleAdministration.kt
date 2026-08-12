package dev.deskseed.sla

import com.fasterxml.jackson.annotation.JsonInclude
import dev.deskseed.foundation.ActorType
import dev.deskseed.foundation.RequestSource
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class BusinessScheduleAdminActor(
    val staffId: UUID,
    val displayName: String,
    val source: RequestSource,
    val requestId: String,
    val correlationId: String,
) {
    init {
        require(source == RequestSource.ADMIN_UI)
    }
}

data class BusinessIntervalView(val start: String, val end: String)

data class WeekdayScheduleView(
    val weekday: DayOfWeek,
    val enabled: Boolean,
    val intervals: List<BusinessIntervalView>,
)

data class ScheduleExceptionView(
    val date: LocalDate,
    val mode: ExceptionMode,
    val intervals: List<BusinessIntervalView>,
    val label: String?,
)

data class ScheduleVersionActorView(
    val actorType: ActorType,
    val actorId: UUID?,
    val displayName: String,
)

data class BusinessScheduleView(
    val id: UUID,
    val name: String,
    val timeZone: String,
    val weekdays: List<WeekdayScheduleView>,
    val exceptions: List<ScheduleExceptionView>,
    val version: Int,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val activeVersion: Int?,
    val aggregateVersion: Long,
    val active: Boolean,
    val createdAt: Instant,
    val createdBy: ScheduleVersionActorView,
)

data class BusinessTimePreview(
    val dueAt: Instant?,
    val elapsedBusinessMinutes: Long,
    val nextOpenAt: Instant?,
    val nextCloseAt: Instant?,
    val dstPolicy: String = DeterministicBusinessTimeCalculator.DST_POLICY,
)

interface BusinessScheduleAdministration {
    fun list(): List<BusinessScheduleView>

    fun get(scheduleId: UUID): BusinessScheduleView

    fun listVersions(scheduleId: UUID): List<BusinessScheduleView>

    fun create(definition: BusinessScheduleDefinition, actor: BusinessScheduleAdminActor): BusinessScheduleView

    fun createVersion(
        scheduleId: UUID,
        expectedAggregateVersion: Long,
        definition: BusinessScheduleDefinition,
        actor: BusinessScheduleAdminActor,
    ): BusinessScheduleView

    fun activate(
        scheduleId: UUID,
        scheduleVersion: Int,
        expectedAggregateVersion: Long,
        actor: BusinessScheduleAdminActor,
    ): BusinessScheduleView

    fun preview(
        definition: BusinessScheduleDefinition,
        startAt: Instant,
        endAt: Instant,
        businessMinutes: Long,
    ): BusinessTimePreview
}

class BusinessScheduleNotFoundException : RuntimeException()

class BusinessScheduleConflictException(val code: String) : RuntimeException()

class BusinessSchedulePreconditionFailedException(val currentAggregateVersion: Long) : RuntimeException()
