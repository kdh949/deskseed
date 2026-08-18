package dev.deskseed.workflow.internal

import dev.deskseed.workflow.ActionHandler
import dev.deskseed.workflow.AnalyticsDimensionProvider
import dev.deskseed.workflow.ConditionHandler
import dev.deskseed.workflow.DuplicateWorkflowDescriptorException
import dev.deskseed.workflow.QueryPredicateContributor
import dev.deskseed.workflow.TemplateVariableProvider
import dev.deskseed.workflow.UnknownWorkflowDescriptorException
import dev.deskseed.workflow.WorkflowCatalog
import dev.deskseed.workflow.WorkflowDescriptor
import dev.deskseed.workflow.WorkflowDescriptorIdentity
import org.springframework.stereotype.Component

@Component
internal class SpringWorkflowCatalog(
    conditions: List<ConditionHandler>,
    actions: List<ActionHandler>,
    variables: List<TemplateVariableProvider>,
    predicates: List<QueryPredicateContributor>,
    dimensions: List<AnalyticsDimensionProvider>,
) : WorkflowCatalog {
    private val conditionByIdentity = index(conditions) { it.descriptor }
    private val actionByIdentity = index(actions) { it.descriptor }
    private val allDescriptors = (
        conditions.map { it.descriptor } +
            actions.map { it.descriptor } +
            variables.map { it.descriptor } +
            predicates.map { it.descriptor } +
            dimensions.map { it.descriptor }
        ).sortedWith(compareBy(WorkflowDescriptor::key, WorkflowDescriptor::schemaVersion))

    init {
        index(allDescriptors) { it }
    }

    override fun condition(identity: WorkflowDescriptorIdentity): ConditionHandler =
        conditionByIdentity[identity] ?: throw UnknownWorkflowDescriptorException(identity)

    override fun action(identity: WorkflowDescriptorIdentity): ActionHandler =
        actionByIdentity[identity] ?: throw UnknownWorkflowDescriptorException(identity)

    override fun descriptors(): List<WorkflowDescriptor> = allDescriptors

    private fun <T> index(values: List<T>, descriptor: (T) -> WorkflowDescriptor): Map<WorkflowDescriptorIdentity, T> {
        val indexed = linkedMapOf<WorkflowDescriptorIdentity, T>()
        values.forEach { candidate ->
            val identity = descriptor(candidate).identity
            if (indexed.putIfAbsent(identity, candidate) != null) {
                throw DuplicateWorkflowDescriptorException(identity)
            }
        }
        return indexed
    }
}
