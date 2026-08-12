package dev.deskseed.staffaccess.internal

import dev.deskseed.foundation.CommandContexts
import dev.deskseed.foundation.RequestSource
import dev.deskseed.sla.BusinessInterval
import dev.deskseed.sla.BusinessScheduleAdminActor
import dev.deskseed.sla.BusinessScheduleAdministration
import dev.deskseed.sla.BusinessScheduleDefinition
import dev.deskseed.sla.BusinessScheduleValidationException
import dev.deskseed.sla.BusinessScheduleView
import dev.deskseed.sla.BusinessTimePreview
import dev.deskseed.sla.ExceptionMode
import dev.deskseed.sla.ScheduleDateException
import dev.deskseed.sla.ScheduleValidationIssue
import dev.deskseed.sla.WeekdaySchedule
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

@RestController
@RequestMapping("/api/v1/admin/business-schedules")
@Validated
internal class AdminBusinessScheduleController(
    private val administration: BusinessScheduleAdministration,
) {
    @GetMapping
    fun list(): List<BusinessScheduleView> = administration.list()

    @PostMapping
    fun create(
        @Valid @RequestBody body: BusinessScheduleRequest,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<BusinessScheduleView> {
        val created = administration.create(body.toDefinition(), request.actor(principal))
        return ResponseEntity.created(URI.create("/api/v1/admin/business-schedules/${created.id}"))
            .eTag(created.aggregateVersion.toString())
            .body(created)
    }

    @PostMapping("/preview")
    fun preview(
        @Valid @RequestBody body: BusinessTimePreviewRequest,
    ): BusinessTimePreview = administration.preview(
        body.schedule.toDefinition(),
        body.startAt,
        body.endAt,
        body.businessMinutes,
    )

    @GetMapping("/{scheduleId}")
    fun get(@PathVariable scheduleId: UUID): ResponseEntity<BusinessScheduleView> {
        val schedule = administration.get(scheduleId)
        return ResponseEntity.ok().eTag(schedule.aggregateVersion.toString()).body(schedule)
    }

    @GetMapping("/{scheduleId}/versions")
    fun listVersions(@PathVariable scheduleId: UUID): List<BusinessScheduleView> =
        administration.listVersions(scheduleId)

    @PostMapping("/{scheduleId}/versions")
    fun createVersion(
        @PathVariable scheduleId: UUID,
        @RequestHeader("If-Match") ifMatch: String,
        @Valid @RequestBody body: BusinessScheduleRequest,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<BusinessScheduleView> {
        val created = administration.createVersion(
            scheduleId,
            parseEtag(ifMatch),
            body.toDefinition(),
            request.actor(principal),
        )
        return ResponseEntity.created(
            URI.create("/api/v1/admin/business-schedules/$scheduleId/versions/${created.version}"),
        ).eTag(created.aggregateVersion.toString()).body(created)
    }

    @PutMapping("/{scheduleId}/versions/{version}/activation")
    fun activate(
        @PathVariable scheduleId: UUID,
        @PathVariable @Min(1) version: Int,
        @RequestHeader("If-Match") ifMatch: String,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<BusinessScheduleView> {
        val activated = administration.activate(
            scheduleId,
            version,
            parseEtag(ifMatch),
            request.actor(principal),
        )
        return ResponseEntity.ok().eTag(activated.aggregateVersion.toString()).body(activated)
    }

    private fun parseEtag(value: String): Long {
        val match = ETAG.matchEntire(value)
            ?: throw BusinessScheduleValidationException(
                listOf(ScheduleValidationIssue("If-Match", "INVALID_ETAG", "If-Match must be a quoted version.")),
            )
        return match.groupValues[1].toLongOrNull()
            ?: throw BusinessScheduleValidationException(
                listOf(ScheduleValidationIssue("If-Match", "INVALID_ETAG", "If-Match version is invalid.")),
            )
    }

    private fun HttpServletRequest.actor(principal: StaffPrincipal): BusinessScheduleAdminActor {
        val context = CommandContexts.from(this, RequestSource.ADMIN_UI)
        return BusinessScheduleAdminActor(
            staffId = principal.id,
            displayName = principal.displayName,
            source = context.source,
            requestId = context.requestId,
            correlationId = context.correlationId,
        )
    }

    private companion object {
        val ETAG = Regex("\"(\\d+)\"")
    }
}

internal data class BusinessScheduleRequest(
    @field:NotBlank
    @field:Size(max = 100)
    val name: String,
    @field:NotBlank
    @field:Size(max = 100)
    val timeZone: String,
    @field:Valid
    @field:Size(min = 7, max = 7)
    val weekdays: List<WeekdayScheduleRequest>,
    @field:Valid
    @field:Size(max = 366)
    val exceptions: List<ScheduleExceptionRequest> = emptyList(),
) {
    fun toDefinition(): BusinessScheduleDefinition {
        val zone = try {
            if (timeZone !in ZoneId.getAvailableZoneIds()) throw DateTimeException("Unknown IANA zone")
            ZoneId.of(timeZone)
        } catch (_: DateTimeException) {
            throw BusinessScheduleValidationException(
                listOf(ScheduleValidationIssue("timeZone", "INVALID_TIMEZONE", "Use a valid IANA timezone.")),
            )
        }
        return BusinessScheduleDefinition.create(
            name = name,
            timeZone = zone,
            weekdays = weekdays.map { weekday ->
                WeekdaySchedule(
                    weekday.weekday,
                    weekday.enabled,
                    weekday.intervals.map(BusinessIntervalRequest::toInterval),
                )
            },
            exceptions = exceptions.map { exception ->
                ScheduleDateException(
                    exception.date,
                    exception.mode,
                    exception.intervals.map(BusinessIntervalRequest::toInterval),
                    exception.label?.trim()?.ifEmpty { null },
                )
            },
        )
    }
}

internal data class WeekdayScheduleRequest(
    val weekday: DayOfWeek,
    val enabled: Boolean,
    @field:Valid
    @field:Size(max = 12)
    val intervals: List<BusinessIntervalRequest>,
)

internal data class BusinessIntervalRequest(
    @field:Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$")
    val start: String,
    @field:Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$")
    val end: String,
) {
    fun toInterval() = BusinessInterval(LocalTime.parse(start), LocalTime.parse(end))
}

internal data class ScheduleExceptionRequest(
    val date: LocalDate,
    val mode: ExceptionMode,
    @field:Valid
    @field:Size(max = 12)
    val intervals: List<BusinessIntervalRequest> = emptyList(),
    @field:Size(max = 200)
    val label: String? = null,
)

internal data class BusinessTimePreviewRequest(
    @field:Valid
    val schedule: BusinessScheduleRequest,
    val startAt: Instant,
    val endAt: Instant,
    @field:Min(0)
    @field:Max(525_600)
    val businessMinutes: Long,
)
