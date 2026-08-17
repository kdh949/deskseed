package dev.deskseed.workflow.internal

import dev.deskseed.workflow.ConditionNode
import dev.deskseed.workflow.InvalidConditionAstException
import dev.deskseed.workflow.VersionedConditionAst

internal object ConditionAstValidator {
    private const val MAX_DEPTH = 12
    private const val MAX_LEAVES = 100
    private const val MAX_CONFIG_BYTES = 16 * 1024

    fun validate(ast: VersionedConditionAst) {
        val leaves = validateNode(ast.root, depth = 1)
        if (leaves > MAX_LEAVES) {
            throw InvalidConditionAstException("Condition AST has more than $MAX_LEAVES leaves")
        }
    }

    private fun validateNode(node: ConditionNode, depth: Int): Int {
        if (depth > MAX_DEPTH) {
            throw InvalidConditionAstException("Condition AST exceeds depth $MAX_DEPTH")
        }
        return when (node) {
            is ConditionNode.All -> validateComposite("ALL", node.children, depth)
            is ConditionNode.Any -> validateComposite("ANY", node.children, depth)
            is ConditionNode.Not -> validateNode(node.child, depth + 1)
            is ConditionNode.Leaf -> {
                if (node.typeKey.isBlank() || node.schemaVersion <= 0) {
                    throw InvalidConditionAstException("Condition leaf descriptor identity is invalid")
                }
                if (node.config.toString().toByteArray().size > MAX_CONFIG_BYTES) {
                    throw InvalidConditionAstException("Condition leaf config exceeds $MAX_CONFIG_BYTES bytes")
                }
                1
            }
        }
    }

    private fun validateComposite(kind: String, children: List<ConditionNode>, depth: Int): Int {
        if (children.isEmpty()) {
            throw InvalidConditionAstException("$kind requires at least one child")
        }
        return children.sumOf { validateNode(it, depth + 1) }
    }
}
