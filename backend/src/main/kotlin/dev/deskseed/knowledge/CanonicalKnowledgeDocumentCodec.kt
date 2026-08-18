package dev.deskseed.knowledge

import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/** Strict JSON boundary for the frozen vendor-neutral block contract. */
class CanonicalKnowledgeDocumentCodec {
    fun decodeJson(json: String, objectMapper: ObjectMapper): CanonicalKnowledgeDocument = decode(objectMapper.readTree(json))

    fun decode(node: JsonNode): CanonicalKnowledgeDocument {
        require(node.isObject) { "knowledge document must be an object" }
        node.requireOnly("schemaVersion", "blocks")
        val schemaVersion = node.required("schemaVersion").takeIf(JsonNode::isInt)?.asInt()
            ?: throw InvalidKnowledgeDocumentException("knowledge document schemaVersion must be an integer")
        val blocksNode = node.required("blocks")
        require(blocksNode.isArray) { "knowledge document blocks must be an array" }
        return CanonicalKnowledgeDocument(
            schemaVersion,
            blocksNode.values().map(::decodeBlock),
        )
    }

    fun encode(document: CanonicalKnowledgeDocument): Map<String, Any> = linkedMapOf(
        "schemaVersion" to document.schemaVersion,
        "blocks" to document.blocks.map(::encodeBlock),
    )

    private fun decodeBlock(node: JsonNode): KnowledgeBlock {
        require(node.isObject) { "knowledge block must be an object" }
        return when (node.requiredText("type")) {
            "paragraph" -> node.only("type", "text") { KnowledgeBlock.Paragraph(node.requiredText("text")) }
            "heading" -> node.only("type", "level", "text") {
                KnowledgeBlock.Heading(node.required("level").takeIf(JsonNode::isInt)?.asInt()
                    ?: throw InvalidKnowledgeDocumentException("knowledge heading level must be an integer"), node.requiredText("text"))
            }
            "list" -> node.only("type", "ordered", "items") {
                val items = node.required("items")
                require(items.isArray) { "knowledge list items must be an array" }
                KnowledgeBlock.ListBlock(
                    ordered = node.required("ordered").takeIf(JsonNode::isBoolean)?.asBoolean()
                        ?: throw InvalidKnowledgeDocumentException("knowledge list ordered must be boolean"),
                    items = items.values().map { item ->
                        require(item.isString) { "knowledge list item must be text" }
                        item.asString()
                    },
                )
            }
            "code" -> node.only("type", "language", "text") {
                KnowledgeBlock.Code(node.optionalText("language"), node.requiredText("text"))
            }
            "callout" -> node.only("type", "text") { KnowledgeBlock.Callout(node.requiredText("text")) }
            "quote" -> node.only("type", "text") { KnowledgeBlock.Quote(node.requiredText("text")) }
            "divider" -> node.only("type") { KnowledgeBlock.Divider }
            "link" -> node.only("type", "text", "url") { KnowledgeBlock.Link(node.requiredText("text"), node.requiredText("url")) }
            "attachment" -> node.only("type", "attachmentId") {
                KnowledgeBlock.Attachment(parseUuid(node.requiredText("attachmentId"), "attachmentId"))
            }
            else -> throw InvalidKnowledgeDocumentException("knowledge block type is not allowlisted")
        }
    }

    private fun encodeBlock(block: KnowledgeBlock): Map<String, Any> = when (block) {
        is KnowledgeBlock.Paragraph -> linkedMapOf("type" to "paragraph", "text" to block.text)
        is KnowledgeBlock.Heading -> linkedMapOf("type" to "heading", "level" to block.level, "text" to block.text)
        is KnowledgeBlock.ListBlock -> linkedMapOf("type" to "list", "ordered" to block.ordered, "items" to block.items)
        is KnowledgeBlock.Code -> linkedMapOf("type" to "code", "language" to block.language, "text" to block.text)
        is KnowledgeBlock.Callout -> linkedMapOf("type" to "callout", "text" to block.text)
        is KnowledgeBlock.Quote -> linkedMapOf("type" to "quote", "text" to block.text)
        KnowledgeBlock.Divider -> linkedMapOf("type" to "divider")
        is KnowledgeBlock.Link -> linkedMapOf("type" to "link", "text" to block.text, "url" to block.url)
        is KnowledgeBlock.Attachment -> linkedMapOf("type" to "attachment", "attachmentId" to block.attachmentId.toString())
        is KnowledgeBlock.Html -> throw InvalidKnowledgeDocumentException("HTML blocks are not canonical knowledge content")
    }

    private fun JsonNode.required(name: String): JsonNode = get(name)
        ?: throw InvalidKnowledgeDocumentException("knowledge document $name is required")

    private fun JsonNode.requiredText(name: String): String {
        val value = required(name)
        require(value.isString) { "knowledge document $name must be text" }
        return value.asString()
    }

    private fun JsonNode.optionalText(name: String): String? {
        if (!has(name)) return null
        val value = required(name)
        if (value.isNull) return null
        require(value.isString) { "knowledge document $name must be text or null" }
        return value.asString()
    }

    private fun JsonNode.requireOnly(vararg names: String) {
        val allowed = names.toSet()
        require(properties().asSequence().map(Map.Entry<String, JsonNode>::key).all(allowed::contains)) {
            "knowledge document contains unsupported properties"
        }
    }

    private fun <T> JsonNode.only(vararg names: String, create: () -> T): T {
        requireOnly(*names)
        return create()
    }

    private fun parseUuid(value: String, name: String): UUID = runCatching { UUID.fromString(value) }
        .getOrElse { throw InvalidKnowledgeDocumentException("knowledge document $name must be a UUID") }
}
