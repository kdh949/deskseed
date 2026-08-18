package dev.deskseed.workflow.internal

import dev.deskseed.workflow.ActionHandler
import dev.deskseed.workflow.ActionPlan
import dev.deskseed.workflow.ConditionHandler
import dev.deskseed.workflow.ConditionNode
import dev.deskseed.workflow.ConditionTruth
import dev.deskseed.workflow.DuplicateWorkflowDescriptorException
import dev.deskseed.workflow.InvalidConditionAstException
import dev.deskseed.workflow.RuleEvaluationContext
import dev.deskseed.workflow.RuleExecutionContext
import dev.deskseed.workflow.UnknownWorkflowDescriptorException
import dev.deskseed.workflow.VersionedConditionAst
import dev.deskseed.workflow.WorkflowCatalog
import dev.deskseed.workflow.WorkflowContext
import dev.deskseed.workflow.WorkflowDescriptor
import dev.deskseed.workflow.WorkflowDescriptorIdentity
import dev.deskseed.workflow.WorkflowValidationIssue
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper

@dev.deskseed.testsupport.category.FastTest
class WorkflowRegistryTest {
    private val mapper = ObjectMapper()

    @Test
    fun `rejects duplicate descriptor key and schema version across extension kinds`() {
        val descriptor = descriptor("ticket.equals")

        assertThatThrownBy {
            SpringWorkflowCatalog(
                conditions = listOf(condition(descriptor)),
                actions = listOf(action(descriptor)),
                variables = emptyList(),
                predicates = emptyList(),
                dimensions = emptyList(),
            )
        }.isInstanceOf(DuplicateWorkflowDescriptorException::class.java)
    }

    @Test
    fun `fails closed for an unknown condition descriptor`() {
        val catalog = SpringWorkflowCatalog(
            conditions = listOf(condition(descriptor("ticket.equals"))),
            actions = emptyList(),
            variables = emptyList(),
            predicates = emptyList(),
            dimensions = emptyList(),
        )

        assertThatThrownBy { catalog.condition(WorkflowDescriptorIdentity("ticket.unknown", 1)) }
            .isInstanceOf(UnknownWorkflowDescriptorException::class.java)
    }

    @Test
    fun `rejects unsafe condition ast depth and oversized leaf config`() {
        var deep: ConditionNode = ConditionNode.Leaf("ticket.equals", 1, mapper.readTree("{}"))
        repeat(12) { deep = ConditionNode.Not(deep) }
        assertThatThrownBy { ConditionAstValidator.validate(VersionedConditionAst(1, deep)) }
            .isInstanceOf(InvalidConditionAstException::class.java)

        val oversized = "x".repeat(16 * 1024 + 1)
        val leaf = ConditionNode.Leaf("ticket.equals", 1, mapper.readTree("{\"value\":\"$oversized\"}"))
        assertThatThrownBy { ConditionAstValidator.validate(VersionedConditionAst(1, leaf)) }
            .isInstanceOf(InvalidConditionAstException::class.java)
    }

    private fun descriptor(key: String) = WorkflowDescriptor(
        key = key,
        schemaVersion = 1,
        displayName = key,
        allowedContexts = setOf(WorkflowContext.TICKET_FORM),
    )

    private fun condition(descriptor: WorkflowDescriptor): ConditionHandler = object : ConditionHandler {
        override val descriptor = descriptor

        override fun validate(
            config: tools.jackson.databind.JsonNode,
            catalog: WorkflowCatalog,
        ): List<WorkflowValidationIssue> = emptyList()

        override fun evaluate(
            config: tools.jackson.databind.JsonNode,
            context: RuleEvaluationContext,
        ) = ConditionTruth.TRUE
    }

    private fun action(descriptor: WorkflowDescriptor): ActionHandler = object : ActionHandler {
        override val descriptor = descriptor

        override fun validate(
            config: tools.jackson.databind.JsonNode,
            catalog: WorkflowCatalog,
        ): List<WorkflowValidationIssue> = emptyList()

        override fun plan(
            config: tools.jackson.databind.JsonNode,
            context: RuleExecutionContext,
        ) = ActionPlan()
    }
}
