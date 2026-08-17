package dev.deskseed.ticketing

import dev.deskseed.ticketing.SavedViewConditionOperator.EQUALS
import dev.deskseed.ticketing.SavedViewConditionOperator.IN
import dev.deskseed.ticketing.SavedViewConditionOperator.IS_CURRENT_ACTOR
import dev.deskseed.ticketing.SavedViewConditionOperator.IS_CURRENT_ACTOR_GROUP
import dev.deskseed.ticketing.SavedViewConditionOperator.IS_UNASSIGNED
import dev.deskseed.ticketing.SavedViewConditionOperator.LESS_THAN_SOLVED
import dev.deskseed.ticketing.SavedViewConditionOperator.NOT_EQUALS
import dev.deskseed.ticketing.SavedViewConditionOperator.NOT_IN
import dev.deskseed.ticketing.SavedViewConditionOperator.WITHIN_LAST_DAYS
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * Persisted view definitions deliberately use a small, typed AST.  They are never a
 * ticket-id collection or an executable expression language.
 */
enum class SavedViewScope {
    SYSTEM,
    PERSONAL,
    SHARED,
}

enum class SavedViewConditionField {
    STATUS,
    PRIORITY,
    GROUP,
    ASSIGNEE,
    FIRST_REPLY_SLA_STATE,
    TICKET_KIND,
    UPDATED_AT,
}

enum class SavedViewConditionOperator {
    EQUALS,
    NOT_EQUALS,
    IN,
    NOT_IN,
    IS_CURRENT_ACTOR,
    IS_UNASSIGNED,
    IS_CURRENT_ACTOR_GROUP,
    LESS_THAN_SOLVED,
    WITHIN_LAST_DAYS,
}

enum class SavedViewColumn {
    TICKET_NUMBER,
    SUBJECT,
    STATUS,
    PRIORITY,
    GROUP,
    ASSIGNEE,
    UPDATED_AT,
    FIRST_REPLY_SLA,
}

data class SavedViewCondition(
    val field: SavedViewConditionField,
    val operator: SavedViewConditionOperator,
    val values: List<String>,
)

data class SavedViewConditions(
    val version: Int,
    val all: List<SavedViewCondition>,
    val any: List<SavedViewCondition>,
)

data class SavedViewDefinition(
    val name: String,
    val description: String = "",
    val conditions: SavedViewConditions,
    val columns: List<SavedViewColumn>,
    val sort: String,
)

data class SavedTicketView(
    val id: UUID,
    val key: String,
    val scope: SavedViewScope,
    val ownerStaffId: UUID?,
    val definition: SavedViewDefinition,
    val active: Boolean,
    val definitionVersion: Long,
    val orderVersion: Long,
    val categoryPath: List<String>,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class SavedViewOrder(
    val scope: SavedViewScope,
    val orderVersion: Long,
    val viewKeys: List<String>,
)

data class SavedViewCountBatch(
    val counts: Map<UUID, Long>,
    val asOf: Instant,
)

interface SavedViewStore {
    fun listVisible(actorId: UUID): List<SavedTicketView>

    fun findByKey(key: String): SavedTicketView?

    fun create(
        key: String,
        scope: SavedViewScope,
        ownerStaffId: UUID?,
        definition: SavedViewDefinition,
        createdAt: Instant,
    ): SavedTicketView

    fun update(
        id: UUID,
        expectedVersion: Long,
        definition: SavedViewDefinition,
        updatedAt: Instant,
    ): SavedTicketView

    fun delete(id: UUID, expectedVersion: Long, updatedAt: Instant): Boolean

    fun reorder(
        scope: SavedViewScope,
        ownerStaffId: UUID?,
        expectedOrderVersion: Long,
        viewKeys: List<String>,
        updatedAt: Instant,
    ): SavedViewOrder
}

object SavedViewDefinitionRules {
    const val MAX_VISIBLE_COUNTED_VIEWS = 20
    const val STABLE_SORT = "updatedAt:desc,ticketNumber:desc"

    fun validate(definition: SavedViewDefinition) {
        require(definition.name.trim().isNotEmpty() && definition.name.length <= 120) {
            "Saved view name must contain between 1 and 120 characters"
        }
        require(definition.description.length <= 500 && definition.description.none(Char::isISOControl)) {
            "Saved view description must contain at most 500 characters without control characters"
        }
        require(definition.columns.size in 1..12 && definition.columns.distinct().size == definition.columns.size) {
            "Saved view columns must be unique and contain between 1 and 12 values"
        }
        require(definition.sort == STABLE_SORT) { "Unsupported saved view sort" }
        validateConditions(definition.conditions)
    }

    fun validateConditions(conditions: SavedViewConditions) {
        require(conditions.version == 1) { "Unsupported saved view condition version" }
        require(conditions.all.size <= 12 && conditions.any.size <= 12) {
            "Saved view conditions exceed the maximum size"
        }
        require(conditions.all.isNotEmpty() || conditions.any.isNotEmpty()) {
            "Saved view conditions cannot be empty"
        }
        val seen = mutableSetOf<String>()
        (conditions.all + conditions.any).forEach { condition ->
            validateCondition(condition)
            require(seen.add(canonicalCondition(condition))) { "Saved view conditions cannot be duplicated" }
        }
    }

    fun fingerprint(definition: SavedViewDefinition): String = MessageDigest.getInstance("SHA-256")
        .digest(canonicalDefinition(definition).toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    fun canonicalDefinition(definition: SavedViewDefinition): String = listOf(
        definition.name.trim(),
        definition.sort,
        definition.columns.joinToString(",") { it.name },
        canonicalConditions(definition.conditions),
    ).joinToString("|")

    fun canonicalConditions(conditions: SavedViewConditions): String = listOf(
        conditions.version.toString(),
        conditions.all.joinToString(separator = ";", transform = ::canonicalCondition),
        conditions.any.joinToString(separator = ";", transform = ::canonicalCondition),
    ).joinToString("|")

    private fun canonicalCondition(condition: SavedViewCondition): String = listOf(
        condition.field.name,
        condition.operator.name,
        condition.values.joinToString(","),
    ).joinToString(":")

    private fun validateCondition(condition: SavedViewCondition) {
        require(condition.values.size <= 10) { "Saved view condition has too many values" }
        require(condition.values.all { it.isNotBlank() && it.length <= 100 && it.none(Char::isISOControl) }) {
            "Saved view condition values are invalid"
        }
        when (condition.field) {
            SavedViewConditionField.STATUS -> {
                require(condition.operator in setOf(EQUALS, NOT_EQUALS, IN, NOT_IN, LESS_THAN_SOLVED)) {
                    "Unsupported status condition"
                }
                validateEnumValues(condition, TicketStatus.entries.map(TicketStatus::name).toSet())
            }
            SavedViewConditionField.PRIORITY -> {
                require(condition.operator in setOf(EQUALS, NOT_EQUALS, IN, NOT_IN)) {
                    "Unsupported priority condition"
                }
                validateEnumValues(condition, TicketPriority.entries.map(TicketPriority::name).toSet())
            }
            SavedViewConditionField.GROUP -> when (condition.operator) {
                EQUALS, NOT_EQUALS, IN, NOT_IN -> validateUuidValues(condition)
                IS_CURRENT_ACTOR_GROUP -> requireEmptyValues(condition)
                else -> throw IllegalArgumentException("Unsupported group condition")
            }
            SavedViewConditionField.ASSIGNEE -> when (condition.operator) {
                EQUALS, NOT_EQUALS, IN, NOT_IN -> validateUuidValues(condition)
                IS_CURRENT_ACTOR, IS_UNASSIGNED -> requireEmptyValues(condition)
                else -> throw IllegalArgumentException("Unsupported assignee condition")
            }
            SavedViewConditionField.FIRST_REPLY_SLA_STATE -> {
                require(condition.operator in setOf(EQUALS, NOT_EQUALS, IN, NOT_IN)) {
                    "Unsupported SLA condition"
                }
                validateEnumValues(condition, StaffSlaDisplayState.entries.map(StaffSlaDisplayState::name).toSet())
            }
            SavedViewConditionField.TICKET_KIND -> {
                require(condition.operator in setOf(EQUALS, NOT_EQUALS, IN, NOT_IN)) {
                    "Unsupported ticket kind condition"
                }
                validateEnumValues(condition, TicketKind.entries.map(TicketKind::name).toSet())
            }
            SavedViewConditionField.UPDATED_AT -> {
                require(condition.operator == WITHIN_LAST_DAYS) { "Unsupported updated-at condition" }
                require(condition.values.size == 1 && condition.values.single().toIntOrNull() in 1..365) {
                    "updated-at condition must contain one bounded day count"
                }
            }
        }
    }

    private fun validateEnumValues(condition: SavedViewCondition, allowed: Set<String>) {
        if (condition.operator == LESS_THAN_SOLVED) {
            requireEmptyValues(condition)
            return
        }
        require(condition.values.isNotEmpty() && condition.values.all(allowed::contains)) {
            "Saved view condition values are not allowlisted"
        }
        if (condition.operator in setOf(EQUALS, NOT_EQUALS)) {
            require(condition.values.size == 1) { "Equality conditions require exactly one value" }
        }
    }

    private fun validateUuidValues(condition: SavedViewCondition) {
        require(condition.values.isNotEmpty() && condition.values.all { value ->
            runCatching { UUID.fromString(value) }.isSuccess
        }) { "Saved view condition UUID values are invalid" }
        if (condition.operator in setOf(EQUALS, NOT_EQUALS)) {
            require(condition.values.size == 1) { "Equality conditions require exactly one value" }
        }
    }

    private fun requireEmptyValues(condition: SavedViewCondition) {
        require(condition.values.isEmpty()) { "Saved view condition must not contain values" }
    }
}

class SavedViewNotFoundException : RuntimeException()

class SavedViewAccessDeniedException : RuntimeException()

class SavedViewConflictException : RuntimeException()

class SavedViewPreconditionFailedException : RuntimeException()
