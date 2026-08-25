package dev.deskseed.trigger

import dev.deskseed.foundation.RequestSource
import dev.deskseed.ticketing.TicketPriority
import java.time.Instant
import java.util.Locale
import java.util.UUID

enum class TriggerEventType { TICKET_CREATED, TICKET_UPDATED }

fun TriggerEventType.requireImplemented() {
    if (this != TriggerEventType.TICKET_CREATED) {
        throw TriggerValidationException("TRIGGER_EVENT_NOT_IMPLEMENTED", "Only TICKET_CREATED is implemented")
    }
}

enum class TriggerConditionGroup { ALL, ANY }
enum class TriggerConditionField { EVENT, PRIORITY, GROUP }
enum class TriggerConditionOperator { IS, IS_NOT, PRESENT, NOT_PRESENT }
enum class TriggerActionType { SET_GROUP, ENQUEUE_WEBHOOK }

data class TriggerConditionDefinition(
    val group: TriggerConditionGroup,
    val field: TriggerConditionField,
    val operator: TriggerConditionOperator,
    val value: String?,
) {
    init {
        when (field) {
            TriggerConditionField.EVENT -> {
                require(operator in setOf(TriggerConditionOperator.IS, TriggerConditionOperator.IS_NOT))
                TriggerEventType.valueOf(requireNotNull(value))
            }
            TriggerConditionField.PRIORITY -> {
                require(operator in setOf(TriggerConditionOperator.IS, TriggerConditionOperator.IS_NOT))
                TicketPriority.valueOf(requireNotNull(value))
            }
            TriggerConditionField.GROUP -> {
                require(operator in setOf(TriggerConditionOperator.PRESENT, TriggerConditionOperator.NOT_PRESENT))
                require(value == null)
            }
        }
    }
}

sealed interface TriggerActionDefinition { val type: TriggerActionType }

data class TriggerSetGroupAction(val groupId: UUID) : TriggerActionDefinition {
    override val type = TriggerActionType.SET_GROUP
}

data class TriggerWebhookAction(val eventType: String = WEBHOOK_EVENT_TYPE) : TriggerActionDefinition {
    override val type = TriggerActionType.ENQUEUE_WEBHOOK

    init {
        require(eventType == WEBHOOK_EVENT_TYPE) { "Only the versioned trigger execution event is supported" }
    }

    companion object { const val WEBHOOK_EVENT_TYPE = "ticket.trigger.executed" }
}

data class TriggerDefinitionDraft(
    val name: String,
    val conditions: List<TriggerConditionDefinition>,
    val actions: List<TriggerActionDefinition>,
) {
    init {
        require(name.trim().length in 1..120 && name.none(Char::isISOControl)) { "Trigger name is invalid" }
        require(conditions.size in 1..50) { "Trigger requires between 1 and 50 conditions" }
        require(actions.size in 1..50) { "Trigger requires between 1 and 50 actions" }
        require(conditions.any { it.field == TriggerConditionField.EVENT }) { "Trigger requires an event condition" }
        conditions.filter { it.field == TriggerConditionField.EVENT }.forEach { condition ->
            TriggerEventType.valueOf(requireNotNull(condition.value)).requireImplemented()
        }
        require(actions.count { it.type == TriggerActionType.SET_GROUP } <= 1) { "Trigger can set group at most once" }
        require(actions.count { it.type == TriggerActionType.ENQUEUE_WEBHOOK } <= 1) { "Trigger can enqueue a webhook at most once" }
    }

    val normalizedName: String get() = name.trim().lowercase(Locale.ROOT)
}

data class TriggerDefinitionActor(
    val staffId: UUID,
    val displayName: String,
    val isAdmin: Boolean,
    val authorities: Set<String>,
    val source: RequestSource,
    val requestId: String,
    val correlationId: String,
)

data class TriggerDefinitionView(
    val id: UUID,
    val name: String,
    val position: Int,
    val currentVersion: Int,
    val activeVersion: Int?,
    val aggregateVersion: Long,
    val conditions: List<TriggerConditionDefinition>,
    val actions: List<TriggerActionDefinition>,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class TriggerDryRunResult(
    val ticketNumber: Long,
    val triggerId: UUID,
    val triggerVersion: Int,
    val matched: Boolean,
    val matchedConditions: List<Int>,
    val unmatchedConditions: List<Int>,
    val proposedActions: List<TriggerActionType>,
    val invariantFailures: List<String>,
)

interface TriggerDefinitionAdministration {
    fun list(actor: TriggerDefinitionActor): List<TriggerDefinitionView>
    fun create(position: Int, draft: TriggerDefinitionDraft, actor: TriggerDefinitionActor): TriggerDefinitionView
    fun createVersion(triggerId: UUID, expectedAggregateVersion: Long, draft: TriggerDefinitionDraft, actor: TriggerDefinitionActor): TriggerDefinitionView
    fun activate(triggerId: UUID, triggerVersion: Int, expectedAggregateVersion: Long, actor: TriggerDefinitionActor): TriggerDefinitionView
    fun deactivate(triggerId: UUID, expectedAggregateVersion: Long, actor: TriggerDefinitionActor): TriggerDefinitionView
    fun reposition(triggerId: UUID, position: Int, expectedAggregateVersion: Long, actor: TriggerDefinitionActor): TriggerDefinitionView
    fun dryRun(triggerId: UUID, triggerVersion: Int, ticketNumber: Long, eventType: TriggerEventType, actor: TriggerDefinitionActor): TriggerDryRunResult
}

class TriggerNotFoundException : RuntimeException()
class TriggerConflictException(val code: String) : RuntimeException(code)
class TriggerPreconditionFailedException(val currentVersion: Long) : RuntimeException()
class TriggerValidationException(val code: String, message: String) : IllegalArgumentException(message)
class TriggerAuditUnavailableException(cause: Throwable) : RuntimeException(cause)
