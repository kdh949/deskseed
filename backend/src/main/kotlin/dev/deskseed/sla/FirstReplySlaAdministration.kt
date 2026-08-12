package dev.deskseed.sla

import com.fasterxml.jackson.annotation.JsonInclude
import dev.deskseed.foundation.RequestSource
import dev.deskseed.ticketing.TicketChannel
import dev.deskseed.ticketing.TicketPriority
import dev.deskseed.ticketing.TicketStatus
import java.time.Instant
import java.util.UUID

data class SlaAdminActor(
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

data class FirstReplyPolicyConditions(
    val groupId: UUID? = null,
    val channel: TicketChannel? = null,
)

data class FirstReplySlaPolicyDefinition(
    val name: String,
    val position: Int,
    val scheduleId: UUID,
    val conditions: FirstReplyPolicyConditions,
    val targets: Map<TicketPriority, Long>,
    val pauseStatuses: Set<TicketStatus>,
) {
    init {
        require(name.trim().isNotEmpty() && name.trim().length <= 100) { "Policy name must contain 1 to 100 characters" }
        require(position in 1..10_000) { "Policy position must be between 1 and 10000" }
        require(targets.values.all { it in 1..525_600 }) { "Priority targets must be between 1 and 525600 minutes" }
        require(TicketStatus.SOLVED !in pauseStatuses && TicketStatus.CLOSED !in pauseStatuses) {
            "SOLVED and CLOSED are terminal statuses and cannot pause First Reply SLA"
        }
    }
}

data class FirstReplySlaPolicyView(
    val id: UUID,
    val name: String,
    val position: Int,
    val scheduleId: UUID,
    val scheduleVersion: Int,
    val conditions: FirstReplyPolicyConditions,
    val targets: Map<TicketPriority, Long>,
    val pauseStatuses: Set<TicketStatus>,
    val version: Int,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val activeVersion: Int?,
    val aggregateVersion: Long,
    val active: Boolean,
    val createdAt: Instant,
    val createdBy: ScheduleVersionActorView,
)

data class FirstReplySlaTicketSample(
    val priority: TicketPriority,
    val groupId: UUID?,
    val channel: TicketChannel,
)

data class FirstReplySlaPreview(
    val matched: Boolean,
    val dueAt: Instant?,
    val targetMinutes: Long?,
    val policyId: UUID?,
    val policyVersion: Int?,
    val scheduleId: UUID?,
    val scheduleVersion: Int?,
    val dstPolicy: String = DeterministicBusinessTimeCalculator.DST_POLICY,
)

interface FirstReplySlaAdministration {
    fun list(): List<FirstReplySlaPolicyView>

    fun get(policyId: UUID): FirstReplySlaPolicyView

    fun listVersions(policyId: UUID): List<FirstReplySlaPolicyView>

    fun create(definition: FirstReplySlaPolicyDefinition, actor: SlaAdminActor): FirstReplySlaPolicyView

    fun createVersion(
        policyId: UUID,
        expectedAggregateVersion: Long,
        definition: FirstReplySlaPolicyDefinition,
        actor: SlaAdminActor,
    ): FirstReplySlaPolicyView

    fun activate(
        policyId: UUID,
        policyVersion: Int,
        expectedAggregateVersion: Long,
        actor: SlaAdminActor,
    ): FirstReplySlaPolicyView

    fun preview(
        candidatePolicyId: UUID?,
        candidate: FirstReplySlaPolicyDefinition?,
        ticket: FirstReplySlaTicketSample,
        startAt: Instant,
    ): FirstReplySlaPreview
}

class FirstReplySlaPolicyNotFoundException : RuntimeException()

class FirstReplySlaPolicyConflictException(val code: String) : RuntimeException()

class FirstReplySlaPolicyPreconditionFailedException(val currentAggregateVersion: Long) : RuntimeException()

class FirstReplySlaPolicyValidationException(val field: String, val code: String, message: String) :
    IllegalArgumentException(message)

data class VersionedBusinessSchedule(
    val id: UUID,
    val version: Int,
    val definition: BusinessScheduleDefinition,
)

interface BusinessScheduleProvider {
    fun active(scheduleId: UUID): VersionedBusinessSchedule?

    fun exact(scheduleId: UUID, version: Int): VersionedBusinessSchedule?
}

data class AppliedFirstReplySlaPolicy(
    val policyId: UUID,
    val policyVersion: Int,
    val schedule: VersionedBusinessSchedule,
    val targetMinutes: Long,
    val pauseStatuses: Set<TicketStatus>,
)

interface FirstReplySlaPolicyMatcher {
    fun match(ticket: FirstReplySlaTicketSample): AppliedFirstReplySlaPolicy?
}
