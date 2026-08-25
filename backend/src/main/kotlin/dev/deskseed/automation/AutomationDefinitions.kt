package dev.deskseed.automation

import dev.deskseed.foundation.RequestSource
import java.time.Instant
import java.util.Locale
import java.util.UUID

enum class AutomationActionType { CLOSE_TICKET }

data class AutomationDefinitionDraft(
    val name: String,
    val solvedAgeMinutes: Int,
    val actionType: AutomationActionType = AutomationActionType.CLOSE_TICKET,
) {
    init {
        require(name.trim().length in 1..120 && name.none(Char::isISOControl)) { "Automation name is invalid" }
        require(solvedAgeMinutes in 1..525_600) { "Solved age must be between 1 minute and 1 year" }
    }
    val normalizedName: String get() = name.trim().lowercase(Locale.ROOT)
}

data class AutomationDefinitionActor(
    val staffId: UUID,
    val displayName: String,
    val isAdmin: Boolean,
    val authorities: Set<String>,
    val source: RequestSource,
    val requestId: String,
    val correlationId: String,
)

data class AutomationDefinitionView(
    val id: UUID,
    val name: String,
    val position: Int,
    val currentVersion: Int,
    val activeVersion: Int?,
    val aggregateVersion: Long,
    val solvedAgeMinutes: Int,
    val actionType: AutomationActionType,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class AutomationDryRunResult(
    val ticketNumber: Long,
    val automationId: UUID,
    val automationVersion: Int,
    val status: String,
    val solvedAt: Instant?,
    val eligibleAt: Instant?,
    val matched: Boolean,
    val proposedAction: AutomationActionType,
)

interface AutomationDefinitionAdministration {
    fun list(actor: AutomationDefinitionActor): List<AutomationDefinitionView>
    fun create(position: Int, draft: AutomationDefinitionDraft, actor: AutomationDefinitionActor): AutomationDefinitionView
    fun createVersion(id: UUID, expectedAggregateVersion: Long, draft: AutomationDefinitionDraft, actor: AutomationDefinitionActor): AutomationDefinitionView
    fun activate(id: UUID, version: Int, expectedAggregateVersion: Long, actor: AutomationDefinitionActor): AutomationDefinitionView
    fun deactivate(id: UUID, expectedAggregateVersion: Long, actor: AutomationDefinitionActor): AutomationDefinitionView
    fun dryRun(id: UUID, version: Int, ticketNumber: Long, actor: AutomationDefinitionActor): AutomationDryRunResult
}

class AutomationNotFoundException : RuntimeException()
class AutomationConflictException(val code: String) : RuntimeException(code)
class AutomationPreconditionFailedException(val currentVersion: Long) : RuntimeException()
class AutomationAuditUnavailableException(cause: Throwable) : RuntimeException(cause)
