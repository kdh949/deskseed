package dev.deskseed.ticketconfiguration.internal

import dev.deskseed.ticketconfiguration.TicketConfigurationValidationException
import dev.deskseed.ticketconfiguration.TicketFormValidationIssue
import dev.deskseed.workflow.ConditionNode
import dev.deskseed.workflow.ConditionTruth
import dev.deskseed.workflow.InvalidConditionAstException
import dev.deskseed.workflow.RuleEvaluationContext
import dev.deskseed.workflow.VersionedConditionAst
import dev.deskseed.workflow.WorkflowCatalog
import dev.deskseed.workflow.WorkflowContext
import dev.deskseed.workflow.WorkflowDescriptor
import dev.deskseed.workflow.WorkflowDescriptorIdentity
import dev.deskseed.workflow.ConditionHandler
import dev.deskseed.workflow.WorkflowValidationIssue
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import java.util.UUID

/**
 * Parses only the non-executable Foundation AST wire representation. Feature
 * condition types are located through WorkflowCatalog, never a local type-key switch.
 */
@Component
internal class TicketFormConditionEngine(
    private val catalog: WorkflowCatalog,
) {
    fun validate(raw: JsonNode, path: String): List<TicketFormValidationIssue> = try {
        val ast = parse(raw)
        val issues = mutableListOf<TicketFormValidationIssue>()
        validateNode(ast.root, "$path.root", issues)
        issues
    } catch (failure: RuntimeException) {
        listOf(TicketFormValidationIssue("INVALID_CONDITION_AST", path, failure.message ?: "Invalid condition AST"))
    }

    fun evaluate(raw: JsonNode, facts: Map<String, String>): ConditionTruth = evaluateNode(
        parse(raw).root,
        RuleEvaluationContext(WorkflowContext.TICKET_FORM, facts),
    )

    fun referencedFieldIds(raw: JsonNode): Set<UUID> = try {
        val fields = linkedSetOf<UUID>()
        collectReferencedFields(parse(raw).root, fields)
        fields
    } catch (_: RuntimeException) {
        emptySet()
    }

    private fun validateNode(
        node: ConditionNode,
        path: String,
        issues: MutableList<TicketFormValidationIssue>,
    ) {
        when (node) {
            is ConditionNode.All -> node.children.forEachIndexed { index, child -> validateNode(child, "$path.children[$index]", issues) }
            is ConditionNode.Any -> node.children.forEachIndexed { index, child -> validateNode(child, "$path.children[$index]", issues) }
            is ConditionNode.Not -> validateNode(node.child, "$path.child", issues)
            is ConditionNode.Leaf -> {
                val handler = catalog.condition(WorkflowDescriptorIdentity(node.typeKey, node.schemaVersion))
                if (WorkflowContext.TICKET_FORM !in handler.descriptor.allowedContexts) {
                    issues += TicketFormValidationIssue(
                        "CONDITION_CONTEXT_NOT_ALLOWED",
                        path,
                        "Condition descriptor is not allowed in a ticket form",
                    )
                }
                issues += handler.validate(node.config, catalog).map { it.toTicketFormIssue(path) }
            }
        }
    }

    private fun evaluateNode(node: ConditionNode, context: RuleEvaluationContext): ConditionTruth = when (node) {
        is ConditionNode.All -> node.children.fold(ConditionTruth.TRUE) { result, child -> result.and(evaluateNode(child, context)) }
        is ConditionNode.Any -> node.children.fold(ConditionTruth.FALSE) { result, child -> result.or(evaluateNode(child, context)) }
        is ConditionNode.Not -> evaluateNode(node.child, context).not()
        is ConditionNode.Leaf -> catalog.condition(WorkflowDescriptorIdentity(node.typeKey, node.schemaVersion))
            .evaluate(node.config, context)
    }

    private fun collectReferencedFields(node: ConditionNode, fields: MutableSet<UUID>) {
        when (node) {
            is ConditionNode.All -> node.children.forEach { collectReferencedFields(it, fields) }
            is ConditionNode.Any -> node.children.forEach { collectReferencedFields(it, fields) }
            is ConditionNode.Not -> collectReferencedFields(node.child, fields)
            is ConditionNode.Leaf -> node.config.path("fact").asText().removePrefix("field.")
                .takeIf { node.config.path("fact").asText().startsWith("field.") }
                ?.let { UUID.fromString(it) }
                ?.let(fields::add)
        }
    }

    private fun parse(raw: JsonNode): VersionedConditionAst {
        if (!raw.isObject || raw.path("schemaVersion").asInt(-1) != VersionedConditionAst.CURRENT_SCHEMA_VERSION) {
            throw InvalidConditionAstException("Condition AST schemaVersion must be 1")
        }
        return VersionedConditionAst(VersionedConditionAst.CURRENT_SCHEMA_VERSION, parseNode(raw.path("root"), 1))
    }

    private fun parseNode(node: JsonNode, depth: Int): ConditionNode {
        if (depth > MAX_DEPTH || !node.isObject) throw InvalidConditionAstException("Condition AST node is invalid")
        return when (node.path("kind").asText()) {
            "ALL" -> ConditionNode.All(children(node, depth))
            "ANY" -> ConditionNode.Any(children(node, depth))
            "NOT" -> ConditionNode.Not(parseNode(node.path("child"), depth + 1))
            "LEAF" -> {
                val typeKey = node.path("typeKey").asText()
                val schemaVersion = node.path("schemaVersion").asInt(0)
                val config = node.path("config")
                if (typeKey.isBlank() || schemaVersion <= 0 || !config.isObject || config.toString().toByteArray().size > MAX_CONFIG_BYTES) {
                    throw InvalidConditionAstException("Condition leaf is invalid")
                }
                ConditionNode.Leaf(typeKey, schemaVersion, config)
            }
            else -> throw InvalidConditionAstException("Condition node kind is invalid")
        }
    }

    private fun children(node: JsonNode, depth: Int): List<ConditionNode> {
        val children = node.path("children")
        if (!children.isArray || children.isEmpty) throw InvalidConditionAstException("Composite condition requires children")
        val parsed = children.iterator().asSequence().map { child -> parseNode(child, depth + 1) }.toList()
        if (parsed.sumOf(::leaves) > MAX_LEAVES) throw InvalidConditionAstException("Condition AST has too many leaves")
        return parsed
    }

    private fun leaves(node: ConditionNode): Int = when (node) {
        is ConditionNode.All -> node.children.sumOf(::leaves)
        is ConditionNode.Any -> node.children.sumOf(::leaves)
        is ConditionNode.Not -> leaves(node.child)
        is ConditionNode.Leaf -> 1
    }

    private fun WorkflowValidationIssue.toTicketFormIssue(pathPrefix: String) = TicketFormValidationIssue(
        code,
        "$pathPrefix.$path",
        message,
    )

    private fun ConditionTruth.and(other: ConditionTruth): ConditionTruth = when {
        this == ConditionTruth.FALSE || other == ConditionTruth.FALSE -> ConditionTruth.FALSE
        this == ConditionTruth.TRUE && other == ConditionTruth.TRUE -> ConditionTruth.TRUE
        else -> ConditionTruth.UNKNOWN
    }

    private fun ConditionTruth.or(other: ConditionTruth): ConditionTruth = when {
        this == ConditionTruth.TRUE || other == ConditionTruth.TRUE -> ConditionTruth.TRUE
        this == ConditionTruth.FALSE && other == ConditionTruth.FALSE -> ConditionTruth.FALSE
        else -> ConditionTruth.UNKNOWN
    }

    private fun ConditionTruth.not(): ConditionTruth = when (this) {
        ConditionTruth.TRUE -> ConditionTruth.FALSE
        ConditionTruth.FALSE -> ConditionTruth.TRUE
        ConditionTruth.UNKNOWN -> ConditionTruth.UNKNOWN
    }

    private companion object {
        const val MAX_DEPTH = 12
        const val MAX_LEAVES = 100
        const val MAX_CONFIG_BYTES = 16 * 1024
    }
}

@Component
internal class TicketFormFactEqualsCondition : ConditionHandler {
    override val descriptor = WorkflowDescriptor(
        key = "ticket.form.fact-equals",
        schemaVersion = 1,
        displayName = "Ticket form fact equals",
        allowedContexts = setOf(WorkflowContext.TICKET_FORM),
    )

    override fun validate(config: JsonNode, catalog: WorkflowCatalog): List<WorkflowValidationIssue> {
        val fact = config.path("fact").asText()
        val equals = config.path("equals").asText()
        return buildList {
            if (!isAllowedFact(fact)) add(WorkflowValidationIssue("INVALID_FACT", "fact", "Condition fact is not allowlisted"))
            if (equals.isBlank() || equals.length > 120 || equals.any(Char::isISOControl)) {
                add(WorkflowValidationIssue("INVALID_EXPECTED_VALUE", "equals", "Condition expected value is invalid"))
            }
        }
    }

    override fun evaluate(config: JsonNode, context: RuleEvaluationContext): ConditionTruth {
        val fact = config.path("fact").asText()
        val equals = config.path("equals").asText()
        if (!isAllowedFact(fact)) return ConditionTruth.UNKNOWN
        val actual = context.facts[fact]?.toString() ?: return ConditionTruth.UNKNOWN
        return if (actual == equals) ConditionTruth.TRUE else ConditionTruth.FALSE
    }

    private fun isAllowedFact(value: String): Boolean = value in CORE_FACTS || FIELD_FACT.matches(value)

    private companion object {
        val CORE_FACTS = setOf(
            "actorKind", "staffRole", "capability", "groupId", "formId", "formVersion",
            "statusCategory", "customStatusId", "ticketKind", "channel",
        )
        val FIELD_FACT = Regex("field\\.[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
