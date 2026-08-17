package dev.deskseed.knowledge

import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

enum class KnowledgeAudienceType {
    PUBLIC,
    SIGNED_IN_CUSTOMER,
    STAFF,
    SELECTED_STAFF_GROUPS,
}

@ConsistentCopyVisibility
data class KnowledgeAudience private constructor(
    val type: KnowledgeAudienceType,
    val groupIds: Set<UUID>,
) {
    init {
        require(groupIds.size <= MAX_GROUPS) { "Knowledge audience group count is bounded" }
        require(type == KnowledgeAudienceType.SELECTED_STAFF_GROUPS || groupIds.isEmpty()) {
            "Only selected-staff-groups audiences can carry groups"
        }
        require(type != KnowledgeAudienceType.SELECTED_STAFF_GROUPS || groupIds.isNotEmpty()) {
            "Selected staff group audiences require at least one group"
        }
    }

    companion object {
        private const val MAX_GROUPS = 100

        fun public(): KnowledgeAudience = KnowledgeAudience(KnowledgeAudienceType.PUBLIC, emptySet())

        fun signedInCustomer(): KnowledgeAudience = KnowledgeAudience(KnowledgeAudienceType.SIGNED_IN_CUSTOMER, emptySet())

        fun staff(): KnowledgeAudience = KnowledgeAudience(KnowledgeAudienceType.STAFF, emptySet())

        fun selectedStaffGroups(groupIds: Set<UUID>): KnowledgeAudience =
            KnowledgeAudience(KnowledgeAudienceType.SELECTED_STAFF_GROUPS, groupIds)
    }
}

sealed interface KnowledgeReader {
    data object AnonymousCustomer : KnowledgeReader

    data object SignedInCustomer : KnowledgeReader

    data class Staff(val activeGroupIds: Set<UUID>) : KnowledgeReader
}

fun interface KnowledgeAudienceEvaluator {
    fun allows(audience: KnowledgeAudience, reader: KnowledgeReader): Boolean
}

class DefaultKnowledgeAudienceEvaluator : KnowledgeAudienceEvaluator {
    override fun allows(audience: KnowledgeAudience, reader: KnowledgeReader): Boolean = when (audience.type) {
        KnowledgeAudienceType.PUBLIC -> true
        KnowledgeAudienceType.SIGNED_IN_CUSTOMER -> reader is KnowledgeReader.SignedInCustomer || reader is KnowledgeReader.Staff
        KnowledgeAudienceType.STAFF -> reader is KnowledgeReader.Staff
        KnowledgeAudienceType.SELECTED_STAFF_GROUPS ->
            reader is KnowledgeReader.Staff && reader.activeGroupIds.any(audience.groupIds::contains)
    }
}

data class CanonicalKnowledgeDocument(
    val schemaVersion: Int,
    val blocks: List<KnowledgeBlock>,
)

sealed interface KnowledgeBlock {
    data class Paragraph(val text: String) : KnowledgeBlock
    data class Heading(val level: Int, val text: String) : KnowledgeBlock
    data class ListBlock(val ordered: Boolean, val items: List<String>) : KnowledgeBlock
    data class Code(val language: String?, val text: String) : KnowledgeBlock
    data class Callout(val text: String) : KnowledgeBlock
    data class Quote(val text: String) : KnowledgeBlock
    data object Divider : KnowledgeBlock
    data class Link(val text: String, val url: String) : KnowledgeBlock
    data class Attachment(val attachmentId: UUID) : KnowledgeBlock

    /** Exists solely so an adapter can reject vendor HTML explicitly rather than silently retain it. */
    data class Html(val value: String) : KnowledgeBlock
}

data class ValidatedKnowledgeDocument(
    val document: CanonicalKnowledgeDocument,
    val plainText: String,
    val checksumSha256: String,
)

class InvalidKnowledgeDocumentException(message: String) : IllegalArgumentException(message)

/**
 * Validates the storage-neutral document before it crosses the persistence boundary.
 * The result intentionally has text only; a renderer must render each canonical block,
 * never a precomposed HTML string.
 */
class CanonicalKnowledgeDocumentValidator {
    fun validate(
        document: CanonicalKnowledgeDocument,
        audience: KnowledgeAudience? = null,
    ): ValidatedKnowledgeDocument {
        if (document.schemaVersion != SCHEMA_VERSION) {
            throw InvalidKnowledgeDocumentException("Unsupported knowledge document schema version")
        }
        if (document.blocks.isEmpty() || document.blocks.size > MAX_BLOCKS) {
            throw InvalidKnowledgeDocumentException("Knowledge document block count is invalid")
        }
        if (audience?.type == KnowledgeAudienceType.PUBLIC && document.blocks.any { it is KnowledgeBlock.Attachment }) {
            throw InvalidKnowledgeDocumentException("PUBLIC articles cannot reference attachments")
        }

        val text = document.blocks.mapNotNull(::validatedText).joinToString("\n")
        if (text.length > MAX_PLAIN_TEXT_LENGTH) {
            throw InvalidKnowledgeDocumentException("Knowledge document text is too long")
        }
        return ValidatedKnowledgeDocument(
            document = document,
            plainText = text,
            checksumSha256 = sha256(canonicalChecksumMaterial(document)),
        )
    }

    private fun validatedText(block: KnowledgeBlock): String? = when (block) {
        is KnowledgeBlock.Paragraph -> boundedText(block.text)
        is KnowledgeBlock.Heading -> {
            if (block.level !in 2..3) throw InvalidKnowledgeDocumentException("Knowledge heading level is invalid")
            boundedText(block.text)
        }
        is KnowledgeBlock.ListBlock -> {
            if (block.items.isEmpty() || block.items.size > MAX_LIST_ITEMS) {
                throw InvalidKnowledgeDocumentException("Knowledge list size is invalid")
            }
            block.items.joinToString("\n") { boundedText(it) }
        }
        is KnowledgeBlock.Code -> boundedText(block.text)
        is KnowledgeBlock.Callout -> boundedText(block.text)
        is KnowledgeBlock.Quote -> boundedText(block.text)
        KnowledgeBlock.Divider -> null
        is KnowledgeBlock.Link -> {
            boundedText(block.text)
            validateSafeExternalUrl(block.url)
            block.text
        }
        is KnowledgeBlock.Attachment -> null
        is KnowledgeBlock.Html -> throw InvalidKnowledgeDocumentException("HTML blocks are not canonical knowledge content")
    }

    private fun boundedText(value: String): String {
        if (value.isBlank() || value.length > MAX_BLOCK_TEXT_LENGTH || value.any(Char::isISOControl)) {
            throw InvalidKnowledgeDocumentException("Knowledge block text is invalid")
        }
        return value.trim()
    }

    private fun validateSafeExternalUrl(value: String) {
        val uri = try {
            URI(value)
        } catch (_: IllegalArgumentException) {
            throw InvalidKnowledgeDocumentException("Knowledge link URL is invalid")
        }
        if (value.length > MAX_URL_LENGTH || uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) {
            throw InvalidKnowledgeDocumentException("Knowledge link URL is not allowlisted")
        }
    }

    /**
     * The checksum must change when the canonical structure changes, not only when its
     * extracted search text happens to match. It is intentionally an internal,
     * delimiter-separated representation: persisted document JSON remains the source
     * representation, while this value is a deterministic integrity marker.
     */
    private fun canonicalChecksumMaterial(document: CanonicalKnowledgeDocument): String = buildString {
        append(document.schemaVersion)
        document.blocks.forEach { block ->
            append('\u001e')
            when (block) {
                is KnowledgeBlock.Paragraph -> append("paragraph\u001f").append(boundedText(block.text))
                is KnowledgeBlock.Heading -> append("heading\u001f").append(block.level).append('\u001f').append(boundedText(block.text))
                is KnowledgeBlock.ListBlock -> {
                    append("list\u001f").append(block.ordered)
                    block.items.forEach { append('\u001f').append(boundedText(it)) }
                }
                is KnowledgeBlock.Code -> append("code\u001f").append(block.language ?: "").append('\u001f').append(boundedText(block.text))
                is KnowledgeBlock.Callout -> append("callout\u001f").append(boundedText(block.text))
                is KnowledgeBlock.Quote -> append("quote\u001f").append(boundedText(block.text))
                KnowledgeBlock.Divider -> append("divider")
                is KnowledgeBlock.Link -> append("link\u001f").append(boundedText(block.text)).append('\u001f').append(block.url)
                is KnowledgeBlock.Attachment -> append("attachment\u001f").append(block.attachmentId)
                is KnowledgeBlock.Html -> throw InvalidKnowledgeDocumentException("HTML blocks are not canonical knowledge content")
            }
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_BLOCKS = 200
        const val MAX_LIST_ITEMS = 100
        const val MAX_BLOCK_TEXT_LENGTH = 10_000
        const val MAX_PLAIN_TEXT_LENGTH = 500_000
        const val MAX_URL_LENGTH = 2_048
    }
}

fun interface KnowledgeContentRenderer {
    fun renderPlainText(document: CanonicalKnowledgeDocument): String
}
