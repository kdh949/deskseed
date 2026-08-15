package dev.deskseed.staffaccess.internal

import dev.deskseed.foundation.CommandContext
import dev.deskseed.foundation.CommandContexts
import dev.deskseed.foundation.RequestIdFilter
import dev.deskseed.foundation.RequestSource
import dev.deskseed.ticketing.AgentTicketNotFoundException
import dev.deskseed.ticketing.TicketAssignmentInvalidException
import dev.deskseed.ticketing.TicketAuditUnavailableException
import dev.deskseed.ticketing.TicketCommandIdReusedException
import dev.deskseed.ticketing.TicketCommandInvalidException
import dev.deskseed.ticketing.TicketCommandResult
import dev.deskseed.ticketing.TicketField
import dev.deskseed.ticketing.TicketFieldConflictException
import dev.deskseed.ticketing.TicketPriority
import dev.deskseed.ticketing.TicketStatus
import dev.deskseed.ticketing.TicketTransitionInvalidException
import dev.deskseed.ticketing.TicketUpdateContentionException
import dev.deskseed.ticketing.TicketVersionPreconditionFailedException
import dev.deskseed.ticketing.TicketWriteForbiddenException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.JsonNode
import java.util.UUID

internal enum class AgentTicketBatchOutcome {
    SUCCEEDED,
    CONFLICT,
    DENIED,
    NOT_FOUND,
    VALIDATION_FAILED,
}

internal data class AgentTicketBatchItemResult(
    val ticketNumber: Long,
    val clientCommandId: String,
    val outcome: AgentTicketBatchOutcome,
    val replayed: Boolean,
    val resultVersion: Long? = null,
    val auditId: UUID? = null,
    val code: String? = null,
)

internal data class AgentTicketBatchExecutionResult(
    val correlationId: String,
    val results: List<AgentTicketBatchItemResult>,
)

/**
 * Deliberately keeps the batch envelope non-transactional. Each item is delegated to
 * [AgentTicketBatchItemExecutor] so an item failure rolls back only that ticket and
 * its required TicketAudit, while committed sibling items remain durable.
 */
@Service
internal class AgentTicketBatchApplicationService(
    private val itemExecutor: AgentTicketBatchItemExecutor,
) {
    fun execute(
        principal: StaffPrincipal,
        items: List<AgentTicketBatchItemInput>,
        request: HttpServletRequest,
    ): AgentTicketBatchExecutionResult {
        require(items.size in 1..MAX_ITEMS) { "batch must contain between 1 and $MAX_ITEMS items" }
        require(items.map(AgentTicketBatchItemInput::ticketNumber).distinct().size == items.size) {
            "batch ticketNumber values must be unique"
        }
        require(items.map(AgentTicketBatchItemInput::clientCommandId).distinct().size == items.size) {
            "batch clientCommandId values must be unique"
        }

        val baseContext = CommandContexts.from(request, RequestSource.AGENT_UI)
        val results = items.map { item ->
            require(RequestIdFilter.isValidIdentifier(item.clientCommandId) && item.clientCommandId.length <= 100) {
                "clientCommandId must be a bounded identifier"
            }
            val context = baseContext.copy(commandId = item.clientCommandId)
            try {
                val command = item.parseCommand()
                val result = itemExecutor.execute(principal, item.ticketNumber, command, context)
                AgentTicketBatchItemResult(
                    ticketNumber = item.ticketNumber,
                    clientCommandId = item.clientCommandId,
                    outcome = AgentTicketBatchOutcome.SUCCEEDED,
                    replayed = result.replayed,
                    resultVersion = result.version,
                    auditId = result.auditId,
                )
            } catch (exception: TicketAuditUnavailableException) {
                throw exception
            } catch (exception: TicketUpdateContentionException) {
                throw exception
            } catch (exception: DataAccessException) {
                // A command cannot safely report a per-item failure if required
                // persistence/audit availability is unknown. The advice converts this
                // to the existing fail-closed 503 response.
                throw exception
            } catch (exception: TicketFieldConflictException) {
                item.result(AgentTicketBatchOutcome.CONFLICT, "TICKET_FIELD_CONFLICT")
            } catch (exception: TicketVersionPreconditionFailedException) {
                item.result(AgentTicketBatchOutcome.CONFLICT, "VERSION_PRECONDITION_FAILED")
            } catch (exception: TicketCommandIdReusedException) {
                item.result(AgentTicketBatchOutcome.CONFLICT, "CLIENT_COMMAND_ID_REUSED")
            } catch (exception: TicketWriteForbiddenException) {
                item.result(AgentTicketBatchOutcome.DENIED, "TICKET_WRITE_FORBIDDEN")
            } catch (exception: AgentTicketNotFoundException) {
                item.result(AgentTicketBatchOutcome.NOT_FOUND, "TICKET_NOT_FOUND")
            } catch (
                exception: TicketCommandInvalidException,
            ) {
                item.result(AgentTicketBatchOutcome.VALIDATION_FAILED, "VALIDATION_FAILED")
            } catch (
                exception: TicketAssignmentInvalidException,
            ) {
                item.result(AgentTicketBatchOutcome.VALIDATION_FAILED, "VALIDATION_FAILED")
            } catch (
                exception: TicketTransitionInvalidException,
            ) {
                item.result(AgentTicketBatchOutcome.VALIDATION_FAILED, "VALIDATION_FAILED")
            } catch (exception: IllegalArgumentException) {
                item.result(AgentTicketBatchOutcome.VALIDATION_FAILED, "VALIDATION_FAILED")
            }
        }
        return AgentTicketBatchExecutionResult(baseContext.correlationId, results)
    }

    private fun AgentTicketBatchItemInput.result(
        outcome: AgentTicketBatchOutcome,
        code: String,
    ) = AgentTicketBatchItemResult(ticketNumber, clientCommandId, outcome, replayed = false, code = code)

    private companion object {
        const val MAX_ITEMS = 100
    }
}

@Service
internal class AgentTicketBatchItemExecutor(
    private val commandService: AgentTicketCommandApplicationService,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun execute(
        principal: StaffPrincipal,
        ticketNumber: Long,
        command: ParsedAgentTicketBatchCommand,
        context: CommandContext,
    ): TicketCommandResult = when (command) {
        is ParsedAgentTicketBatchCommand.Update -> commandService.update(
            principal,
            ticketNumber,
            UpdateAgentTicketInput(
                expectedVersion = command.expectedVersion,
                changedFields = command.changedFields,
                status = command.status,
                priority = command.priority,
                groupId = null,
                assigneeId = command.assigneeId,
                comment = null,
            ),
            context,
        )

        is ParsedAgentTicketBatchCommand.Transfer -> commandService.transfer(
            principal,
            ticketNumber,
            TransferTicketInput(
                expectedVersion = command.expectedVersion,
                groupId = command.groupId,
                assigneeId = command.assigneeId,
                reason = command.reason,
            ),
            context,
        )
    }
}

internal data class AgentTicketBatchItemInput(
    val ticketNumber: Long,
    val expectedVersion: Long,
    val clientCommandId: String,
    val command: JsonNode,
) {
    fun parseCommand(): ParsedAgentTicketBatchCommand {
        require(command.isObject) { "batch command must be an object" }
        val type = command.requiredText("type")
        return when (type) {
            "UPDATE" -> parseUpdate()
            "TRANSFER" -> parseTransfer()
            else -> throw TicketCommandInvalidException("Unsupported batch command type")
        }
    }

    private fun parseUpdate(): ParsedAgentTicketBatchCommand.Update {
        command.requireOnly("type", "changedFields", "status", "priority", "assigneeId")
        val fieldsNode = command.required("changedFields")
        require(fieldsNode.isArray && fieldsNode.size() in 1..3) { "changedFields must contain one to three fields" }
        val fields = (0 until fieldsNode.size()).map { index ->
            val field = fieldsNode[index]
            require(field.isString) { "changedFields values must be strings" }
            when (field.asString()) {
                "status" -> TicketField.STATUS
                "priority" -> TicketField.PRIORITY
                "assigneeId" -> TicketField.ASSIGNEE_ID
                else -> throw TicketCommandInvalidException("Unsupported batch changed field")
            }
        }.toSet()
        require(fields.size == fieldsNode.size()) { "changedFields must be unique" }
        val status = command.optionalEnum("status", TicketStatus.entries.toList())
        val priority = command.optionalEnum("priority", TicketPriority.entries.toList())
        val assigneeId = command.optionalNullableUuid("assigneeId")
        require((TicketField.STATUS in fields) == (status != null)) { "status must exactly match changedFields" }
        require((TicketField.PRIORITY in fields) == (priority != null)) { "priority must exactly match changedFields" }
        require((TicketField.ASSIGNEE_ID in fields) == command.has("assigneeId")) {
            "assigneeId must exactly match changedFields"
        }
        return ParsedAgentTicketBatchCommand.Update(expectedVersion, fields, status, priority, assigneeId)
    }

    private fun parseTransfer(): ParsedAgentTicketBatchCommand.Transfer {
        command.requireOnly("type", "groupId", "assigneeId", "reason")
        val groupId = command.requiredUuid("groupId")
        val reason = command.requiredText("reason")
        require(reason.length <= 1_000 && reason.none(Char::isISOControl)) { "transfer reason is invalid" }
        return ParsedAgentTicketBatchCommand.Transfer(
            expectedVersion = expectedVersion,
            groupId = groupId,
            assigneeId = command.optionalNullableUuid("assigneeId"),
            reason = reason,
        )
    }

    private fun JsonNode.requireOnly(vararg names: String) {
        val allowed = names.toSet()
        require(properties().asSequence().map(Map.Entry<String, JsonNode>::key).all(allowed::contains)) {
            "batch command contains an unsupported property"
        }
    }

    private fun JsonNode.required(name: String): JsonNode = get(name)
        ?: throw TicketCommandInvalidException("$name is required")

    private fun JsonNode.requiredText(name: String): String {
        val value = required(name)
        require(value.isString && value.asString().isNotBlank()) { "$name must be a non-empty string" }
        return value.asString()
    }

    private fun JsonNode.requiredUuid(name: String): UUID = parseUuid(requiredText(name), name)

    private fun JsonNode.optionalNullableUuid(name: String): UUID? {
        if (!has(name)) return null
        val value = required(name)
        if (value.isNull) return null
        require(value.isString) { "$name must be a UUID or null" }
        return parseUuid(value.asString(), name)
    }

    private fun <T : Enum<T>> JsonNode.optionalEnum(name: String, values: List<T>): T? {
        if (!has(name)) return null
        val value = required(name)
        require(value.isString) { "$name must be a string enum" }
        return values.firstOrNull { it.name == value.asString() }
            ?: throw TicketCommandInvalidException("$name is not an allowlisted enum value")
    }

    private fun parseUuid(value: String, name: String): UUID = runCatching { UUID.fromString(value) }
        .getOrElse { throw TicketCommandInvalidException("$name must be a UUID") }
}

internal sealed interface ParsedAgentTicketBatchCommand {
    data class Update(
        val expectedVersion: Long,
        val changedFields: Set<TicketField>,
        val status: TicketStatus?,
        val priority: TicketPriority?,
        val assigneeId: UUID?,
    ) : ParsedAgentTicketBatchCommand

    data class Transfer(
        val expectedVersion: Long,
        val groupId: UUID,
        val assigneeId: UUID?,
        val reason: String,
    ) : ParsedAgentTicketBatchCommand
}
