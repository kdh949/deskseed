package dev.deskseed.workflow

import tools.jackson.databind.JsonNode

/**
 * Public, non-executable contract for configuration-driven workflow features.
 * Feature modules contribute handlers; they never extend a central enum or switch.
 */
data class WorkflowDescriptor(
    val key: String,
    val schemaVersion: Int,
    val displayName: String,
    val allowedContexts: Set<WorkflowContext>,
    val sensitive: Boolean = false,
    val optionSource: String? = null,
) {
    init {
        require(KEY.matches(key)) { "Descriptor key must be a stable lowercase dotted identifier" }
        require(schemaVersion > 0) { "Descriptor schemaVersion must be positive" }
        require(displayName.isNotBlank() && displayName.length <= 120) { "Descriptor displayName must be bounded" }
        require(allowedContexts.isNotEmpty()) { "Descriptor must declare at least one allowed context" }
        require(optionSource == null || optionSource.matches(KEY)) { "optionSource must be a stable descriptor key" }
    }

    val identity: WorkflowDescriptorIdentity = WorkflowDescriptorIdentity(key, schemaVersion)

    private companion object {
        val KEY = Regex("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*")
    }
}

data class WorkflowDescriptorIdentity(
    val key: String,
    val schemaVersion: Int,
)

enum class WorkflowContext {
    TICKET_FORM,
    SAVED_VIEW,
    MACRO,
    TRIGGER,
    AUTOMATION,
    ANALYTICS,
}

sealed interface ConditionNode {
    data class All(val children: List<ConditionNode>) : ConditionNode

    data class Any(val children: List<ConditionNode>) : ConditionNode

    data class Not(val child: ConditionNode) : ConditionNode

    data class Leaf(
        val typeKey: String,
        val schemaVersion: Int,
        val config: JsonNode,
    ) : ConditionNode
}

data class VersionedConditionAst(
    val schemaVersion: Int,
    val root: ConditionNode,
) {
    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) { "Unsupported condition AST schemaVersion" }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

data class WorkflowValidationIssue(
    val code: String,
    val path: String,
    val message: String,
)

enum class ConditionTruth {
    TRUE,
    FALSE,
    UNKNOWN,
}

data class ConditionEvaluationTrace(
    val path: String,
    val truth: ConditionTruth,
    val descriptor: WorkflowDescriptorIdentity? = null,
    val children: List<ConditionEvaluationTrace> = emptyList(),
)

data class ConditionEvaluation(
    val truth: ConditionTruth,
    val trace: ConditionEvaluationTrace,
)

data class RuleEvaluationContext(
    val context: WorkflowContext,
    /** Non-sensitive, feature-owned facts only. Raw ticket/comment/body values never belong in traces. */
    val facts: Map<String, Any?> = emptyMap(),
)

interface ConditionHandler {
    val descriptor: WorkflowDescriptor

    fun validate(config: JsonNode, catalog: WorkflowCatalog): List<WorkflowValidationIssue>

    fun evaluate(config: JsonNode, context: RuleEvaluationContext): ConditionTruth
}

data class ActionPlan(
    /** Typed, transaction-local mutations for a caller-owned application service. */
    val mutations: List<WorkflowMutation> = emptyList(),
    /** Durable intent data only. Workers execute external I/O after the transaction commits. */
    val postCommitIntents: List<PostCommitIntent> = emptyList(),
)

data class WorkflowMutation(
    val kind: String,
    val attributes: Map<String, String>,
) {
    init {
        require(kind.matches(Regex("[A-Z][A-Z0-9_]*"))) { "Workflow mutation kind must be stable" }
    }
}

data class PostCommitIntent(
    val kind: String,
    val attributes: Map<String, String>,
) {
    init {
        require(kind.matches(Regex("[A-Z][A-Z0-9_]*"))) { "Post-commit intent kind must be stable" }
    }
}

data class RuleExecutionContext(
    val context: WorkflowContext,
    val facts: Map<String, Any?> = emptyMap(),
)

interface ActionHandler {
    val descriptor: WorkflowDescriptor

    fun validate(config: JsonNode, catalog: WorkflowCatalog): List<WorkflowValidationIssue>

    /** Must only plan local mutation and durable post-commit intent; no network I/O occurs here. */
    fun plan(config: JsonNode, context: RuleExecutionContext): ActionPlan
}

interface TemplateVariableProvider {
    val descriptor: WorkflowDescriptor

    fun values(context: RuleEvaluationContext): Map<String, String>
}

interface QueryPredicateContributor {
    val descriptor: WorkflowDescriptor

    fun validate(config: JsonNode, catalog: WorkflowCatalog): List<WorkflowValidationIssue>
}

interface AnalyticsDimensionProvider {
    val descriptor: WorkflowDescriptor
}

interface WorkflowCatalog {
    fun condition(identity: WorkflowDescriptorIdentity): ConditionHandler

    fun action(identity: WorkflowDescriptorIdentity): ActionHandler

    fun descriptors(): List<WorkflowDescriptor>
}

class UnknownWorkflowDescriptorException(identity: WorkflowDescriptorIdentity) : RuntimeException(
    "Unknown workflow descriptor '${identity.key}' schema ${identity.schemaVersion}",
)

class DuplicateWorkflowDescriptorException(identity: WorkflowDescriptorIdentity) : RuntimeException(
    "Duplicate workflow descriptor '${identity.key}' schema ${identity.schemaVersion}",
)

class InvalidConditionAstException(message: String) : RuntimeException(message)
