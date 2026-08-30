package dev.deskseed.ticketing

import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.util.UUID

enum class CommentContentFormat {
    PLAIN_TEXT,
    RICH_TEXT_V1,
}

data class CanonicalCommentContent(
    val format: CommentContentFormat,
    val body: String,
    val document: JsonNode? = null,
)

sealed interface CommentContentView {
    val format: CommentContentFormat
}

data class PlainTextCommentContentView(
    val text: String,
    override val format: CommentContentFormat = CommentContentFormat.PLAIN_TEXT,
) : CommentContentView

data class RichTextCommentContentView(
    val document: JsonNode,
    override val format: CommentContentFormat = CommentContentFormat.RICH_TEXT_V1,
) : CommentContentView

fun commentContentView(
    format: CommentContentFormat,
    body: String,
    document: JsonNode?,
): CommentContentView = when (format) {
    CommentContentFormat.PLAIN_TEXT -> PlainTextCommentContentView(body)
    CommentContentFormat.RICH_TEXT_V1 -> RichTextCommentContentView(checkNotNull(document))
}

class InvalidCommentContentException(message: String) : IllegalArgumentException(message)

/** Closed, deterministic decoder for the frozen RICH_TEXT_V1 wire document. */
class CanonicalCommentContentCodec(
    private val objectMapper: ObjectMapper,
) {
    fun decode(
        body: String?,
        content: JsonNode?,
        attachmentIds: Set<UUID>,
        allowEmpty: Boolean = false,
    ): CanonicalCommentContent {
        if ((body == null) == (content == null)) {
            invalid("exactly one of body or content is required")
        }
        if (body != null) {
            return plain(body, allowEmpty)
        }
        val contentNode = checkNotNull(content)
        contentNode.requireObject("content")
        contentNode.requireOnly("format", "text", "document")
        return when (contentNode.requiredText("format", "content")) {
            CommentContentFormat.PLAIN_TEXT.name -> {
                if (contentNode.has("document")) invalid("PLAIN_TEXT content cannot contain document")
                plain(contentNode.requiredText("text", "content"), allowEmpty)
            }
            CommentContentFormat.RICH_TEXT_V1.name -> {
                if (contentNode.has("text")) invalid("RICH_TEXT_V1 content cannot contain text")
                rich(contentNode.required("document", "content"), attachmentIds, allowEmpty)
            }
            else -> invalid("content format is not allowlisted")
        }
    }

    private fun plain(value: String, allowEmpty: Boolean): CanonicalCommentContent {
        validateText(value, "plain text", MAX_TEXT_LENGTH, allowEmpty)
        return CanonicalCommentContent(CommentContentFormat.PLAIN_TEXT, value)
    }

    private fun rich(document: JsonNode, attachmentIds: Set<UUID>, allowEmpty: Boolean): CanonicalCommentContent {
        val state = DecodeState(attachmentIds)
        document.requireObject("document")
        document.requireOnly("type", "content")
        if (document.requiredText("type", "document") != "doc") invalid("document.type must be doc")
        val blocks = document.requiredArray("content", "document")
        if (blocks.isEmpty || blocks.size() > MAX_TOP_LEVEL_BLOCKS) invalid("document content size is invalid")
        val normalizedBlocks = blocks.values().map { decodeBlock(it, state, 1, "document.content") }
        val normalized = linkedMapOf<String, Any>("type" to "doc", "content" to normalizedBlocks)
        val plainText = state.plainText.toString().trimEnd()
        if (!allowEmpty && plainText.isBlank()) invalid("rich text content must not be empty")
        if (plainText.length > MAX_TEXT_LENGTH) invalid("derived plain text is too long")
        return CanonicalCommentContent(
            format = CommentContentFormat.RICH_TEXT_V1,
            body = plainText,
            document = objectMapper.readTree(objectMapper.writeValueAsString(normalized)),
        )
    }

    private fun decodeBlock(node: JsonNode, state: DecodeState, depth: Int, path: String): Map<String, Any> {
        state.visit(depth)
        node.requireObject(path)
        return when (val type = node.requiredText("type", path)) {
            "paragraph" -> decodeTextBlock(node, state, path, type, heading = false)
            "heading" -> decodeTextBlock(node, state, path, type, heading = true)
            "bulletList", "orderedList" -> {
                node.requireOnly("type", "content")
                val items = node.requiredArray("content", path)
                if (items.isEmpty || items.size() > MAX_CHILDREN) invalid("$path list size is invalid")
                val content = items.values().mapIndexed { index, item ->
                    decodeListItem(item, state, depth + 1, "$path.content[$index]")
                }
                state.appendLine()
                linkedMapOf("type" to type, "content" to content)
            }
            "blockquote" -> {
                node.requireOnly("type", "content")
                val children = node.requiredArray("content", path)
                if (children.isEmpty || children.size() > MAX_CHILDREN) invalid("$path blockquote size is invalid")
                val content = children.values().mapIndexed { index, child ->
                    if (child.requiredText("type", "$path.content[$index]") != "paragraph") {
                        invalid("blockquote children must be paragraphs")
                    }
                    decodeBlock(child, state, depth + 1, "$path.content[$index]")
                }
                state.appendLine()
                linkedMapOf("type" to type, "content" to content)
            }
            "codeBlock" -> {
                node.requireOnly("type", "content")
                val contentNode = node.get("content")
                val content = if (contentNode == null) {
                    emptyList()
                } else {
                    if (!contentNode.isArray || contentNode.size() > 1) invalid("$path codeBlock content is invalid")
                    contentNode.values().mapIndexed { index, text ->
                        decodeText(text, state, "$path.content[$index]", marksAllowed = false)
                    }
                }
                state.appendLine()
                linkedMapOf("type" to type, "content" to content)
            }
            "attachmentImage" -> {
                node.requireOnly("type", "attrs")
                val attrs = node.required("attrs", path).also { it.requireObject("$path.attrs") }
                attrs.requireOnly("attachmentId", "alt")
                val attachmentId = parseUuid(attrs.requiredText("attachmentId", "$path.attrs"), "$path.attrs.attachmentId")
                if (attachmentId !in state.attachmentIds) invalid("attachment image must reference a submitted attachment")
                val alt = attrs.requiredText("alt", "$path.attrs")
                validateText(alt, "$path.attrs.alt", MAX_ALT_LENGTH, allowEmpty = false)
                state.appendText(alt)
                state.appendLine()
                linkedMapOf(
                    "type" to type,
                    "attrs" to linkedMapOf("attachmentId" to attachmentId.toString(), "alt" to alt),
                )
            }
            else -> invalid("$path block type is not allowlisted")
        }
    }

    private fun decodeTextBlock(
        node: JsonNode,
        state: DecodeState,
        path: String,
        type: String,
        heading: Boolean,
    ): Map<String, Any> {
        node.requireOnly("type", "attrs", "content")
        val result = linkedMapOf<String, Any>("type" to type)
        val attrs = node.get("attrs")
        if (heading && attrs == null) invalid("heading attrs are required")
        if (attrs != null) {
            attrs.requireObject("$path.attrs")
            attrs.requireOnly(*(if (heading) arrayOf("level", "textAlign") else arrayOf("textAlign")))
            val normalizedAttrs = linkedMapOf<String, Any>()
            if (heading) {
                val level = attrs.required("level", "$path.attrs")
                if (!level.isInt || level.asInt() !in 1..3) invalid("heading level must be 1, 2, or 3")
                normalizedAttrs["level"] = level.asInt()
            }
            attrs.get("textAlign")?.let { alignment ->
                if (!alignment.isString || alignment.asString() !in ALIGNMENTS) invalid("text alignment is not allowlisted")
                normalizedAttrs["textAlign"] = alignment.asString()
            }
            result["attrs"] = normalizedAttrs
        }
        node.get("content")?.let { contentNode ->
            if (!contentNode.isArray || contentNode.size() > MAX_CHILDREN) invalid("$path inline content is invalid")
            result["content"] = contentNode.values().mapIndexed { index, inline ->
                decodeInline(inline, state, "$path.content[$index]")
            }
        }
        state.appendLine()
        return result
    }

    private fun decodeListItem(node: JsonNode, state: DecodeState, depth: Int, path: String): Map<String, Any> {
        state.visit(depth)
        node.requireObject(path)
        node.requireOnly("type", "content")
        if (node.requiredText("type", path) != "listItem") invalid("list children must be listItem")
        val children = node.requiredArray("content", path)
        if (children.isEmpty || children.size() > MAX_CHILDREN) invalid("$path content size is invalid")
        return linkedMapOf(
            "type" to "listItem",
            "content" to children.values().mapIndexed { index, child ->
                if (child.requiredText("type", "$path.content[$index]") != "paragraph") {
                    invalid("listItem children must be paragraphs")
                }
                decodeBlock(child, state, depth + 1, "$path.content[$index]")
            },
        )
    }

    private fun decodeInline(node: JsonNode, state: DecodeState, path: String): Map<String, Any> {
        state.visit(1)
        node.requireObject(path)
        return when (node.requiredText("type", path)) {
            "text" -> decodeText(node, state, path, marksAllowed = true)
            "hardBreak" -> {
                node.requireOnly("type")
                state.appendText("\n")
                linkedMapOf("type" to "hardBreak")
            }
            else -> invalid("$path inline type is not allowlisted")
        }
    }

    private fun decodeText(node: JsonNode, state: DecodeState, path: String, marksAllowed: Boolean): Map<String, Any> {
        node.requireOnly("type", "text", "marks")
        if (node.requiredText("type", path) != "text") invalid("$path must be text")
        val text = node.requiredText("text", path)
        validateText(text, "$path.text", MAX_TEXT_LENGTH, allowEmpty = false)
        state.appendText(text)
        val result = linkedMapOf<String, Any>("type" to "text", "text" to text)
        node.get("marks")?.let { marks ->
            if (!marksAllowed || !marks.isArray || marks.size() > MAX_MARKS) invalid("$path marks are invalid")
            val normalized = marks.values().mapIndexed { index, mark -> decodeMark(mark, "$path.marks[$index]") }
            val identities = normalized.map { objectMapper.writeValueAsString(it) }
            if (identities.size != identities.distinct().size) invalid("$path marks must be unique")
            result["marks"] = normalized
        }
        return result
    }

    private fun decodeMark(node: JsonNode, path: String): Map<String, Any> {
        node.requireObject(path)
        return when (val type = node.requiredText("type", path)) {
            "bold", "italic", "underline", "code" -> {
                node.requireOnly("type")
                linkedMapOf("type" to type)
            }
            "link" -> {
                node.requireOnly("type", "attrs")
                val attrs = node.required("attrs", path).also { it.requireObject("$path.attrs") }
                attrs.requireOnly("href")
                val href = attrs.requiredText("href", "$path.attrs")
                if (href.length > MAX_URL_LENGTH || runCatching { URI(href).scheme?.lowercase() }.getOrNull() !in LINK_SCHEMES) {
                    invalid("$path link protocol is not allowlisted")
                }
                linkedMapOf("type" to type, "attrs" to linkedMapOf("href" to href))
            }
            else -> invalid("$path mark type is not allowlisted")
        }
    }

    private fun validateText(value: String, path: String, maxLength: Int, allowEmpty: Boolean) {
        if (value.length > maxLength || (!allowEmpty && value.isBlank())) invalid("$path is invalid")
        if (value.any { it.isISOControl() && it !in setOf('\n', '\r', '\t') }) invalid("$path contains control characters")
    }

    private fun JsonNode.required(name: String, path: String): JsonNode = get(name)
        ?: invalid("$path.$name is required")

    private fun JsonNode.requiredText(name: String, path: String): String = required(name, path).let { value ->
        if (!value.isString) invalid("$path.$name must be text")
        value.asString()
    }

    private fun JsonNode.requiredArray(name: String, path: String): JsonNode = required(name, path).also {
        if (!it.isArray) invalid("$path.$name must be an array")
    }

    private fun JsonNode.requireObject(path: String) {
        if (!isObject) invalid("$path must be an object")
    }

    private fun JsonNode.requireOnly(vararg names: String) {
        val allowed = names.toSet()
        if (!properties().asSequence().map(Map.Entry<String, JsonNode>::key).all(allowed::contains)) {
            invalid("content contains unsupported properties")
        }
    }

    private fun parseUuid(value: String, path: String): UUID = runCatching { UUID.fromString(value) }
        .getOrElse { invalid("$path must be a UUID") }

    private fun invalid(message: String): Nothing = throw InvalidCommentContentException(message)

    private class DecodeState(val attachmentIds: Set<UUID>) {
        val plainText = StringBuilder()
        private var nodes = 0

        fun visit(depth: Int) {
            nodes += 1
            if (nodes > MAX_NODES || depth > MAX_DEPTH) throw InvalidCommentContentException("rich text document is too complex")
        }

        fun appendText(value: String) {
            if (plainText.length + value.length > MAX_TEXT_LENGTH) {
                throw InvalidCommentContentException("derived plain text is too long")
            }
            plainText.append(value)
        }

        fun appendLine() {
            if (plainText.isNotEmpty() && plainText.last() != '\n') plainText.append('\n')
        }
    }

    private companion object {
        const val MAX_TEXT_LENGTH = 20_000
        const val MAX_ALT_LENGTH = 500
        const val MAX_URL_LENGTH = 2048
        const val MAX_TOP_LEVEL_BLOCKS = 500
        const val MAX_CHILDREN = 1000
        const val MAX_MARKS = 5
        const val MAX_NODES = 5000
        const val MAX_DEPTH = 12
        val ALIGNMENTS = setOf("left", "center", "right")
        val LINK_SCHEMES = setOf("http", "https", "mailto")
    }
}
